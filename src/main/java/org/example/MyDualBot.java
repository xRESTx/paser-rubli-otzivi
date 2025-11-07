package org.example;

import com.google.gson.*;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import org.example.config.BotCommands;
import org.example.jsonmodel.UrlFetcher;
import org.example.tasks.TaskOrchestrator;
import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

import java.io.*;
import java.net.*;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
 
import java.util.*;
import java.io.BufferedWriter;
import java.io.IOException;
import java.util.concurrent.*;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import static java.nio.file.StandardOpenOption.*;

public class MyDualBot extends TelegramLongPollingBot {
    private static final String FILE_PATH = "sent_articles";
    private static final String FILE_PATH_COMMUNITY = "sent_articles_community.txt";

    private static final Logger log = LoggerFactory.getLogger(MyDualBot.class);

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

    private static final BlockingQueue<String> queue100 = new LinkedBlockingQueue<>();
    private static final BlockingQueue<String> queue90 = new LinkedBlockingQueue<>();
    private static final BlockingQueue<String> queue80 = new LinkedBlockingQueue<>();
    private static final BlockingQueue<String> queueBig = new LinkedBlockingQueue<>();
    private static final BlockingQueue<String> queueMyChat = new LinkedBlockingQueue<>();
    private static final BlockingQueue<String> queueFood = new LinkedBlockingQueue<>();
    private static final BlockingQueue<String> queueDetyam = new LinkedBlockingQueue<>();
//    private static final BlockingQueue<ProductInfo> queueStrippingLazar = new LinkedBlockingQueue<>();
    private static final BlockingQueue<String> queueFree = new LinkedBlockingQueue<>();


    static List<String[]> urls = new ArrayList<>();
    private static Set<String> urlsFood = ConcurrentHashMap.newKeySet();
    private static Set<String> urlsDetyam = ConcurrentHashMap.newKeySet();
    // Map для хранения категория -> JSON URL из wb_url_mapping.txt
    private static java.util.Map<String, String> categoryUrlMap = new ConcurrentHashMap<>();
    // Map для категорий Food и Detyam с их JSON URL
    private static java.util.Map<String, String> categoryUrlMapFood = new ConcurrentHashMap<>();
    private static java.util.Map<String, String> categoryUrlMapDetyam = new ConcurrentHashMap<>();

private final BotCommands commands = BotCommands.load(Path.of("commands.json"));
    private org.example.telegram.TelegramSender telegramSender;
	private TaskOrchestrator orchestrator;

    private static volatile boolean running = false;
    private static volatile boolean isFree = true;
    private static Set<HttpCookie> Cookies;
    public static Set<String> pidory = ConcurrentHashMap.newKeySet();
    private final Set<Long> waitingForMessage = new HashSet<>();
    private final List<String> admin = new ArrayList<>(Arrays.asList("1027094894", "1039378955","5392268853"));
    private final List<String> worker = new ArrayList<>(Arrays.asList("466086607","1039378955"));

    static Map<String, ProductInfo> mapOnSent = new HashMap<>();

    

    
    private org.example.pipeline.PipelineManager pipelineManager;
    private org.example.pipeline.ProductRouter productRouter;
    private org.example.http.CookieService cookieService;
    public MyDualBot(String botToken) {
        this.telegramSender = new org.example.telegram.TelegramSender(botToken);
    }

    public MyDualBot() {
        this.telegramSender = new org.example.telegram.TelegramSender(getBotToken());
    }

    @Override
    public String getBotUsername() {
        return "test_WBs_bot";//shovel_seller_bot
    }

    @Override
    public String getBotToken() {
        return "8253015281:AAGb2HQU7BheAlHV6YZlcfxrAOi-kPtILCc";//7564492259:AAHJFWRqVvJQuuUIVd5584h8ePoFxsg7YVc
    }

    @Override
    public void onUpdateReceived(Update update) {
        if (update.hasMessage() && update.getMessage().getText() != null) {
            String messageText = update.getMessage().getText();
            long chatId = update.getMessage().getChatId();
            log.info("Update: chatId={}, text={}", chatId, messageText);
            String regex = "^(?:\\D*\\d\\D*){3,11}$";
            Pattern pattern = Pattern.compile(regex);
            if(worker.contains(String.valueOf(chatId))){
                if(pattern.matcher(messageText).matches()){
                    log.info("Worker query detected from {}", chatId);
                    addMessage(chatId, messageText);
                }
            }

            if(admin.contains(String.valueOf(chatId))){
                if (waitingForMessage.contains(chatId)) {
                    try (BufferedWriter reader = new BufferedWriter(new FileWriter("pidory.txt", true))) {
                        pidory.add(messageText);
                        reader.write("\n" + messageText);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                    telegramSender.sendText(String.valueOf(chatId), null, "The text is written to a file!");
                    waitingForMessage.remove(chatId); // Убираем из режима ожидания
                    return;
                }
                if (commands.isRun(messageText)) {
                    log.info("Command RUN by {}", chatId);
                    startTask(chatId);
                } else if (commands.isStop(messageText)) {
                    log.info("Command STOP by {}", chatId);
                    stopTask(chatId);
                } else if (commands.isClear(messageText)) {
                    log.info("Command CLEAR by {}", chatId);
                    try {
                        clearTask(chatId);
                    } catch (IOException e) {
                        // ignore
                    }
                } else if (commands.isStopFree(messageText)) {
                    log.info("Command STOP FREE by {}", chatId);
                    isFree = false;
                    queueFree.clear();
                    queueFree.add("00");
                    telegramSender.sendText(String.valueOf(chatId), null, "Бесплатный чат остановлен");
                } else if (commands.isRunFree(messageText)) {
                    log.info("Command RUN FREE by {}", chatId);
                    queueFree.clear();
                    isFree = true;
                    telegramSender.sendText(String.valueOf(chatId), null, "Бесплатный чат запущен");
                } else if (commands.isPidory(messageText)) {
                    log.info("Command PIDORY by {}", chatId);
                    waitingForMessage.add(chatId);
                    telegramSender.sendText(String.valueOf(chatId), null, "Send pidora");
                }
            }
        }
    }
    private void addMessage(long chatId, String messageText){
        ensureRouter();
        List<String> sent = productRouter.repeatCheck(messageText);
        if(sent.isEmpty()) {
            telegramSender.sendText(String.valueOf(chatId), null, "Кешбэка нет");
            return;
        }
        try {
            productRouter.routeProduct(sent.get(0), sent.get(1), sent.get(2), messageText, sent.get(3), "", " ");
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }
        telegramSender.sendText(String.valueOf(chatId), null, "Сообщение отправлено ");
    }
    private void clearTask(long chatId) throws IOException {
        telegramSender.sendText(String.valueOf(chatId), null, "Start cleaning");
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

        telegramSender.sendText(String.valueOf(chatId), null, "Cleaning is complete");
        startTask(chatId);
    }

    private void startTask(long chatId) {
        if (running) {
            telegramSender.sendText(String.valueOf(chatId), null, "Task is already running.");
            return;
        }
        telegramSender.sendText(String.valueOf(chatId), null, "Start tasks.");

        loadCachesAndPidory();

        running = true;
        isFree = true;
        clearSentinelMarkers();

        // Загружаем cookies точно как в старом main методе
        try {
            CookieManager cookieManager = new CookieManager();
            cookieManager.setCookiePolicy(CookiePolicy.ACCEPT_ALL);
            
            HttpClient client = HttpClient.newBuilder()
                    .cookieHandler(cookieManager)
                    .build();
            
            String urlWb = "https://www.wildberries.ru/";
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(urlWb))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:132.0) Gecko/20100101 Firefox/132.0")
                    .GET()
                    .build();
            
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            log.info("Cookie loading HTTP status: {}", response.statusCode());
            
            Cookies = new HashSet<>(cookieManager.getCookieStore().getCookies());
            log.info("Initial cookies loaded: {} cookies", Cookies.size());
            
            if (Cookies.isEmpty()) {
                log.warn("No cookies received in startTask. Response status: {}, Headers: {}", 
                        response.statusCode(), response.headers().map());
            } else {
                log.debug("Cookies received in startTask: {}", Cookies.stream()
                        .map(c -> c.getName() + "=" + c.getValue().substring(0, Math.min(20, c.getValue().length())) + "...")
                        .collect(java.util.stream.Collectors.joining(", ")));
            }
            
            // Также обновляем CookieService для последующего обновления
            cookieService = new org.example.http.CookieService();
            cookieService.initAndFetch();
        } catch (Exception e) {
            log.error("Error loading cookies in startTask: {}", e.getMessage(), e);
            Cookies = new HashSet<>();
            cookieService = new org.example.http.CookieService();
            cookieService.initAndFetch();
        }

        productRouter = new org.example.pipeline.ProductRouter(
                queue100, queue90, queue80, queueBig, queueMyChat, queueFood, queueDetyam,
                sentArticles100, sentArticles90, sentArticles80, sentArticlesBig,
                sentArticlesCommunity, sentArticlesFood, sentArticlesDetyam, test,
                pidory, urlsFood, urlsDetyam, categoryUrlMapFood, categoryUrlMapDetyam,
                () -> cookieService.getCookiesSet()
        );

        pipelineManager = new org.example.pipeline.PipelineManager(urls, Cookies, productRouter);
        // Немедленно обновляем cookies в PipelineManager
        if (cookieService != null) {
            pipelineManager.updateCookies(cookieService.getCookiesSet());
        }

        orchestrator = new TaskOrchestrator(
                20,
                createThreadFactory(),
                () -> running,
                log,
                TimeUnit.SECONDS.toMillis(10));

        registerSenderTasks(orchestrator);
        orchestrator.addFixedDelayTask("free-sender", this::processFreeQueue, 0, 5000, TimeUnit.SECONDS);
        orchestrator.addFixedDelayTask("map-cleaner", this::cleanupMapOnSent, 0, 10, TimeUnit.SECONDS);
        orchestrator.addImmediateTask("pipeline", () -> pipelineManager.run(() -> running));
        orchestrator.addFixedDelayTask("cookie-refresh", this::refreshCookies, 0, 300, TimeUnit.SECONDS);

        orchestrator.start();
        log.info("All tasks started: pipeline, {} sender tasks, free-sender, map-cleaner, cookie-refresh", 7);
        log.info("Pipeline will process {} categories with up to 300 concurrent requests", urls.size());
    }

    private void stopTask(long chatId) {
        if (!running) {
        telegramSender.sendText(String.valueOf(chatId), null, "Task is not running.");
            return;
        }
        running = false;
        telegramSender.sendText(String.valueOf(chatId), null, "Wait pls.");

        if (pipelineManager != null) {
            try { pipelineManager.stop(); } catch (Throwable ignored) {}
            pipelineManager = null;
        }
        productRouter = null;
        cookieService = null;

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
        queueFree.add("00");

        if (orchestrator != null) {
            orchestrator.stop();
            orchestrator = null;
        }

        telegramSender.sendText(String.valueOf(chatId), null, "Task stopped.");
    }

    // Sending logic moved to TelegramSender

    private static Set<String> readPidora(String FILE_PATH) {
        Set<String> sentArticles = ConcurrentHashMap.newKeySet();
        File file = new File(FILE_PATH);
        try {
            if (!file.exists()) {
                file.createNewFile();
            }
            try (BufferedReader reader = new BufferedReader(new FileReader(FILE_PATH))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sentArticles.add(line);
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return sentArticles;
    }
    
    /**
     * Читает файл wb_url_mapping.txt в формате "категория№ссылка"
     * @param filePath путь к файлу
     * @return Map где ключ - категория, значение - JSON URL
     */
    private static java.util.Map<String, String> readCategoryUrlMapping(String filePath) {
        java.util.Map<String, String> mapping = new ConcurrentHashMap<>();
        File file = new File(filePath);
        try {
            if (!file.exists()) {
                log.warn("Category URL mapping file not found: {}", filePath);
                return mapping;
            }
            try (BufferedReader reader = new BufferedReader(new FileReader(filePath))) {
                String line;
                int lineNumber = 0;
                while ((line = reader.readLine()) != null) {
                    lineNumber++;
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) {
                        continue; // Пропускаем пустые строки и комментарии
                    }
                    
                    // Формат: категория№ссылка
                    if (line.contains("№")) {
                        String[] parts = line.split("№", 2);
                        if (parts.length == 2) {
                            String category = parts[0].trim();
                            String jsonUrl = parts[1].trim();
                            if (!category.isEmpty() && !jsonUrl.isEmpty()) {
                                mapping.put(category, jsonUrl);
                            } else {
                                log.debug("Skipping invalid line {} in {}: empty category or URL", lineNumber, filePath);
                            }
                        } else {
                            log.debug("Skipping invalid line {} in {}: no separator", lineNumber, filePath);
                        }
                    } else {
                        log.debug("Skipping invalid line {} in {}: no № separator", lineNumber, filePath);
                    }
                }
            } catch (IOException e) {
                log.error("Error reading category URL mapping file: {}", filePath, e);
            }
        } catch (Exception e) {
            log.error("Error processing category URL mapping file: {}", filePath, e);
        }
        return mapping;
    }
    
    /**
     * Фильтрует Map категорий по заданному набору категорий
     * @param categoryUrlMap полный Map категорий
     * @param categorySet набор категорий для фильтрации
     * @return отфильтрованный Map
     */
    private static java.util.Map<String, String> filterCategoryUrlMap(
            java.util.Map<String, String> categoryUrlMap, Set<String> categorySet) {
        java.util.Map<String, String> filtered = new ConcurrentHashMap<>();
        for (String category : categorySet) {
            String jsonUrl = categoryUrlMap.get(category);
            if (jsonUrl != null && !jsonUrl.isEmpty()) {
                filtered.put(category, jsonUrl);
            }
        }
        return filtered;
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
                            System.err.println("Invalid number format in file: " + filePath + " -> " + line);
                        }
                    }
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static void main(String[] args) {
        // Установка CookieManager
        CookieManager cookieManager = new CookieManager();

        cookieManager.setCookiePolicy(CookiePolicy.ACCEPT_ALL);

        // Создание HttpClient с поддержкой CookieManager
        HttpClient client = HttpClient.newBuilder()
                .cookieHandler(cookieManager)
                .build();

        String urlWb = "https://www.wildberries.ru/";

        try {
            // Отправка запроса к сайту Wildberries
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(urlWb))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:132.0) Gecko/20100101 Firefox/132.0")
                    .GET()
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            // Проверка успешности запроса
            log.info("Cookie loading HTTP status: {}", response.statusCode());

            // Извлечение cookies
            Cookies = new HashSet<>(cookieManager.getCookieStore().getCookies());
            log.info("Cookies loaded: {} cookies", Cookies.size());
            
            if (Cookies.isEmpty()) {
                log.warn("No cookies received in main. Response status: {}, Headers: {}", 
                        response.statusCode(), response.headers().map());
            } else {
                log.debug("Cookies received in main: {}", Cookies.stream()
                        .map(c -> c.getName() + "=" + c.getValue().substring(0, Math.min(20, c.getValue().length())) + "...")
                        .collect(java.util.stream.Collectors.joining(", ")));
            }
            urls = getURL();
            log.info("Categories loaded: {} categories", urls.size());
            urlsFood = readPidora("Food.txt");
            urlsDetyam = readPidora("detyam.txt");
            log.info("Food categories: {}, Detyam categories: {}", urlsFood.size(), urlsDetyam.size());
            
            // Читаем маппинг категорий на JSON URL из wb_url_mapping.txt
            categoryUrlMap = readCategoryUrlMapping("wb_url_mapping.txt");
            log.info("Category URL mapping loaded: {} entries", categoryUrlMap.size());
            
            // Фильтруем Map для Food и Detyam категорий
            categoryUrlMapFood = filterCategoryUrlMap(categoryUrlMap, urlsFood);
            categoryUrlMapDetyam = filterCategoryUrlMap(categoryUrlMap, urlsDetyam);
            log.info("Food category URLs: {}, Detyam category URLs: {}", 
                    categoryUrlMapFood.size(), categoryUrlMapDetyam.size());

        } catch (Exception e) {
            log.error("Failed to initialize WB data", e);
            e.printStackTrace();
        }

        // Регистрация бота Telegram
        try {
            TelegramBotsApi botsApi = new TelegramBotsApi(DefaultBotSession.class);
            MyDualBot bot = new MyDualBot();
            botsApi.registerBot(bot);
            log.info("Bot registered: username={}, token={}...", bot.getBotUsername(), bot.getBotToken().substring(0, Math.min(10, bot.getBotToken().length())));
            log.info("Bot is running and waiting for commands...");
        } catch (TelegramApiException e) {
            log.error("Failed to register bot", e);
            e.printStackTrace();
        }

    }

    private static void runSender(String fileName,
                                  BlockingQueue<String> queue,
                                  Cache<String, Double> cache,
                                  String chatId,
                                  Integer threadId,
                                  String secondChatId,
                                  org.example.telegram.TelegramSender tgSender) throws InterruptedException {
        Path path = Path.of(FILE_PATH + fileName);
        // общий single-writer
        org.example.io.FileSingleWriter singleWriter = Holder.FILE_WRITER;
        if (!Holder.WRITER_STARTED.getAndSet(true)) {
            Thread t = new Thread(singleWriter, "file-single-writer");
            t.setDaemon(true);
            t.start();
        }

        while (running || !queue.isEmpty()) {
            String data = queue.take();
            if ("0~~0~~0".equals(data)) return;
            String[] parts = data.split("~~", 3);
            String article = parts[0];
            String productInfo = parts[1];
            double percent = Double.parseDouble(parts[2]);

            Double old = cache.getIfPresent(article);
            if (old == null) {
                cache.put(article, percent);

                // основной канал
                tgSender.sendText(chatId, threadId, productInfo);
                // второй канал (если указан)
                if (secondChatId != null) {
                    tgSender.sendText(secondChatId, 0, productInfo);
                }

                singleWriter.submit(path, article + " " + percent);
            }
        }
    }

    private static class Holder {
        private static final org.example.io.FileSingleWriter FILE_WRITER = new org.example.io.FileSingleWriter();
        private static final java.util.concurrent.atomic.AtomicBoolean WRITER_STARTED = new java.util.concurrent.atomic.AtomicBoolean(false);
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

    

    // hasFeedbackPoints moved into ProductRouter

    public static List<String[]> getURL() throws IOException {
        Connection connection = Jsoup.connect("https://static-basket-01.wbbasket.ru/vol0/data/main-menu-ru-ru-v3.json")
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:132.0) Gecko/20100101 Firefox/132.0")
                .method(Connection.Method.GET)
                .ignoreContentType(true);

        // если есть cookies
        for (HttpCookie cookie : Cookies) {
            connection.cookie(cookie.getName(), cookie.getValue());
        }

        String json = connection.execute().body();

        Gson gson = new Gson();
        UrlFetcher.RootItem[] items = gson.fromJson(json, UrlFetcher.RootItem[].class);

        List<String[]> urls = new ArrayList<>();
        for (UrlFetcher.RootItem item : items) {
            processChildGson(item, urls);
        }

        return urls;
    }

    private static void processChildGson(UrlFetcher.RootItem item, List<String[]> urls) {
        addUrlIfValid(item.url, item.shard, item.query, item.name, urls);

        if (item.children != null) {
            for (UrlFetcher.Child child : item.children) {
                processChildGson(child, urls);
            }
        }
    }

    private static void processChildGson(UrlFetcher.Child child, List<String[]> urls) {
        // Используем searchQuery если есть, иначе name
        String categoryName = (child.searchQuery != null && !child.searchQuery.isEmpty()) 
                ? child.searchQuery 
                : (child.name != null ? child.name : "");
        addUrlIfValid(child.url, child.shard, child.query, categoryName, urls);
        if (child.children != null) {
            for (UrlFetcher.Child nested : child.children) {
                processChildGson(nested, urls);
            }
        }
    }

    private static void addUrlIfValid(String url, String shard, String query, String name, List<String[]> urls) {
        if (url == null || url.isEmpty()) return;
        if (url.startsWith("https://vmeste.wildberries.ru")
                || url.startsWith("https://travel.wildberries.ru")
                || url.startsWith("https://digital.wildberries.ru")) return;
        if (shard == null || shard.isEmpty()) return;

        if (query == null) query = "";
        if (name == null) name = "";

        url += "?sort=popular&page=1&ffeedbackpoints=1";
        url = new org.example.wb.WbUrlBuilder().ensurePrefix(url);

        String finalUrl = url;
        boolean exists = urls.stream().anyMatch(u -> u[0].equals(finalUrl));
        if (!exists) {
            // Массив: [url, shard, query, name]
            urls.add(new String[]{url, shard, query, name});
        }
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
        MyDualBot botRef = new MyDualBot("7564492259:AAHJFWRqVvJQuuUIVd5584h8ePoFxsg7YVc");
        botRef.ensureRouter();
        for (String article : cache.asMap().keySet()) {
            pool.submit(() -> {
                try {
                    double actual = botRef.productRouter.hasFeedbackPoints(article);
                    Double stored = cache.getIfPresent(article);
                    if (stored == null || Math.abs(actual - stored) > 0.1) {
                        toRemove.add(article);
                    }
                } catch (IOException e) {
                    log.error("Check failed for {}", article, e);
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

    private void ensureRouter() {
        if (cookieService == null) {
            cookieService = new org.example.http.CookieService();
            cookieService.initAndFetch();
        }
        if (productRouter == null) {
            productRouter = new org.example.pipeline.ProductRouter(
                    queue100, queue90, queue80, queueBig, queueMyChat, queueFood, queueDetyam,
                    sentArticles100, sentArticles90, sentArticles80, sentArticlesBig,
                    sentArticlesCommunity, sentArticlesFood, sentArticlesDetyam, test,
                    pidory, urlsFood, urlsDetyam, categoryUrlMapFood, categoryUrlMapDetyam,
                    () -> cookieService.getCookiesSet()
            );
        }
    }

    private void loadCachesAndPidory() {
        pidory = readPidora("pidory.txt");
        readSentArticlesToCache(FILE_PATH + "100.txt", sentArticles100);
        readSentArticlesToCache(FILE_PATH + "90.txt", sentArticles90);
        readSentArticlesToCache(FILE_PATH + "80.txt", sentArticles80);
        readSentArticlesToCache(FILE_PATH + "Big.txt", sentArticlesBig);
        readSentArticlesToCache(FILE_PATH + "Food.txt", sentArticlesFood);
        readSentArticlesToCache(FILE_PATH + "detyam.txt", sentArticlesDetyam);
        readSentArticlesToCache(FILE_PATH_COMMUNITY, sentArticlesCommunity);
        readSentArticlesToCache("test.txt", test);
    }

    private ThreadFactory createThreadFactory() {
        return new ThreadFactory() {
            private final AtomicInteger idx = new AtomicInteger();

            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "wb-sched-" + idx.incrementAndGet());
                t.setDaemon(true);
                t.setUncaughtExceptionHandler((th, ex) -> log.error("Thread {} died", th.getName(), ex));
                return t;
            }
        };
    }

    private void registerSenderTasks(TaskOrchestrator orchestrator) {
        orchestrator.addImmediateTask("sender-100", () -> runSender("100.txt", queue100, sentArticles100, "-1002340997107", 2, "-1002402655346", telegramSender));
        orchestrator.addImmediateTask("sender-90", () -> runSender("90.txt", queue90, sentArticles90, "-1002340997107", 4, "-1002446322077", telegramSender));
        orchestrator.addImmediateTask("sender-80", () -> runSender("80.txt", queue80, sentArticles80, "-1002340997107", 6, "-1002305962649", telegramSender));
        orchestrator.addImmediateTask("sender-big", () -> runSender("Big.txt", queueBig, sentArticlesBig, "-1002340997107", 13, "-1002290311759", telegramSender));
        orchestrator.addImmediateTask("sender-food", () -> runSender("Food.txt", queueFood, sentArticlesFood, "-1002340997107", 89330, "-1002474423617", telegramSender));
        orchestrator.addImmediateTask("sender-detyam", () -> runSender("detyam.txt", queueDetyam, sentArticlesDetyam, "-1002340997107", 255209, null, telegramSender));
        orchestrator.addImmediateTask("sender-community", () -> runSender("_community.txt", queueMyChat, sentArticlesCommunity, "-1002397733938", 8, null, telegramSender));
    }

    private void processFreeQueue() throws InterruptedException {
        final String chatId = "-1002346226214";
        while (running && isFree) {
            String article = queueFree.take();
            if (Objects.equals(article, "00")) {
                return;
            }
            ensureRouter();
            List<String> sent = productRouter.repeatCheck(article);
            if (sent.isEmpty()) {
                continue;
            }
            double percent = Double.parseDouble(sent.get(2)) / Integer.parseInt(sent.get(1));
            boolean needSend = percent >= 0.8
                    || ((percent > 0.49 && Integer.parseInt(sent.get(2)) >= 1000 && Integer.parseInt(sent.get(2)) < 2500)
                    || (percent > 0.59 && Integer.parseInt(sent.get(2)) >= 699 && Integer.parseInt(sent.get(2)) < 1000)
                    || (percent >= 0.4 && Integer.parseInt(sent.get(2)) >= 2500));
            if (!needSend) {
                continue;
            }
            String data = productRouter.createMessage(sent.get(0), sent.get(1), sent.get(2), article, percent, sent.get(3));
            String[] parts = data.split("~~", 3);
            String productInfo = parts[1];
            productInfo += "\n\n <a href=\"https://t.me/WB_Jackpot/3793\">\uD83D\uDCB0Товар найден группой WB_Jackpot. Присоединяйтесь!\uD83D\uDCB0</a>";

            byte[] imageBytes = null;
            for (int i = 1; i <= 31; i++) {
                String url = (i < 10)
                        ? "https://basket-0" + i + ".wbbasket.ru/vol" + article.substring(0, article.length() - 5)
                        + "/part" + article.substring(0, article.length() - 3) + "/" + article + "/images/c516x688/1.webp"
                        : "https://basket-" + i + ".wbbasket.ru/vol" + article.substring(0, article.length() - 5)
                        + "/part" + article.substring(0, article.length() - 3) + "/" + article + "/images/c516x688/1.webp";

                int statusCode = checkLinkStatus(url);
                if (statusCode == 200) {
                    try {
                        imageBytes = downloadImageToBuffer(url);
                        break;
                    } catch (IOException ignored) {
                    }
                }
            }
            if (imageBytes == null || imageBytes.length == 0) {
                telegramSender.sendText(chatId, 0, productInfo);
            } else {
                telegramSender.sendPhoto(chatId, 0, productInfo, imageBytes);
            }
        }
    }

    private void cleanupMapOnSent() {
        long now = System.currentTimeMillis();
        mapOnSent.entrySet().removeIf(e -> {
            long age = now - e.getValue().gettime();
            if (age > TimeUnit.MINUTES.toMillis(2) + 20_000) {
                queueFree.add(e.getKey());
                return true;
            }
            return false;
        });
    }

    private void refreshCookies() {
        try {
            cookieService.refresh();
            Set<HttpCookie> newCookies = cookieService.getCookiesSet();
            Cookies = newCookies != null ? new HashSet<>(newCookies) : new HashSet<>();
            if (pipelineManager != null) {
                pipelineManager.updateCookies(Cookies);
            }
            log.info("Cookies refreshed: {} cookies", Cookies.size());
        } catch (Throwable t) {
            log.warn("Cookie refresh failed", t);
        }
    }

    private void clearSentinelMarkers() {
        queue100.remove("0~~0~~0");
        queue90.remove("0~~0~~0");
        queue80.remove("0~~0~~0");
        queueBig.remove("0~~0~~0");
        queueMyChat.remove("0~~0~~0");
        queueFood.remove("0~~0~~0");
        queueDetyam.remove("0~~0~~0");
        while (queueFree.remove("00")) {
            // remove all markers
        }
    }
}