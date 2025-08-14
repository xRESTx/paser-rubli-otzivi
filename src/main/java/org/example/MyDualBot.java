package org.example;

import com.google.gson.*;
import com.google.gson.stream.JsonReader;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import com.pengrad.telegrambot.TelegramBot;
import com.pengrad.telegrambot.model.request.ParseMode;
import com.pengrad.telegrambot.request.SendMessage;
import com.pengrad.telegrambot.response.SendResponse;

import org.example.jsonmodel.Product;
import org.example.jsonmodel.Root;
import org.example.jsonmodel.UrlFetcher;
import org.jsoup.Connection;
import org.jsoup.Jsoup;
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

import java.util.*;
import java.io.BufferedWriter;
import java.io.FileWriter;
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

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(MyDualBot.class);

        // один пул на весь бот
    private static final ScheduledExecutorService SCHEDULER =
            Executors.newScheduledThreadPool(
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

    private static final Cache<String, Double> sentArticles100 =
            Caffeine.newBuilder().maximumSize(200_000).build();
    private static final Cache<String, Double> sentArticles90 =
            Caffeine.newBuilder().maximumSize(200_000).build();
    private static final Cache<String, Double> sentArticles80 =
            Caffeine.newBuilder().maximumSize(200_000).build();
    private static final Cache<String, Double> sentArticlesBig =
            Caffeine.newBuilder().maximumSize(200_000).build();
    private static final Cache<String, Double> sentArticlesCommunity =
            Caffeine.newBuilder().maximumSize(200_000).build();
    private static final Cache<String, Double> sentArticlesFood =
            Caffeine.newBuilder().maximumSize(200_000).build();

    private static final BlockingQueue<String> queue100 = new LinkedBlockingQueue<>();
    private static final BlockingQueue<String> queue90 = new LinkedBlockingQueue<>();
    private static final BlockingQueue<String> queue80 = new LinkedBlockingQueue<>();
    private static final BlockingQueue<String> queueBig = new LinkedBlockingQueue<>();
    private static final BlockingQueue<String> queueMyChat = new LinkedBlockingQueue<>();
    private static final BlockingQueue<String> queueFood = new LinkedBlockingQueue<>();
    private static final BlockingQueue<ProductInfo> queueStrippingLazar = new LinkedBlockingQueue<>();
    private static final BlockingQueue<String> queueFree = new LinkedBlockingQueue<>();


    static List<String[]> urls = new ArrayList<>();
    private static Set<String> urlsFood = ConcurrentHashMap.newKeySet();

    private final TelegramBot pengradBot;

    private static volatile boolean running = false;
    private static Set<HttpCookie> Cookies;
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
                if (messageText.equals("/run")) {
                    startTask(chatId);
                } else if (messageText.equals("/stop")) {
                    stopTask(chatId);
                }else if(messageText.equals("/clear")){
                    try {
                        clearTask(chatId);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                } else if(messageText.equals("/pidory")){
                    waitingForMessage.add(chatId);
                    sendPengradMessage(String.valueOf(chatId),  "Send pidora");
                }else if (waitingForMessage.contains(chatId)) {
                    try (BufferedWriter reader = new BufferedWriter(new FileWriter("pidory.txt", true))) {
                        pidory.add(messageText);
                        reader.write("\n" + messageText);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                    sendPengradMessage(String.valueOf(chatId),  "The text is written to a file!");
                    waitingForMessage.remove(chatId); // Убираем из режима ожидания
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

        sendPengradMessage(String.valueOf(chatId),  "Cleaning is complete");
        startTask(chatId);
    }

    private final List<Future<?>> tasks = new ArrayList<>();

    private void startTask(long chatId) {
        if (running) {
            sendPengradMessage(String.valueOf(chatId), "Task is already running.");
            return;
        }
        sendPengradMessage(String.valueOf(chatId), "Start task.");
        pidory = readPidora("pidory.txt");
        readSentArticlesToCache(FILE_PATH + "100.txt", sentArticles100);
        readSentArticlesToCache(FILE_PATH + "90.txt", sentArticles90);
        readSentArticlesToCache(FILE_PATH + "80.txt", sentArticles80);
        readSentArticlesToCache(FILE_PATH + "Big.txt", sentArticlesBig);
        readSentArticlesToCache(FILE_PATH + "Food.txt", sentArticlesFood);
        readSentArticlesToCache(FILE_PATH_COMMUNITY, sentArticlesCommunity);

        running = true;

        // 100
        tasks.add(SCHEDULER.submit(() ->
        {
            try {
                runSender("100.txt", queue100, sentArticles100,"-1002340997107", 2,
                        "-1002402655346");
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

        tasks.forEach(f -> f.cancel(false));   // мягкая отмена
        tasks.clear();

        // «пустышки» в очередях, чтобы потоки-таскеры вышли из блокирующего take()
        queue100.add("0~~0~~0");
        queue90.add("0~~0~~0");
        queue80.add("0~~0~~0");
        queueBig.add("0~~0~~0");
        queueMyChat.add("0~~0~~0");
        queueFood.add("0~~0~~0");
        queueStrippingLazar.add(new ProductInfo()); // time=0 → выход
        queueFree.add("0");

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
                        e.printStackTrace();
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
                e.printStackTrace();
            }
        }
        return 0;
    }
//    static boolean direction = false;

    public static void mainOld(boolean version, boolean reverse) {
        ExecutorService executorService = Executors.newFixedThreadPool(330);
//        ExecutorService executorService1 = Executors.newFixedThreadPool(400);
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

                        List<String> newInem = new ArrayList<>();
                        for (Product product : root.data.products) {
                            String itemName = product.name != null ? product.name : " ";
                            String feedBackSum = product.feedbackPoints != null ? product.feedbackPoints : "0";
                            String article = product.id != null ? product.id : "0";
                            String totalQuery = product.totalQuantity != null ? product.totalQuantity : "0";
//                            System.out.println(itemName+" " + feedBackSum + " " + article + " " + totalQuery);
                            if (!newInem.contains(article)) {
                                newInem.add(article);
                                int total = 0;
                                if (product.sizes != null && !product.sizes.isEmpty() && product.sizes.getFirst().price != null) {
                                    total = product.sizes.getFirst().price.total / 100;
                                }

                                readTxtFile(itemName, String.valueOf(total), feedBackSum, article, totalQuery, url[0]);
                            }
                        }
//                        Integer previousTotalPage = check.get(url[0]);
                        int totalPage;

//                        if ((previousTotalPage == null || !previousTotalPage.equals(numberCells)) && checkPage) {
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
//                        check.put(url[0], numberCells);
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

    public static void readTxtFile(String itemName, String itemCost, String itemfFeedBackCost, String article, String totalQuery, String category) throws IOException, InterruptedException {
        if(Objects.equals(itemCost,"0")){
            List<String> sent = repeatCheck(article);
            itemCost = sent.get(1);
        }
        double percent = Double.parseDouble(itemfFeedBackCost) / Double.parseDouble(itemCost);
        ProductInfo productInfo = new ProductInfo();
        Double old100 = sentArticles100.getIfPresent(article);
        Double old90  = sentArticles90.getIfPresent(article);
        Double old80  = sentArticles80.getIfPresent(article);
        Double oldBig = sentArticlesBig.getIfPresent(article);
        Double oldCommunity =  sentArticlesCommunity.getIfPresent(article);
        Double oldFood = sentArticlesFood.getIfPresent(article);
        boolean absent = old100 == null && old90 == null && old80 == null && oldBig == null;
        boolean changed =
                (old100 != null && Math.abs(old100 - percent) > 0.05) ||
                        (old90  != null && Math.abs(old90  - percent) > 0.05) ||
                        (old80  != null && Math.abs(old80  - percent) > 0.05) ||
                        (oldBig != null && Math.abs(oldBig - percent) > 0.05);
        if(absent || changed){
            String messege;

            if (((percent > 0.49 && Integer.parseInt(itemfFeedBackCost) >= 1000 && Integer.parseInt(itemfFeedBackCost) < 2500)
                    || (percent > 0.59 && Integer.parseInt(itemfFeedBackCost) >= 699 && Integer.parseInt(itemfFeedBackCost) < 1000 && percent < 0.9)
                    || (percent >= 0.4 && Integer.parseInt(itemfFeedBackCost) >= 2500))) {

                double bol = hasFeedbackPoints(article);
                if (bol == 0) {
                    return;
                }
                messege = createMessege(itemName, itemCost, itemfFeedBackCost, article, percent, totalQuery);
                queueBig.add(messege);
                productInfo.setquantity(String.valueOf(Integer.parseInt(totalQuery)));
                productInfo.settime(System.currentTimeMillis());
                mapOnSent.put(article, productInfo);
            }
            if (percent >= 1) {
                double bol = hasFeedbackPoints(article);
                if (bol == 0) {
                    return;
                }
                messege = createMessege(itemName, itemCost, itemfFeedBackCost, article,percent,totalQuery);
                queue100.add(messege);
                productInfo.setquantity(String.valueOf(Integer.parseInt(totalQuery)));
                productInfo.settime(System.currentTimeMillis());
                mapOnSent.put(article, productInfo);
            } else if (percent >= 0.9) {
                double bol = hasFeedbackPoints(article);
                if (bol == 0) {
                    return;
                }
                messege = createMessege(itemName, itemCost, itemfFeedBackCost, article,percent,totalQuery);
                queue90.add(messege);
                productInfo.setquantity(String.valueOf(Integer.parseInt(totalQuery)));
                productInfo.settime(System.currentTimeMillis());
                mapOnSent.put(article, productInfo);
            } else if (percent >= 0.8) {
                double bol = hasFeedbackPoints(article);
                if (bol == 0) {
                    return;
                }
                messege = createMessege(itemName, itemCost, itemfFeedBackCost, article,percent,totalQuery);
                queue80.add(messege);
                productInfo.setquantity(String.valueOf(Integer.parseInt(totalQuery)));
                productInfo.settime(System.currentTimeMillis());
                mapOnSent.put(article, productInfo);
            }
        }
        if (oldCommunity == null || Math.abs(oldCommunity - percent) > 0.05) {
            String messege;

            if (percent >= 1.5 || (Double.parseDouble(itemfFeedBackCost) - Double.parseDouble(itemCost) >= 199 && percent > 1)) {
                double bol = hasFeedbackPoints(article);
                if (bol == 0) {
                    return;
                }
                messege = createMessege(itemName, itemCost, itemfFeedBackCost, article,percent,totalQuery);
                productInfo.setquantity(String.valueOf(Integer.parseInt(totalQuery)));
                productInfo.settime(System.currentTimeMillis());
                mapOnSent.put(article, productInfo);
                queueMyChat.add(messege);
            }
        }
        if((oldFood==null || Math.abs(oldFood - percent) > 0.05) && urlsFood.contains(category)){
            String messege;

            if (percent >= 0.45) {
                double bol = hasFeedbackPoints(article);
                if (bol == 0) {
                    return;
                }
                messege = createMessege(itemName, itemCost, itemfFeedBackCost, article,percent,totalQuery);
                queueFood.add(messege);
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
                if ("0~~0~~0".equals(data)) break;

                String[] parts = data.split("~~", 3);
                String article = parts[0];
                String productInfo = parts[1];
                double percent = Double.parseDouble(parts[2]);

                Double old = cache.getIfPresent(article);
                if (old == null || Math.abs(old - percent) > 0.05) {
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

    private static void sentStrippingLazarSent() throws InterruptedException {
        String chatId = "-1002239949862";
//        String chatId = "-1002275882959";
        MyDualBot tgBot = new MyDualBot("7564492259:AAHJFWRqVvJQuuUIVd5584h8ePoFxsg7YVc");
        try{
            while (running) {
                ProductInfo article = queueStrippingLazar.take(); // Извлечение данных из очереди
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
                    String data = createMessege(sent.get(0), sent.get(1), sent.get(2), article.getArticle(), percent, sent.get(3));
                    String[] parts = data.split("~~", 3);
                    String productInfo = parts[1]+ "\n\uD83D\uDCCAКуплено с момента публикации в <a href=\"https://t.me/WB_Jackpot_sub_bot\">бота</a>: " + (Integer.parseInt(article.getquantity()) - Integer.parseInt(sent.get(3)))  + "\n\n<a href=\"https://t.me/WB_Jackpot/3793\">\uD83D\uDCB0Товар найден группой WB_Jackpot. Присоединяйтесь!\uD83D\uDCB0</a>";
                    mapOnSentFree.put(article.getArticle(),System.currentTimeMillis());
                    tgBot.sendMessage(chatId, 0, productInfo);
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
            while (running) {
                String article = queueFree.take(); // Извлечение данных из очереди
                if(Objects.equals(article, "0")){
                    continue;
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
                    String data = createMessege(sent.get(0), sent.get(1), sent.get(2), article, percent, sent.get(3));
                    String[] parts = data.split("~~", 3);
                    String productInfo = parts[1] + "\n\n <a href=\"https://t.me/WB_Jackpot/3793\">\uD83D\uDCB0Товар найден группой WB_Jackpot. Присоединяйтесь!\uD83D\uDCB0</a>";
                    tgBot.sendMessage(chatId, 0, productInfo);
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
            // пропускаем товары от запрещённых поставщиков
            if (product.supplier != null && pidory.contains(product.supplier)) {
                return 0;
            }

            if (product.feedbackPoints != null && !product.feedbackPoints.equals("0")) {
                double total = 1;
                if (product.sizes != null && !product.sizes.isEmpty() && product.sizes.get(0).price != null) {
                    total = product.sizes.get(0).price.total / 100.0;
                }

                double feedbackPoint = Double.parseDouble(product.feedbackPoints) / total;
                return feedbackPoint;
            }
        }
        return 0;
    }

    static String createMessege(String itemName, String itemCost, String itemfFeedBackCost, String article, Double percent, String totalQuery){
        String href = "https://www.wildberries.ru/catalog/" + article + "/detail.aspx";
        DecimalFormat df = new DecimalFormat("#.##");
        itemName = itemName.replace(":","");
        return article+ "~~"  + itemName + "\n\uD83D\uDCB8Стоимость " + itemCost + "\u20BD\n" +
                "\uD83C\uDFB0Кешбэк " + itemfFeedBackCost + "\u20BD\n " +
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
                if (product.feedbackPoints != null) {
                    String feedBackSum = product.feedbackPoints;
                    String itemName = product.name != null ? product.name : " ";
                    String totalQuery = product.totalQuantity != null ? product.totalQuantity : "0";

                    int total = 0;
                    if (product.sizes != null && !product.sizes.isEmpty() && product.sizes.getFirst().price != null) {
                        total = product.sizes.getFirst().price.total / 100;
                    }

                    sent.add(itemName);
                    sent.add(String.valueOf(total));
                    sent.add(feedBackSum);
                    sent.add(totalQuery);
                    return sent;
                }
            }
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
                    if (stored == null || Math.abs(actual - stored) > 0.05) {
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