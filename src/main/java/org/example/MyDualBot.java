package org.example;

import com.google.gson.*;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonArray;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import com.google.gson.reflect.TypeToken;
import com.pengrad.telegrambot.TelegramBot;
import com.pengrad.telegrambot.model.request.ParseMode;
import com.pengrad.telegrambot.request.SendMessage;
import com.pengrad.telegrambot.request.SendPhoto;
import com.pengrad.telegrambot.response.SendResponse;
import okhttp3.Credentials;
import okhttp3.OkHttpClient;

import org.example.jsonmodel.Data;
import org.example.jsonmodel.Product;
import org.example.jsonmodel.Root;
import org.example.jsonmodel.Size;
import org.example.jsonmodel.UrlFetcher;
import org.example.config.BotConfig;
import org.example.http.ProxyConfig;
import org.example.http.ProxyLoader;
import org.example.http.RotatingCookieJar;
import org.example.http.WbCookieFetcher;
import org.example.http.WbHttpClient;
import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.bots.DefaultBotOptions;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

import javax.net.ssl.SSLSession;
import java.io.*;
import java.net.*;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import org.example.http.WbHttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.DecimalFormat;

import java.util.*;
import java.io.BufferedWriter;
import java.io.IOException;
import java.util.concurrent.*;
import java.util.concurrent.TimeUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.nio.file.StandardOpenOption.*;

public class MyDualBot extends TelegramLongPollingBot {
    private static final String FILE_PATH = "sent_articles";
    private static final String FILE_PATH_COMMUNITY = "sent_articles_community.txt";

    private static final Logger log = LoggerFactory.getLogger(MyDualBot.class);

    private ScheduledExecutorService SCHEDULER;

    private static final Cache<String, Double> sentArticles100 =
            Caffeine.newBuilder().maximumSize(Long.MAX_VALUE).build();
    private static final Cache<String, Double> sentArticles90 =
            Caffeine.newBuilder().maximumSize(Long.MAX_VALUE).build();
    private static final Cache<String, Double> sentArticles80 =
            Caffeine.newBuilder().maximumSize(Long.MAX_VALUE).build();
    private static final Cache<String, Double> sentArticlesBig =
            Caffeine.newBuilder().maximumSize(Long.MAX_VALUE).build();
    private static final Cache<String, Double> sentArticlesCommunity =
            Caffeine.newBuilder().maximumSize(Long.MAX_VALUE).build();
    private static final Cache<String, Double> sentArticlesFood =
            Caffeine.newBuilder().maximumSize(Long.MAX_VALUE).build();
    private static final Cache<String, Double> sentArticlesDetyam =
            Caffeine.newBuilder().maximumSize(Long.MAX_VALUE).build();
    private static final Cache<String, Double> test =
            Caffeine.newBuilder().maximumSize(Long.MAX_VALUE).build();
    private static final Cache<String, Double> sentArticlesFree =
            Caffeine.newBuilder().maximumSize(Long.MAX_VALUE).build();

    private static final BlockingQueue<String> queue100 = new LinkedBlockingQueue<>();
    private static final BlockingQueue<String> queue90 = new LinkedBlockingQueue<>();
    private static final BlockingQueue<String> queue80 = new LinkedBlockingQueue<>();
    private static final BlockingQueue<String> queueBig = new LinkedBlockingQueue<>();
    private static final BlockingQueue<String> queueMyChat = new LinkedBlockingQueue<>();
    private static final BlockingQueue<String> queueFood = new LinkedBlockingQueue<>();
    private static final BlockingQueue<String> queueDetyam = new LinkedBlockingQueue<>();
    private static final BlockingQueue<String> queueFree = new LinkedBlockingQueue<>();


    static List<String[]> urls = new ArrayList<>();
    private static Set<String> urlsFood = ConcurrentHashMap.newKeySet();
    private static Set<String> urlsDetyam = ConcurrentHashMap.newKeySet();

    private static TelegramBot pengradBot = null;
    private final String botUsername;
    private final String botToken;

    private static volatile boolean running = false;
    private static volatile boolean isFree = true;
    private static Set<HttpCookie> Cookies;
    private static WbHttpClient wbHttpClient;
    private static Map<String, String> cookiesMap;
    // Список всех активных ExecutorService для корректной остановки
    private static final Set<ExecutorService> activeExecutors = ConcurrentHashMap.newKeySet();
    public static Set<String> pidory = ConcurrentHashMap.newKeySet();
    private static final Pattern SELLER_URL_PATTERN = Pattern.compile("https?://(?:www\\.)?wildberries\\.ru/seller/(\\d+)", Pattern.CASE_INSENSITIVE);
    private final Set<Long> waitingForMessage = new HashSet<>();
    private static final List<String> admin = new ArrayList<>(Arrays.asList("1027094894", "1039378955","5392268853"));
    private final List<String> worker = new ArrayList<>(Arrays.asList("466086607","1039378955"));

//    static Map<String, ProductInfo> mapOnSent = new HashMap<>();
//    static Map<String, Long> mapOnSentFree = new HashMap<>();

    static int[] numberPagesProcessed = new int[2];

    // Счетчики отправленных товаров в Telegram (накопительные)
    private static final AtomicInteger sentTo100 = new AtomicInteger(0);
    private static final AtomicInteger sentTo90 = new AtomicInteger(0);
    private static final AtomicInteger sentTo80 = new AtomicInteger(0);
    private static final AtomicInteger sentToBig = new AtomicInteger(0);
    private static final AtomicInteger sentToCommunity = new AtomicInteger(0);
    private static final AtomicInteger sentToFood = new AtomicInteger(0);
    private static final AtomicInteger sentToDetyam = new AtomicInteger(0);
    private static final AtomicInteger sentToFree = new AtomicInteger(0);

    // Предыдущие значения счетчиков для вычисления разницы за цикл
    private static final AtomicInteger prevSentTo100 = new AtomicInteger(0);
    private static final AtomicInteger prevSentTo90 = new AtomicInteger(0);
    private static final AtomicInteger prevSentTo80 = new AtomicInteger(0);
    private static final AtomicInteger prevSentToBig = new AtomicInteger(0);
    private static final AtomicInteger prevSentToCommunity = new AtomicInteger(0);
    private static final AtomicInteger prevSentToFood = new AtomicInteger(0);
    private static final AtomicInteger prevSentToDetyam = new AtomicInteger(0);
    private static final AtomicInteger prevSentToFree = new AtomicInteger(0);

    private static int delta = 0;
    public MyDualBot(String botToken, String botUsername) {
        this(botToken, botUsername, BotConfig.load());
    }

    private MyDualBot(String botToken, String botUsername, BotConfig cfg) {
        super(createTelegramBotOptions(cfg), botToken);
        this.botToken = botToken;
        this.botUsername = botUsername;
        MyDualBot.pengradBot = createPengradBot(botToken, cfg);
    }

    private static DefaultBotOptions createTelegramBotOptions(BotConfig cfg) {
        DefaultBotOptions options = new DefaultBotOptions();
        TelegramProxyConfig proxy = TelegramProxyConfig.from(cfg);
        if (proxy.enabled()) {
            options.setProxyType(proxy.telegramType());
            options.setProxyHost(proxy.host());
            options.setProxyPort(proxy.port());
            log.info("Telegram polling proxy enabled: {}://{}:{}", proxy.type(), proxy.host(), proxy.port());
        }
        return options;
    }

    private static TelegramBot createPengradBot(String token, BotConfig cfg) {
        TelegramProxyConfig proxy = TelegramProxyConfig.from(cfg);
        if (!proxy.enabled()) {
            return new TelegramBot(token);
        }

        OkHttpClient.Builder client = new OkHttpClient.Builder()
                .proxy(new Proxy(proxy.javaType(), new InetSocketAddress(proxy.host(), proxy.port())));
        if (proxy.hasAuth()) {
            client.proxyAuthenticator((route, response) -> {
                String credential = Credentials.basic(proxy.username(), proxy.password());
                return response.request().newBuilder()
                        .header("Proxy-Authorization", credential)
                        .build();
            });
        }
        log.info("Telegram send proxy enabled: {}://{}:{}", proxy.type(), proxy.host(), proxy.port());
        return new TelegramBot.Builder(token).okHttpClient(client.build()).build();
    }

    private record TelegramProxyConfig(
            boolean enabled,
            String type,
            String host,
            int port,
            String username,
            String password
    ) {
        static TelegramProxyConfig from(BotConfig cfg) {
            String host = cfg.getOptional("telegram.proxy.host");
            String portRaw = cfg.getOptional("telegram.proxy.port");
            if (host == null || host.isBlank() || portRaw == null || portRaw.isBlank()) {
                return new TelegramProxyConfig(false, "NO_PROXY", "", 0, null, null);
            }

            String type = Optional.ofNullable(cfg.getOptional("telegram.proxy.type"))
                    .filter(v -> !v.isBlank())
                    .orElse("HTTP")
                    .trim()
                    .toUpperCase(Locale.ROOT);
            int port = Integer.parseInt(portRaw.trim());
            return new TelegramProxyConfig(
                    true,
                    type,
                    host.trim(),
                    port,
                    cfg.getOptional("telegram.proxy.username"),
                    cfg.getOptional("telegram.proxy.password")
            );
        }

        boolean hasAuth() {
            return username != null && !username.isBlank() && password != null;
        }

        DefaultBotOptions.ProxyType telegramType() {
            return switch (type) {
                case "SOCKS4" -> DefaultBotOptions.ProxyType.SOCKS4;
                case "SOCKS5" -> DefaultBotOptions.ProxyType.SOCKS5;
                case "HTTP", "HTTPS" -> DefaultBotOptions.ProxyType.HTTP;
                default -> throw new IllegalArgumentException("Unsupported telegram.proxy.type: " + type);
            };
        }

        Proxy.Type javaType() {
            return type.startsWith("SOCKS") ? Proxy.Type.SOCKS : Proxy.Type.HTTP;
        }
    }

    @Override
    public String getBotUsername() {
        return botUsername;
    }

    @Override
    public String getBotToken() {
        return botToken;
    }

    @Override
    public void onUpdateReceived(Update update) {
        if (update.hasMessage() && update.getMessage().getText() != null) {
            String messageText = update.getMessage().getText();
            long chatId = update.getMessage().getChatId();
            String regex = "^(?:\\D*\\d\\D*){3,11}$";
            Pattern pattern = Pattern.compile(regex);
            if(worker.contains(String.valueOf(chatId))){
                if(pattern.matcher(messageText).matches()){
                    addMessage(chatId, messageText);
                }
            }

            if(admin.contains(String.valueOf(chatId))){
                if (waitingForMessage.contains(chatId)) {
                    String normalized = normalizeBlockedSupplierInput(messageText);
                    if (normalized == null) {
                        sendPengradMessage(String.valueOf(chatId), "Не удалось распознать продавца. Пришли имя или ссылку вида https://www.wildberries.ru/seller/ID");
                        return;
                    }
                    if (pidory.contains(normalized)) {
                        sendPengradMessage(String.valueOf(chatId), "Этот продавец уже заблокирован.");
                        waitingForMessage.remove(chatId);
                        return;
                    }
                    File pidoryFile = new File("pidory.txt");
                    try (BufferedWriter writer = new BufferedWriter(new FileWriter(pidoryFile, true))) {
                        if (pidoryFile.exists() && pidoryFile.length() > 0) {
                            writer.write(System.lineSeparator());
                        }
                        writer.write(normalized);
                        pidory.add(normalized);
                    } catch (IOException e) {
                        log.warn("Failed to save blocked seller to pidory.txt (chatId={})", chatId, e);
                        sendPengradMessage(String.valueOf(chatId), "Не удалось сохранить продавца.");
                        return;
                    }
                    sendPengradMessage(String.valueOf(chatId),  "Продавец добавлен в блок-лист.");
                    waitingForMessage.remove(chatId); // Убираем из режима ожидания
                    return;
                }
                switch (messageText) {
                    case "/run" -> {
                        startTask(chatId);
                    }
                    case "/stop" -> {
                        stopTask(chatId);
                    }
                    case "/clear" -> {
                        try {
                            clearTask(chatId);
                        } catch (IOException e) {
//                        e.printStackTrace();
                        }
                    }
                    case "/stopFree" -> {
                        isFree = false;
                        queueFree.clear();
                        queueFree.add("00");
                        sendPengradMessage(String.valueOf(chatId), "Бесплатный чат остановлен");
                    }
                    case "/runFree" -> {
                        queueFree.clear();
                        isFree = true;
                        sendPengradMessage(String.valueOf(chatId), "Бесплатный чат запущен");
                    }
                    case "/pidory" -> {
                        waitingForMessage.add(chatId);
                        sendPengradMessage(String.valueOf(chatId),  "Send pidora");
                    }
                    case "/reboot" -> {
                        rebootServer(chatId);
                    }
                    case "/help" -> {
                        StringBuilder helpText = new StringBuilder();
                        helpText.append("Доступные команды:\n\n");
                        helpText.append("/run - Запустить парсинг\n");
                        helpText.append("/stop - Остановить парсинг\n");
                        helpText.append("/clear - Очистить кэш и перезапустить\n");
                        helpText.append("/stopFree - Остановить бесплатный чат\n");
                        helpText.append("/runFree - Запустить бесплатный чат\n");
                        helpText.append("/pidory - Добавить продавца в блок-лист\n");
                        helpText.append("/reboot - Перезапустить сервер\n");
                        helpText.append("/help - Показать эту справку");
                        sendPengradMessage(String.valueOf(chatId), helpText.toString());
                    }
                }
            }
        }
    }
    private void addMessage(long chatId, String messageText){
        List<String> sent = repeatCheck(messageText);
        if(sent.isEmpty()) {
            sendPengradMessage(String.valueOf(chatId),  "Кешбэка нет");
            return;
        }
        try {
            readTxtFile(sent.get(0), sent.get(1), sent.get(2), messageText, sent.get(3),"");
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }
        sendPengradMessage(String.valueOf(chatId),  "Сообщение отправлено ");
    }
    private void clearTask(long chatId) throws IOException {
        sendPengradMessage(String.valueOf(chatId),  "Start cleaning");
        if(running){
            stopTask(chatId);
        }
//        queueStrippingLazar.clear();
        queueFree.clear();
        hasPoint(sentArticles100, FILE_PATH + "100.txt");
        hasPoint(sentArticles90, FILE_PATH + "90.txt");
        hasPoint(sentArticles80, FILE_PATH + "80.txt");
        hasPoint(sentArticlesBig, FILE_PATH + "Big.txt");
        hasPoint(sentArticlesCommunity, FILE_PATH_COMMUNITY);
        hasPoint(sentArticlesFood, FILE_PATH + "Food.txt");
        hasPoint(sentArticlesDetyam, FILE_PATH + "detyam.txt");
        hasPoint(sentArticlesFree, FILE_PATH + "Free.txt");

        sendPengradMessage(String.valueOf(chatId),  "Cleaning is complete");
        startTask(chatId);
    }

    private final List<Future<?>> tasks = new ArrayList<>();

    private void startTask(long chatId) {
        if (running) {
            sendPengradMessage(String.valueOf(chatId), "Task is already running.");
            return;
        }
        sendPengradMessage(String.valueOf(chatId), "Start tasks.");
        startTaskInternal();
    }
    
    private void startTaskAuto() {
        if (running) {
            log.info("Task is already running, skipping auto-start");
            return;
        }
        log.info("Auto-starting tasks...");
        startTaskInternal();
    }
    
    private void startTaskInternal() {
        // Закрой предыдущий пул потоков, если он существует
        if (SCHEDULER != null) {
            SCHEDULER.shutdown();
            try {
                if (!SCHEDULER.awaitTermination(60, TimeUnit.SECONDS)) {
                    SCHEDULER.shutdownNow();
                    if (!SCHEDULER.awaitTermination(60, TimeUnit.SECONDS)) {
                    }
                }
            } catch (InterruptedException e) {
                SCHEDULER.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        // Создай новый пул потоков
        SCHEDULER = Executors.newScheduledThreadPool(
                20,
                new ThreadFactory() {
                    private final AtomicInteger idx = new AtomicInteger();
                    @Override
                    public Thread newThread(Runnable r) {
                        Thread t = new Thread(r, "wb-sched-" + idx.incrementAndGet());
                        t.setDaemon(true);
                        t.setUncaughtExceptionHandler(
                                (th, ex) -> log.error("Thread {} died", th.getName(), ex));
                        return t;
                    }
                });
        pidory = readPidora("pidory.txt", true);
        readSentArticlesToCache(FILE_PATH + "100.txt", sentArticles100);
        readSentArticlesToCache(FILE_PATH + "90.txt", sentArticles90);
        readSentArticlesToCache(FILE_PATH + "80.txt", sentArticles80);
        readSentArticlesToCache(FILE_PATH + "Big.txt", sentArticlesBig);
        readSentArticlesToCache(FILE_PATH + "Food.txt", sentArticlesFood);
        readSentArticlesToCache(FILE_PATH + "detyam.txt", sentArticlesDetyam);
        readSentArticlesToCache(FILE_PATH + "Free.txt", sentArticlesFree);
        readSentArticlesToCache(FILE_PATH_COMMUNITY, sentArticlesCommunity);

        readSentArticlesToCache("test.txt", test);
        running = true;
        isFree = true;

        // 100
        tasks.add(SCHEDULER.submit(() ->
        {
            try {
                runSender("100.txt", queue100, sentArticles100,"-1002340997107", 2,"-1002402655346");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Throwable t) {
            }
        }));

        // 90
        tasks.add(SCHEDULER.submit(() ->
        {
            try {
                runSender("90.txt", queue90, sentArticles90, "-1002340997107", 4,"-1002446322077");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Throwable t) {
            }
        }));

        tasks.add(SCHEDULER.submit(() ->
        {
            try {
                runSender("80.txt", queue80, sentArticles80, "-1002340997107", 6,"-1002305962649");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Throwable t) {
            }
        }));


        // Big
        tasks.add(SCHEDULER.submit(() ->
        {
            try {
                runSender("Big.txt", queueBig, sentArticlesBig, "-1002340997107", 13,"-1002290311759");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Throwable t) {
            }
        }));

        // Food
        tasks.add(SCHEDULER.submit(() ->
        {
            try {
                runSender("Food.txt", queueFood, sentArticlesFood, "-1002340997107", 89330,"-1002474423617");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Throwable t) {
            }
        }));
        //detyam
        tasks.add(SCHEDULER.submit(() ->
        {
            try {
                runSender("detyam.txt", queueDetyam, sentArticlesDetyam, "-1002340997107", 255209,"-1002805053383");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Throwable t) {
            }
        }));

        // Community
        tasks.add(SCHEDULER.submit(() ->
        {
            try {
                runSender("_community.txt", queueMyChat, sentArticlesCommunity, "-1002397733938", 8,null);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Throwable t) {
            }
        }));
        //Free
        tasks.add(SCHEDULER.submit(() ->
        {
            try {
                runSender("Free.txt", queueFree, sentArticlesFree, "-1002346226214", 0,null);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Throwable t) {
            }
        }));

//        // Free
//        tasks.add(SCHEDULER.scheduleWithFixedDelay(
//                () -> {
//                    try {
//                        MyDualBot.sentFree();
//                    } catch (InterruptedException e) {
//                        Thread.currentThread().interrupt();
//                    } catch (Throwable t) {
//                    }
//                },
//                0, 5000, TimeUnit.SECONDS));
//
//        tasks.add(SCHEDULER.scheduleWithFixedDelay(
//                () -> {
//                    try {
//                        long now = System.currentTimeMillis();
//                        // Очищаем старые записи из mapOnSentFree (старше 1 часа)
//                        synchronized (mapOnSentFree) {
//                            mapOnSentFree.entrySet().removeIf(e -> {
//                                long age = now - e.getValue();
//                                return age > TimeUnit.HOURS.toMillis(1); // Удаляем записи старше 1 часа
//                            });
//                        }
//                        mapOnSent.entrySet().removeIf(e -> {
//                            queueFree.add(e.getKey());
//                            return true;
////                            long age = now - e.getValue().gettime();
////                            if (age > TimeUnit.MINUTES.toMillis(0) + 20_000) { // 2 мин 20 сек
////                                queueFree.add(e.getKey());
////                                return true;
////                            }
////                            return false;
//                        });
//                    } catch (Throwable t) {
//                    }
//                },
//                0, 10, TimeUnit.SECONDS));
        // --- два парсера с «переключением» направления ---
        // Запускаем парсеры сразу, они будут рекурсивно вызывать сами себя после завершения
        tasks.add(SCHEDULER.submit(() -> {
            while (running) {
                try {
                    mainOld(true, true);
                    if (!running) {
                        break;
                    }
            } catch (Throwable t) {
                log.error("Parser loop #1 failed", t);
                    // При ошибке делаем небольшую паузу перед повтором
                    if (running) {
                        try {
                            Thread.sleep(1000);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
            }
        }));

        // Второй парсер запускаем с небольшой задержкой, чтобы не перегружать систему
        tasks.add(SCHEDULER.schedule(() -> {
            while (running) {
                try {
                    mainOld(false, true);
                    if (!running) {
                        break;
                    }
            } catch (Throwable t) {
                log.error("Parser loop #2 failed", t);
                    // При ошибке делаем небольшую паузу перед повтором
                    if (running) {
                        try {
                            Thread.sleep(1000);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
            }
        }, 5, TimeUnit.SECONDS));
    }

    private void stopTask(long chatId) {
        if (!running) {
            sendPengradMessage(String.valueOf(chatId), "Task is not running.");
            return;
        }
        running = false;
        sendPengradMessage(String.valueOf(chatId), "Wait pls.");

        // «пустышки» в очередях, чтобы потоки-таскеры вышли из блокирующего take()
        queue100.add("0~~0~~0");
        queue90.add("0~~0~~0");
        queue80.add("0~~0~~0");
        queueBig.add("0~~0~~0");
        queueMyChat.add("0~~0~~0");
        queueFood.add("0~~0~~0");
        queueDetyam.add("0~~0~~0");
//        ProductInfo productInfo = new ProductInfo();
//        productInfo.settime(0L);
//        queueStrippingLazar.add(productInfo); // time=0 → выход
        queueFree.add("0~~0~~0");

        tasks.forEach(f -> f.cancel(false));

        // Останавливаем все активные ExecutorService из mainOld
        // Ждем завершения всех задач, а не принуждаем к завершению
        for (ExecutorService executor : activeExecutors) {
            try {
                executor.shutdown(); // Мягкое завершение - не принимаем новые задачи, но ждем завершения текущих
                if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
                    executor.shutdownNow(); // Только если не завершился за 60 секунд
                    if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                executor.shutdownNow();
            }
        }
        activeExecutors.clear();

        SCHEDULER.shutdown();

        try {
            if (!SCHEDULER.awaitTermination(60, TimeUnit.SECONDS)) { // Увеличил время ожидания до 60 секунд
                SCHEDULER.shutdownNow();
                if (!SCHEDULER.awaitTermination(60, TimeUnit.SECONDS)) { // Добавил еще одну проверку
                }
            }
        } catch (InterruptedException e) {
            SCHEDULER.shutdownNow();
            Thread.currentThread().interrupt();
        }

        // Закрываем ScheduledExecutorService для бесплатного чата
//        freeChatScheduler.shutdown();
//        try {
//            if (!freeChatScheduler.awaitTermination(60, TimeUnit.SECONDS)) {
//                freeChatScheduler.shutdownNow();
//                if (!freeChatScheduler.awaitTermination(60, TimeUnit.SECONDS)) {
//                }
//            }
//        } catch (InterruptedException e) {
//            freeChatScheduler.shutdownNow();
//            Thread.currentThread().interrupt();
//        }

        tasks.clear();

        sendPengradMessage(String.valueOf(chatId), "Task stopped.");
    }

    private void rebootServer(long chatId) {
        sendPengradMessage(String.valueOf(chatId), "⚠️ Перезапуск сервера через 3 секунды...");
        
        // Запускаем перезапуск в отдельном потоке с задержкой
        new Thread(() -> {
            try {
                Thread.sleep(3000); // Даем время на отправку сообщения
                log.info("Rebooting server by command from chatId: {}", chatId);
                
                // Пробуем различные способы перезагрузки сервера
                boolean rebooted = false;
                String lastError = null;
                
                // Способ 1: /sbin/reboot (если доступен напрямую)
                try {
                    Process process = Runtime.getRuntime().exec(new String[]{"/sbin/reboot"});
                    int exitCode = process.waitFor();
                    if (exitCode == 0) {
                        rebooted = true;
                        log.info("Server reboot initiated via /sbin/reboot");
                    }
                } catch (Exception e) {
                    lastError = e.getMessage();
                    log.debug("Failed to execute /sbin/reboot: {}", e.getMessage());
                }
                
                // Способ 2: sudo reboot
                if (!rebooted) {
                    try {
                        Process process = Runtime.getRuntime().exec(new String[]{"sudo", "reboot"});
                        int exitCode = process.waitFor();
                        if (exitCode == 0) {
                            rebooted = true;
                            log.info("Server reboot initiated via sudo reboot");
                        }
                    } catch (Exception e) {
                        lastError = e.getMessage();
                        log.debug("Failed to execute 'sudo reboot': {}", e.getMessage());
                    }
                }
                
                // Способ 3: sudo shutdown -r now
                if (!rebooted) {
                    try {
                        Process process = Runtime.getRuntime().exec(new String[]{"sudo", "shutdown", "-r", "now"});
                        int exitCode = process.waitFor();
                        if (exitCode == 0) {
                            rebooted = true;
                            log.info("Server reboot initiated via sudo shutdown -r now");
                        }
                    } catch (Exception e) {
                        lastError = e.getMessage();
                        log.debug("Failed to execute 'sudo shutdown -r now': {}", e.getMessage());
                    }
                }
                
                // Способ 4: systemctl reboot (для systemd систем)
                if (!rebooted) {
                    try {
                        Process process = Runtime.getRuntime().exec(new String[]{"sudo", "systemctl", "reboot"});
                        int exitCode = process.waitFor();
                        if (exitCode == 0) {
                            rebooted = true;
                            log.info("Server reboot initiated via sudo systemctl reboot");
                        }
                    } catch (Exception e) {
                        lastError = e.getMessage();
                        log.debug("Failed to execute 'sudo systemctl reboot': {}", e.getMessage());
                    }
                }
                
                if (!rebooted) {
                    log.error("Failed to reboot server using all available methods. Last error: {}", lastError);
                    sendPengradMessage(String.valueOf(chatId), 
                        "❌ Ошибка при перезапуске сервера.\n" +
                        "Проверьте права доступа (нужны права sudo).\n" +
                        "Последняя ошибка: " + (lastError != null ? lastError : "неизвестная"));
                } else {
                    log.info("Server reboot command executed successfully");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("Reboot thread interrupted", e);
                sendPengradMessage(String.valueOf(chatId), "❌ Перезапуск был прерван");
            } catch (Exception e) {
                log.error("Unexpected error during reboot", e);
                sendPengradMessage(String.valueOf(chatId), "❌ Неожиданная ошибка при перезапуске: " + e.getMessage());
            }
        }).start();
    }

    private static void sendPengradMessage(String chatId, String messageText) {
        if (pengradBot == null) {
            log.error("pengradBot is null! Cannot send message to {}", chatId);
            return;
        }
        boolean sent = false;
        while (!sent) {
            SendMessage request = new SendMessage(chatId, messageText).parseMode(ParseMode.Markdown);
            SendResponse response = pengradBot.execute(request);

            if (response.isOk()) {
                sent = true;
            } else {
                int retryAfter = getRetryAfter(response);
                if (retryAfter > 0) {
                    try {
                        Thread.sleep(retryAfter * 1000L);
                    } catch (InterruptedException e) {
//                        e.printStackTrace();
                        break;
                    }
                } else {
                    break;
                }
            }
        }
    }

    private static int getRetryAfter(SendResponse response) {
        String description = response.description();
        if (description != null && description.contains("retry after")) {
            String[] parts = description.split(" ");
            try {
                return Integer.parseInt(parts[parts.length - 1]);
            } catch (NumberFormatException e) {
//                e.printStackTrace();
            }
        }
        return 0;
    }

    public static void mainOld(boolean version, boolean reverse) {
        // Проверяем, не остановлена ли работа
        if (!running) {
            return;
        }
        
        // Настройка RPS для одной половины парсера; одновременно работают две половины.
        // Держим спокойный дефолт, чтобы cookie не отваливались от слишком плотного потока.
        final double TARGET_RPS = Double.parseDouble(System.getProperty("wb.parser.targetRps", "20"));
        final double INTERVAL_MS = 1000.0 / TARGET_RPS;
        final AtomicLong lastRequestTime = new AtomicLong(System.currentTimeMillis());
        
        // Запоминаем время начала цикла для таймаута 45 секунд
        long cycleStartTime = System.currentTimeMillis();
        final long MAX_CYCLE_TIME = Long.getLong("wb.parser.maxCycleMs", 300_000L);
        final long MAX_DISCOVERY_TIME = Long.getLong("wb.parser.maxDiscoveryMs", 120_000L);
        final int executorThreads = Integer.getInteger("wb.parser.executorThreads", 40);
        final int discoveryThreads = Integer.getInteger("wb.parser.discoveryThreads", 8);
        final int pageThreads = Integer.getInteger("wb.parser.pageThreads", 24);

        // Класс для представления задачи обработки страницы
        class PageTask {
            final String[] url; // [categoryUrl, shardKey, query, action]
            final int pageNumber;
            
            PageTask(String[] url, int pageNumber) {
                this.url = url;
                this.pageNumber = pageNumber;
            }
        }
        
        // Используем очередь задач для равномерного распределения страниц между потоками
        // Каждая задача - это одна страница одной категории
        BlockingQueue<PageTask> pageQueue = new LinkedBlockingQueue<>();
        ExecutorService executorService = Executors.newFixedThreadPool(executorThreads);
        activeExecutors.add(executorService);
        long startTime = System.currentTimeMillis();
        // Счетчик критичных ошибок (только парсинг и HTML)
        AtomicInteger i = new AtomicInteger();
        AtomicInteger it= new AtomicInteger();
        // Счетчик общего количества запросов (для расчета процента ошибок)
        AtomicInteger totalRequests = new AtomicInteger();
        // Счетчики для статистики
        AtomicInteger totalProducts = new AtomicInteger(); // Общее количество обработанных товаров
        AtomicInteger totalPages = new AtomicInteger(); // Общее количество обработанных страниц
        AtomicInteger totalPagesQueued = new AtomicInteger(); // Общее количество страниц, добавленных в очередь
        // Счетчики для диагностики discovery phase
        AtomicInteger discoverySuccess = new AtomicInteger(); // Успешных запросов в discovery
        AtomicInteger discoveryErrors = new AtomicInteger(); // Ошибок в discovery
        AtomicInteger discoveryEmpty = new AtomicInteger(); // Пустых ответов
        AtomicInteger discoveryNoProducts = new AtomicInteger(); // Категорий без товаров
        AtomicInteger discoveryHtmlResponse = new AtomicInteger(); // HTML ответов (антибот)
        AtomicInteger discoveryLogged = new AtomicInteger(); // Сколько раз залогировали детали
        // Счетчики по типам ошибок для диагностики
        // Счетчики ошибок для диагностики
        AtomicInteger error404 = new AtomicInteger();
        AtomicInteger error429 = new AtomicInteger();
        AtomicInteger error498 = new AtomicInteger();
        AtomicInteger errorOther = new AtomicInteger(); // Другие ошибки (HTTP != 200, сетевые, исключения)
        AtomicInteger errorParse = new AtomicInteger();
        AtomicInteger errorHtml = new AtomicInteger();
        AtomicInteger errorEmpty = new AtomicInteger();
        // Детальные счетчики для диагностики
        AtomicInteger errorNetworkException = new AtomicInteger(); // Исключения от WbHttpClient
        AtomicInteger errorHttpStatus = new AtomicInteger(); // HTTP статусы != 200 (не 404/429/498)
        AtomicInteger errorGeneralException = new AtomicInteger(); // Общие исключения в catch
        int halfSize = urls.size() / 2;
        List<String[]> halfUrls;
        if((numberPagesProcessed[0] - numberPagesProcessed[1])>50){
            delta++;
        }else if((numberPagesProcessed[1] - numberPagesProcessed[0])>50){
            delta--;
        }
        if(version) {
            halfUrls = new ArrayList<>(urls.subList(0, halfSize - delta));
        } else {
            halfUrls = new ArrayList<>(urls.subList(halfSize-delta, urls.size()));
        }
        if(reverse){
            Collections.reverse(halfUrls);
        }

        // Первый проход: получаем количество страниц для каждой категории и добавляем задачи в очередь
        // Discovery phase ограничен MAX_DISCOVERY_TIME (30 секунд), чтобы осталось время на executor phase
        ExecutorService discoveryService = Executors.newFixedThreadPool(discoveryThreads);
        activeExecutors.add(discoveryService);
        AtomicInteger categoriesProcessed = new AtomicInteger(0);
        CountDownLatch discoveryLatch = new CountDownLatch(halfUrls.size());
        
        for (String[] url : halfUrls) {
            discoveryService.submit(() -> {
                try {
                    // Проверяем флаг running перед началом обработки
                    if (!running) {
                        return;
                    }
                    
                    // Проверяем таймаут discovery phase перед началом обработки категории
                    long elapsedTime = System.currentTimeMillis() - cycleStartTime;
                    if (elapsedTime >= MAX_DISCOVERY_TIME) {
                        return; // Прерываем обработку, если превышен таймаут discovery phase
                    }
                    
                    String shardKey = url[1];
                    String query = url[2];
                    String action = url[3];
                    
                    // Формируем URL для первой страницы, чтобы узнать общее количество страниц
                    StringBuilder queryParams = new StringBuilder();
                    queryParams.append("ab_testing=false&action=").append(action);
                    queryParams.append("&appType=1");
                    if (query != null && !query.isEmpty()) {
                        queryParams.append("&").append(query);
                    }
                    queryParams.append("&curr=rub&dest=-1257786&hide_dtype=15&hide_vflags=4294967296&lang=ru");
                    queryParams.append("&sort=popular&spp=30");
                    
                    String firstPageUrl = "https://www.wildberries.ru/__internal/u-catalog/catalog/" + shardKey + "/v4/catalog?" + queryParams.toString();
                    
                    
                    // Проверяем таймаут discovery phase перед HTTP-запросом
                    elapsedTime = System.currentTimeMillis() - cycleStartTime;
                    if (elapsedTime >= MAX_DISCOVERY_TIME) {
                        return; // Прерываем обработку, если превышен таймаут discovery phase
                    }
                    
                    // Используем WbHttpClient вместо Jsoup
                    Map<String, String> headers = new HashMap<>();
                    headers.put("Referer", url[0]);
                    
                    WbHttpClient.HttpResponse<String> responsePage;
                    if (wbHttpClient != null) {
                        try {
                            responsePage = wbHttpClient.get(firstPageUrl, headers);
                        } catch (RuntimeException e) {
                            // Ошибка выполнения HTTP-запроса (сеть, таймаут и т.д.)
                            totalRequests.getAndIncrement(); // Считаем и неудачные запросы
                            // Не считаем это критичной ошибкой - просто пропускаем категорию
                            return;
                        }
                    } else {
                        // Fallback на Jsoup
                    Connection connectionPage = Jsoup.connect(firstPageUrl)
                            .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:145.0) Gecko/20100101 Firefox/145.0")
                            .header("Accept", "*/*")
                            .header("Accept-Language", "en-US,en;q=0.5")
                            .header("Accept-Encoding", "gzip, deflate")
                            .header("Referer", url[0])
                            .header("Connection", "keep-alive")
                            .header("Sec-Fetch-Dest", "empty")
                            .header("Sec-Fetch-Mode", "cors")
                            .header("Sec-Fetch-Site", "same-origin")
                            .header("Priority", "u=4")
                            .header("TE", "trailers")
                            .header("x-requested-with", "XMLHttpRequest")
                            .header("x-spa-version", "14.13.6")
                            .header("deviceid", "site_e51163b702ac40d3b293a9ccc7c333b8")
                            .method(Connection.Method.GET)
                            .ignoreContentType(true)
                            .timeout(30_000)
                            .followRedirects(true)
                            .maxBodySize(0);

                    if (Cookies != null && !Cookies.isEmpty()) {
                        for (HttpCookie cookie : Cookies) {
                            connectionPage.cookie(cookie.getName(), cookie.getValue());
                        }
                    }
                    
                        Connection.Response jsoupResponse = connectionPage.execute();
                        final String jsoupBody = jsoupResponse.body();
                        final int jsoupStatusCode = jsoupResponse.statusCode();
                        final java.net.URI requestUri = java.net.URI.create(firstPageUrl);
                        responsePage = new WbHttpClient.HttpResponse<String>() {
                            @Override public int statusCode() { return jsoupStatusCode; }
                            @Override public java.net.http.HttpRequest request() { return null; }
                            @Override public java.util.Optional<WbHttpClient.HttpResponse<String>> previousResponse() { return java.util.Optional.empty(); }
                            @Override public java.net.http.HttpHeaders headers() { 
                                Map<String, String> jsoupHeaders = jsoupResponse.headers();
                                java.util.Map<String, java.util.List<String>> headersMap = new java.util.HashMap<>();
                                for (Map.Entry<String, String> entry : jsoupHeaders.entrySet()) {
                                    headersMap.put(entry.getKey(), java.util.Collections.singletonList(entry.getValue()));
                                }
                                return java.net.http.HttpHeaders.of(headersMap, (k, v) -> true);
                            }
                            @Override public String body() { return jsoupBody; }
                            @Override public java.net.URI uri() { return requestUri; }
                        };
                    }
                    
                    int statusCode = responsePage.statusCode();
                    
                    // Увеличиваем счетчик общего количества запросов
                    totalRequests.getAndIncrement();
                    
                    // Пропускаем категории с ошибками
                    if (statusCode == 429 || statusCode == 404 || statusCode == 498) {
                        discoveryErrors.getAndIncrement();
                        return;
                    }
                    
                    if (statusCode != 200) {
                        discoveryErrors.getAndIncrement();
                        return;
                    }
                    
                    // Проверяем таймаут discovery phase перед парсингом JSON
                    elapsedTime = System.currentTimeMillis() - cycleStartTime;
                    if (elapsedTime >= MAX_DISCOVERY_TIME) {
                        return; // Прерываем обработку, если превышен таймаут discovery phase
                    }
                    
                    String jsons = responsePage.body();
                    if (jsons == null || jsons.trim().isEmpty()) {
                        discoveryEmpty.getAndIncrement();
                        // Логируем первые несколько пустых ответов для диагностики
                        if (discoveryLogged.getAndIncrement() < 3) {
                        }
                        return;
                    }
                    
                    // Проверяем, что это JSON (начинается с { или [)
                    String trimmed = jsons.trim();
                    if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) {
                        discoveryHtmlResponse.getAndIncrement();
                        // Логируем первые несколько HTML ответов для диагностики
                        if (discoveryLogged.get() < 5) {
                            String preview = trimmed.length() > 200 ? trimmed.substring(0, 200) + "..." : trimmed;
                            discoveryLogged.incrementAndGet();
                        }
                        return;
                    }
                    
                    Gson gson = new Gson();
                    Data data = null;
                    int numberCells = 0;
                    
                    // ПРИОРИТЕТ 1: Пробуем парсить как простой объект с products (новый формат: {"products": [...]})
                    // Это самый распространенный формат сейчас
                    try {
                        JsonElement jsonElement = gson.fromJson(jsons, JsonElement.class);
                        if (jsonElement != null && jsonElement.isJsonObject()) {
                            JsonObject jsonObject = jsonElement.getAsJsonObject();
                            if (jsonObject.has("products")) {
                                JsonArray productsArray = jsonObject.getAsJsonArray("products");
                                if (productsArray != null && productsArray.size() > 0) {
                                    // Создаем Data объект для совместимости
                                    data = new Data();
                                    data.products = gson.fromJson(productsArray, new TypeToken<List<Product>>(){}.getType());
                                    
                                    // Проверяем, есть ли total в JSON (может быть в разных местах)
                                    if (jsonObject.has("total") && !jsonObject.get("total").isJsonNull()) {
                                        numberCells = jsonObject.get("total").getAsInt();
                                        data.total = numberCells;
                                    } else if (jsonObject.has("data") && jsonObject.get("data").isJsonObject()) {
                                        // Проверяем, есть ли total в data
                                        JsonObject dataObj = jsonObject.getAsJsonObject("data");
                                        if (dataObj.has("total") && !dataObj.get("total").isJsonNull()) {
                                            numberCells = dataObj.get("total").getAsInt();
                                            data.total = numberCells;
                                        } else {
                                            // Если total отсутствует, используем размер products
                                            numberCells = data.products.size();
                                            data.total = 0;
                                            // Если получили 100 товаров, значит могут быть еще страницы
                                            if (numberCells == 100) {
                                                numberCells = 10000; // Устанавливаем большое число для парсинга всех страниц
                                            }
                                        }
                                    } else {
                                        // Если total отсутствует, используем размер products
                                        numberCells = data.products.size();
                                        data.total = 0;
                                        // Если получили 100 товаров, значит могут быть еще страницы
                                        if (numberCells == 100) {
                                            numberCells = 10000; // Устанавливаем большое число для парсинга всех страниц
                                        }
                                    }
                                }
                            }
                        }
                    } catch (Exception e) {
                        // Пробуем другие форматы
                    }
                    
                    // ПРИОРИТЕТ 2: Пробуем парсить как Data (с total или без)
                    if (data == null || data.products == null || data.products.isEmpty()) {
                    try {
                        data = gson.fromJson(jsons, Data.class);
                        if (data != null && data.products != null && !data.products.isEmpty()) {
                            // Если total > 0, используем его (это правильное значение)
                            if (data.total > 0) {
                                numberCells = data.total;
                            } else {
                                // Если total = 0 или отсутствует, используем размер products
                                numberCells = data.products.size();
                                // Если получили 100 товаров, значит могут быть еще страницы
                                if (numberCells == 100) {
                                    // Устанавливаем большое число, чтобы парсить все страницы
                                    numberCells = 10000; // Максимум страниц для парсинга
                                }
                            }
                        }
                    } catch (Exception e) {
                        // Пробуем парсить как Root (с оберткой data)
                        try {
                            Root root = gson.fromJson(jsons, Root.class);
                            if (root != null && root.data != null) {
                                data = root.data;
                                if (data.total == 0 && data.products != null && !data.products.isEmpty()) {
                                    numberCells = data.products.size();
                                    if (numberCells == 100) {
                                        numberCells = 10000;
                                    }
                                } else {
                                    numberCells = data.total;
                                }
                            }
                        } catch (Exception e2) {
                                return;
                            }
                        }
                    }
                    
                    // Проверяем наличие товаров
                    if (data == null || data.products == null || data.products.isEmpty()) {
                        discoveryNoProducts.getAndIncrement();
                        return;
                    }
                    
                    // Если total = 0 и нет товаров, пропускаем категорию
                    if (numberCells == 0 && (data.products == null || data.products.isEmpty())) {
                        discoveryNoProducts.getAndIncrement();
                        return;
                    }

                    // Вычисляем количество страниц
                    int totalPage;
                    if (numberCells % 100 == 0) {
                        totalPage = numberCells / 100;
                    } else {
                        totalPage = numberCells / 100 + 1;
                    }
                    
                    // Ограничиваем максимальное количество страниц для нового формата
                    if (totalPage > 100) {
                        totalPage = 100; // Максимум 100 страниц
                    }
                    
                    // Проверяем таймаут discovery phase перед добавлением страниц в очередь
                    elapsedTime = System.currentTimeMillis() - cycleStartTime;
                    if (elapsedTime >= MAX_DISCOVERY_TIME) {
                        return; // Прерываем обработку, если превышен таймаут discovery phase
                    }
                    
                    // Добавляем все страницы этой категории в очередь
                    for (int pageNum = 1; pageNum <= totalPage; pageNum++) {
                        // Проверяем таймаут перед каждой итерацией (для больших категорий)
                        if (pageNum % 10 == 0) {
                            elapsedTime = System.currentTimeMillis() - cycleStartTime;
                            if (elapsedTime >= MAX_DISCOVERY_TIME) {
                                break; // Прерываем добавление страниц, если превышен таймаут discovery phase
                            }
                        }
                        pageQueue.offer(new PageTask(url, pageNum));
                        totalPagesQueued.getAndIncrement();
                    }
                    
                    discoverySuccess.getAndIncrement();
                    categoriesProcessed.incrementAndGet();
                } catch (Exception e) {
                    // Игнорируем ошибки для ускорения
                } finally {
                    // Всегда уменьшаем счетчик защелки, независимо от результата
                    // Это гарантирует, что счетчик уменьшается ровно один раз для каждой задачи
                    discoveryLatch.countDown();
                }
            });
        }
        
        discoveryService.shutdown();
        try {
            // Вычисляем оставшееся время до MAX_DISCOVERY_TIME (30 секунд)
            long elapsedTime = System.currentTimeMillis() - cycleStartTime;
            long remainingTime = MAX_DISCOVERY_TIME - elapsedTime;
            
            if (remainingTime <= 0) {
                // Таймаут discovery phase уже превышен, принудительно завершаем discovery service
                log.warn("Discovery phase timeout already exceeded ({} ms elapsed), forcing shutdown", elapsedTime);
                discoveryService.shutdownNow();
            } else {
                // Ждем завершения обнаружения страниц, но не дольше оставшегося времени до MAX_DISCOVERY_TIME
                boolean completed = discoveryLatch.await(remainingTime, TimeUnit.MILLISECONDS);
                if (!completed) {
                    // Таймаут discovery phase достигнут, принудительно завершаем discovery service
                    log.warn("Discovery phase timeout reached ({} ms elapsed), forcing shutdown", 
                            System.currentTimeMillis() - cycleStartTime);
                    discoveryService.shutdownNow();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            // При прерывании принудительно завершаем discovery service
            discoveryService.shutdownNow();
        }
        
        // Дополнительная проверка: если discovery service не завершился, принудительно останавливаем
        if (!discoveryService.isTerminated()) {
            log.warn("Discovery service did not terminate, forcing shutdown");
            discoveryService.shutdownNow();
        }
        
        // Проверяем общий таймаут цикла после завершения discovery phase
        long elapsedTimeAfterDiscovery = System.currentTimeMillis() - cycleStartTime;
        long remainingTimeForExecutor = MAX_CYCLE_TIME - elapsedTimeAfterDiscovery;
        
//        int pagesQueued = totalPagesQueued.get();
//        int successCount = discoverySuccess.get();
//        int errorCount = discoveryErrors.get();
//        int emptyCount = discoveryEmpty.get();
//        int noProductsCount = discoveryNoProducts.get();
//        int htmlCount = discoveryHtmlResponse.get();
        
        if (elapsedTimeAfterDiscovery >= MAX_CYCLE_TIME) {
            // Таймаут цикла уже превышен, завершаем executor service и выходим
            log.warn("Cycle timeout exceeded after discovery phase ({} ms), shutting down executor service", 
                    elapsedTimeAfterDiscovery);
            executorService.shutdownNow();
            activeExecutors.remove(executorService);
            activeExecutors.remove(discoveryService);
            return;
        }
        
        int pagesInQueue = pageQueue.size();
        
        // Если очередь пуста, значит все категории вернули ошибки или не имеют товаров
        if (pagesInQueue == 0) {
            executorService.shutdown();
            activeExecutors.remove(executorService);
            activeExecutors.remove(discoveryService);
            return;
        }
        
        // Второй проход: обрабатываем все страницы из очереди параллельно
        // Каждый поток берет задачи из очереди до тех пор, пока она не пуста
        // Запускаем максимальное количество потоков для быстрой обработки
        int threadsToStart = pageThreads;
        
        
        // Запоминаем время начала executor phase для проверки таймаута
        long executorPhaseStartTime = System.currentTimeMillis();
        // Делаем переменную final для использования в лямбде
        final long finalRemainingTimeForExecutor = remainingTimeForExecutor;
        
        for (int t = 0; t < threadsToStart; t++) {
            final int threadId = t;
            executorService.submit(() -> {
                int tasksProcessed = 0;
                try {
                    while (running) {
                        // Проверяем, не был ли поток прерван
                        if (Thread.currentThread().isInterrupted()) {
                            break;
                        }
                        
                        // Проверяем оставшееся время для executor phase (не общий таймаут цикла)
                        long executorElapsedTime = System.currentTimeMillis() - executorPhaseStartTime;
                        if (executorElapsedTime >= finalRemainingTimeForExecutor) {
                            log.debug("Thread {} timeout reached ({} ms >= {} ms), exiting", 
                                    threadId, executorElapsedTime, finalRemainingTimeForExecutor);
                            break;
                        }
                        
                        PageTask task;
                        try {
                            // Используем очень короткий таймаут для poll, чтобы быстро реагировать на изменения
                            task = pageQueue.poll(100, TimeUnit.MILLISECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            log.debug("Thread {} interrupted during poll, exiting", threadId);
                            break;
                        }
                        
                        if (task == null) {
                            // Если очередь пуста и discovery завершен - выходим
                            if (discoveryService.isTerminated() && pageQueue.isEmpty()) {
                                break;
                            }
                            continue;
                        }
                        
                        // Получили задачу - обрабатываем
                        tasksProcessed++;
                        
                        // Проверяем условия выхода
                        if (!running || Thread.currentThread().isInterrupted()) {
                            break;
                        }
                        
                        // Проверяем таймаут
                        executorElapsedTime = System.currentTimeMillis() - executorPhaseStartTime;
                        if (executorElapsedTime >= finalRemainingTimeForExecutor) {
                            break;
                        }
                        
                        // Rate limiting: поддерживаем 250 RPS (глобальный лимит)
                        long now = System.currentTimeMillis();
                        long lastTime;
                        long timeSinceLastRequest;
                        do {
                            lastTime = lastRequestTime.get();
                            timeSinceLastRequest = now - lastTime;
                            if (timeSinceLastRequest < INTERVAL_MS) {
                                long sleepTime = (long)(INTERVAL_MS - timeSinceLastRequest);
                                if (sleepTime > 0) {
                                    try {
                                        Thread.sleep(sleepTime);
                                    } catch (InterruptedException e) {
                                        Thread.currentThread().interrupt();
                                        break;
                                    }
                                    now = System.currentTimeMillis();
                                }
                            }
                        } while (!lastRequestTime.compareAndSet(lastTime, System.currentTimeMillis()));
                        
                        try {
                            String[] url = task.url;
                            int pageNum = task.pageNumber;
                            
                            String shardKey = url[1];
                            String query = url[2];
                            String action = url[3];
                            
                            // Формируем query параметры
                            StringBuilder queryParams = new StringBuilder();
                            queryParams.append("ab_testing=false&action=").append(action);
                            queryParams.append("&appType=1");
                            if (query != null && !query.isEmpty()) {
                                queryParams.append("&").append(query);
                            }
                            queryParams.append("&curr=rub&dest=-1257786&hide_dtype=15&hide_vflags=4294967296&lang=ru");
                            queryParams.append("&page=").append(pageNum);
                            queryParams.append("&sort=popular&spp=30");
                            
                            String currentPage = "https://www.wildberries.ru/__internal/u-catalog/catalog/" + shardKey + "/v4/catalog?" + queryParams.toString();
                            
                            // Используем Firefox User-Agent точно как в браузере
                            String userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:145.0) Gecko/20100101 Firefox/145.0";
                            
                            // Пробуем использовать WbHttpClient, если доступен
                            WbHttpClient.HttpResponse<String> responsePage;
                            if (wbHttpClient != null) {
                                try {
                                    Map<String, String> headers = new HashMap<>();
                                    headers.put("Referer", url[0]);
                                    responsePage = wbHttpClient.get(currentPage, headers);
                                } catch (RuntimeException e) {
                                    // Ошибка выполнения HTTP-запроса (сеть, таймаут и т.д.)
                                    totalRequests.getAndIncrement(); // Считаем и неудачные запросы
                                    errorNetworkException.getAndIncrement();
                                    errorOther.getAndIncrement();
                                    // Не логируем Network exceptions - это нормально при большом количестве параллельных запросов
                                    continue;
                                }
                            } else {
                                // Fallback на Jsoup
                            Connection connectionPage = Jsoup.connect(currentPage)
                                    .userAgent(userAgent)
                                    .header("Accept", "*/*")
                                    .header("Accept-Language", "en-US,en;q=0.5")
                                    .header("Accept-Encoding", "gzip, deflate")  // Только gzip и deflate, Jsoup не поддерживает br и zstd
                                    .header("Referer", url[0])  // Конкретный URL категории как в браузере
                                    .header("Connection", "keep-alive")
                                    .header("Sec-Fetch-Dest", "empty")
                                    .header("Sec-Fetch-Mode", "cors")
                                    .header("Sec-Fetch-Site", "same-origin")
                                    .header("Priority", "u=4")
                                    .header("TE", "trailers")
                                    .header("x-requested-with", "XMLHttpRequest")
                                    .header("x-spa-version", "14.13.6")  // Обновленная версия
                                    .header("deviceid", "site_e51163b702ac40d3b293a9ccc7c333b8")
                                    .method(Connection.Method.GET)
                                    .ignoreContentType(true)
                                    .timeout(30_000)
                                    .followRedirects(true)
                                    .maxBodySize(0);

                            if (Cookies != null && !Cookies.isEmpty()) {
                                for (HttpCookie cookie : Cookies) {
                                    connectionPage.cookie(cookie.getName(), cookie.getValue());
                                }
                            }
                            
                            // Проверяем флаг running перед выполнением запроса
                            if (!running) {
                                break;
                            }
                            
                                Connection.Response jsoupResponse = connectionPage.execute();
                                final String jsoupBody = jsoupResponse.body();
                                final int jsoupStatusCode = jsoupResponse.statusCode();
                                final java.net.URI requestUri = java.net.URI.create(currentPage);
                                responsePage = new WbHttpClient.HttpResponse<String>() {
                                    @Override public int statusCode() { return jsoupStatusCode; }
                                    @Override public java.net.http.HttpRequest request() { return null; }
                                    @Override public java.util.Optional<WbHttpClient.HttpResponse<String>> previousResponse() { return java.util.Optional.empty(); }
                                    @Override public java.net.http.HttpHeaders headers() { 
                                        Map<String, String> jsoupHeaders = jsoupResponse.headers();
                                        java.util.Map<String, java.util.List<String>> headersMap = new java.util.HashMap<>();
                                        for (Map.Entry<String, String> entry : jsoupHeaders.entrySet()) {
                                            headersMap.put(entry.getKey(), java.util.Collections.singletonList(entry.getValue()));
                                        }
                                        return java.net.http.HttpHeaders.of(headersMap, (k, v) -> true);
                                    }
                                    @Override public String body() { return jsoupBody; }
                                    @Override public java.net.URI uri() { return requestUri; }
                                };
                            }
                            
                            int statusCode = responsePage.statusCode();
                            
                            // Увеличиваем счетчик общего количества запросов
                            totalRequests.getAndIncrement();
                            
                            // Пропускаем ошибки 429, 404, 498 (это нормально, не считаем ошибками)
                            if (statusCode == 404) {
                                error404.getAndIncrement();
                                continue; // 404 - категория не существует, это нормально
                            }
                            if (statusCode == 429) {
                                error429.getAndIncrement();
                                continue; // 429 - rate limit, это нормально
                            }
                            if (statusCode == 498) {
                                error498.getAndIncrement();
                                continue; // 498 - антибот, это нормально
                            }
                            
                            if (statusCode != 200) {
                                // HTTP статус != 200 - не критично, просто пропускаем страницу
                                // Уже обработали 404, 429, 498 выше, здесь остальные (403, 500 и т.д.)
                                errorHttpStatus.getAndIncrement();
                                errorOther.getAndIncrement();
                                // Логируем первые несколько для диагностики
                                continue;
                            }
                            
                            String jsons = responsePage.body();
                            if (jsons == null || jsons.trim().isEmpty()) {
                                errorEmpty.getAndIncrement();
                                // Пустой ответ - не считаем ошибкой, просто пропускаем
                                continue;
                            }
                            
                            // Проверяем, что ответ действительно JSON (а не HTML антибот страница или сжатые данные)
                            String trimmed = jsons.trim();
                            if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) {
                                // Ответ не JSON - это может быть HTML антибот страница или сжатые данные
                                if (trimmed.toLowerCase().contains("<!doctype") || trimmed.toLowerCase().contains("<html")) {
                                    errorHtml.getAndIncrement();
                                    i.getAndIncrement(); // КРИТИЧНО: HTML вместо JSON
                                } else {
                                    errorParse.getAndIncrement();
                                    i.getAndIncrement(); // КРИТИЧНО: не JSON и не HTML (возможно сжатые данные)
                                }
                                continue;
                            }
                            
                            Gson gson = new Gson();
                            Data data = null;
                            
                            // ПРИОРИТЕТ 1: Пробуем парсить как простой объект с products (новый формат: {"products": [...]})
                            // Это самый распространенный формат сейчас
                            try {
                                JsonObject jsonObject = gson.fromJson(jsons, JsonObject.class);
                                if (jsonObject != null && jsonObject.has("products")) {
                                    JsonArray productsArray = jsonObject.getAsJsonArray("products");
                                    if (productsArray != null && productsArray.size() > 0) {
                                        // Создаем Data объект для совместимости
                                        data = new Data();
                                        data.products = gson.fromJson(productsArray, new TypeToken<List<Product>>(){}.getType());
                                        
                                        // Проверяем, есть ли total в JSON
                                        if (jsonObject.has("total") && !jsonObject.get("total").isJsonNull()) {
                                            data.total = jsonObject.get("total").getAsInt();
                                        } else {
                                            data.total = 0; // total отсутствует в ответе
                                        }
                                        
                                        // Gson автоматически конвертирует числа в строки для String полей
                                        // Но на всякий случай проверяем и конвертируем явно, если нужно
                                        if (data.products != null) {
                                            for (Product product : data.products) {
                                                // Если id null или пустой, это проблема, но Gson должен это обработать
                                                // feedbackPoints тоже должен быть автоматически сконвертирован
                                            }
                                        }
                                    }
                                }
                            } catch (Exception e) {
                                // Пробуем другие форматы
                            }
                            
                            // ПРИОРИТЕТ 2: Пробуем парсить как Data (может быть с total или без)
                            if (data == null || data.products == null || data.products.isEmpty()) {
                            try {
                                data = gson.fromJson(jsons, Data.class);
                                // Если total = 0, но есть products, это нормально - просто нет total в ответе
                                if (data != null && data.products == null) {
                                    data = null; // Сбрасываем, если products отсутствует
                                }
                            } catch (Exception e) {
                                // Пробуем парсить как Root (с оберткой data)
                                try {
                                    Root root = gson.fromJson(jsons, Root.class);
                                    if (root != null && root.data != null) {
                                        data = root.data;
                                    }
                                } catch (Exception e2) {
                                        // Ошибка парсинга JSON - возможно, формат ответа не соответствует ожидаемому
                                        // Это не критично - просто пропускаем эту страницу
                                        errorParse.getAndIncrement();
                                        continue;
                                    }
                                }
                            }
                            
                            if(data == null || data.products == null){
                                // Нет данных или товаров - это нормально, просто пропускаем
                                continue;
                            }

                            List<String> newItem = new ArrayList<>();
                            int productsOnPage = 0;
                            for (Product product : data.products) {
                                String article = product.id != null ? product.id : "0";
                                if (!newItem.contains(article)) {
                                    newItem.add(article);
                                    
                                    // Пропускаем товары с null или пустым feedbackPoints
                                    if (product.feedbackPoints == null || product.feedbackPoints.isEmpty() || product.feedbackPoints.equals("0")) {
                                        continue;
                                    }
                                    
                                    String itemName = product.name != null ? product.name : " ";
                                    String feedBackSum = product.feedbackPoints;
                                    String totalQuery = product.totalQuantity != null ? product.totalQuantity : "0";
                                    String supplierRaw = product.supplier != null ? product.supplier.trim() : "";
                                    if(isSupplierBlocked(supplierRaw, product.supplierId)){
                                        continue;
                                    }
                                    int total = 0;
                                    if (product.sizes != null) {
                                        for (Size size : product.sizes) {
                                            if (size.price != null && size.price.product != 0) {
                                                total = size.price.product / 100;
                                                break;
                                            }
                                        }
                                    }
                                    // Пропускаем товары с нулевой ценой
                                    if (total == 0) {
                                        continue;
                                    }
                                    
                                    readTxtFile(itemName, String.valueOf(total), feedBackSum, article, totalQuery, url[0]);
                                    productsOnPage++;
                                }
                            }
                            // Увеличиваем счетчик товаров
                            totalProducts.addAndGet(productsOnPage);
                            
                            // Увеличиваем счетчик обработанных страниц только если страница успешно обработана
                            if (statusCode == 200 && data != null && data.products != null) {
                                it.getAndIncrement();
                                totalPages.getAndIncrement();
                            }
                        } catch (Exception e) {
                            // Неожиданная ошибка при обработке страницы
                            errorGeneralException.getAndIncrement();
                            errorOther.getAndIncrement();
                            // Логируем первые несколько для диагностики
                        }
                    }
                } finally {
                    // Статистика работы потока (убрано для уменьшения логов)
                }
            });
        }
        
        
        // Проверяем, не остановлена ли работа перед завершением
        if (!running) {
            // Если работа остановлена, мягко завершаем все задачи (ждем их завершения)
            discoveryService.shutdown();
            executorService.shutdown();
            
            // Ждем завершения всех задач с максимальным таймаутом 45 секунд
            try {
                long startWait = System.currentTimeMillis();
                long maxWaitTime = 45_000; // 45 секунд в миллисекундах
                
                // Ждем завершения discovery service (максимум 45 секунд)
                while (!discoveryService.isTerminated() && (System.currentTimeMillis() - startWait) < maxWaitTime) {
                    if (!discoveryService.awaitTermination(5, TimeUnit.SECONDS)) {
                        // Продолжаем ждать, если не истек общий таймаут
                        continue;
                    }
                }
                
                // Если discovery service не завершился за 45 секунд, принудительно останавливаем
                if (!discoveryService.isTerminated()) {
                    log.warn("Discovery service did not terminate within 45 seconds, forcing shutdown");
                    discoveryService.shutdownNow();
                }
                
                // Ждем завершения executor service (максимум 45 секунд от начала ожидания)
                startWait = System.currentTimeMillis();
                while (!executorService.isTerminated() && (System.currentTimeMillis() - startWait) < maxWaitTime) {
                    if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                        // Продолжаем ждать, если не истек общий таймаут
                        continue;
                    }
                }
                
                // Если executor service не завершился за 45 секунд, принудительно останавливаем
                if (!executorService.isTerminated()) {
                    executorService.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                // При прерывании принудительно останавливаем
                discoveryService.shutdownNow();
                executorService.shutdownNow();
            } finally {
                activeExecutors.remove(discoveryService);
                activeExecutors.remove(executorService);
            }
            return;
        }
        
        // Ждем завершения обработки всех страниц
        // Упрощенная логика: просто ждем с таймаутом
        try {
            
            // Вызываем shutdown() чтобы executor service не принимал новые задачи
            executorService.shutdown();
            discoveryService.shutdown();
            
            // Просто ждем завершения executor service с таймаутом
            boolean executorCompleted = executorService.awaitTermination(remainingTimeForExecutor, TimeUnit.MILLISECONDS);
            if (!executorCompleted) {
                log.warn("Executor service did not terminate within {} ms, forcing shutdown", remainingTimeForExecutor);
                executorService.shutdownNow();
            }
            
            long executorElapsedTime = System.currentTimeMillis() - executorPhaseStartTime;
            int finalQueueSize = pageQueue.size();
            int totalProductsCount = totalProducts.get();
            int totalPagesCount = totalPages.get();
            long totalCycleTime = System.currentTimeMillis() - cycleStartTime;
            
            // Вычисляем количество отправленных товаров за этот цикл (разница с предыдущими значениями)
            int sent100 = sentTo100.get() - prevSentTo100.get();
            int sent90 = sentTo90.get() - prevSentTo90.get();
            int sent80 = sentTo80.get() - prevSentTo80.get();
            int sentBig = sentToBig.get() - prevSentToBig.get();
            int sentCommunity = sentToCommunity.get() - prevSentToCommunity.get();
            int sentFood = sentToFood.get() - prevSentToFood.get();
            int sentDetyam = sentToDetyam.get() - prevSentToDetyam.get();
            int sentFree = sentToFree.get() - prevSentToFree.get();
            
            log.info("Cycle completed: total time={} ms, executor time={} ms, pages processed={}, products processed={}, remaining queue={}, sent: 100={}, 90={}, 80={}, Big={}, Community={}, Food={}, Detyam={}, Free={}", 
                    totalCycleTime, executorElapsedTime, totalPagesCount, totalProductsCount, finalQueueSize,
                    sent100, sent90, sent80, sentBig, sentCommunity, sentFood, sentDetyam, sentFree);
            
            // Обновляем предыдущие значения ПОСЛЕ логирования для следующего цикла
            // Обновляем только если это первый парсер (version=true), чтобы избежать конфликтов
            if (version) {
                prevSentTo100.set(sentTo100.get());
                prevSentTo90.set(sentTo90.get());
                prevSentTo80.set(sentTo80.get());
                prevSentToBig.set(sentToBig.get());
                prevSentToCommunity.set(sentToCommunity.get());
                prevSentToFood.set(sentToFood.get());
                prevSentToDetyam.set(sentToDetyam.get());
                prevSentToFree.set(sentToFree.get());
            }
            
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            // При прерывании принудительно останавливаем
            discoveryService.shutdownNow();
            executorService.shutdownNow();
        } finally {
            // Удаляем из списка активных после завершения
            activeExecutors.remove(discoveryService);
            activeExecutors.remove(executorService);
        }
        
        if(version){
            numberPagesProcessed[0] = Integer.parseInt(String.valueOf(it));
        }else {
            numberPagesProcessed[1] = Integer.parseInt(String.valueOf(it));
        }
        int remainingPages = pageQueue.size();
        // Проверяем процент ошибок 498 (антибот)
        int totalRequestsCount = totalRequests.get();
        int error498Count = error498.get();
        if (totalRequestsCount > 0) {
            double error498Percentage = (double) error498Count / totalRequestsCount * 100.0;
            if (error498Percentage >= 90.0) {
                // Отправляем сообщение всем админам
                String alertMessage = String.format(
                    "⚠️ ВНИМАНИЕ! Высокий процент ошибок 498 (антибот): %.2f%%\n\n" +
                    "Детали:\n" +
                    "• Всего запросов: %d\n" +
                    "• Ошибок 498: %d\n" +
                    "• Ошибок 429: %d\n" +
                    "• Ошибок 404: %d\n" +
                    "• Обработано страниц: %d\n" +
                    "• Версия парсера: %s",
                    error498Percentage, totalRequestsCount, error498Count, 
                    error429.get(), error404.get(), it.get(), version ? "true" : "false"
                );
                
                // Отправляем сообщение всем админам
                for (String adminId : admin) {
                    try {
                        sendPengradMessage(adminId, alertMessage);
                    } catch (Exception e) {
                        log.error("Failed to send alert to admin {}: {}", adminId, e.getMessage());
                    }
                }
            }
        }
    
        // Вычисляем количество отправленных товаров за этот цикл (разница с предыдущими значениями)
        int sent100 = sentTo100.get() - prevSentTo100.get();
        int sent90 = sentTo90.get() - prevSentTo90.get();
        int sent80 = sentTo80.get() - prevSentTo80.get();
        int sentBig = sentToBig.get() - prevSentToBig.get();
        int sentCommunity = sentToCommunity.get() - prevSentToCommunity.get();
        int sentFood = sentToFood.get() - prevSentToFood.get();
        int sentDetyam = sentToDetyam.get() - prevSentToDetyam.get();
        int sentFree = sentToFree.get() - prevSentToFree.get();
        
        log.info("Time : {}, 429={}, 498={} Pages: {} out of {} queue {}, sent: 100={}, 90={}, 80={}, Big={}, Community={}, Food={}, Detyam={}, Free={}", 
                (System.currentTimeMillis() - startTime), error429.get(), error498.get(), 
                it.get(), pagesInQueue, remainingPages,
                sent100, sent90, sent80, sentBig, sentCommunity, sentFood, sentDetyam, sentFree);
        
    }

    private static Set<String> readPidora(String FILE_PATH) {
        return readPidora(FILE_PATH, false);
    }

    private static Set<String> readPidora(String FILE_PATH, boolean normalizeLowerCase) {
        Set<String> sentArticles = ConcurrentHashMap.newKeySet();
        File file = new File(FILE_PATH);
        try {
            if (!file.exists()) {
                file.createNewFile();
            }
            try (BufferedReader reader = new BufferedReader(new FileReader(FILE_PATH))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String value = line.trim();
                    if (value.isEmpty()) {
                        continue;
                    }
                    if (normalizeLowerCase) {
                        value = value.toLowerCase(Locale.ROOT);
                    }
                    sentArticles.add(value);
                }
            } catch (IOException e) {
                log.warn("Failed to read file {}", FILE_PATH, e);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return sentArticles;
    }

    private static boolean isSupplierBlocked(String supplierName, Long supplierId) {
        if (supplierName != null && !supplierName.isEmpty()) {
            String normalizedName = supplierName.toLowerCase(Locale.ROOT);
            if (pidory.contains(normalizedName)) {
                return true;
            }
        }
        if (supplierId != null) {
            if (pidory.contains(String.valueOf(supplierId))) {
                return true;
            }
        }
        return false;
    }

    private static String normalizeBlockedSupplierInput(String rawInput) {
        if (rawInput == null) {
            return null;
        }
        String trimmed = rawInput.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        Matcher matcher = SELLER_URL_PATTERN.matcher(trimmed);
        if (matcher.find()) {
            return matcher.group(1);
        }
        if (isDigitsOnly(trimmed)) {
            return trimmed;
        }
        return trimmed.toLowerCase(Locale.ROOT);
    }

    private static boolean isDigitsOnly(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static void readSentArticlesToCache(String filePath, Cache<String, Double> cache) {
        File file = new File(filePath);
        try {
            if (!file.exists()) {
                file.createNewFile();
            }
            try (BufferedReader reader = new BufferedReader(new FileReader(filePath))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String[] parts = line.split(" ");
                    if (parts.length == 2) {
                        try {
                            double value = Double.parseDouble(parts[1]);
                            cache.put(parts[0], value);
                        } catch (NumberFormatException e) {
                            log.warn("Invalid number format in file: {} -> {}", filePath, line);
                        }
                    }
                }
            } catch (IOException e) {
                log.warn("Failed to read sent articles file {}", filePath, e);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static void main(String[] args) throws InterruptedException {
        try {
            BotConfig cfg = BotConfig.load();
            Path cookiesFile = cfg.getPath("wb.cookies.file", "cookies.txt");
            String proxyFileRaw = cfg.getOptional("wb.proxies.file");
            Path proxyFile = (proxyFileRaw == null || proxyFileRaw.isBlank()) ? null : Path.of(proxyFileRaw);
            String proxyUsername = cfg.getOptional("wb.proxy.username");
            if (proxyUsername != null && !proxyUsername.isBlank()) {
                System.setProperty("wb.proxyUsername", proxyUsername);
            }
            String proxyPassword = cfg.getOptional("wb.proxy.password");
            if (proxyPassword != null) {
                System.setProperty("wb.proxyPassword", proxyPassword);
            }
            String proxyRequired = cfg.getOptional("wb.proxy.required");
            if (proxyRequired != null && !proxyRequired.isBlank()) {
                System.setProperty("wb.proxyRequired", proxyRequired);
            }

            // Используем WbCookieFetcher для получения куки
            RotatingCookieJar cookieJar = WbCookieFetcher.loadCookieJar(cookiesFile);
            cookiesMap = WbCookieFetcher.parseCookieHeaderToMap(cookieJar.firstCookieHeader());
            
            // Конвертируем Map в Set<HttpCookie> для обратной совместимости
                    Cookies = new HashSet<>();
                    for (Map.Entry<String, String> entry : cookiesMap.entrySet()) {
                        try {
                            HttpCookie cookie = new HttpCookie(entry.getKey(), entry.getValue());
                            cookie.setDomain(".wildberries.ru");
                            cookie.setPath("/");
                            Cookies.add(cookie);
                        } catch (IllegalArgumentException e) {
                            // Игнорируем некорректные cookies
                }
            }
            
            // Создаем WbHttpClient для HTTP-запросов
            java.util.List<ProxyConfig> proxies = ProxyLoader.loadFromFile(
                    proxyFile,
                    cfg.getOptional("wb.proxy.username"),
                    cfg.getOptional("wb.proxy.password")
            );
            wbHttpClient = new WbHttpClient(cookieJar, proxies);
            
            
            urls = getURL();
            urlsFood = readPidora("Food.txt");
            urlsDetyam = readPidora("detyam.txt");

        } catch (Exception e) {
            log.error("Startup failed", e);
            throw new IllegalStateException("Startup failed", e);
        }

        // Регистрация бота Telegram
        try {
            TelegramBotsApi botsApi = new TelegramBotsApi(DefaultBotSession.class);
            BotConfig cfg = BotConfig.load();
            String token = cfg.getRequired("bot.token");
            String username = cfg.getRequired("bot.username");
            MyDualBot bot = new MyDualBot(token, username);
            botsApi.registerBot(bot);
            log.info("Telegram bot registered successfully as {}", username);
            
            // Автоматический запуск задач после регистрации бота
            // Даем небольшую задержку для завершения регистрации
            new Thread(() -> {
                try {
                    Thread.sleep(2000); // 2 секунды задержки
                    bot.startTaskAuto();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.error("Auto-start thread interrupted", e);
                } catch (Exception e) {
                    log.error("Auto-start failed", e);
                }
            }, "wb-auto-start").start();

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                running = false;
                log.info("Shutdown requested, stopping bot tasks");
            }, "wb-shutdown"));
            new CountDownLatch(1).await();
        } catch (Exception e) {
            log.error("Telegram bot registration failed", e);
            throw new IllegalStateException("Telegram bot registration failed", e);
        }
    }

    public void sendMessage(String chatId, Integer messageThreadId, String messageText) throws IOException {
        boolean sent = false;
        while (!sent) {
            SendMessage sendMessage = new SendMessage(chatId, messageText).messageThreadId(messageThreadId).parseMode(ParseMode.HTML);
            SendResponse response = pengradBot.execute(sendMessage);
            if (response.isOk()) {
                sent = true;
            } else {
                int retryAfter = getRetryAfter(response);
                if (retryAfter > 0) {
                    try {
                        Thread.sleep(retryAfter * 1000L);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        log.warn("Interrupted while waiting retry-after for chatId={}", chatId);
                        break;
                    }
                } else {
                    break;
                }
            }
        }
    }

    public void sendPhoto(String chatId, Integer messageThreadId, String messageText, byte[] imageBytes) throws IOException {
        boolean sent = false;
        while (!sent) {
            try {

                SendPhoto sendPhoto = new SendPhoto(chatId, imageBytes);
                sendPhoto.fileName("photo.jpg");
                sendPhoto.caption(messageText);
                sendPhoto.parseMode(ParseMode.HTML);
                if (messageThreadId != null) {
                    sendPhoto.messageThreadId(messageThreadId);
                }

                // Выполняем запрос с использованием библиотеки com.pengrad.telegrambot
                SendResponse response = pengradBot.execute(sendPhoto);
                if (response.isOk()) {
                    sent = true;
                } else {
                    int retryAfter = getRetryAfter(response);
                    if (retryAfter > 0) {
                        Thread.sleep(retryAfter * 1000L);
                    } else {
                        throw new RuntimeException("Failed to send message: " + response.description());
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Thread was interrupted", e);
            } catch (Exception e) {
                throw new RuntimeException("Failed to send message", e);
            }
        }
    }

    public static void readTxtFile(String itemName, String itemCost, String itemFeedBackCost, String article, String totalQuery, String category) throws IOException, InterruptedException {
        // Проверка на null или пустой feedbackPoints
        if (itemFeedBackCost == null || itemFeedBackCost.isEmpty() || itemFeedBackCost.equals("0")) {
            return; // Пропускаем товары с null или нулевым feedbackPoints
        }
        
        if(Objects.equals(itemCost,"0")){
            itemCost = String.valueOf(hasFeedbackPoints(article));
            // Если не удалось получить цену, пропускаем товар
            if (itemCost == null || itemCost.equals("0") || itemCost.equals("0.0")) {
                return;
            }
        }
        
        // Проверка на деление на ноль
        double costValue = Double.parseDouble(itemCost);
        if (costValue == 0) {
            return; // Пропускаем товары с нулевой ценой
        }
        
        double percent = Double.parseDouble(itemFeedBackCost) / costValue;
        ProductInfo productInfo = new ProductInfo();
        Double old100 = sentArticles100.getIfPresent(article);
        Double old90  = sentArticles90.getIfPresent(article);
        Double old80  = sentArticles80.getIfPresent(article);
        Double oldBig = sentArticlesBig.getIfPresent(article);
        Double oldCommunity =  sentArticlesCommunity.getIfPresent(article);
        Double oldFood = sentArticlesFood.getIfPresent(article);
        Double oldDetyam = sentArticlesDetyam.getIfPresent(article);
        Double oldFree = sentArticlesFree.getIfPresent(article);
        boolean absent = old100 == null && old90 == null && old80 == null && oldBig == null;
        final double RESEND_THRESHOLD = 0.15;
        boolean changed =
                (old100 != null && Math.abs(old100 - percent) > RESEND_THRESHOLD) ||
                        (old90  != null && Math.abs(old90  - percent) > RESEND_THRESHOLD) ||
                        (old80  != null && Math.abs(old80  - percent) > RESEND_THRESHOLD) ||
                        (oldBig != null && Math.abs(oldBig - percent) > RESEND_THRESHOLD) ||
                        (oldFree != null && Math.abs(oldFree - percent) > RESEND_THRESHOLD);
        if(absent || changed){
            String message;
            if (percent >= 1) {
                message = createMessage(itemName, itemCost, itemFeedBackCost, article,percent,totalQuery);
                queue100.add(message);
//                productInfo.setquantity(String.valueOf(Integer.parseInt(totalQuery)));
//                productInfo.settime(System.currentTimeMillis());
//                mapOnSent.put(article, productInfo);
            } else if (percent >= 0.9) {
                message = createMessage(itemName, itemCost, itemFeedBackCost, article,percent,totalQuery);
                queue90.add(message);
//                productInfo.setquantity(String.valueOf(Integer.parseInt(totalQuery)));
//                productInfo.settime(System.currentTimeMillis());
//                mapOnSent.put(article, productInfo);
            } else if (percent >= 0.8) {
                message = createMessage(itemName, itemCost, itemFeedBackCost, article,percent,totalQuery);
                queue80.add(message);
//                productInfo.setquantity(String.valueOf(Integer.parseInt(totalQuery)));
//                productInfo.settime(System.currentTimeMillis());
//                mapOnSent.put(article, productInfo);
            }else if (percent >0.65){
                message = createMessage(itemName, itemCost, itemFeedBackCost, article,percent,totalQuery);
                queueFree.add(message);
            }
            if (((percent > 0.49 && Integer.parseInt(itemFeedBackCost) >= 1000 && Integer.parseInt(itemFeedBackCost) < 2500)
                    || (percent > 0.59 && Integer.parseInt(itemFeedBackCost) >= 699 && Integer.parseInt(itemFeedBackCost) < 1000 && percent < 0.9)
                    || (percent >= 0.4 && Integer.parseInt(itemFeedBackCost) >= 2500))) {

                message = createMessage(itemName, itemCost, itemFeedBackCost, article, percent, totalQuery);
                queueBig.add(message);
//                productInfo.setquantity(String.valueOf(Integer.parseInt(totalQuery)));
//                productInfo.settime(System.currentTimeMillis());
//                mapOnSent.put(article, productInfo);
            }
        }
        if (oldCommunity == null || Math.abs(oldCommunity - percent) > RESEND_THRESHOLD) {
            String message;

            if (percent >= 1.5 || (Double.parseDouble(itemFeedBackCost) - Double.parseDouble(itemCost) >= 199 && percent > 1)) {
                message = createMessage(itemName, itemCost, itemFeedBackCost, article,percent,totalQuery);
                // НЕ обновляем кэш здесь - кэш обновится в runSender после отправки
                // Это предотвращает ситуацию, когда товар добавлен в очередь, но не отправлен
                productInfo.setquantity(String.valueOf(Integer.parseInt(totalQuery)));
                productInfo.settime(System.currentTimeMillis());
                queueMyChat.add(message);
            }
        }
        if((oldFood==null || Math.abs(oldFood - percent) > RESEND_THRESHOLD) && urlsFood.contains(category)){
            String message;

            if (percent >= 0.45) {
                message = createMessage(itemName, itemCost, itemFeedBackCost, article,percent,totalQuery);
                queueFood.add(message);
                // НЕ обновляем кэш здесь - кэш обновится в runSender после отправки
                // Это предотвращает ситуацию, когда товар добавлен в очередь, но не отправлен
//                productInfo.setquantity(String.valueOf(Integer.parseInt(totalQuery)));
//                productInfo.settime(System.currentTimeMillis());
//                mapOnSent.put(article, productInfo);
            }
        }
        if((oldDetyam==null || Math.abs(oldDetyam - percent) > RESEND_THRESHOLD) && urlsDetyam.contains(category)){
            String message;

            if (percent >= 0.5) {
                message = createMessage(itemName, itemCost, itemFeedBackCost, article,percent,totalQuery);
                queueDetyam.add(message);
                // НЕ обновляем кэш здесь - кэш обновится в runSender после отправки
                // Это предотвращает ситуацию, когда товар добавлен в очередь, но не отправлен
//                productInfo.setquantity(String.valueOf(Integer.parseInt(totalQuery)));
//                productInfo.settime(System.currentTimeMillis());
//                mapOnSent.put(article, productInfo);
            }
        }
    }




    // ExecutorService для управления отложенными отправками в бесплатный чат
//    private static final ScheduledExecutorService freeChatScheduler =
//            java.util.concurrent.Executors.newScheduledThreadPool(10);

    private static void runSender(String fileName,
                                  BlockingQueue<String> queue,
                                  Cache<String, Double> cache,
                                  String chatId,
                                  Integer threadId,
                                  String secondChatId) throws InterruptedException {

        BotConfig cfg = BotConfig.load();
        MyDualBot tgBot = new MyDualBot(cfg.getRequired("bot.token"), cfg.getRequired("bot.username"));
//         String freeChatId = "-1002346226214"; // ID бесплатного чата
//        String freeChatId = "-1002397733938";
        Path path = Path.of(FILE_PATH + fileName);

        try (BufferedWriter writer = Files.newBufferedWriter(path, CREATE, APPEND)) {
            log.info("runSender started for {} with queue size: {}", fileName, queue.size());
            while (running || !queue.isEmpty()) {
                String data = queue.take();
                if ("0~~0~~0".equals(data)) {
                    log.info("runSender stopping for {} (stop signal received)", fileName);
                    return;
                }
                String[] parts = data.split("~~", 3);
                if (parts.length < 3) {
                    log.warn("Invalid data format in queue for {}: {}", fileName, data);
                    continue;
                }
                String article = parts[0];
                String productInfo = parts[1];
                double percent = Double.parseDouble(parts[2]);
                log.debug("Processing article {} from queue {} with percent {}", article, fileName, percent);

                // Проверяем кэш - если товар уже был отправлен с таким же процентом, пропускаем
                // Это защита от дубликатов: если товар уже был отправлен ранее
                // Используем тот же порог, что и в readTxtFile (0.15), чтобы избежать проблем
                Double old = cache.getIfPresent(article);
                if (old == null || Math.abs(old - percent) > 0.15) {
                    try {
                        // основной канал
                        tgBot.sendMessage(chatId, threadId, productInfo);
                        // Увеличиваем счетчик отправленных товаров в зависимости от типа чата
                        if (fileName.equals("100.txt")) {
                            sentTo100.incrementAndGet();
                        } else if (fileName.equals("90.txt")) {
                            sentTo90.incrementAndGet();
                        } else if (fileName.equals("80.txt")) {
                            sentTo80.incrementAndGet();
                        } else if (fileName.equals("Big.txt")) {
                            sentToBig.incrementAndGet();
                        } else if (fileName.equals("_community.txt")) {
                            sentToCommunity.incrementAndGet();
                        } else if (fileName.equals("Food.txt")) {
                            sentToFood.incrementAndGet();
                        } else if (fileName.equals("detyam.txt")) {
                            sentToDetyam.incrementAndGet();
                        } else if (fileName.equals("free.txt")) {
                            sentToFree.incrementAndGet();
                        }
                        // второй канал (если указан)
                        if (secondChatId != null) {
                            tgBot.sendMessage(secondChatId, 0, productInfo);
                        }
                        
                        // Обновляем кэш ТОЛЬКО после успешной отправки
                        // Это предотвращает дубликаты: если товар попадет в readTxtFile повторно до обработки,
                        // он не будет добавлен в очередь (old != null)
                        cache.put(article, percent);
                        
                        writer.write(article + " " + percent);
                        writer.newLine();
                        writer.flush();
                        
                        // Отправка в бесплатный чат через 4 минуты (только если товар еще не был отправлен)
                        // Делаем это только после успешной отправки в основной чат
//                        productInfo += "\n\n <a href=\"https://t.me/WB_Jackpot/3793\">\uD83D\uDCB0Товар найден группой WB_Jackpot. Присоединяйтесь!\uD83D\uDCB0</a>";
//                        final String finalArticle = article;
//                        final String finalFreeProductInfo = productInfo;

                        // Используем ScheduledExecutorService для отложенной отправки (не блокирует поток)
//                        freeChatScheduler.schedule(() -> {
//                        try {
//                            if (running && isFree) {
//                                // Проверяем, не был ли товар уже отправлен в бесплатный чат
//                                synchronized (mapOnSentFree) {
//                                    Long lastSentTime = mapOnSentFree.get(finalArticle);
//                                    if (lastSentTime != null) {
//                                        // Товар уже был отправлен в бесплатный чат - пропускаем
//                                        return;
//                                    }
//                                    // Помечаем товар как отправленный в бесплатный чат
//                                    mapOnSentFree.put(finalArticle, System.currentTimeMillis());
//                                }
//
//                                // Загружаем изображение для бесплатного чата
//                                byte[] imageBytes = new byte[0];
//                                for (int i = 1; i <= 40 ; i++) {
//                                    String url = (i < 10) ? "https://basket-0" + i + ".wbbasket.ru/vol" + finalArticle.substring(0, finalArticle.length() - 5) + "/part" + finalArticle.substring(0, finalArticle.length() - 3) + "/" + finalArticle + "/images/c516x688/1.webp" : "https://basket-" + i + ".wbbasket.ru/vol" + finalArticle.substring(0, finalArticle.length() - 5) + "/part" + finalArticle.substring(0, finalArticle.length() - 3) + "/" + finalArticle + "/images/c516x688/1.webp";
//
//                                    int statusCode = checkLinkStatus(url);
//                                    if (statusCode == 200) {
//                                        try {
//                                            imageBytes = downloadImageToBuffer(url);
//                                            break;
//                                        } catch (IOException e) {
//                                            // Продолжаем поиск изображения
//                                        }
//                                    }
//                                }
//
//                                if (imageBytes == null || imageBytes.length == 0) {
//                                    tgBot.sendMessage(freeChatId, 0, finalFreeProductInfo);
//                                } else {
//                                    tgBot.sendPhoto(freeChatId, 0, finalFreeProductInfo, imageBytes);
//                                }
//                                // Увеличиваем счетчик отправленных товаров в бесплатный чат
//                                sentToFree.incrementAndGet();
//                            }
//                        } catch (IOException e) {
//                            log.warn("Failed to send free chat message/photo", e);
//                        }
//                    }, 4, TimeUnit.MINUTES);
                    } catch (Exception e) {
                        // Если отправка не удалась, НЕ обновляем кэш, чтобы товар можно было отправить позже
                        log.warn("Failed to send message for article {} to chat {}, will retry later", article, chatId, e);
                        // НЕ возвращаем в очередь, чтобы избежать бесконечного цикла
                        // Товар будет добавлен в очередь снова при следующем парсинге, если процент изменится
                    }
                } else {
                    // Товар уже был отправлен с таким же процентом - пропускаем
                    // Это нормально, так как товар мог быть добавлен в очередь несколько раз
                    // до обработки в runSender
                    log.debug("Skipping article {} - already sent with similar percent (old: {}, new: {})", article, old, percent);
                }
            }
        } catch (IOException e) {
            log.warn("runSender failed", e);
        }
    }
    public static int checkLinkStatus(String imageUrl) {
        try {
            URL url = new URL(imageUrl);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            connection.connect();
            return connection.getResponseCode();
        } catch (Exception e) {
            return -1;
        }
    }

    public static byte[] downloadImageToBuffer(String imageUrl) throws IOException {
        URL url = new URL(imageUrl);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(5000);

        try (InputStream in = connection.getInputStream();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[1024];
            int bytesRead;
            while ((bytesRead = in.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
            }
            return out.toByteArray();
        } finally {
            connection.disconnect();
        }
    }

    private static void sentFree() throws InterruptedException {
        // Метод больше не используется - отправка в бесплатный чат теперь происходит через runSender
        // Оставляем пустую реализацию для совместимости
        while (running && isFree) {
            Thread.sleep(1000); // Просто ждем, чтобы не загружать CPU
        }
    }

    public static double hasFeedbackPoints(String url1) throws IOException, InterruptedException {
        String card = "https://card.wb.ru/cards/v2/detail?appType=1&curr=rub&dest=-5923914&spp=30&ab_testing=false&nm="+ url1;
        Connection connection = Jsoup.connect(card)
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:132.0) Gecko/20100101 Firefox/132.0")
                .method(Connection.Method.GET)
                .ignoreContentType(true);

        for (HttpCookie cookie : Cookies) {
            connection.cookie(cookie.getName(), cookie.getValue());
        }
        Connection.Response response = connection.execute();

        String json = response.body();

        Gson gson = new Gson();
        // API card.wb.ru возвращает {"data":{"products":...}}, используем Root
        Root root = gson.fromJson(json, Root.class);
        
        if (root == null || root.data == null || root.data.products == null) {
            return 0;
        }

        for (Product product : root.data.products) {

            if (product.feedbackPoints != null && !product.feedbackPoints.equals("0")) {
                double total = 1;
                if (product.sizes != null) {
                    for (Size size : product.sizes) {
                        if (size.price != null && size.price.product != 0) {
                            total = (double) size.price.product / 100;
                            break;
                        }
                    }
                }
                return Double.parseDouble(product.feedbackPoints) / total;
            }
        }
        return 0;
    }

    static String createMessage(String itemName, String itemCost, String itemFeedBackCost, String article, Double percent, String totalQuery){
        String href = "https://www.wildberries.ru/catalog/" + article + "/detail.aspx";
        DecimalFormat df = new DecimalFormat("#.##");
        itemName = itemName.replace(":","");
        return article+ "~~"  + itemName + "\n\uD83D\uDCB8Стоимость " + itemCost + "\u20BD\n" +
                "\uD83C\uDFB0Кешбэк " + itemFeedBackCost + "\u20BD\n " +
                "\uD83D\uDCAFПроцент выгоды " + df.format(percent * 100) + "%\n" +
                "\uD83C\uDFB2Количество " + totalQuery + "\n"+ href + "~~" + percent;
    }

    public static List<String[]> getURL() throws IOException {
        if (wbHttpClient != null) {
            String promotionsUrl = "https://static-basket-01.wbbasket.ru/vol0/data/promotions/rubli-za-otzyvy-v3.json";
            WbHttpClient.HttpResponse<String> response = wbHttpClient.get(
                    promotionsUrl,
                    Map.of("Referer", "https://www.wildberries.ru/"));
            return parsePromotionsJson(response.body(), response.statusCode());
        }

        // Используем User-Agent из реального браузера (Firefox)
        String userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:144.0) Gecko/20100101 Firefox/144.0";
        
        Connection connection = Jsoup.connect("https://static-basket-01.wbbasket.ru/vol0/data/promotions/rubli-za-otzyvy-v3.json")
                .userAgent(userAgent)
                .header("Accept", "*/*")
                .header("Accept-Language", "en-US,en;q=0.5")
                // Убираем Accept-Encoding, чтобы получить несжатый ответ (Jsoup не распаковывает автоматически)
                // .header("Accept-Encoding", "gzip, deflate, br, zstd")
                .header("Referer", "https://www.wildberries.ru/")
                .header("Origin", "https://www.wildberries.ru")
                // Connection header не нужен для Jsoup
                .header("Sec-Fetch-Dest", "empty")
                .header("Sec-Fetch-Mode", "cors")
                .header("Sec-Fetch-Site", "same-origin")
                .header("x-requested-with", "XMLHttpRequest")
                .method(Connection.Method.GET)
                .ignoreContentType(true)
                .timeout(20_000)
                .followRedirects(true);

        // если есть cookies
        if (Cookies != null && !Cookies.isEmpty()) {
            for (HttpCookie cookie : Cookies) {
                connection.cookie(cookie.getName(), cookie.getValue());
            }
        }

        Connection.Response response = connection.execute();
        String json = response.body();
        
        // Проверяем статус код
        int statusCode = response.statusCode();
        // Проверяем, что ответ действительно JSON (начинается с { или [)
        if (json == null || json.trim().isEmpty()) {
            throw new IOException("Empty response from JSON endpoint");
        }
        
        String trimmedJson = json.trim();
        if (!trimmedJson.startsWith("{") && !trimmedJson.startsWith("[")) {
            throw new IOException("Response is not JSON. Status: " + statusCode);
        }

        Gson gson = new Gson();
        UrlFetcher.Root root = gson.fromJson(json, UrlFetcher.Root.class);

        int action = root.promo.id;
        List<String[]> urls = new ArrayList<>();
        
        // Сбрасываем счетчики перед парсингом
        totalNodesProcessed = 0;
        filteredNodes = 0;
        duplicateNodes = 0;
        
        if (root.menu != null) {
            for (UrlFetcher.MenuNode menuNode : root.menu) {
                if (menuNode.childNodes != null) {
                    for (UrlFetcher.CategoryNode categoryNode : menuNode.childNodes) {
                        processCategoryNode(categoryNode, urls, action);
                    }
                }
            }
        }

        return urls;
    }

    private static List<String[]> parsePromotionsJson(String json, int statusCode) throws IOException {
        if (json == null || json.trim().isEmpty()) {
            throw new IOException("Empty response from JSON endpoint");
        }

        String trimmedJson = json.trim();
        if (!trimmedJson.startsWith("{") && !trimmedJson.startsWith("[")) {
            throw new IOException("Response is not JSON. Status: " + statusCode);
        }

        Gson gson = new Gson();
        UrlFetcher.Root root = gson.fromJson(json, UrlFetcher.Root.class);
        if (root == null || root.promo == null) {
            throw new IOException("Invalid promotions JSON. Status: " + statusCode);
        }

        int action = root.promo.id;
        List<String[]> urls = new ArrayList<>();

        totalNodesProcessed = 0;
        filteredNodes = 0;
        duplicateNodes = 0;

        if (root.menu != null) {
            for (UrlFetcher.MenuNode menuNode : root.menu) {
                if (menuNode.childNodes != null) {
                    for (UrlFetcher.CategoryNode categoryNode : menuNode.childNodes) {
                        processCategoryNode(categoryNode, urls, action);
                    }
                }
            }
        }

        return urls;
    }

    private static void processCategoryNode(UrlFetcher.CategoryNode node, List<String[]> urls, int action) {
        addUrlIfValid(node, urls, action);

        if (node.childNodes != null) {
            for (UrlFetcher.CategoryNode child : node.childNodes) {
                processCategoryNode(child, urls, action);
            }
        }
    }
    
    // Счетчики для диагностики парсинга категорий
    private static int totalNodesProcessed = 0;
    private static int filteredNodes = 0;
    private static int duplicateNodes = 0;

    private static void addUrlIfValid(UrlFetcher.CategoryNode node, List<String[]> urls, int action) {
        totalNodesProcessed++;
        
        if (node.url == null || node.url.isEmpty()) {
            filteredNodes++;
            return;
        }
        if (node.url.startsWith("https://vmeste.wildberries.ru")
                || node.url.startsWith("https://travel.wildberries.ru")
                || node.url.startsWith("https://digital.wildberries.ru")) {
            filteredNodes++;
            return;
        }
        if (node.shardKey == null || node.shardKey.isEmpty()) {
            filteredNodes++;
            return;
        }
        // Фильтруем blackhole - это недопустимый shardKey, который возвращает 404
        if ("blackhole".equals(node.shardKey)) {
            filteredNodes++;
            return;
        }

        // Очищаем query от action, так как action будет добавлен отдельно
        String query = node.query != null ? node.query : "";
        // Убираем action из query, если он там есть
        query = query.replaceAll("&?action=\\d+", "").replaceAll("action=\\d+&?", "");
        // Убираем лишние & в начале и конце
        query = query.trim();
        while (query.startsWith("&")) {
            query = query.substring(1);
        }
        while (query.endsWith("&")) {
            query = query.substring(0, query.length() - 1);
        }

        // Используем url как идентификатор категории
        String categoryUrl = ensureUrlStartsWithPrefix(node.url);
        
        // Проверяем, не существует ли уже такая категория
        String finalCategoryUrl = categoryUrl;
        boolean exists = urls.stream().anyMatch(u -> u[0].equals(finalCategoryUrl));
        if (!exists) {
            // Структура: [categoryUrl, shardKey, query, action]
            urls.add(new String[]{categoryUrl, node.shardKey, query, String.valueOf(action)});
        } else {
            duplicateNodes++;
        }
    }

    public static String ensureUrlStartsWithPrefix(String url) {
        String prefixDigital = "https://www.wildberries.ru";
        String prefixVmeste = "https://vmeste.wildberries.ru/";

        if (url.startsWith(prefixDigital) || url.startsWith(prefixVmeste)) {
            return url;
        }
        return prefixDigital + url;
    }
    private static List<String> repeatCheck(String article){
        List<String> sent = new ArrayList<>();
        try {
            String jsonUrl = "https://www.wildberries.ru/__internal/u-card/cards/v4/list?appType=1&curr=rub&dest=-1257786&spp=30&hide_vflags=4294967296&hide_dtype=9%3B11&ab_testing=false&lang=ru&nm="+ article;
            Connection connection = Jsoup.connect(jsonUrl)
                    .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:132.0) Gecko/20100101 Firefox/132.0")
                    .method(Connection.Method.GET)
                    .ignoreContentType(true);

            for (HttpCookie cookie : Cookies) {
                connection.cookie(cookie.getName(), cookie.getValue());
            }
            Connection.Response response = connection.execute();

            String json = response.body();

            Gson gson = new Gson();
            Data data = null;
            
            // ПРИОРИТЕТ 1: Пробуем парсить как простой объект с products (формат: {"products": [...]})
            try {
                JsonElement jsonElement = gson.fromJson(json, JsonElement.class);
                if (jsonElement != null && jsonElement.isJsonObject()) {
                    JsonObject jsonObject = jsonElement.getAsJsonObject();
                    if (jsonObject.has("products")) {
                        JsonArray productsArray = jsonObject.getAsJsonArray("products");
                        if (productsArray != null && productsArray.size() > 0) {
                            data = new Data();
                            data.products = gson.fromJson(productsArray, new TypeToken<List<Product>>(){}.getType());
                        }
                    }
                }
            } catch (Exception e) {
                // Пробуем другие форматы
            }
            
            
            if (data == null || data.products == null || data.products.isEmpty()) {
                return sent;
            }

            // Парсим JSON напрямую для доступа к feedbacks и nmFeedbacks
            JsonElement jsonElement = gson.fromJson(json, JsonElement.class);
            JsonArray productsArray = null;
            if (jsonElement != null && jsonElement.isJsonObject()) {
                JsonObject jsonObject = jsonElement.getAsJsonObject();
                if (jsonObject.has("products")) {
                    productsArray = jsonObject.getAsJsonArray("products");
                }
            }
            
            // Если не нашли products в корне, пробуем через data
            if (productsArray == null && jsonElement != null && jsonElement.isJsonObject()) {
                JsonObject jsonObject = jsonElement.getAsJsonObject();
                if (jsonObject.has("data") && jsonObject.get("data").isJsonObject()) {
                    JsonObject dataObj = jsonObject.getAsJsonObject("data");
                    if (dataObj.has("products")) {
                        productsArray = dataObj.getAsJsonArray("products");
                    }
                }
            }
            
            if (productsArray == null) {
                return sent;
            }
            
            for (JsonElement productElement : productsArray) {
                if (!productElement.isJsonObject()) continue;
                
                JsonObject productObj = productElement.getAsJsonObject();
                
                // Приоритет: nmFeedbacks > feedbacks
                String feedBackSum = null;
                if (productObj.has("nmFeedbacks") && !productObj.get("nmFeedbacks").isJsonNull()) {
                    String nmFeedbacks = productObj.get("nmFeedbacks").getAsString();
                    if (nmFeedbacks != null && !nmFeedbacks.isEmpty() && !nmFeedbacks.equals("0")) {
                        feedBackSum = nmFeedbacks;
                    }
                }
                if (feedBackSum == null && productObj.has("feedbacks") && !productObj.get("feedbacks").isJsonNull()) {
                    String feedbacks = productObj.get("feedbacks").getAsString();
                    if (feedbacks != null && !feedbacks.isEmpty() && !feedbacks.equals("0")) {
                        feedBackSum = feedbacks;
                    }
                }
                
                if (feedBackSum == null) continue;
                
                // Проверяем totalQuantity
                String totalQuantity = null;
                if (productObj.has("totalQuantity") && !productObj.get("totalQuantity").isJsonNull()) {
                    totalQuantity = productObj.get("totalQuantity").getAsString();
                }
                if (totalQuantity == null || totalQuantity.equals("0")) continue;
                
                // Получаем name
                String itemName = " ";
                if (productObj.has("name") && !productObj.get("name").isJsonNull()) {
                    itemName = productObj.get("name").getAsString();
                }
                
                // Получаем цену из sizes
                if (productObj.has("sizes") && productObj.get("sizes").isJsonArray()) {
                    JsonArray sizesArray = productObj.getAsJsonArray("sizes");
                    for (JsonElement sizeElement : sizesArray) {
                        if (!sizeElement.isJsonObject()) continue;
                        JsonObject sizeObj = sizeElement.getAsJsonObject();
                        if (sizeObj.has("price") && sizeObj.get("price").isJsonObject()) {
                            JsonObject priceObj = sizeObj.getAsJsonObject("price");
                            if (priceObj.has("product") && !priceObj.get("product").isJsonNull()) {
                                int productPrice = priceObj.get("product").getAsInt();
                                if (productPrice != 0) {
                                    int priceRub = productPrice / 100;
                                    sent.add(itemName);
                                    sent.add(String.valueOf(priceRub));
                                    sent.add(feedBackSum);
                                    sent.add(totalQuantity);
                                    break;
                                }
                            }
                        }
                    }
                }
            }
            return sent;
        }catch (Exception e){
//            e.printStackTrace();
        }
        return sent;
    }

    private static void hasPoint(Cache<String, Double> cache,
                                 String fileName) throws IOException {

        ExecutorService pool = Executors.newFixedThreadPool(100);

        Path path = Path.of(fileName);
        if (Files.exists(path)) {
            Files.lines(path)
                    .map(l -> l.split(" "))
                    .filter(p -> p.length == 2)          // article double epoch
                    .forEach(p -> cache.put(p[0], Double.parseDouble(p[1])));
        }

        Set<String> toRemove = ConcurrentHashMap.newKeySet();
        for (String article : cache.asMap().keySet()) {
            pool.submit(() -> {
                try {
                    double actual = hasFeedbackPoints(article);
                    Double stored = cache.getIfPresent(article);
                    if (stored == null || Math.abs(actual - stored) > 0.15) {
                        toRemove.add(article);
                    }
                } catch (IOException e) {
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            });
        }

        pool.shutdown();
        while (!pool.isTerminated()) {
        }

        toRemove.forEach(cache::invalidate);

        try (BufferedWriter w = Files.newBufferedWriter(path, CREATE, TRUNCATE_EXISTING)) {
            for (Map.Entry<String, Double> e : cache.asMap().entrySet()) {
                w.write(e.getKey() + " " + e.getValue());
                w.newLine();
            }
        }
    }
}
