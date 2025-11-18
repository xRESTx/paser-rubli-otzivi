package org.example;

import com.google.gson.*;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import com.pengrad.telegrambot.TelegramBot;
import com.pengrad.telegrambot.model.request.ParseMode;
import com.pengrad.telegrambot.request.SendMessage;
import com.pengrad.telegrambot.request.SendPhoto;
import com.pengrad.telegrambot.response.SendResponse;

import org.example.jsonmodel.Data;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.DecimalFormat;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.io.BufferedWriter;
import java.io.IOException;
import java.util.concurrent.*;
import java.util.concurrent.TimeUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
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

    private final TelegramBot pengradBot;

    private static volatile boolean running = false;
    private static volatile boolean isFree = true;
    private static Set<HttpCookie> Cookies;
    // Список всех активных ExecutorService для корректной остановки
    private static final Set<ExecutorService> activeExecutors = ConcurrentHashMap.newKeySet();
    public static Set<String> pidory = ConcurrentHashMap.newKeySet();
    private final Set<Long> waitingForMessage = new HashSet<>();
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
                if (waitingForMessage.contains(chatId)) {
                    try (BufferedWriter reader = new BufferedWriter(new FileWriter("pidory.txt", true))) {
                        pidory.add(messageText);
                        reader.write("\n" + messageText);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                    sendPengradMessage(String.valueOf(chatId),  "The text is written to a file!");
                    waitingForMessage.remove(chatId); // Убираем из режима ожидания
                    return;
                }
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
                    case "/pidory" -> {
                        waitingForMessage.add(chatId);
                        sendPengradMessage(String.valueOf(chatId),  "Send pidora");
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

        // Free
        tasks.add(SCHEDULER.scheduleWithFixedDelay(
                () -> {
                    try {
                        MyDualBot.sentFree();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } catch (Throwable t) {
                    }
                },
                0, 5000, TimeUnit.SECONDS));

        tasks.add(SCHEDULER.scheduleWithFixedDelay(
                () -> {
                    try {
                        long now = System.currentTimeMillis();
                        mapOnSent.entrySet().removeIf(e -> {
                            long age = now - e.getValue().gettime();
                            if (age > TimeUnit.MINUTES.toMillis(2) + 20_000) { // 2 мин 20 сек
                                queueFree.add(e.getKey());
                                return true;
                            }
                            return false;
                        });
                    } catch (Throwable t) {
                    }
                },
                0, 10, TimeUnit.SECONDS));
        // --- два парсера с «переключением» направления ---
        // Используем scheduleAtFixedRate вместо scheduleWithFixedDelay
        // Это позволяет запускать задачи с фиксированным интервалом, не дожидаясь завершения предыдущей
        // Интервал 15 секунд - достаточно для завершения большинства задач
        tasks.add(SCHEDULER.scheduleAtFixedRate(() -> {
            try {
                mainOld(true, true);
            } catch (Throwable t) {
                t.printStackTrace();
            }
        }, 0, 6, TimeUnit.SECONDS));

        tasks.add(SCHEDULER.scheduleAtFixedRate(() -> {
            try {
                mainOld(false, true);
            } catch (Throwable t) {
                t.printStackTrace();
            }
        }, 5, 6, TimeUnit.SECONDS));
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
        queueFree.add("00");

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
        // Проверяем, не остановлена ли работа
        if (!running) {
            return;
        }
        
        // Используем cookies, полученные в main(), не обновляем их каждый раз
        // Обновление cookies может привести к блокировке (статус 498)
        if (Cookies == null || Cookies.isEmpty()) {
            // Пытаемся получить cookies только если их нет
            try {
                String urlWb = "https://www.wildberries.ru/";
                Connection connection = Jsoup.connect(urlWb)
                        .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:144.0) Gecko/20100101 Firefox/144.0")
                        .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
                        .header("Accept-Language", "en-US,en;q=0.5")
                        // Убираем Accept-Encoding, чтобы получить несжатый ответ
                        .header("Upgrade-Insecure-Requests", "1")
                        .header("Sec-Fetch-Dest", "document")
                        .header("Sec-Fetch-Mode", "navigate")
                        .header("Sec-Fetch-Site", "none")
                        .header("Sec-Fetch-User", "?1")
                        .method(Connection.Method.GET)
                        .timeout(15_000)
                        .followRedirects(true);
                
                Connection.Response response = connection.execute();
                
                // Получаем cookies из ответа
                Map<String, String> cookiesMap = response.cookies();
                Cookies = new HashSet<>();
                
                // Конвертируем Map<String, String> в Set<HttpCookie>
                for (Map.Entry<String, String> entry : cookiesMap.entrySet()) {
                    try {
                        HttpCookie cookie = new HttpCookie(entry.getKey(), entry.getValue());
                        cookie.setDomain(".wildberries.ru");
                        cookie.setPath("/");
                        Cookies.add(cookie);
                    } catch (IllegalArgumentException e) {
                    }
                }
            } catch (Exception e) {
                // Если не удалось получить cookies, используем пустой набор
                if (Cookies == null) {
                    Cookies = new HashSet<>();
                }
            }
        }
        
        // Обновляем список URL из JSON перед каждой обработкой
        // Это гарантирует, что мы используем актуальные категории
        try {
            urls = getURL();
        } catch (Exception e) {
            log.error("Error loading URLs from JSON: {}", e.getMessage());
            // Если не удалось загрузить URL, используем старый список или выходим
            if (urls == null || urls.isEmpty()) {
                return;
            }
        }
        
        if (urls.isEmpty()) {
            return;
        }
        
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
        ExecutorService executorService = Executors.newFixedThreadPool(300);
        activeExecutors.add(executorService);
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

        // Первый проход: получаем количество страниц для каждой категории и добавляем задачи в очередь
        ExecutorService discoveryService = Executors.newFixedThreadPool(50);
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
                    
                    String shardKey = url[1];
                    String query = url[2];
                    String action = url[3];
                    
                    // Формируем URL для первой страницы, чтобы узнать общее количество страниц
                    StringBuilder queryParams = new StringBuilder();
                    queryParams.append("ab_testing=false&ab_testing=false&action=").append(action);
                    queryParams.append("&appType=1");
                    if (query != null && !query.isEmpty()) {
                        queryParams.append("&").append(query);
                    }
                    queryParams.append("&curr=rub&dest=-1257786&hide_dtype=11&lang=ru");
                    queryParams.append("&page=1");
                    queryParams.append("&sort=popular&spp=30");
                    
                    String firstPageUrl = "https://www.wildberries.ru/__internal/u-catalog/catalog/" + shardKey + "/v4/catalog?" + queryParams.toString();
                    
                    String userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:144.0) Gecko/20100101 Firefox/144.0";
                    Connection connectionPage = Jsoup.connect(firstPageUrl)
                            .userAgent(userAgent)
                            .header("Accept", "*/*")
                            .header("Accept-Language", "en-US,en;q=0.5")
                            .header("Referer", url[0])
                            .header("Origin", "https://www.wildberries.ru")
                            .header("Sec-Fetch-Dest", "empty")
                            .header("Sec-Fetch-Mode", "cors")
                            .header("Sec-Fetch-Site", "same-origin")
                            .header("TE", "trailers")
                            .header("Priority", "u=4")
                            .header("x-requested-with", "XMLHttpRequest")
                            .header("x-spa-version", "13.12.0")
                            .header("deviceid", "site_2bc3dd7d2f1a4eb28539e17ff17c894a")
                            .method(Connection.Method.GET)
                            .ignoreContentType(true)
                            .timeout(20_000)
                            .followRedirects(true)
                            .maxBodySize(0);

                    if (Cookies != null && !Cookies.isEmpty()) {
                        for (HttpCookie cookie : Cookies) {
                            connectionPage.cookie(cookie.getName(), cookie.getValue());
                        }
                    }
                    
                    Connection.Response responsePage = connectionPage.execute();
                    int statusCode = responsePage.statusCode();
                    
                    // Пропускаем категории с ошибками
                    if (statusCode == 429 || statusCode == 404 || statusCode == 498) {
                        return;
                    }
                    
                    if (statusCode != 200) {
                        return;
                    }
                    
                    String jsons = responsePage.body();
                    if (jsons == null || jsons.trim().isEmpty()) {
                        return;
                    }
                    
                    Gson gson = new Gson();
                    Data data = gson.fromJson(jsons, Data.class);
                    
                    // Пропускаем категории с пустым ответом или без товаров
                    if (data == null || data.products == null || data.products.isEmpty()) {
                        return;
                    }
                    
                    int numberCells = data.total;
                    
                    // Если total = 0, пропускаем категорию
                    if (numberCells == 0) {
                        return;
                    }

                    // Вычисляем количество страниц
                    int totalPage;
                    if (numberCells % 100 == 0) {
                        totalPage = numberCells / 100;
                    } else {
                        totalPage = numberCells / 100 + 1;
                    }
                    
                    // Добавляем все страницы этой категории в очередь
                    for (int pageNum = 1; pageNum <= totalPage; pageNum++) {
                        pageQueue.offer(new PageTask(url, pageNum));
                    }
                    
                    categoriesProcessed.incrementAndGet();
                } catch (Exception e) {
                    // Игнорируем ошибки при получении количества страниц
                } finally {
                    // Всегда уменьшаем счетчик защелки, независимо от результата
                    // Это гарантирует, что счетчик уменьшается ровно один раз для каждой задачи
                    discoveryLatch.countDown();
                }
            });
        }
        
        discoveryService.shutdown();
        try {
            discoveryLatch.await(5, TimeUnit.MINUTES); // Ждем завершения обнаружения страниц
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        int pagesInQueue = pageQueue.size();
        int categoriesProcessedCount = categoriesProcessed.get();
        log.info("Discovery phase completed. Pages in queue: {}, Categories processed: {}, Total categories: {}", 
                pagesInQueue, categoriesProcessedCount, halfUrls.size());
        
        // Если очередь пуста, значит все категории вернули ошибки или не имеют товаров
        if (pagesInQueue == 0) {
            log.warn("No pages to process! All categories may have returned errors or have no products.");
            executorService.shutdown();
            activeExecutors.remove(executorService);
            activeExecutors.remove(discoveryService);
            log.info("mainOld({}, {}) completed early - no pages to process", version, reverse);
            return;
        }
        
        // Второй проход: обрабатываем все страницы из очереди параллельно
        // Каждый поток берет задачи из очереди до тех пор, пока она не пуста
        for (int t = 0; t < 300; t++) {
            executorService.submit(() -> {
                try {
                    while (running) {
                        PageTask task = pageQueue.poll(5, TimeUnit.SECONDS);
                        if (task == null) {
                            // Если очередь пуста более 5 секунд, проверяем еще раз
                            // Возможно, discovery phase еще добавляет задачи
                            if (pageQueue.isEmpty() && discoveryService.isTerminated()) {
                                // Если discovery завершился и очередь пуста, завершаем поток
                                log.debug("Thread exiting - queue empty and discovery terminated");
                                break;
                            }
                            // Иначе продолжаем ждать
                            continue;
                        }
                        
                        // Проверяем флаг running перед обработкой задачи
                        if (!running) {
                            break;
                        }
                        
                        try {
                            String[] url = task.url;
                            int pageNum = task.pageNumber;
                            
                            String shardKey = url[1];
                            String query = url[2];
                            String action = url[3];
                            
                            // Формируем query параметры
                            StringBuilder queryParams = new StringBuilder();
                            queryParams.append("ab_testing=false&ab_testing=false&action=").append(action);
                            queryParams.append("&appType=1");
                            if (query != null && !query.isEmpty()) {
                                queryParams.append("&").append(query);
                            }
                            queryParams.append("&curr=rub&dest=-1257786&hide_dtype=11&lang=ru");
                            queryParams.append("&page=").append(pageNum);
                            queryParams.append("&sort=popular&spp=30");
                            
                            String currentPage = "https://www.wildberries.ru/__internal/u-catalog/catalog/" + shardKey + "/v4/catalog?" + queryParams.toString();
                            
                            String userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:144.0) Gecko/20100101 Firefox/144.0";
                            Connection connectionPage = Jsoup.connect(currentPage)
                                    .userAgent(userAgent)
                                    .header("Accept", "*/*")
                                    .header("Accept-Language", "en-US,en;q=0.5")
                                    .header("Referer", url[0])
                                    .header("Origin", "https://www.wildberries.ru")
                                    .header("Sec-Fetch-Dest", "empty")
                                    .header("Sec-Fetch-Mode", "cors")
                                    .header("Sec-Fetch-Site", "same-origin")
                                    .header("TE", "trailers")
                                    .header("Priority", "u=4")
                                    .header("x-requested-with", "XMLHttpRequest")
                                    .header("x-spa-version", "13.12.0")
                                    .header("deviceid", "site_2bc3dd7d2f1a4eb28539e17ff17c894a")
                                    .method(Connection.Method.GET)
                                    .ignoreContentType(true)
                                    .timeout(20_000)
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
                            
                            Connection.Response responsePage = connectionPage.execute();
                            int statusCode = responsePage.statusCode();
                            
                            // Пропускаем ошибки 429, 404, 498
                            if (statusCode == 429 || statusCode == 404 || statusCode == 498) {
                                continue;
                            }
                            
                            if (statusCode != 200) {
                                i.getAndIncrement();
                                continue;
                            }
                            
                            String jsons = responsePage.body();
                            if (jsons == null || jsons.trim().isEmpty()) {
                                continue;
                            }
                            
                            Gson gson = new Gson();
                            Data data = gson.fromJson(jsons, Data.class);
                            
                            if(data == null || data.products == null){
                                continue;
                            }

                            List<String> newItem = new ArrayList<>();
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
                                    if(pidory.contains(supplierRaw)){
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
                                }
                            }
                            
                            it.getAndIncrement();
                        } catch (Exception e) {
                            i.getAndIncrement();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        
        // Проверяем, не остановлена ли работа перед завершением
        if (!running) {
            // Если работа остановлена, мягко завершаем все задачи (ждем их завершения)
            discoveryService.shutdown();
            executorService.shutdown();
            
            // Ждем завершения всех задач
            try {
                if (!discoveryService.awaitTermination(60, TimeUnit.SECONDS)) {
                    discoveryService.shutdownNow();
                }
                if (!executorService.awaitTermination(60, TimeUnit.SECONDS)) {
                    executorService.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                discoveryService.shutdownNow();
                executorService.shutdownNow();
            } finally {
                activeExecutors.remove(discoveryService);
                activeExecutors.remove(executorService);
            }
            return;
        }
        
        // Ждем завершения обработки всех страниц
        // Discovery service уже завершен, теперь ждем завершения executor service
        discoveryService.shutdown();
        executorService.shutdown();
        
        // Ждем завершения всех задач с таймаутом
        // Это гарантирует, что все страницы будут обработаны
        try {
            // Ждем завершения discovery service (он уже должен быть завершен)
            if (!discoveryService.awaitTermination(10, TimeUnit.SECONDS)) {
                log.warn("Discovery service did not terminate within timeout");
            }
            
            // Ждем завершения executor service (обработка страниц)
            // Таймаут зависит от количества страниц: минимум 30 секунд, максимум 5 минут
            int timeoutSeconds = Math.max(30, Math.min(300, pagesInQueue / 100));
            log.info("Waiting for executor service to complete (timeout: {}s, pages: {})", timeoutSeconds, pagesInQueue);
            
            if (!executorService.awaitTermination(timeoutSeconds, TimeUnit.SECONDS)) {
                log.warn("Executor service did not terminate within timeout ({}s). Some pages may not be processed.", timeoutSeconds);
                // Не принуждаем к завершению - задачи продолжат выполняться в фоне
            } else {
                log.info("All pages processed successfully");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while waiting for tasks to complete");
        } finally {
            // Удаляем из списка активных после завершения
            if (running) {
                activeExecutors.remove(discoveryService);
                activeExecutors.remove(executorService);
            }
        }
        
        if(version){
            numberPagesProcessed[0] = Integer.parseInt(String.valueOf(it));
        }else {
            numberPagesProcessed[1] = Integer.parseInt(String.valueOf(it));
        }
        log.info("mainOld({}, {}) completed. Time: {}ms, Errors: {}, Processed: {}, Categories: {}", 
                version, reverse, (System.currentTimeMillis() - startTime), i.get(), it.get(), halfUrls.size());
        System.out.println((System.currentTimeMillis() - startTime) + " " + i + " " + it + " " + halfUrls.size());
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
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:144.0) Gecko/20100101 Firefox/144.0")
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
                    .header("Accept-Language", "en-US,en;q=0.5")
                    .header("Upgrade-Insecure-Requests", "1")
                    .header("Sec-Fetch-Dest", "document")
                    .header("Sec-Fetch-Mode", "navigate")
                    .header("Sec-Fetch-Site", "none")
                    .header("Sec-Fetch-User", "?1")
                    .GET()
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            // Проверка успешности запроса
            System.out.println(response.statusCode());

            // Извлечение cookies
            Cookies = new HashSet<>(cookieManager.getCookieStore().getCookies());
            System.out.println("Cookies obtained in main(): " + Cookies.size());
            Cookies.forEach(System.out::println);
            
            // Если cookies не получены, попробуем использовать Jsoup
            if (Cookies == null || Cookies.isEmpty()) {
                System.out.println("No cookies from HttpClient, trying Jsoup...");
                try {
                    Connection connection = Jsoup.connect(urlWb)
                            .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:144.0) Gecko/20100101 Firefox/144.0")
                            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
                            .header("Accept-Language", "en-US,en;q=0.5")
                            .header("Upgrade-Insecure-Requests", "1")
                            .header("Sec-Fetch-Dest", "document")
                            .header("Sec-Fetch-Mode", "navigate")
                            .header("Sec-Fetch-Site", "none")
                            .header("Sec-Fetch-User", "?1")
                            .method(Connection.Method.GET)
                            .timeout(15_000)
                            .followRedirects(true);
                    
                    Connection.Response jsoupResponse = connection.execute();
                    Map<String, String> cookiesMap = jsoupResponse.cookies();
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
                    System.out.println("Cookies obtained from Jsoup: " + Cookies.size());
                } catch (Exception e) {
                    System.err.println("Error getting cookies from Jsoup: " + e.getMessage());
                    if (Cookies == null) {
                        Cookies = new HashSet<>();
                    }
                }
            }
            
            // Если cookies все еще не получены (IP заблокирован), используем статические cookies
            // ВАЖНО: Замените эти значения на реальные cookies из вашего браузера!
            if (Cookies == null || Cookies.isEmpty()) {
                System.out.println("IP appears to be blocked. Using static cookies from browser...");
                System.out.println("WARNING: Replace these with your actual browser cookies!");
                Cookies = new HashSet<>();
                
                // Пример статических cookies (замените на реальные из браузера)
                // Получите cookies из браузера: F12 -> Network -> Request Headers -> Cookie
                try {
                    // Добавьте здесь ваши реальные cookies из браузера
                    // Формат: new HttpCookie("имя", "значение")
                    // Например:
                    // HttpCookie cookie1 = new HttpCookie("_wbauid", "ваше_значение");
                    // cookie1.setDomain(".wildberries.ru");
                    // cookie1.setPath("/");
                    // Cookies.add(cookie1);
                    
                    // Используем реальные cookies из браузера
                    HttpCookie wbauid = new HttpCookie("_wbauid", "10161773411755534979");
                    wbauid.setDomain(".wildberries.ru");
                    wbauid.setPath("/");
                    Cookies.add(wbauid);
                    
                    HttpCookie ga = new HttpCookie("_ga", "GA1.1.859637948.1758564015");
                    ga.setDomain(".wildberries.ru");
                    ga.setPath("/");
                    Cookies.add(ga);
                    
                    HttpCookie gaTXRZMJQDFE = new HttpCookie("_ga_TXRZMJQDFE", "GS2.1.s1759239458$o2$g1$t1759239461$j57$l0$h0");
                    gaTXRZMJQDFE.setDomain(".wildberries.ru");
                    gaTXRZMJQDFE.setPath("/");
                    Cookies.add(gaTXRZMJQDFE);
                    
                    HttpCookie wbaasToken = new HttpCookie("x_wbaas_token", "1.1000.005c5fe06b83419cbf1c1bd1da4d8295.MHwxODUuOTIuMTM5LjEzNnxNb3ppbGxhLzUuMCAoV2luZG93cyBOVCAxMC4wOyBXaW42NDsgeDY0OyBydjoxNDMuMCkgR2Vja28vMjAxMDAxMDEgRmlyZWZveC8xNDMuMHwxNzYzNzQxODg0fHJldXNhYmxlfDJ8ZXlKb1lYTm9Jam9pSW4wPXwwfDN8MTc2MzEzNzA4NA==.MEQCICLQJ0JKUSrfi4cvtmBS9pftMrhnf0vrvWC26H2ii/NwAiABM+wG0h2dLn4qM2aBB5BeBNMIbBOAQ8JYffIrvI9wMg==");
                    wbaasToken.setDomain(".wildberries.ru");
                    wbaasToken.setPath("/");
                    Cookies.add(wbaasToken);
                    
                    System.out.println("Static cookies initialized: " + Cookies.size());
                    System.out.println("Using browser cookies: _wbauid, _ga, _ga_TXRZMJQDFE, x_wbaas_token");
                } catch (Exception e) {
                    System.err.println("Error setting static cookies: " + e.getMessage());
                }
            }
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
//        Double tests = test.getIfPresent(article);
        boolean absent = old100 == null && old90 == null && old80 == null && oldBig == null;
        boolean changed =
                (old100 != null && Math.abs(old100 - percent) > 0.1) ||
                        (old90  != null && Math.abs(old90  - percent) > 0.1) ||
                        (old80  != null && Math.abs(old80  - percent) > 0.1) ||
                        (oldBig != null && Math.abs(oldBig - percent) > 0.1);
//        if(tests == null || Math.abs(tests - percent) > 0.01){
//            try (BufferedWriter writer = Files.newBufferedWriter(Path.of("test.txt"), CREATE, APPEND)) {
//                DateTimeFormatter dtf = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss");
//                LocalDateTime now = LocalDateTime.now();
//                writer.write(article + " " + dtf.format(now) + "\t");
//                test.put(article, percent);
//            }
//        }
        if(absent || changed){
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
        if (oldCommunity == null || Math.abs(oldCommunity - percent) > 0.1) {
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
        if((oldFood==null || Math.abs(oldFood - percent) > 0.1) && urlsFood.contains(category)){
            String message;

            if (percent >= 0.45) {
                message = createMessage(itemName, itemCost, itemFeedBackCost, article,percent,totalQuery);
                queueFood.add(message);
                // НЕ обновляем кэш здесь - кэш обновится в runSender после отправки
                // Это предотвращает ситуацию, когда товар добавлен в очередь, но не отправлен
                productInfo.setquantity(String.valueOf(Integer.parseInt(totalQuery)));
                productInfo.settime(System.currentTimeMillis());
                mapOnSent.put(article, productInfo);
            }
        }
        if((oldDetyam==null || Math.abs(oldDetyam - percent) > 0.1) && urlsDetyam.contains(category)){
            String message;

            if (percent >= 0.5) {
                message = createMessage(itemName, itemCost, itemFeedBackCost, article,percent,totalQuery);
                queueDetyam.add(message);
                // НЕ обновляем кэш здесь - кэш обновится в runSender после отправки
                // Это предотвращает ситуацию, когда товар добавлен в очередь, но не отправлен
                productInfo.setquantity(String.valueOf(Integer.parseInt(totalQuery)));
                productInfo.settime(System.currentTimeMillis());
                mapOnSent.put(article, productInfo);
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

                // Проверяем кэш - если товар уже был отправлен с таким же процентом, пропускаем
                // Это защита от дубликатов: если товар уже был отправлен ранее
                Double old = cache.getIfPresent(article);
                if (old == null || Math.abs(old - percent) > 0.01) {
                    // Обновляем кэш только после успешной отправки
                    // Это предотвращает дубликаты: если товар попадет в readTxtFile повторно до обработки,
                    // он не будет добавлен в очередь (old != null)
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
                } else {
                    // Товар уже был отправлен с таким же процентом - пропускаем
                    // Это нормально, так как товар мог быть добавлен в очередь несколько раз
                    // до обработки в runSender
                }
            }
        } catch (IOException e) {
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

//    private static void sentStrippingLazarSent() throws InterruptedException {
//        String chatId = "-1002239949862";
//        MyDualBot tgBot = new MyDualBot("7564492259:AAHJFWRqVvJQuuUIVd5584h8ePoFxsg7YVc");
//        try{
//            while (running) {
//                ProductInfo article = queueStrippingLazar.take();
//                if(article.gettime()==0){
//                    return;
//                }
//                List<String> sent = repeatCheck(article.getArticle());
//                if(sent.isEmpty()){
//                    continue;
//                }
//                double percent = Double.parseDouble(sent.get(2)) / Integer.parseInt(sent.get(1));
//                if (percent >= 0.8 || ((percent > 0.49 && Integer.parseInt(sent.get(2)) >= 1000 && Integer.parseInt(sent.get(2)) < 2500)
//                        || (percent > 0.59 && Integer.parseInt(sent.get(2)) >= 699 && Integer.parseInt(sent.get(2)) < 1000)
//                        || (percent >= 0.4 && Integer.parseInt(sent.get(2)) >= 2500))){
//                    String data = createMessage(sent.get(0), sent.get(1), sent.get(2), article.getArticle(), percent, sent.get(3));
//                    String[] parts = data.split("~~", 3);
//                    String productInfo = parts[1];
//
//                    productInfo += "\n\uD83D\uDCCAКуплено с момента публикации в <a href=\"https://t.me/WB_Jackpot_sub_bot\">бота</a>: " + (Integer.parseInt(article.getquantity()) - Integer.parseInt(sent.get(3)))  + "\n\n<a href=\"https://t.me/WB_Jackpot/3793\">\uD83D\uDCB0Товар найден группой WB_Jackpot. Присоединяйтесь!\uD83D\uDCB0</a>";
//                    mapOnSentFree.put(article.getArticle(),System.currentTimeMillis());
//                    byte[] imageBytes = new byte[0];
//                    for (int i = 1; i <= 31; i++) {
//                        String url = (i < 10) ? "https://basket-0" + i + ".wbbasket.ru/vol" + article.getArticle().substring(0, article.getArticle().length() - 5) + "/part" + article.getArticle().substring(0, article.getArticle().length() - 3) + "/" + article + "/images/c516x688/1.webp" : "https://basket-" + i + ".wbbasket.ru/vol" + article.getArticle().substring(0, article.getArticle().length() - 5) + "/part" + article.getArticle().substring(0, article.getArticle().length() - 3) + "/" + article + "/images/c516x688/1.webp";
//
//                        int statusCode = checkLinkStatus(url);
//                        if (statusCode == 200) {
//                            try {
//                                imageBytes = downloadImageToBuffer(url);
//                                break;
//                            } catch (IOException e) {
//
//                            }
//                        }
//                    }
//                    if (imageBytes == null || imageBytes.length == 0) {
//                        tgBot.sendMessage(chatId, 0, productInfo);
//                    }
//                    else {
//                        tgBot.sendPhoto(chatId, 0, productInfo,imageBytes);
//                    }
//
//                }
//            }
//        }catch (IOException e) {
//            e.printStackTrace();
//        }
//    }

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
                    if (imageBytes == null || imageBytes.length == 0) {
                        tgBot.sendMessage(chatId, 0, productInfo);
                    }
                    else {
                        tgBot.sendPhoto(chatId, 0, productInfo,imageBytes);
                    }
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

    private static void addUrlIfValid(UrlFetcher.CategoryNode node, List<String[]> urls, int action) {
        if (node.url == null || node.url.isEmpty()) return;
        if (node.url.startsWith("https://vmeste.wildberries.ru")
                || node.url.startsWith("https://travel.wildberries.ru")
                || node.url.startsWith("https://digital.wildberries.ru")) return;
        if (node.shardKey == null || node.shardKey.isEmpty()) return;
        // Фильтруем blackhole - это недопустимый shardKey, который возвращает 404
        if ("blackhole".equals(node.shardKey)) return;

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
            // API card.wb.ru возвращает {"data":{"products":...}}, используем Root
            Root root = gson.fromJson(json, Root.class);
            
            if (root == null || root.data == null || root.data.products == null) {
                return sent;
            }

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