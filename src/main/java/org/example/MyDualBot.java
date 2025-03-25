package org.example;

import com.google.gson.*;
import com.google.gson.stream.JsonReader;
import com.pengrad.telegrambot.TelegramBot;
import com.pengrad.telegrambot.model.request.ParseMode;
import com.pengrad.telegrambot.request.SendMessage;
import com.pengrad.telegrambot.response.SendResponse;

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

public class MyDualBot extends TelegramLongPollingBot {
    private static final String FILE_PATH = "sent_articles";
    private static final String FILE_PATH_COMMUNITY = "sent_articles_community.txt";

    private Thread consumer100;
    private Thread consumer90;
    private Thread consumer80;
    private Thread consumerBig;
    private Thread consumerMyChat;
//    private Thread consumerError;
    private Thread consumerStrippingLazar;
    private Thread consumerStrippingLazarSent;
    private Thread consumerFree;
    private Thread consumerFreeSent;
    private Thread consumerFood;

    private static ConcurrentHashMap<String, String> sentArticles100 = new ConcurrentHashMap<>();
    private static ConcurrentHashMap<String, String> sentArticles90 = new ConcurrentHashMap<>();
    private static ConcurrentHashMap<String, String> sentArticles80 = new ConcurrentHashMap<>();
    private static ConcurrentHashMap<String, String> sentArticlesBig = new ConcurrentHashMap<>();
    private static ConcurrentHashMap<String, String> sentArticlesCommunity = new ConcurrentHashMap<>();
    private static ConcurrentHashMap<String, String> sentArticlesFood = new ConcurrentHashMap<>();

//    private static Set<String> sentArticles100 = ConcurrentHashMap.newKeySet();
//    private static Set<String> sentArticles90 = ConcurrentHashMap.newKeySet();
//    private static Set<String> sentArticles80 = ConcurrentHashMap.newKeySet();
//    private static Set<String> sentArticlesBig = ConcurrentHashMap.newKeySet();
//    private static Set<String> sentArticlesCommunity = ConcurrentHashMap.newKeySet();
//    private static Set<String> sentArticlesFood = ConcurrentHashMap.newKeySet();

    private static final BlockingQueue<String> queue100 = new LinkedBlockingQueue<>();
    private static final BlockingQueue<String> queue90 = new LinkedBlockingQueue<>();
    private static final BlockingQueue<String> queue80 = new LinkedBlockingQueue<>();
    private static final BlockingQueue<String> queueBig = new LinkedBlockingQueue<>();
    private static final BlockingQueue<String> queueMyChat = new LinkedBlockingQueue<>();
    private static final BlockingQueue<String> queueFood = new LinkedBlockingQueue<>();
    private static final BlockingQueue<ProductInfo> queueStrippingLazar = new LinkedBlockingQueue<>();
    private static final BlockingQueue<String> queueFree = new LinkedBlockingQueue<>();

//    private static final BlockingQueue<String> queueError = new LinkedBlockingQueue<>();

    static List<String[]> urls = new ArrayList<>();
    private static Set<String> urlsFood = ConcurrentHashMap.newKeySet();

    private final TelegramBot pengradBot;
    private Thread taskThreadV1;
    private Thread taskThreadV2;
    private static volatile boolean running = false;
    private static Set<HttpCookie> Cookies;
    public static Set<String> pidory = ConcurrentHashMap.newKeySet();
    private final Set<Long> waitingForMessage = new HashSet<>();
    private final List<String> admin = new ArrayList<>(Arrays.asList("1027094894", "1039378955","5392268853"));
    private final List<String> worker = new ArrayList<>(Arrays.asList("466086607","1039378955"));

    static Map<String, ProductInfo> mapOnSent = new HashMap<>();
    static Map<String, Long> mapOnSentFree = new HashMap<>();

    static int[] numberPagesProcessed = new int[2];

//    private static Map<String, Integer> check = new HashMap<>();
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
        } catch (IOException e) {
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
    
    private void startTask(long chatId) {
        if (running) {
            sendPengradMessage(String.valueOf(chatId),  "Task is already running.");
            return;
        }
//        queueError.clear();
        pidory = readPidora("pidory.txt");
        sentArticles100 = readSentArticles(FILE_PATH + "100.txt");
        sentArticles90 = readSentArticles(FILE_PATH + "90.txt");
        sentArticles80 = readSentArticles(FILE_PATH + "80.txt");
        sentArticlesBig = readSentArticles(FILE_PATH + "Big.txt");
        sentArticlesFood = readSentArticles(FILE_PATH + "Food.txt");
        sentArticlesCommunity = readSentArticles(FILE_PATH_COMMUNITY);

        running = true;
        consumer100 = new Thread(() -> {
            try {
                sentMessege100();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        });
        consumer100.setDaemon(true);
        consumer100.start();

        consumer90 = new Thread(() -> {
            try {
                sentMessege90();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        });
        consumer90.setDaemon(true);
        consumer90.start();

        consumer80 = new Thread(() -> {
            try {
                sentMessege80();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        });
        consumer80.setDaemon(true);
        consumer80.start();

        consumerBig = new Thread(() -> {
            try {
                sentMessegeBig();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        });
        consumerBig.setDaemon(true);
        consumerBig.start();

        consumerMyChat = new Thread(() -> {
            try {
                sentMessegeMyChat();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        });
        consumerMyChat.setDaemon(true);
        consumerMyChat.start();

//        consumerError = new Thread(() -> {
//            try {
//                errorHandler();
//            } catch (InterruptedException | IOException e) {
//                e.printStackTrace();
//            }
//        });
//        consumerError.setDaemon(true);
//        consumerError.start();

        consumerStrippingLazar = new Thread(() -> {
            try {
                strippingLazar();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        });
        consumerStrippingLazar.setDaemon(true);
        consumerStrippingLazar.start();

        consumerFree = new Thread(() -> {
            try {
                Free();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        });
        consumerFree.setDaemon(true);
        consumerFree.start();

        consumerFreeSent = new Thread(() -> {
            try {
                sentFree();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        });
        consumerFreeSent.setDaemon(true);
        consumerFreeSent.start();

        consumerFood = new Thread(() -> {
            try {
                sentMessegeFood();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        });
        consumerFood.setDaemon(true);
        consumerFood.start();

        consumerStrippingLazarSent = new Thread(() -> {
            try {
                sentStrippingLazarSent();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        });
        consumerStrippingLazarSent.setDaemon(true);
        consumerStrippingLazarSent.start();

        taskThreadV1 = new Thread(() -> {
            try {
                boolean reverse= true;
                while (running) {
                    mainOld(true, reverse);
                    reverse = !reverse;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
        taskThreadV1.start();
        taskThreadV2 = new Thread(() -> {
            try {
                Thread.sleep(2500);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            try {
                boolean reverse = true;
                while (running) {
                    mainOld(false, reverse);
                    reverse = !reverse;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
        taskThreadV2.start();

        sendPengradMessage(String.valueOf(chatId),  "Task started.");
    }

    private void stopTask(long chatId) {
        if (!running) {
            sendPengradMessage(String.valueOf(chatId),  "Task is not running.");
            return;
        }
        sendPengradMessage(String.valueOf(chatId),  "Wait pls");
        running = false;
        try {
            taskThreadV1.join();
            taskThreadV2.join();
            queue100.add("0~~0~~0");
            consumer100.join();
            queueFood.add("0~~0~~0");
            consumerFood.join();
            queue90.add("0~~0~~0");
            consumer90.join();
            queue80.add("0~~0~~0");
            consumer80.join();
            queueBig.add("0~~0~~0");
            consumerBig.join();
            queueMyChat.add("0~~0~~0");
            consumerMyChat.join();
//            queueError.add("0");
//            consumerError.join();
            ProductInfo productInfo = new ProductInfo();
            productInfo.settime(0L);
            queueStrippingLazar.add(productInfo);
            consumerStrippingLazarSent.join();

            consumerFree.join();
            queueFree.add("0");
            consumerFreeSent.join();

            consumerStrippingLazar.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        sendPengradMessage(String.valueOf(chatId),  "Task stopped.");
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

    public static void mainOld(boolean version, boolean reverse) throws InterruptedException {
        ExecutorService executorService = Executors.newFixedThreadPool(400);
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
                String currentPage ="";
                try {
                    int page=0, increment = 0;

                    boolean checkPage = true;

                    do{
                        it.getAndIncrement();
                        currentPage = "https://catalog.wb.ru/catalog/" + url[1] + "/v2/catalog?ab_testing=false&appType=1&" + url[2] + "&curr=rub&dest=-5551776&ffeedbackpoints=1&page=" + (increment+1) + "&sort=priceup&priceU=0;800000&spp=30";
                        Connection connectionPage = Jsoup.connect(currentPage)
                                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:132.0) Gecko/20100101 Firefox/132.0")
                                .method(Connection.Method.GET)
                                .ignoreContentType(true)
                                .timeout(1500);

                        for (HttpCookie cookie : Cookies) {
                            connectionPage.cookie(cookie.getName(), cookie.getValue());
                        }
                        Connection.Response responsePage = connectionPage.execute();

                        String jsons = responsePage.body();
                        JsonReader jsonReaderPage = new JsonReader(new StringReader(jsons));
                        jsonReaderPage.setLenient(true);

                        JsonElement rootElementPage = JsonParser.parseReader(jsonReaderPage);
                        JsonObject rootObjectPage = rootElementPage.getAsJsonObject();

                        int numberCells = rootObjectPage.getAsJsonObject("data").get("total").getAsInt();

                        if(numberCells == 0){
                            urls.remove(url);
                            continue;
                        }

                        JsonArray productsArray = rootObjectPage.getAsJsonObject("data").getAsJsonArray("products");
                        List<String> newInem = new ArrayList<>();
                        for (JsonElement productElement : productsArray) {
                            JsonObject productObject = productElement.getAsJsonObject();

                            String itemName = productObject.has("name") ? productObject.get("name").getAsString() : " ";
                            String feedBackSum = productObject.has("feedbackPoints") ? productObject.get("feedbackPoints").getAsString() : "0";
                            String article = productObject.has("id") ? productObject.get("id").getAsString() : "0";
                            String totalQuery = productObject.has("totalQuantity") ? productObject.get("totalQuantity").getAsString() : "0";
                            JsonArray sizesArray = productObject.getAsJsonArray("sizes");
                            for (JsonElement sizeElement : sizesArray) {
                                if (!newInem.contains(article)) {
                                    newInem.add(article);
                                    JsonObject sizeObject = sizeElement.getAsJsonObject();
                                    int total = sizeObject.getAsJsonObject("price").has("total") ? sizeObject.getAsJsonObject("price").get("total").getAsInt() : 0;
                                    total = total/100;
                                    readTxtFile(itemName, String.valueOf(total), feedBackSum, article, totalQuery, url[0]);
                                }
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
                    }while (increment<page);
                } catch (Exception e) {
//                    check.put(url[0], 0);
                    i.getAndIncrement();
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
        try (BufferedReader reader = new BufferedReader(new FileReader(FILE_PATH))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sentArticles.add(line);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return sentArticles;
    }

    private static ConcurrentHashMap<String, String> readSentArticles(String FILE_PATH) {
        ConcurrentHashMap<String, String> sentArticles =  new ConcurrentHashMap<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(FILE_PATH))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split(" ");
                if (parts.length == 2) {
                    sentArticles.put(parts[0],parts[1]);
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return sentArticles;
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

    public static void readTxtFile(String itemName, String itemCost, String itemfFeedBackCost, String article, String totalQuery, String category) throws IOException {
        if(Objects.equals(itemCost,"0")){
            List<String> sent = repeatCheck(article);
            itemCost = sent.get(1);
        }
        double percent = Double.parseDouble(itemfFeedBackCost) / Double.parseDouble(itemCost);
        ProductInfo productInfo = new ProductInfo();
        if ((!sentArticles100.containsKey(article) && !sentArticles90.containsKey(article) && !sentArticles80.containsKey(article) && !sentArticlesBig.containsKey(article))
                || (sentArticles100.containsKey(article) && (Math.abs(Double.parseDouble(sentArticles100.get(article)) -(percent))> 0.05))
                || (sentArticles80.containsKey(article) && (Math.abs(Double.parseDouble(sentArticles80.get(article)) - (percent))> 0.05))
                || (sentArticles90.containsKey(article) && (Math.abs(Double.parseDouble(sentArticles90.get(article)) - (percent))> 0.05))
                || (sentArticlesBig.containsKey(article) && (Math.abs(Double.parseDouble(sentArticlesBig.get(article)) - (percent))> 0.05))
        ) {
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
//        }
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
            } else if (percent >= 0.9 && percent < 1) {
                double bol = hasFeedbackPoints(article);
                if (bol == 0) {
                    return;
                }
                messege = createMessege(itemName, itemCost, itemfFeedBackCost, article,percent,totalQuery);
                queue90.add(messege);
                productInfo.setquantity(String.valueOf(Integer.parseInt(totalQuery)));
                productInfo.settime(System.currentTimeMillis());
                mapOnSent.put(article, productInfo);
            } else if (percent >= 0.8 && percent < 0.9) {
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
        if (!sentArticlesCommunity.containsKey(article)
                || (sentArticlesCommunity.containsKey(article) && (Math.abs(Double.parseDouble(sentArticlesCommunity.get(article)) - (percent))> 0.05))
        ) {
            String messege;

            if (percent >= 1.5 || (Double.parseDouble(itemfFeedBackCost) - Double.parseDouble(itemCost) >= 199 && percent > 1)) {
//            if (percent >= 0.8 || ((percent > 0.49 && Integer.parseInt(itemfFeedBackCost) >= 1000 && Integer.parseInt(itemfFeedBackCost) < 2500)
//                    || (percent > 0.59 && Integer.parseInt(itemfFeedBackCost) >= 699 && Integer.parseInt(itemfFeedBackCost) < 1000)
//                    || (percent >= 0.4 && Integer.parseInt(itemfFeedBackCost) >= 2500))){
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
        if ((!sentArticlesFood.containsKey(article)  || (sentArticlesFood.containsKey(article) && (Math.abs(Double.parseDouble(sentArticlesFood.get(article)) - (percent))> 0.05))) && urlsFood.contains(category)) {
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

    private static void sentMessege100() throws InterruptedException {
        String chatIds = "-1002340997107";
        String chatId = "-1002402655346";
        MyDualBot tgBot = new MyDualBot("7564492259:AAHJFWRqVvJQuuUIVd5584h8ePoFxsg7YVc");
        try(BufferedWriter writer = new BufferedWriter(new FileWriter(FILE_PATH + "100.txt",true))){
            while (running || !queue100.isEmpty()) {
                String data = queue100.take(); // Извлечение данных из очереди
                String[] parts = data.split("~~", 3);
                String article = parts[0];
                String productInfo = parts[1];
                String percent = parts[2];
                if(Objects.equals(article, "0")){
                    continue;
                }
                if (!sentArticles100.containsKey(article)
                        || (sentArticles100.containsKey(article) && (Math.abs(Double.parseDouble(sentArticles100.get(article)) - Double.parseDouble(percent))> 0.05))) { // Проверка уникальности
                    sentArticles100.put(article, percent);
                    tgBot.sendMessage(chatId, 0, productInfo);
                    tgBot.sendMessage(chatIds, 2, productInfo);
                    writer.write(article + " " + percent + "\n");
                    writer.flush();
                }
            }
        }catch (IOException e) {
            e.printStackTrace();
        }
    }
    private static void sentMessegeFood() throws InterruptedException {
        String chatIds = "-1002340997107";
        String chatId = "-1002474423617";
        MyDualBot tgBot = new MyDualBot("7564492259:AAHJFWRqVvJQuuUIVd5584h8ePoFxsg7YVc");
        try(BufferedWriter writer = new BufferedWriter(new FileWriter(FILE_PATH + "Food.txt",true))){
            while (running || !queueFood.isEmpty()) {
                String data = queueFood.take(); // Извлечение данных из очереди
                String[] parts = data.split("~~", 3);
                String article = parts[0];
                String productInfo = parts[1];
                String percent = parts[2];
                if(Objects.equals(article, "0")){
                    continue;
                }
                if (!sentArticlesFood.containsKey(article)
                        || (sentArticlesFood.containsKey(article) && (Math.abs(Double.parseDouble(sentArticlesFood.get(article)) - Double.parseDouble(percent))> 0.05))) { // Проверка уникальности
                    sentArticlesFood.put(article, percent);
                    tgBot.sendMessage(chatId, 0, productInfo);
                    tgBot.sendMessage(chatIds, 89330, productInfo);
                    writer.write(article + " " + percent + "\n");
                    writer.flush();
                }
            }
        }catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static void sentMessege90() throws InterruptedException {
        String chatIds = "-1002340997107";
        String chatId = "-1002446322077";
        MyDualBot tgBot = new MyDualBot("7564492259:AAHJFWRqVvJQuuUIVd5584h8ePoFxsg7YVc");
        try(BufferedWriter writer = new BufferedWriter(new FileWriter(FILE_PATH + "90.txt",true ))){
            while (running || !queue90.isEmpty()) {
                String data = queue90.take(); // Извлечение данных из очереди
                String[] parts = data.split("~~", 3);
                String article = parts[0];
                String productInfo = parts[1];
                String percent = parts[2];
                if(Objects.equals(article, "0")){
                    continue;
                }
                if (!sentArticles90.containsKey(article) ||
                        (sentArticles90.containsKey(article) && (Math.abs(Double.parseDouble(sentArticles90.get(article)) - Double.parseDouble(percent))> 0.05))) { // Проверка уникальности
                    sentArticles90.put(article, percent);
                    tgBot.sendMessage(chatId, 0, productInfo);
                    tgBot.sendMessage(chatIds, 4, productInfo);
                    writer.write(article + " " + percent + "\n");
                    writer.flush();
                }
            }
        }catch (IOException e) {

        }
    }

    private static void sentMessege80() throws InterruptedException {
        String chatIds = "-1002340997107";
        String chatId = "-1002305962649";
        MyDualBot tgBot = new MyDualBot("7564492259:AAHJFWRqVvJQuuUIVd5584h8ePoFxsg7YVc");
        try(BufferedWriter writer = new BufferedWriter(new FileWriter(FILE_PATH + "80.txt",true ))){
            while (running || !queue80.isEmpty()) {
                String data = queue80.take(); // Извлечение данных из очереди
                String[] parts = data.split("~~", 3);
                String article = parts[0];
                String productInfo = parts[1];
                String percent = parts[2];
                if(Objects.equals(article, "0")){
                    continue;
                }
                if (!sentArticles80.containsKey(article)||
                        (sentArticles80.containsKey(article) && (Math.abs(Double.parseDouble(sentArticles80.get(article)) - Double.parseDouble(percent))> 0.05))) { // Проверка уникальности
                    sentArticles80.put(article, percent);
                    tgBot.sendMessage(chatId, 0, productInfo);
                    tgBot.sendMessage(chatIds, 6, productInfo);
                    writer.write(article + " " + percent + "\n");
                    writer.flush();
                }
            }
        }catch (IOException e) {
        }
    }

    private static void sentMessegeBig() throws InterruptedException {
        String chatIds = "-1002340997107";
        String chatId = "-1002290311759";
        MyDualBot tgBot = new MyDualBot("7564492259:AAHJFWRqVvJQuuUIVd5584h8ePoFxsg7YVc");
        try(BufferedWriter writer = new BufferedWriter(new FileWriter(FILE_PATH + "Big.txt",true ))){
            while (running || !queueBig.isEmpty()) {
                String data = queueBig.take(); // Извлечение данных из очереди
                String[] parts = data.split("~~", 3);
                String article = parts[0];
                String productInfo = parts[1];
                String percent = parts[2];
                if(Objects.equals(article, "0")){
                    continue;
                }
                if (!sentArticlesBig.containsKey(article) ||
                        (sentArticlesBig.containsKey(article) && (Math.abs(Double.parseDouble(sentArticlesBig.get(article)) - Double.parseDouble(percent))> 0.05))) { // Проверка уникальности
                    sentArticlesBig.put(article, percent);
                    tgBot.sendMessage(chatId, 0, productInfo);
                    tgBot.sendMessage(chatIds, 13, productInfo);
                    writer.write(article + " " + percent + "\n");
                    writer.flush();
                }
            }
        }catch (IOException e) {
//            e.printStackTrace();
        }
    }

    private static void sentMessegeMyChat() throws InterruptedException {
        String chatId = "-1002397733938";
        MyDualBot tgBot = new MyDualBot("7564492259:AAHJFWRqVvJQuuUIVd5584h8ePoFxsg7YVc");
        try(BufferedWriter writer = new BufferedWriter(new FileWriter(FILE_PATH_COMMUNITY, true))){
            while (running || !queueMyChat.isEmpty()) {
                String data = queueMyChat.take(); // Извлечение данных из очереди
                String[] parts = data.split("~~", 3);
                String article = parts[0];
                String productInfo = parts[1];
                String percent = parts[2];
                if(Objects.equals(article, "0")){
                    continue;
                }
                if (!sentArticlesCommunity.containsKey(article)||
                        (sentArticlesCommunity.containsKey(article) && (Math.abs(Double.parseDouble(sentArticlesCommunity.get(article)) - Double.parseDouble(percent))> 0.05))) { // Проверка уникальности
                    sentArticlesCommunity.put(article, percent);
                    tgBot.sendMessage(chatId, 8, productInfo);
                    writer.write(article + " " + percent + "\n");
                    writer.flush();
                }
            }
        }catch (IOException e) {
//            e.printStackTrace();
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

//    private static void errorHandler() throws InterruptedException, IOException {
//        ExecutorService executorError = Executors.newFixedThreadPool(400);
//        for (int i = 0; i < 200; i++) {
//            executorError.submit(() -> {
//                while (running || !queueError.isEmpty()) {
//                    String exception = "";
//                    try {
//                        String data = queueError.take(); // Извлекаем данные из очереди
//                        String[] parts = data.split(":", 2);
//                        String urls = parts[0];
//                        String category = parts[1];
//                        Thread.sleep(2000);
//                        if ("0".equals(urls)) {
//                            queueError.add("0"); // Позволяет другим потокам тоже завершиться
//                            break; // Завершаем поток
//                        }
//                        exception = urls;
//                        test2(urls, category);
//                    } catch (Exception e) {
//                        queueError.add(exception);
//                    }
//                }
//            });
//        }
//    }

    private static void strippingLazar() throws InterruptedException { // обработка ключ значений
        while (running) {
            Iterator<Map.Entry<String, ProductInfo>> iterator = mapOnSent.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<String, ProductInfo> map = iterator.next();
                String article = map.getKey();
                ProductInfo productInfo = map.getValue();
                productInfo.setArticle(article);
                long time = System.currentTimeMillis() - map.getValue().gettime();
                if(time > (1000*30+2*60*1000)){
//                if(time > (2*1000)){
                    queueStrippingLazar.add(productInfo);
                    iterator.remove();
                }
            }
            Thread.sleep(10*1000);
        }
    }

    private static void Free() throws InterruptedException { // обработка ключ значений
        while (running) {
            Iterator<Map.Entry<String, Long>> iterator = mapOnSentFree.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<String, Long> map = iterator.next();
                String article = map.getKey();
                long time = System.currentTimeMillis() - map.getValue();
                if(time > 1000*60*8){
                    queueFree.add(article);
                    iterator.remove();
                }
            }
            Thread.sleep(30*1000);
        }
    }

    public static double hasFeedbackPoints(String url1) throws IOException {
        String jsonUrl = "https://card.wb.ru/cards/v2/detail?appType=1&curr=rub&dest=-5923914&spp=30&ab_testing=false&nm="+ url1;
        Connection connection = Jsoup.connect(jsonUrl)
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:132.0) Gecko/20100101 Firefox/132.0")
                .method(Connection.Method.GET)
                .ignoreContentType(true);

        for (HttpCookie cookie : Cookies) {
            connection.cookie(cookie.getName(), cookie.getValue());
        }
        Connection.Response response = connection.execute();

        String json = response.body();

        JsonReader jsonReader = new JsonReader(new StringReader(json));
        jsonReader.setLenient(true);

        JsonElement rootElement = JsonParser.parseReader(jsonReader);
        JsonObject rootObject = rootElement.getAsJsonObject();

        JsonArray productsArray = rootObject.getAsJsonObject("data").getAsJsonArray("products");
        double hasFeedbackPoint = 0;
        for (JsonElement productElement : productsArray) {
            JsonObject productObject = productElement.getAsJsonObject();
            if(productObject.has("supplier")){
                String supplier = productObject.get("supplier").getAsString();
                if(pidory.contains(supplier)){
                    return 0;
                }
            }
            if (productObject.has("feedbackPoints")) {
                JsonArray sizesArray = productObject.getAsJsonArray("sizes");
                double total = 1;
                for (JsonElement sizeElement : sizesArray) {
                    JsonObject sizeObject = sizeElement.getAsJsonObject();
                    if(sizeObject.has("price")){
                        total = sizeObject.getAsJsonObject("price").has("total") ? sizeObject.getAsJsonObject("price").get("total").getAsInt() : 0;
                        total = total/100;
                        break;
                    }
                }
                String feedBackSum = productObject.get("feedbackPoints").getAsString();
                hasFeedbackPoint = Double.parseDouble(feedBackSum) / total;
                if (!feedBackSum.equals("0")) {
                    break;
                }
            }
        }
        return hasFeedbackPoint;
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

        for (HttpCookie cookie : Cookies) {
            connection.cookie(cookie.getName(), cookie.getValue());
        }
        Connection.Response response = connection.execute();

        String json = response.body();
        JsonReader jsonReader = new JsonReader(new StringReader(json));
        jsonReader.setLenient(true);

        JsonElement rootElement = JsonParser.parseReader(jsonReader);
        JsonArray objectArray = rootElement.getAsJsonArray();

        List<String[]> urls = new ArrayList<>();
        for (JsonElement object : objectArray) {
            JsonObject productObject = object.getAsJsonObject();
            processChildElements(productObject, urls);
        }
        return urls;
    }

    public static void processChildElements(JsonObject jsonObject, List<String[]> urls) {
        if (jsonObject.has("childs")) {
            JsonArray childsArray = jsonObject.getAsJsonArray("childs");
            for (JsonElement childElement : childsArray) {
                JsonObject childObject = childElement.getAsJsonObject();
                processChildElements(childObject, urls);
            }
        } else {
            String obj = jsonObject.has("url") ? jsonObject.get("url").getAsString() : "";
            if (!obj.isEmpty() && !obj.startsWith("https://vmeste.wildberries.ru") && !obj.startsWith("https://travel.wildberries.ru") && !obj.startsWith("https://digital.wildberries.ru")) {
                String shard = jsonObject.has("shard") && !jsonObject.get("shard").isJsonNull() ? jsonObject.get("shard").getAsString() : "";
                if (Objects.equals(shard, "")) {
                    return;
                }
                String query = jsonObject.has("query") && !jsonObject.get("query").isJsonNull() ? jsonObject.get("query").getAsString() : "";

                obj += "?sort=popular&page=1&ffeedbackpoints=1";
                obj = ensureUrlStartsWithPrefix(obj);
                boolean exists = false;
                for (String[] url : urls) {
                    if (url[0].equals(obj)) {
                        exists = true;
                        break;
                    }
                }

                if (!exists) {
                    urls.add(new String[]{obj, shard, query});
                }
            }
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

            JsonReader jsonReader = new JsonReader(new StringReader(json));
            jsonReader.setLenient(true);

            JsonElement rootElement = JsonParser.parseReader(jsonReader);
            JsonObject rootObject = rootElement.getAsJsonObject();

            JsonArray productsArray = rootObject.getAsJsonObject("data").getAsJsonArray("products");

            for (JsonElement productElement : productsArray) {
                JsonObject productObject = productElement.getAsJsonObject();
                if (productObject.has("feedbackPoints")) {
                    String feedBackSum = productObject.get("feedbackPoints").getAsString();
                    String itemName = productObject.has("name") ? productObject.get("name").getAsString() : " ";
                    String totalQuery = productObject.has("totalQuantity") ? productObject.get("totalQuantity").getAsString() : "0";
                    JsonArray sizesArray = productObject.getAsJsonArray("sizes");
                    for (JsonElement sizeElement : sizesArray) {
                        JsonObject sizeObject = sizeElement.getAsJsonObject();
                        if(sizeObject.has("price")){
                            int total = sizeObject.getAsJsonObject("price").has("total") ? sizeObject.getAsJsonObject("price").get("total").getAsInt() : 0;
                            total = total/100;
                            sent.add(itemName);
                            sent.add(String.valueOf(total));
                            sent.add(feedBackSum);
                            sent.add(totalQuery);
                            break;
                        }
                    }
                    return sent;
                }
            }
        }catch (Exception e){
//            e.printStackTrace();
        }
        return sent;
    }

//    private static void test2(String UrlPage, String category) throws InterruptedException {
//        try {
//            Connection connection = Jsoup.connect(UrlPage)
//                    .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:132.0) Gecko/20100101 Firefox/132.0")
//                    .method(Connection.Method.GET)
//                    .ignoreContentType(true)
//                    .timeout(1800);
//            for (HttpCookie cookie : Cookies) {
//                connection.cookie(cookie.getName(), cookie.getValue());
//            }
//            Connection.Response response = connection.execute();
//
//            String json = response.body();
//            JsonReader jsonReader = new JsonReader(new StringReader(json));
//            jsonReader.setLenient(true);
//
//            JsonElement rootElement = JsonParser.parseReader(jsonReader);
//            JsonObject rootObject = rootElement.getAsJsonObject();
//
//            JsonArray productsArray = rootObject.getAsJsonObject("data").getAsJsonArray("products");
//            List<String> newInem = new ArrayList<>();
//            for (JsonElement productElement : productsArray) {
//                JsonObject productObject = productElement.getAsJsonObject();
//
//                String itemName = productObject.has("name") ? productObject.get("name").getAsString() : " ";
//                String feedBackSum = productObject.has("feedbackPoints") ? productObject.get("feedbackPoints").getAsString() : "0";
//                String article = productObject.has("id") ? productObject.get("id").getAsString() : "0";
//                String totalQuery = productObject.has("totalQuantity") ? productObject.get("totalQuantity").getAsString() : "0";
//                JsonArray sizesArray = productObject.getAsJsonArray("sizes");
//                for (JsonElement sizeElement : sizesArray) {
//                    if (!newInem.contains(article)) {
//                        newInem.add(article);
//                        JsonObject sizeObject = sizeElement.getAsJsonObject();
//                        int total = sizeObject.getAsJsonObject("price").has("total") ? sizeObject.getAsJsonObject("price").get("total").getAsInt() : 0;
//                        total = total/100;
//                        readTxtFile(itemName, String.valueOf(total), feedBackSum, article, totalQuery, category);
//                    }
//                }
//            }
//        }catch (Exception e){
////            queueError.add(UrlPage);
//            Thread.sleep(1000);
//            test2(UrlPage, category);
//        }
//    }


    private static ConcurrentHashMap<String, String> hasPoint(ConcurrentHashMap<String, String> articles,String fileName) throws IOException {
        ExecutorService executorService = Executors.newFixedThreadPool(100);
        if(articles.isEmpty()){
            try (BufferedReader reader = new BufferedReader(new FileReader(fileName))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String[] parts = line.split(" ");
                    if (parts.length == 2) {
                        articles.put(parts[0],parts[1]);
                    }
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        for (String article : articles.keySet()){
            executorService.submit(() -> {
                double checkArticle = 0;
                try {
                    checkArticle = hasFeedbackPoints(article);
                } catch (IOException e) {
                    e.printStackTrace();
                }
                if(checkArticle != Double.parseDouble(articles.get(article))){
                    articles.remove(article);
                }
            });
        }

        executorService.shutdown();
        while (!executorService.isTerminated());
        try(BufferedWriter writer = new BufferedWriter(new FileWriter(fileName))) {
            for(String article : articles.keySet()){
                writer.write(article + " " + articles.get(article) + "\n");
                writer.flush();
            }
        }
        return articles;
    }
}