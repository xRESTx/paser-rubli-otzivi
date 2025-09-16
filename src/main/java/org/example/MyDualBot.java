package org.example;

import com.google.gson.*;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import com.pengrad.telegrambot.TelegramBot;
import com.pengrad.telegrambot.model.request.ParseMode;
import com.pengrad.telegrambot.request.SendMessage;
import com.pengrad.telegrambot.request.SendPhoto;
import com.pengrad.telegrambot.response.SendResponse;

import org.example.jsonmodel.Product;
import org.example.jsonmodel.Root;
import org.example.jsonmodel.Size;
import org.example.jsonmodel.UrlFetcher;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.text.DecimalFormat;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
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

    // один пул на весь бот
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

    private static final BlockingQueue<String> queue100 = new LinkedBlockingQueue<>();
    private static final BlockingQueue<String> queue90 = new LinkedBlockingQueue<>();
    private static final BlockingQueue<String> queue80 = new LinkedBlockingQueue<>();
    private static final BlockingQueue<String> queueBig = new LinkedBlockingQueue<>();
    private static final BlockingQueue<String> queueMyChat = new LinkedBlockingQueue<>();
    private static final BlockingQueue<String> queueFood = new LinkedBlockingQueue<>();
    private static final BlockingQueue<String> queueDetyam = new LinkedBlockingQueue<>();
    private static final BlockingQueue<ProductInfo> queueStrippingLazar = new LinkedBlockingQueue<>();
    private static final BlockingQueue<String> queueFree = new LinkedBlockingQueue<>();


    static List<String[]> urls = new ArrayList<>();
    private static Set<String> urlsFood = ConcurrentHashMap.newKeySet();
    private static Set<String> urlsDetyam = ConcurrentHashMap.newKeySet();

    private final TelegramBot pengradBot;

    private static volatile boolean running = false;
    private static volatile boolean isFree = true;
    private static Set<HttpCookie> Cookies;
    private final List<String> admin = new ArrayList<>(Arrays.asList("1027094894", "1039378955","5392268853"));
    private final List<String> worker = new ArrayList<>(Arrays.asList("466086607","1039378955"));

    static Map<String, ProductInfo> mapOnSent = new HashMap<>();
    static Map<String, Long> mapOnSentFree = new HashMap<>();

    static int[] numberPagesProcessed = new int[2];

    private static int delta = 0;
    public MyDualBot(String pengradBotToken) {
        this.pengradBot = new TelegramBot(pengradBotToken);
    }

    @Override
    public String getBotUsername() {
        return "shovel_seller_bot";
    }

    @Override
    public String getBotToken() {
        return "7564492259:AAHJFWRqVvJQuuUIVd5584h8ePoFxsg7YVc";
//        return System.getenv("botToken");
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
                switch (messageText) {
                    case "/run" -> startTask(chatId);
                    case "/stop" -> stopTask(chatId);
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
        queueStrippingLazar.clear();
        queueFree.clear();
        hasPoint(sentArticles100, FILE_PATH + "100.txt");
        hasPoint(sentArticles90, FILE_PATH + "90.txt");
        hasPoint(sentArticles80, FILE_PATH + "80.txt");
        hasPoint(sentArticlesBig, FILE_PATH + "Big.txt");
        hasPoint(sentArticlesCommunity, FILE_PATH_COMMUNITY);
        hasPoint(sentArticlesFood, FILE_PATH + "Food.txt");
        hasPoint(sentArticlesDetyam, FILE_PATH + "detyam.txt");

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

        // Закрой предыдущий пул потоков, если он существует
        if (SCHEDULER != null) {
            SCHEDULER.shutdown();
            try {
                if (!SCHEDULER.awaitTermination(60, TimeUnit.SECONDS)) {
                    SCHEDULER.shutdownNow();
                    if (!SCHEDULER.awaitTermination(60, TimeUnit.SECONDS)) {
                        log.error("Scheduler did not terminate");
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

        readSentArticlesToCache(FILE_PATH + "100.txt", sentArticles100);
        readSentArticlesToCache(FILE_PATH + "90.txt", sentArticles90);
        readSentArticlesToCache(FILE_PATH + "80.txt", sentArticles80);
        readSentArticlesToCache(FILE_PATH + "Big.txt", sentArticlesBig);
        readSentArticlesToCache(FILE_PATH + "Food.txt", sentArticlesFood);
        readSentArticlesToCache(FILE_PATH + "detyam.txt", sentArticlesDetyam);
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
                log.warn("Sender 100 interrupted", e);
            } catch (Throwable t) {
                log.error("Error in sender 100", t);
            }
        }));

        // 90
        tasks.add(SCHEDULER.submit(() ->
        {
            try {
                runSender("90.txt", queue90, sentArticles90, "-1002340997107", 4,"-1002446322077");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Sender 90 interrupted", e);
            } catch (Throwable t) {
                log.error("Error in sender 90", t);
            }
        }));

        tasks.add(SCHEDULER.submit(() ->
        {
            try {
                runSender("80.txt", queue80, sentArticles80, "-1002340997107", 6,"-1002305962649");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Sender 80 interrupted", e);
            } catch (Throwable t) {
                log.error("Error in sender 80", t);
            }
        }));


        // Big
        tasks.add(SCHEDULER.submit(() ->
        {
            try {
                runSender("Big.txt", queueBig, sentArticlesBig, "-1002340997107", 13,"-1002290311759");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Sender Big interrupted", e);
            } catch (Throwable t) {
                log.error("Error in sender Big", t);
            }
        }));

        // Food
        tasks.add(SCHEDULER.submit(() ->
        {
            try {
                runSender("Food.txt", queueFood, sentArticlesFood, "-1002340997107", 89330,"-1002474423617");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Sender Food interrupted", e);
            } catch (Throwable t) {
                log.error("Error in sender Food", t);
            }
        }));
        //detyam
        tasks.add(SCHEDULER.submit(() ->
        {
            try {
                runSender("detyam.txt", queueDetyam, sentArticlesDetyam, "-1002340997107", 255209,null);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Sender Food interrupted", e);
            } catch (Throwable t) {
                log.error("Error in sender Food", t);
            }
        }));

        // Community
        tasks.add(SCHEDULER.submit(() ->
        {
            try {
                runSender("_community.txt", queueMyChat, sentArticlesCommunity, "-1002397733938", 8,null);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Sender Community interrupted", e);
            } catch (Throwable t) {
                log.error("Error in sender Community", t);
            }
        }));

        // StrippingLazarSent
        tasks.add(SCHEDULER.scheduleWithFixedDelay(
                () -> {
                    try {
                        MyDualBot.sentStrippingLazarSent();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        log.warn("Consumer StrippingLazarSent was interrupted, exiting", e);
                    } catch (Throwable t) {
                        log.error("Error in consumer StrippingLazarSent", t);
                    }
                },
                0, 500, TimeUnit.SECONDS));

        // Free
        tasks.add(SCHEDULER.scheduleWithFixedDelay(
                () -> {
                    try {
                        MyDualBot.sentFree();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        log.warn("Consumer Free was interrupted, exiting", e);
                    } catch (Throwable t) {
                        log.error("Error in consumer Free", t);
                    }
                },
                0, 5000, TimeUnit.SECONDS));

        tasks.add(SCHEDULER.scheduleWithFixedDelay(
                () -> {
                    try {
                        long now = System.currentTimeMillis();
                        mapOnSent.entrySet().removeIf(e -> {
                            long age = now - e.getValue().gettime();
                            if (age > TimeUnit.MINUTES.toMillis(2) + 30_000) { // 2 мин 30 сек
                                ProductInfo pi = e.getValue();
                                pi.setArticle(e.getKey());
                                queueStrippingLazar.add(pi);
                                return true;
                            }
                            return false;
                        });
                    } catch (Throwable t) {
                        log.error("Error in strippingLazar cleaner", t);
                    }
                },
                0, 10, TimeUnit.SECONDS));

        tasks.add(SCHEDULER.scheduleWithFixedDelay(
                () -> {
                    try {
                        long now = System.currentTimeMillis();
                        mapOnSentFree.entrySet().removeIf(e -> {
                            long age = now - e.getValue();
                            if (age > TimeUnit.MINUTES.toMillis(8)) {
                                queueFree.add(e.getKey());
                                return true;
                            }
                            return false;
                        });
                    } catch (Throwable t) {
                        log.error("Error in Free cleaner", t);
                    }
                },
                0, 30, TimeUnit.SECONDS));

        // --- два парсера с «переключением» направления ---
        tasks.add(SCHEDULER.scheduleWithFixedDelay(() -> {
            try { mainOld(true,  true); } catch (Throwable t) { log.error("parser-v1", t); }
        }, 0, 100, TimeUnit.MILLISECONDS));

        tasks.add(SCHEDULER.scheduleWithFixedDelay(() -> {
            try { mainOld(false, true); } catch (Throwable t) { log.error("parser-v2", t); }
        }, 5_000, 100, TimeUnit.MILLISECONDS));
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
        ProductInfo productInfo = new ProductInfo();
        productInfo.settime(0L);
        queueStrippingLazar.add(productInfo); // time=0 → выход
        queueFree.add("00");

        tasks.forEach(f -> f.cancel(false));

        SCHEDULER.shutdown();

        try {
            if (!SCHEDULER.awaitTermination(60, TimeUnit.SECONDS)) { // Увеличил время ожидания до 60 секунд
                SCHEDULER.shutdownNow();
                if (!SCHEDULER.awaitTermination(60, TimeUnit.SECONDS)) { // Добавил еще одну проверку
                    log.error("Scheduler did not terminate");
                }
            }
        } catch (InterruptedException e) {
            SCHEDULER.shutdownNow();
            Thread.currentThread().interrupt();
        }

        tasks.clear();

        sendPengradMessage(String.valueOf(chatId), "Task stopped.");
    }

    private void sendPengradMessage(String chatId, String messageText) {
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

    private int getRetryAfter(SendResponse response) {
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
        ExecutorService executorService = Executors.newFixedThreadPool(330);
        long startTime = System.currentTimeMillis();
        AtomicInteger i= new AtomicInteger();
        AtomicInteger it= new AtomicInteger();
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

        for (String[] url : halfUrls) {
            executorService.submit(() -> {
                String currentPage;
                try {
                    int page=0, increment = 0;

                    boolean checkPage = true;

                    do{
                        currentPage = "https://catalog.wb.ru/catalog/" + url[1] + "/v2/catalog?ab_testing=false&appType=1&" + url[2] + "&curr=rub&dest=-5551776&ffeedbackpoints=1&page=" + (increment+1) + "&sort=priceup&priceU=0;800000&spp=30";
                        Connection connectionPage = Jsoup.connect(currentPage)
                                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:132.0) Gecko/20100101 Firefox/132.0")
                                .method(Connection.Method.GET)
                                .ignoreContentType(true)
                                .timeout(10_000);

                        for (HttpCookie cookie : Cookies) {
                            connectionPage.cookie(cookie.getName(), cookie.getValue());
                        }
                        Connection.Response responsePage = connectionPage.execute();

                        String jsons = responsePage.body();

                        Gson gson = new Gson();
                        Root root = gson.fromJson(jsons, Root.class);
                        int numberCells = root.data.total;

                        if(numberCells == 0){
                            urls.remove(url);
                            continue;
                        }

                        List<String> newItem = new ArrayList<>();
                        for (Product product : root.data.products) {
                            String article = product.id != null ? product.id : "0";
                            if (!newItem.contains(article)) {
                                newItem.add(article);
                                String itemName = product.name != null ? product.name : " ";
                                String feedBackSum = product.feedbackPoints != null ? product.feedbackPoints : "0";
                                String totalQuery = product.totalQuantity != null ? product.totalQuantity : "0";
                                int total = 0;
                                if (product.sizes != null) {
                                    for (Size size : product.sizes) {
                                        if (size.price != null && size.price.product != 0) {
                                            total = size.price.product / 100;
                                            break;
                                        }
                                    }
                                }
                                readTxtFile(itemName, String.valueOf(total), feedBackSum, article, totalQuery, url[0]);
                            }
                        }
                        int totalPage;

                        if (checkPage) {
                            if (numberCells % 100 == 0) {
                                totalPage = numberCells / 100;
                            } else {
                                totalPage = numberCells / 100;
                                totalPage++;
                            }
                            page = totalPage;
                            checkPage = false;
                        }
                        increment++;
                        it.getAndIncrement();
                    }while (increment<page);
                } catch (Exception e) {
                    i.getAndIncrement();
//                    e.printStackTrace();
                }
            });
        }
        executorService.shutdown();
        while (!executorService.isTerminated()) {
        }
        if(version){
            numberPagesProcessed[0] = Integer.parseInt(String.valueOf(it));
        }else {
            numberPagesProcessed[1] = Integer.parseInt(String.valueOf(it));
        }
        System.out.println((System.currentTimeMillis() - startTime) + " " + i + " " + it + " " + halfUrls.size());
    }

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
                    .GET()
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            // Проверка успешности запроса
            System.out.println(response.statusCode());

            // Извлечение cookies
            Cookies = new HashSet<>(cookieManager.getCookieStore().getCookies());
            Cookies.forEach(System.out::println);
            urls = getURL();
            urlsFood = readPidora("Food.txt");
            urlsDetyam = readPidora("detyam.txt");

        } catch (Exception e) {
            e.printStackTrace();
        }

        // Регистрация бота Telegram
        try {
            TelegramBotsApi botsApi = new TelegramBotsApi(DefaultBotSession.class);
            botsApi.registerBot(new MyDualBot("7564492259:AAHJFWRqVvJQuuUIVd5584h8ePoFxsg7YVc"));
        } catch (TelegramApiException e) {
            e.printStackTrace();
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
                        e.printStackTrace();
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
                sendPhoto.fileName("photo.jpg"); // Устанавливаем имя файла (опционально)
                sendPhoto.caption(messageText); // Подпись к фото
                sendPhoto.parseMode(ParseMode.HTML); // Режим парсинга
                if (messageThreadId != null) {
                    sendPhoto.messageThreadId(messageThreadId);
                }

                sendPhoto.parseMode(ParseMode.valueOf("HTML")); // Устанавливаем режим парсинга

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
        if(Objects.equals(itemCost,"0")){
            itemCost = String.valueOf(hasFeedbackPoints(article));
        }
        double percent = Double.parseDouble(itemFeedBackCost) / Double.parseDouble(itemCost);
        ProductInfo productInfo = new ProductInfo();
        Double old100 = sentArticles100.getIfPresent(article);
        Double old90  = sentArticles90.getIfPresent(article);
        Double old80  = sentArticles80.getIfPresent(article);
        Double oldBig = sentArticlesBig.getIfPresent(article);
        Double oldCommunity =  sentArticlesCommunity.getIfPresent(article);
        Double oldFood = sentArticlesFood.getIfPresent(article);
        Double oldDetyam = sentArticlesDetyam.getIfPresent(article);
        Double tests = test.getIfPresent(article);
        boolean absent = old100 == null && old90 == null && old80 == null && oldBig == null;
        if(tests == null || Math.abs(tests - percent) > 0.01){
            try (BufferedWriter writer = Files.newBufferedWriter(Path.of("test.txt"), CREATE, APPEND)) {
                DateTimeFormatter dtf = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss");
                LocalDateTime now = LocalDateTime.now();
                writer.write(article + " " + dtf.format(now) + "\t");
                test.put(article, percent);
            }
        }
        if(absent){
            String message;
            if (((percent > 0.49 && Integer.parseInt(itemFeedBackCost) >= 1000 && Integer.parseInt(itemFeedBackCost) < 2500)
                    || (percent > 0.59 && Integer.parseInt(itemFeedBackCost) >= 699 && Integer.parseInt(itemFeedBackCost) < 1000 && percent < 0.9)
                    || (percent >= 0.4 && Integer.parseInt(itemFeedBackCost) >= 2500))) {

                message = createMessage(itemName, itemCost, itemFeedBackCost, article, percent, totalQuery);
                queueBig.add(message);
                productInfo.setquantity(String.valueOf(Integer.parseInt(totalQuery)));
                productInfo.settime(System.currentTimeMillis());
                mapOnSent.put(article, productInfo);
            }
            if (percent >= 1) {
                message = createMessage(itemName, itemCost, itemFeedBackCost, article,percent,totalQuery);
                queue100.add(message);
                productInfo.setquantity(String.valueOf(Integer.parseInt(totalQuery)));
                productInfo.settime(System.currentTimeMillis());
                mapOnSent.put(article, productInfo);
            } else if (percent >= 0.9) {
                message = createMessage(itemName, itemCost, itemFeedBackCost, article,percent,totalQuery);
                queue90.add(message);
                productInfo.setquantity(String.valueOf(Integer.parseInt(totalQuery)));
                productInfo.settime(System.currentTimeMillis());
                mapOnSent.put(article, productInfo);
            } else if (percent >= 0.8) {
                message = createMessage(itemName, itemCost, itemFeedBackCost, article,percent,totalQuery);
                queue80.add(message);
                productInfo.setquantity(String.valueOf(Integer.parseInt(totalQuery)));
                productInfo.settime(System.currentTimeMillis());
                mapOnSent.put(article, productInfo);
            }
        }
        if (oldCommunity == null) {
            String message;

            if (percent >= 1.5 || (Double.parseDouble(itemFeedBackCost) - Double.parseDouble(itemCost) >= 199 && percent > 1)) {
                message = createMessage(itemName, itemCost, itemFeedBackCost, article,percent,totalQuery);
                productInfo.setquantity(String.valueOf(Integer.parseInt(totalQuery)));
                productInfo.settime(System.currentTimeMillis());
                queueMyChat.add(message);
            }
        }
        if((oldFood==null) && urlsFood.contains(category)){
            String message;

            if (percent >= 0.45) {
                message = createMessage(itemName, itemCost, itemFeedBackCost, article,percent,totalQuery);
                queueFood.add(message);
            }
        }
        if((oldDetyam==null) && urlsDetyam.contains(category)){
            String message;

            if (percent >= 0.5) {
                message = createMessage(itemName, itemCost, itemFeedBackCost, article,percent,totalQuery);
                queueDetyam.add(message);
            }
        }
    }

    private static void runSender(String fileName,
                                  BlockingQueue<String> queue,
                                  Cache<String, Double> cache,
                                  String chatId,
                                  Integer threadId,
                                  String secondChatId) throws InterruptedException {

        MyDualBot tgBot = new MyDualBot("7564492259:AAHJFWRqVvJQuuUIVd5584h8ePoFxsg7YVc");
        Path path = Path.of(FILE_PATH + fileName);

        try (BufferedWriter writer = Files.newBufferedWriter(path, CREATE, APPEND)) {
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
                    tgBot.sendMessage(chatId, threadId, productInfo);
                    // второй канал (если указан)
                    if (secondChatId != null) {
                        tgBot.sendMessage(secondChatId, 0, productInfo);
                    }

                    writer.write(article + " " + percent);
                    writer.newLine();
                    writer.flush();
                }
            }
        } catch (IOException e) {
            log.error("Error in sender {}", fileName, e);
            e.printStackTrace();
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

    private static void sentStrippingLazarSent() throws InterruptedException {
        String chatId = "-1002239949862";
        MyDualBot tgBot = new MyDualBot("7564492259:AAHJFWRqVvJQuuUIVd5584h8ePoFxsg7YVc");
        try{
            while (running) {
                ProductInfo article = queueStrippingLazar.take();
                if(article.gettime()==0){
                    return;
                }
                List<String> sent = repeatCheck(article.getArticle());
                if(sent.isEmpty()){
                    continue;
                }
                double percent = Double.parseDouble(sent.get(2)) / Integer.parseInt(sent.get(1));
                if (percent >= 0.8 || ((percent > 0.49 && Integer.parseInt(sent.get(2)) >= 1000 && Integer.parseInt(sent.get(2)) < 2500)
                        || (percent > 0.59 && Integer.parseInt(sent.get(2)) >= 699 && Integer.parseInt(sent.get(2)) < 1000)
                        || (percent >= 0.4 && Integer.parseInt(sent.get(2)) >= 2500))){
                    String data = createMessage(sent.get(0), sent.get(1), sent.get(2), article.getArticle(), percent, sent.get(3));
                    String[] parts = data.split("~~", 3);
                    String productInfo = parts[1];

                    productInfo += "\n\uD83D\uDCCAКуплено с момента публикации в <a href=\"https://t.me/WB_Jackpot_sub_bot\">бота</a>: " + (Integer.parseInt(article.getquantity()) - Integer.parseInt(sent.get(3)))  + "\n\n<a href=\"https://t.me/WB_Jackpot/3793\">\uD83D\uDCB0Товар найден группой WB_Jackpot. Присоединяйтесь!\uD83D\uDCB0</a>";
                    mapOnSentFree.put(article.getArticle(),System.currentTimeMillis());
                    byte[] imageBytes = new byte[0];
                    for (int i = 1; i <= 31; i++) {
                        String url = (i < 10) ? "https://basket-0" + i + ".wbbasket.ru/vol" + article.getArticle().substring(0, article.getArticle().length() - 5) + "/part" + article.getArticle().substring(0, article.getArticle().length() - 3) + "/" + article + "/images/c516x688/1.webp" : "https://basket-" + i + ".wbbasket.ru/vol" + article.getArticle().substring(0, article.getArticle().length() - 5) + "/part" + article.getArticle().substring(0, article.getArticle().length() - 3) + "/" + article + "/images/c516x688/1.webp";

                        int statusCode = checkLinkStatus(url);
                        if (statusCode == 200) {
                            try {
                                imageBytes = downloadImageToBuffer(url);
                                break;
                            } catch (IOException e) {

                            }
                        }
                    }
                    tgBot.sendPhoto(chatId, 0, productInfo,imageBytes);
                }
            }
        }catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static void sentFree() throws InterruptedException {
        String chatId = "-1002346226214";
        MyDualBot tgBot = new MyDualBot("7564492259:AAHJFWRqVvJQuuUIVd5584h8ePoFxsg7YVc");
        try{
            while (running && isFree) {
                String article = queueFree.take(); // Извлечение данных из очереди
                if(Objects.equals(article, "00")){
                    return;
                }
                List<String> sent = repeatCheck(article);
                if(sent.isEmpty()){
                    continue;
                }
                double percent = Double.parseDouble(sent.get(2)) / Integer.parseInt(sent.get(1));
                if (percent >= 0.8
                        || ((percent > 0.49 && Integer.parseInt(sent.get(2)) >= 1000 && Integer.parseInt(sent.get(2)) < 2500)
                        || (percent > 0.59 && Integer.parseInt(sent.get(2)) >= 699 && Integer.parseInt(sent.get(2)) < 1000)
                        || (percent >= 0.4 && Integer.parseInt(sent.get(2)) >= 2500))){
                    String data = createMessage(sent.get(0), sent.get(1), sent.get(2), article, percent, sent.get(3));
                    String[] parts = data.split("~~", 3);
                    String productInfo = parts[1];
                    productInfo += "\n\n <a href=\"https://t.me/WB_Jackpot/3793\">\uD83D\uDCB0Товар найден группой WB_Jackpot. Присоединяйтесь!\uD83D\uDCB0</a>";
                    byte[] imageBytes = new byte[0];
                    for (int i = 1; i <= 31; i++) {
                        String url = (i < 10) ? "https://basket-0" + i + ".wbbasket.ru/vol" + article.substring(0, article.length() - 5) + "/part" + article.substring(0, article.length() - 3) + "/" + article + "/images/c516x688/1.webp" : "https://basket-" + i + ".wbbasket.ru/vol" + article.substring(0, article.length() - 5) + "/part" + article.substring(0, article.length() - 3) + "/" + article + "/images/c516x688/1.webp";

                        int statusCode = checkLinkStatus(url);
                        if (statusCode == 200) {
                            try {
                                imageBytes = downloadImageToBuffer(url);
                                break;
                            } catch (IOException e) {

                            }
                        }
                    }
                    tgBot.sendPhoto(chatId, 0, productInfo,imageBytes);
                }
            }
        }catch (IOException e) {
            e.printStackTrace();
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
        Root root = gson.fromJson(json, Root.class);

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
        addUrlIfValid(item.url, item.shard, item.query, urls);

        if (item.children != null) {
            for (UrlFetcher.Child child : item.children) {
                processChildGson(child, urls);
            }
        }
    }

    private static void processChildGson(UrlFetcher.Child child, List<String[]> urls) {
        addUrlIfValid(child.url, child.shard, child.query, urls);
        if (child.children != null) {
            for (UrlFetcher.Child nested : child.children) {
                processChildGson(nested, urls);
            }
        }
    }

    private static void addUrlIfValid(String url, String shard, String query, List<String[]> urls) {
        if (url == null || url.isEmpty()) return;
        if (url.startsWith("https://vmeste.wildberries.ru")
                || url.startsWith("https://travel.wildberries.ru")
                || url.startsWith("https://digital.wildberries.ru")) return;
        if (shard == null || shard.isEmpty()) return;

        if (query == null) query = "";

        url += "?sort=popular&page=1&ffeedbackpoints=1";
        url = ensureUrlStartsWithPrefix(url);

        String finalUrl = url;
        boolean exists = urls.stream().anyMatch(u -> u[0].equals(finalUrl));
        if (!exists) {
            urls.add(new String[]{url, shard, query});
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
            String jsonUrl = "https://card.wb.ru/cards/v2/detail?appType=1&curr=rub&dest=-5923914&spp=30&ab_testing=false&nm="+ article;
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
            Root root = gson.fromJson(json, Root.class);

            for (Product product : root.data.products) {
                if (product.feedbackPoints != null && product.totalQuantity != null && !product.totalQuantity.equals("0")) {
                    String feedBackSum = product.feedbackPoints;
                    String itemName = product.name != null ? product.name : " ";
                    String totalQuery = product.totalQuantity;

                    if (product.sizes != null) {
                        for (Size size : product.sizes) {
                            if (size.price != null && size.price.product != 0) {
                                int priceRub = size.price.product / 100;
                                sent.add(itemName);
                                sent.add(String.valueOf(priceRub));
                                sent.add(feedBackSum);
                                sent.add(totalQuery);
                                break;
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
                    if (stored == null || Math.abs(actual - stored) > 0.1) {
                        toRemove.add(article);
                    }
                } catch (IOException e) {
                    log.error("Check failed for {}", article, e);
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