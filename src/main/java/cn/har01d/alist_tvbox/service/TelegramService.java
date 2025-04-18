package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.dto.tg.Chat;
import cn.har01d.alist_tvbox.dto.tg.Message;
import cn.har01d.alist_tvbox.entity.Setting;
import cn.har01d.alist_tvbox.entity.SettingRepository;
import cn.har01d.alist_tvbox.util.IdUtils;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import telegram4j.core.MTProtoTelegramClient;
import telegram4j.core.auth.AuthorizationHandler;
import telegram4j.core.auth.CodeAuthorizationHandler;
import telegram4j.core.auth.QRAuthorizationHandler;
import telegram4j.core.auth.TwoFactorHandler;
import telegram4j.mtproto.store.FileStoreLayout;
import telegram4j.mtproto.store.StoreLayout;
import telegram4j.mtproto.store.StoreLayoutImpl;
import telegram4j.tl.BaseChat;
import telegram4j.tl.BaseMessage;
import telegram4j.tl.Channel;
import telegram4j.tl.ImmutableInputPeerChannel;
import telegram4j.tl.ImmutableInputPeerChat;
import telegram4j.tl.InputMessagesFilterEmpty;
import telegram4j.tl.InputPeer;
import telegram4j.tl.InputPeerSelf;
import telegram4j.tl.InputUserSelf;
import telegram4j.tl.User;
import telegram4j.tl.messages.ChannelMessages;
import telegram4j.tl.messages.DialogsSlice;
import telegram4j.tl.messages.Messages;
import telegram4j.tl.request.messages.ImmutableGetDialogs;
import telegram4j.tl.request.messages.ImmutableGetHistory;
import telegram4j.tl.request.messages.ImmutableSearch;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static cn.har01d.alist_tvbox.util.Constants.USER_AGENT;

@Slf4j
@Service
public class TelegramService {
    private final AppProperties appProperties;
    private final SettingRepository settingRepository;
    private final ExecutorService executorService = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors() * 2);
    private final OkHttpClient httpClient = new OkHttpClient();
    private MTProtoTelegramClient client;

    public TelegramService(AppProperties appProperties, SettingRepository settingRepository) {
        this.appProperties = appProperties;
        this.settingRepository = settingRepository;
    }

    @PostConstruct
    public void init() {
        String tgPhase = settingRepository.findById("tg_phase").map(Setting::getValue).orElse("0");
        if ("9".equals(tgPhase)) {
            connect();
        }
    }

    public void reset() {
        settingRepository.deleteById("tg_phone");
        settingRepository.deleteById("tg_code");
        settingRepository.deleteById("tg_password");
        settingRepository.deleteById("tg_qr_img");
        settingRepository.deleteById("tg_scanned");
    }

    public void logout() {
        if (client != null) {
            client.getServiceHolder().getAuthService().logOut().block();
            client.disconnect().block();
            client = null;
        }

        reset();
        settingRepository.save(new Setting("tg_phase", "0"));

        try {
            Files.deleteIfExists(Path.of("/data/t4j.bin"));
        } catch (IOException e) {
            log.warn("删除session文件失败", e);
        }
    }

    public void connect() {
        if (client != null) {
            client.disconnect().block();
        }

        new Thread(() -> {
            int apiId = IdUtils.getApiId();
            String apiHash = IdUtils.getApiHash();
            boolean qr = settingRepository.findById("tg_auth_type").map(Setting::getValue).orElse("qr").equals("qr");
            AuthorizationHandler authHandler;
            if (qr) {
                log.info("Telegram扫码登陆");
                settingRepository.deleteById("tg_scanned");
                settingRepository.deleteById("tg_qr_img");
                authHandler = new QRAuthorizationHandler(new QRAuthorizationHandler.Callback() {
                    @Override
                    public Mono<ActionType> onLoginToken(AuthorizationHandler.Resources res, QRAuthorizationHandler.Context ctx) {
                        settingRepository.save(new Setting("tg_phase", "0"));
                        log.info("Scan QR {}, expired: {}.", ctx.loginUrl(), ctx.expiresIn());
                        try {
                            String img = getQrCode(ctx.loginUrl());
                            settingRepository.save(new Setting("tg_qr_img", img));
                        } catch (IOException e) {
                            return Mono.error(e);
                        }
                        settingRepository.save(new Setting("tg_phase", "1"));
                        String scanned = waitSettingAvailable("tg_scanned");
                        settingRepository.deleteById("tg_password");
                        settingRepository.save(new Setting("tg_phase", "2"));
                        return scanned != null ? Mono.just(ActionType.STOP) : Mono.just(ActionType.RETRY);
                    }

                    @Override
                    public Mono<String> on2FAPassword(AuthorizationHandler.Resources res, TwoFactorHandler.Context ctx) {
                        log.info("Input the 2FA password.");
                        settingRepository.save(new Setting("tg_phase", "5"));
                        String password = waitSettingAvailable("tg_password");
                        settingRepository.save(new Setting("tg_phase", "6"));
                        return password != null ? Mono.just(password) : Mono.empty();
                    }
                });
            } else {
                log.info("Telegram验证码登陆");
                authHandler = new CodeAuthorizationHandler(new CodeAuthorizationHandler.Callback() {
                    @Override
                    public Mono<CodeAuthorizationHandler.PhoneNumberAction> onPhoneNumber(AuthorizationHandler.Resources res, CodeAuthorizationHandler.PhoneNumberContext ctx) {
                        log.info("Input the phone number.");
                        settingRepository.save(new Setting("tg_phase", "1"));
                        String phone = waitSettingAvailable("tg_phone");
                        settingRepository.deleteById("tg_code");
                        settingRepository.deleteById("tg_password");
                        settingRepository.save(new Setting("tg_phase", "2"));
                        return phone != null ? Mono.just(CodeAuthorizationHandler.PhoneNumberAction.of(phone)) : Mono.just(CodeAuthorizationHandler.PhoneNumberAction.cancel());
                    }

                    @Override
                    public Mono<CodeAuthorizationHandler.CodeAction> onSentCode(AuthorizationHandler.Resources res, CodeAuthorizationHandler.PhoneCodeContext ctx) {
                        log.info("Input the verification code.");
                        settingRepository.save(new Setting("tg_phase", "3"));
                        String code = waitSettingAvailable("tg_code");
                        settingRepository.save(new Setting("tg_phase", "4"));
                        return code != null ? Mono.just(CodeAuthorizationHandler.CodeAction.of(code)) : Mono.just(CodeAuthorizationHandler.CodeAction.cancel());
                    }

                    @Override
                    public Mono<String> on2FAPassword(AuthorizationHandler.Resources res, TwoFactorHandler.Context ctx) {
                        log.info("Input the 2FA password.");
                        settingRepository.save(new Setting("tg_phase", "5"));
                        String password = waitSettingAvailable("tg_password");
                        settingRepository.save(new Setting("tg_phase", "6"));
                        return password != null ? Mono.just(password) : Mono.empty();
                    }
                });
            }
            StoreLayout storeLayout = new FileStoreLayout(new StoreLayoutImpl(c -> c.maximumSize(1000)), Path.of("/data/t4j.bin"));
            client = MTProtoTelegramClient.create(apiId, apiHash, authHandler).setStoreLayout(storeLayout).connect().block();

            if (client == null) {
                settingRepository.save(new Setting("tg_phase", "0"));
                log.warn("Telegram连接失败");
                return;
            }

            settingRepository.save(new Setting("tg_phase", "9"));
            log.info("Telegram连接成功");
            client.onDisconnect().block();
            client = null;
            log.info("Telegram关闭连接");
        }).start();
    }

    public static String getQrCode(String text) throws IOException {
        log.info("get qr code for text: {}", text);
        try {
            ProcessBuilder builder = new ProcessBuilder();
            builder.command("/atv-cli", text);
            builder.inheritIO();
            Process process = builder.start();
            process.waitFor();
        } catch (Exception e) {
            log.warn("", e);
        }
        Path file = Paths.get("/www/tvbox/qr.png");
        return Base64.getEncoder().encodeToString(Files.readAllBytes(file));
    }

    public User getUser() {
        if (client == null) {
            return null;
        }
        return client.getServiceHolder().getUserService().getUser(InputUserSelf.instance()).block();
    }

    public List<Chat> getAllChats() {
        if (client == null) {
            return List.of();
        }
        DialogsSlice dialogs = (DialogsSlice) client.getServiceHolder().getChatService().getDialogs(ImmutableGetDialogs.of(0, 0, 0, InputPeerSelf.instance(), 100, 0)).block();
        return dialogs.chats().stream().filter(e -> e instanceof Channel).map(Channel.class::cast).map(Chat::new).toList();
    }

    public List<Message> getHistory(String id) {
        if (client == null) {
            return List.of();
        }
        String[] parts = id.split("\\$");
        InputPeer inputPeer = ImmutableInputPeerChannel.of(Long.parseLong(parts[0]), Long.parseLong(parts[1]));

        Messages messages = client.getServiceHolder().getChatService().getHistory(ImmutableGetHistory.of(inputPeer, 0, 0, 0, 100, 0, 0, 0)).block();
        log.info("{}", messages);
        if (messages instanceof ChannelMessages) {
            return ((ChannelMessages) messages).messages().stream().filter(e -> e instanceof BaseMessage).map(BaseMessage.class::cast).map(e -> new Message("", e)).toList();
        }
        return List.of();
    }

    private String waitSettingAvailable(String key) {
        for (int i = 0; i < 120; ++i) {
            String value = settingRepository.findById(key).map(Setting::getValue).orElse(null);
            if (value != null) {
                return value;
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                break;
            }
        }
        return null;
    }

    public Map<String, Object> searchZx(String keyword, String username) {
        String[] channels = username.split(",");
        List<Future<List<Message>>> futures = new ArrayList<>();
        for (String channel : channels) {
            Future<List<Message>> future = executorService.submit(() -> searchFromChannel(channel, keyword));
            futures.add(future);
        }
        long startTime = System.currentTimeMillis();

        int total = 0;
        List<String> result = new ArrayList<>();
        for (int i = 0; i < futures.size(); i++) {
            long remaining = Math.max(1, appProperties.getTgTimeout() - (System.currentTimeMillis() - startTime));
            Future<List<Message>> future = futures.get(i);
            String channel = channels[i];
            try {
                List<Message> list = future.get(remaining, TimeUnit.MILLISECONDS);
                total += list.size();
                result.add(channel + "$$$" + list.stream().filter(e -> e.getContent().contains("http")).map(Message::toZxString).collect(Collectors.joining("##")));
            } catch (InterruptedException e) {
                break;
            } catch (ExecutionException | TimeoutException e) {
                log.warn("", e);
            }
        }

        log.info("Search TG zx get {} results.", total);
        return Map.of("results", result);
    }

    public String searchPg(String keyword, String username, String encode) {
        log.info("search {} from channels {}", keyword, username);
        String[] channels = username.split(",");
        List<Future<List<Message>>> futures = new ArrayList<>();
        for (String channel : channels) {
            String name = channel.split("\\|")[0];
            Future<List<Message>> future = executorService.submit(() -> searchFromChannel(name, keyword));
            futures.add(future);
        }

        List<Message> list = getResult(futures);

        log.info("Search TG pg get {} results.", list.size());
        return list.stream()
                .map(Message::toPgString)
                .map(e -> {
                    if ("1".equals(encode)) {
                        return Base64.getEncoder().encodeToString(e.getBytes());
                    }
                    return e;
                })
                .collect(Collectors.joining("\n"));
    }

    public List<Message> search(String keyword) {
        String[] channels;
        if (client == null) {
            channels = appProperties.getTgWebChannels().split(",");
        } else {
            channels = appProperties.getTgChannels().split(",");
        }

        List<Future<List<Message>>> futures = new ArrayList<>();
        for (String channel : channels) {
            String name = channel.split("\\|")[0];
            Future<List<Message>> future = executorService.submit(() -> searchFromChannel(name, keyword));
            futures.add(future);
        }

        List<Message> results = getResult(futures);

        List<Message> list = results.stream()
                .filter(e -> !e.getContent().toLowerCase().contains("pdf"))
                .filter(e -> !e.getContent().toLowerCase().contains("epub"))
                .filter(e -> !e.getContent().toLowerCase().contains("azw3"))
                .filter(e -> !e.getContent().toLowerCase().contains("mobi"))
                .filter(e -> !e.getContent().toLowerCase().contains("ppt"))
                .filter(e -> !e.getContent().contains("软件"))
                .filter(e -> !e.getContent().contains("图书"))
                .filter(e -> !e.getContent().contains("电子书"))
                .sorted(Comparator.comparing(Message::getTime).reversed())
                .distinct()
                .toList();
        log.info("Search {} get {} results.", keyword, list.size());
        return list;
    }

    private List<Message> getResult(List<Future<List<Message>>> futures) {
        long startTime = System.currentTimeMillis();
        List<Message> results = new ArrayList<>();
        List<Future<List<Message>>> incompleteFutures = new ArrayList<>();

        for (Future<List<Message>> future : futures) {
            long remaining = Math.max(1, appProperties.getTgTimeout() - (System.currentTimeMillis() - startTime));

            try {
                List<Message> result = future.get(remaining, TimeUnit.MILLISECONDS);
                results.addAll(result);
            } catch (TimeoutException e) {
                incompleteFutures.add(future);
            } catch (InterruptedException | ExecutionException e) {
                log.warn("", e);
            }
        }

        Iterator<Future<List<Message>>> iterator = incompleteFutures.iterator();
        while (iterator.hasNext()) {
            Future<List<Message>> future = iterator.next();
            if (future.isDone()) {
                try {
                    results.addAll(future.get());
                    iterator.remove();
                } catch (InterruptedException | ExecutionException e) {
                    log.warn("", e);
                }
            }
        }

        incompleteFutures.forEach(f -> f.cancel(true));

        return results;
    }

    public List<Message> searchFromChannel(String username, String keyword) throws IOException {
        if (client == null) {
            List<Message> list = searchFromWeb(username, keyword);
            List<Message> result = list.stream().filter(e -> e.getType() != null).toList();
            log.info("Search {} from web {} get {} results.", keyword, username, result.size());
            return result;
        }
        List<Message> result = List.of();

        try {
            var resolvedPeer = client.getServiceHolder().getUserService().resolveUsername(username).block();
            var chat = resolvedPeer.chats().get(0);
            InputPeer inputPeer = null;
            if (chat instanceof Channel) {
                inputPeer = ImmutableInputPeerChannel.of(chat.id(), ((Channel) chat).accessHash());
            } else if (chat instanceof BaseChat) {
                inputPeer = ImmutableInputPeerChat.of(chat.id());
            }
            int minDate = (int) (Instant.now().minus(60, ChronoUnit.DAYS).truncatedTo(ChronoUnit.DAYS).toEpochMilli() / 1000);
            Messages messages = client.getServiceHolder().getChatService().search(ImmutableSearch.of(inputPeer, keyword, InputMessagesFilterEmpty.instance(), minDate, 0, 0, 0, 100, 0, 0, 0)).block();
            if (messages instanceof ChannelMessages) {
                result = ((ChannelMessages) messages).messages().stream().filter(e -> e instanceof BaseMessage).map(BaseMessage.class::cast).flatMap(e -> parseMessage(username, e)).toList();
            }
            log.info("Search {} from {} get {} results.", keyword, username, result.size());
        } catch (Exception e) {
            log.warn("search from channel {} failed", username, e);
        }
        return result;
    }

    private Stream<Message> parseMessage(String channel, telegram4j.tl.BaseMessage message) {
        List<Message> list = new ArrayList<>();
        for (String link : Message.parseLinks(message.message())) {
            list.add(new Message(channel, message, link));
        }
        return list.stream();
    }

    public List<Message> searchFromWeb(String username, String keyword) throws IOException {
        String url = "https://t.me/s/" + username + "?q=" + keyword;

        String html = getHtml(url);

        List<Message> list = new ArrayList<>();
        Document doc = Jsoup.parse(html);
        Elements elements = doc.select("div.tgme_container div.tgme_widget_message_wrap");
        for (Element element : elements) {
            Element elTime = element.selectFirst("time");
            String time = elTime != null ? elTime.attr("datetime") : null;
            list.add(new Message(username, getTextWithNewlines(element.select(".tgme_widget_message_text").first()), time));
        }
        return list;
    }

    public static String getTextWithNewlines(Element element) {
        if (element == null) {
            return "";
        }
        Element clone = element.clone();
        clone.select("br").before("\\n");
        clone.select("br").remove();
        clone.select("p, div, li").before("\\n");
        String text = clone.text().replace("\\n", "\n");
        return text.trim();
    }

    public String searchWeb(String keyword, String username, String encode) {
        log.info("search {} from web channels {}", keyword, username);
        String[] channels = username.split(",");
        List<Future<List<String>>> futures = new ArrayList<>();
        for (String channel : channels) {
            Future<List<String>> future = executorService.submit(() -> searchWeb(channel, keyword));
            futures.add(future);
        }

        int total = 0;
        List<String> result = new ArrayList<>();
        for (Future<List<String>> future : futures) {
            try {
                List<String> list = future.get(appProperties.getTgTimeout(), TimeUnit.MILLISECONDS);
                total += list.size();
                for (String line : list) {
                    if ("1".equals(encode)) {
                        result.add(Base64.getEncoder().encodeToString(line.getBytes()));
                    } else {
                        result.add(line);
                    }
                }
            } catch (InterruptedException e) {
                break;
            } catch (ExecutionException | TimeoutException e) {
                log.warn("", e);
            }
        }

        log.info("Search TG web get {} results.", total);
        return String.join("\n", result);
    }

    public List<String> searchWeb(String username, String keyword) throws IOException {
        String url = "https://t.me/s/" + username + "?q=" + keyword;

        String html = getHtml(url);

        List<String> list = new ArrayList<>();
        Document doc = Jsoup.parse(html);
        Elements elements = doc.select("div.tgme_container div.tgme_widget_message_wrap");
        for (Element element : elements) {
            Element elTime = element.selectFirst("time");
            String time = elTime != null ? elTime.attr("datetime") : Instant.now().atZone(ZoneId.of("UTC")).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"));
            list.add(time + "\t" + username + "\t" + element.html().replace("\n", " ") + "\t");
        }
        Collections.reverse(list);
        log.info("Search TG web {} get {} results.", username, list.size());
        return list;
    }

    private String getHtml(String url) throws IOException {
        Request request = new Request.Builder()
                .url(url)
                .addHeader("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7")
                .addHeader("Accept-Language", "en-US,en;q=0.9,zh-CN;q=0.8,zh;q=0.7,ja;q=0.6,zh-TW;q=0.5")
                .addHeader("User-Agent", USER_AGENT)
                .addHeader("Referer", "https://t.me/")
                .build();

        Call call = httpClient.newCall(request);
        Response response = call.execute();
        String html = response.body().string();
        response.close();

        return html;
    }

    @PreDestroy
    public void disconnect() {
        client.disconnect().block();
    }
}
