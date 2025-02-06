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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.HashMap;
import java.util.Map;

public class MyDualBot extends TelegramLongPollingBot {
    private static final String FILE_PATH = "sent_articles";
    private static final String FILE_PATH_COMMUNITY = "sent_articles_community.txt";

    private Thread consumer100;
    private Thread consumer90;
    private Thread consumer80;
    private Thread consumerBig;
    private Thread consumerMyChat;
    private Thread consumerError;
    private Thread consumerStrippingLazar;
    private Thread consumerStrippingLazarSent;


    private static Set<String> sentArticles100 = ConcurrentHashMap.newKeySet();
    private static Set<String> sentArticles90 = ConcurrentHashMap.newKeySet();
    private static Set<String> sentArticles80 = ConcurrentHashMap.newKeySet();
    private static Set<String> sentArticlesBig = ConcurrentHashMap.newKeySet();
    private static Set<String> sentArticlesCommunity = ConcurrentHashMap.newKeySet();

    private static final BlockingQueue<String> queue100 = new LinkedBlockingQueue<>();
    private static final BlockingQueue<String> queue90 = new LinkedBlockingQueue<>();
    private static final BlockingQueue<String> queue80 = new LinkedBlockingQueue<>();
    private static final BlockingQueue<String> queueBig = new LinkedBlockingQueue<>();
    private static final BlockingQueue<String> queueMyChat = new LinkedBlockingQueue<>();
    private static final BlockingQueue<String> queueStrippingLazar = new LinkedBlockingQueue<>();

    private static final BlockingQueue<String> queueError = new LinkedBlockingQueue<>();

    static List<String[]> urls = new ArrayList<>();

    private final TelegramBot pengradBot;
    private Thread taskThread;
    private static volatile boolean running = false;
    private static Set<HttpCookie> Cookies;
    public static Set<String> pidory = ConcurrentHashMap.newKeySet();
    private final Set<Long> waitingForMessage = new HashSet<>();
    private final List<String> admin = new ArrayList<>(Arrays.asList("1027094894", "1039378955","5392268853"));

    static Map<String, Long> mapOnSent = new HashMap<>();

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
            if(admin.contains(String.valueOf(chatId))){
                if (messageText.equals("/run")) {
                    startTask(chatId);
                } else if (messageText.equals("/stop")) {
                    stopTask(chatId);
                }else if(messageText.equals("/cleen")){
                    try {
                        clearTask(chatId);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                } else if(messageText.equals("/pirory")){
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
    private void clearTask(long chatId) throws IOException {
        sendPengradMessage(String.valueOf(chatId),  "Start cleaning");
        if(running){
            stopTask(chatId);
        }

        hasPoint(sentArticles100, FILE_PATH + "100.txt");
        hasPoint(sentArticles90, FILE_PATH + "90.txt");
        hasPoint(sentArticles80, FILE_PATH + "80.txt");
        hasPoint(sentArticlesBig, FILE_PATH + "Big.txt");
        hasPoint(sentArticlesCommunity, FILE_PATH_COMMUNITY);

        sendPengradMessage(String.valueOf(chatId),  "Cleaning is complete");
        startTask(chatId);
    }
    private void startTask(long chatId) {
        if (running) {
            sendPengradMessage(String.valueOf(chatId),  "Task is already running.");
            return;
        }
        queueError.clear();
        pidory = readSentArticles("pidory.txt");
        sentArticles100 = readSentArticles(FILE_PATH + "100.txt");
        sentArticles90 = readSentArticles(FILE_PATH + "90.txt");
        sentArticles80 = readSentArticles(FILE_PATH + "80.txt");
        sentArticlesBig = readSentArticles(FILE_PATH + "Big.txt");
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

        consumerError = new Thread(() -> {
            try {
                errorHandler();
            } catch (InterruptedException | IOException e) {
                e.printStackTrace();
            }
        });
        consumerError.setDaemon(true);
        consumerError.start();

        consumerStrippingLazar = new Thread(() -> {
            try {
                strippingLazar();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        });
        consumerStrippingLazar.setDaemon(true);
        consumerStrippingLazar.start();


        consumerStrippingLazarSent = new Thread(() -> {
            try {
                sentStrippingLazarSent();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        });
        consumerStrippingLazarSent.setDaemon(true);
        consumerStrippingLazarSent.start();

        taskThread = new Thread(() -> {
            try {
                while (running) {
                    mainOld();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
        taskThread.start();
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
            taskThread.join();
            queue100.add("0:0");
            consumer100.join();
            queue90.add("0:0");
            consumer90.join();
            queue80.add("0:0");
            consumer80.join();
            queueBig.add("0:0");
            consumerBig.join();
            queueMyChat.add("0:0");
            queueError.add("0");
            consumerError.join();
            queueStrippingLazar.add("0");
            consumerStrippingLazarSent.join();
            consumerMyChat.join();
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

    public static void mainOld() throws InterruptedException, ExecutionException {
        ExecutorService executorService = Executors.newFixedThreadPool(100);
        ExecutorService executorService1 = Executors.newFixedThreadPool(500);
        long startTime = System.currentTimeMillis();

        List<String> urlsPage = Collections.synchronizedList(new ArrayList<>());
        List<Future<?>> futures = new ArrayList<>();

        for (String[] url : urls) {
            futures.add(executorService.submit(() -> {
                try {
                    Connection connectionPage = Jsoup.connect("https://catalog.wb.ru/catalog/" + url[1] + "/v6/filters?ab_testing=false&appType=1&" + url[2] + "&curr=rub&dest=-5551776&ffeedbackpoints=1&spp=30")
                            .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:132.0) Gecko/20100101 Firefox/132.0")
                            .method(Connection.Method.GET)
                            .header("Accept", "application/json")
                            .ignoreContentType(true);

                    for (HttpCookie cookie : Cookies) {
                        connectionPage.cookie(cookie.getName(), cookie.getValue());
                    }
                    Connection.Response responsePage = connectionPage.execute();

                    String jsons = responsePage.body();
                    JsonReader jsonReaderPage = new JsonReader(new StringReader(jsons));
                    jsonReaderPage.setLenient(true);

                    JsonElement rootElementPage = JsonParser.parseReader(jsonReaderPage);
                    JsonObject rootObjectPage = rootElementPage.getAsJsonObject();
                    int totalPage = rootObjectPage.getAsJsonObject("data").get("total").getAsInt();
                    if(totalPage%100==0){
                        totalPage = totalPage /100;
                    }else{
                        totalPage = totalPage/ 100;
                        totalPage++;
                    }
                    for(int i = 1; i<=totalPage; i++) {
                        urlsPage.add("https://catalog.wb.ru/catalog/" + url[1] + "/v2/catalog?ab_testing=false&appType=1&" + url[2] + "&curr=rub&dest=-5551776&ffeedbackpoints=1&" + "page=" + i + "&sort=popular&spp=30");
                    }
                } catch (IOException e) {
                }
            }));
        }

        // Ждём завершения всех задач
        for (Future<?> future : futures) {
            future.get();
        }

        executorService.shutdown();

        futures.clear();
        AtomicInteger i = new AtomicInteger();
        for (String url : urlsPage) {
            futures.add(executorService1.submit(() -> {
                try {
                    test2(url);
                } catch (IOException | InterruptedException e) {
                    i.getAndIncrement();
                    queueError.add(url);
                }
            }));
        }

        for (Future<?> future : futures) {
            future.get();
        }
        executorService1.shutdown();
        long endTime = System.currentTimeMillis();
        System.out.println("E:" + queueError.size() + ",P:" + urlsPage.size() + ",T:" + (endTime - startTime));
    }

    private static Set<String> readSentArticles(String FILE_PATH) {
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
            SendMessage sendMessage = new SendMessage(chatId, messageText).messageThreadId(messageThreadId);
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

    public static void readTxtFile(String itemName, String itemCost, String itemfFeedBackCost, String article, String totalQuery) throws IOException, InterruptedException {
//        if (!sentArticles100.contains(article) && !sentArticles90.contains(article) && !sentArticles80.contains(article) && !sentArticlesBig.contains(article)) {
//            String messege;
//
//            double percent = Double.parseDouble(itemfFeedBackCost) / Integer.parseInt(itemCost);
//            if (((percent > 0.49 && Integer.parseInt(itemfFeedBackCost) >= 1000 && Integer.parseInt(itemfFeedBackCost) < 2500)
//                    || (percent > 0.59 && Integer.parseInt(itemfFeedBackCost) >= 699 && Integer.parseInt(itemfFeedBackCost) < 1000 && percent < 0.9)
//                    || (percent >= 0.4 && Integer.parseInt(itemfFeedBackCost) >= 2500))) {
//                boolean bol = hasFeedbackPoints(article);
//                if (!bol) {
//                    return;
//                }
//                messege = createMessege(itemName, itemCost, itemfFeedBackCost, article,percent,totalQuery);
//                queueBig.add(messege);
//                mapOnSent.put(article, System.currentTimeMillis());
//            }
//            if (percent >= 1) {
//                boolean bol = hasFeedbackPoints(article);
//                if (!bol) {
//                    return;
//                }
//                messege = createMessege(itemName, itemCost, itemfFeedBackCost, article,percent,totalQuery);
//                queue100.add(messege);
//                mapOnSent.put(article, System.currentTimeMillis());
//            } else if (percent >= 0.9 && percent < 1) {
//                boolean bol = hasFeedbackPoints(article);
//                if (!bol) {
//                    return;
//                }
//                messege = createMessege(itemName, itemCost, itemfFeedBackCost, article,percent,totalQuery);
//                queue90.add(messege);
//                mapOnSent.put(article, System.currentTimeMillis());
//            } else if (percent >= 0.8 && percent < 0.9) {
//                boolean bol = hasFeedbackPoints(article);
//                if (!bol) {
//                    return;
//                }
//                messege = createMessege(itemName, itemCost, itemfFeedBackCost, article,percent,totalQuery);
//                queue80.add(messege);
//                mapOnSent.put(article, System.currentTimeMillis());
//            }
//        }
        if (!sentArticlesCommunity.contains(article)) {
            double percent = Double.parseDouble(itemfFeedBackCost) / Integer.parseInt(itemCost);
            String messege;

            if (percent >= 1.5 || (Double.parseDouble(itemfFeedBackCost) - Double.parseDouble(itemCost) >= 199 && percent > 1)) {
                boolean bol = hasFeedbackPoints(article);
                if (!bol) {
                    return;
                }
                messege = createMessege(itemName, itemCost, itemfFeedBackCost, article,percent,totalQuery);
                queueMyChat.add(messege);
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
                String[] parts = data.split(":", 2);
                String article = parts[0];
                String productInfo = parts[1];
                if(Objects.equals(article, "0")){
                    continue;
                }
                if (sentArticles100.add(article)) { // Проверка уникальности
                    tgBot.sendMessage(chatId, 0, productInfo);
                    tgBot.sendMessage(chatIds, 2, productInfo);
                    writer.write(article + "\n");
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
                String[] parts = data.split(":", 2);
                String article = parts[0];
                String productInfo = parts[1];
                if(Objects.equals(article, "0")){
                    continue;
                }
                if (sentArticles90.add(article)) { // Проверка уникальности
                    tgBot.sendMessage(chatId, 0, productInfo);
                    tgBot.sendMessage(chatIds, 4, productInfo);
                    writer.write(article + "\n");
                    writer.flush();
                }
            }
        }catch (IOException e) {
            e.printStackTrace();
        }
    }
    private static void sentMessege80() throws InterruptedException {
        String chatIds = "-1002340997107";
        String chatId = "-1002305962649";
        MyDualBot tgBot = new MyDualBot("7564492259:AAHJFWRqVvJQuuUIVd5584h8ePoFxsg7YVc");
        try(BufferedWriter writer = new BufferedWriter(new FileWriter(FILE_PATH + "80.txt",true ))){
            while (running || !queue80.isEmpty()) {
                String data = queue80.take(); // Извлечение данных из очереди
                String[] parts = data.split(":", 2);
                String article = parts[0];
                String productInfo = parts[1];
                if(Objects.equals(article, "0")){
                    continue;
                }
                if (sentArticles80.add(article)) { // Проверка уникальности
                    tgBot.sendMessage(chatId, 0, productInfo);
                    tgBot.sendMessage(chatIds, 6, productInfo);
                    writer.write(article + "\n");
                    writer.flush();
                }
            }
        }catch (IOException e) {
            e.printStackTrace();
        }
    }
    private static void sentMessegeBig() throws InterruptedException {
        String chatIds = "-1002340997107";
        String chatId = "-1002290311759";
        MyDualBot tgBot = new MyDualBot("7564492259:AAHJFWRqVvJQuuUIVd5584h8ePoFxsg7YVc");
        try(BufferedWriter writer = new BufferedWriter(new FileWriter(FILE_PATH + "Big.txt",true ))){
            while (running || !queueBig.isEmpty()) {
                String data = queueBig.take(); // Извлечение данных из очереди
                String[] parts = data.split(":", 2);
                String article = parts[0];
                String productInfo = parts[1];
                if(Objects.equals(article, "0")){
                    continue;
                }
                if (sentArticlesBig.add(article)) { // Проверка уникальности
                    tgBot.sendMessage(chatId, 0, productInfo);
                    tgBot.sendMessage(chatIds, 13, productInfo);
                    writer.write(article + "\n");
                    writer.flush();
                }
            }
        }catch (IOException e) {
            e.printStackTrace();
        }
    }
    private static void sentMessegeMyChat() throws InterruptedException {
        String chatId = "-1002397733938";
        MyDualBot tgBot = new MyDualBot("7564492259:AAHJFWRqVvJQuuUIVd5584h8ePoFxsg7YVc");
        try(BufferedWriter writer = new BufferedWriter(new FileWriter(FILE_PATH_COMMUNITY, true))){
            while (running || !queueMyChat.isEmpty()) {
                String data = queueMyChat.take(); // Извлечение данных из очереди
                String[] parts = data.split(":", 2);
                String article = parts[0];
                String productInfo = parts[1];
                if(Objects.equals(article, "0")){
                    continue;
                }
                if (sentArticlesCommunity.add(article)) { // Проверка уникальности
                    tgBot.sendMessage(chatId, 8, productInfo);
                    writer.write(article + "\n");
                    writer.flush();
                }
            }
        }catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static void sentStrippingLazarSent() throws InterruptedException {
        String chatId = "-1002239949862";
        MyDualBot tgBot = new MyDualBot("7564492259:AAHJFWRqVvJQuuUIVd5584h8ePoFxsg7YVc");
        try{
            while (running || !queueStrippingLazar.isEmpty()) {
                String article = queueStrippingLazar.take(); // Извлечение данных из очереди
                if(Objects.equals(article, "0")){
                    continue;
                }
                List<String> sent = repeatCheck(article);
                if(sent.isEmpty()){
                    continue;
                }
                double percent = Double.parseDouble(sent.get(2)) / Integer.parseInt(sent.get(1));
                if (percent >= 0.8 || ((percent > 0.49 && Integer.parseInt(sent.get(2)) >= 1000 && Integer.parseInt(sent.get(2)) < 2500)
                        || (percent > 0.59 && Integer.parseInt(sent.get(2)) >= 699 && Integer.parseInt(sent.get(2)) < 1000)
                        || (percent >= 0.4 && Integer.parseInt(sent.get(2)) >= 2500))){
                    String data = createMessege(sent.get(0), sent.get(1), sent.get(2), article, percent, sent.get(3));
                    String[] parts = data.split(":", 2);
                    String productInfo = parts[1];

                    tgBot.sendMessage(chatId, 0, productInfo);
                }

            }
        }catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static void errorHandler() throws InterruptedException, IOException {
        ExecutorService executorError = Executors.newFixedThreadPool(200);
        for (int i = 0; i < 200; i++) {
            executorError.submit(() -> {
                while (running || !queueError.isEmpty()) {
                    String exception = "";
                    try {
                        String data = queueError.take(); // Извлекаем данные из очереди
                        if ("0".equals(data)) {
                            queueError.add("0"); // Позволяет другим потокам тоже завершиться
                            break; // Завершаем поток
                        }
                        exception = data;
                        test2(data);
                    } catch (Exception e) {
                        queueError.add(exception);
                    }
                }
            });
        }
    }

    private static void strippingLazar() throws InterruptedException { // обработка ключ значений
        while (running) {
            Iterator<Map.Entry<String, Long>> iterator = mapOnSent.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<String, Long> map = iterator.next();
                String article = map.getKey();
                long time = System.currentTimeMillis() - map.getValue();
                if(time > 1000*60*2){
                    queueStrippingLazar.add(article);
                    iterator.remove();
                }
            }
            Thread.sleep(5000);
        }
    }

    public static boolean hasFeedbackPoints(String url1) throws IOException {
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
        boolean hasFeedbackPoint = false;
        for (JsonElement productElement : productsArray) {
            JsonObject productObject = productElement.getAsJsonObject();
            if(productObject.has("supplier")){
                String supplier = productObject.get("supplier").getAsString();
                if(pidory.contains(supplier)){
                    return false;
                }
            }
            if (productObject.has("feedbackPoints")) {
                String feedBackSum = productObject.get("feedbackPoints").getAsString();
                if (!feedBackSum.equals("0")) {
                    hasFeedbackPoint = true;
                    break;
                }
            }
        }
        return hasFeedbackPoint;
    }

    static String createMessege(String itemName, String itemCost, String itemfFeedBackCost, String article, Double percent, String totalQuery){
        String href = "https://www.wildberries.ru/catalog/" + article + "/detail.aspx";
        DecimalFormat df = new DecimalFormat("#.##");
        return article+ ":" + itemName + "\n\uD83D\uDCB8Price " + itemCost + "\u20BD\n" +
                "\uD83C\uDFB0Cashback " + itemfFeedBackCost + "\u20BD\n" +
                "\uD83D\uDCAFPercent " + df.format(percent * 100) + "%\n" +
                "\uD83C\uDFB2Quantity " + totalQuery + "\n"+ href;
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

    public static List<String[]> processChildElements(JsonObject jsonObject, List<String[]> urls) {
        if (jsonObject.has("childs")) {
            JsonArray childsArray = jsonObject.getAsJsonArray("childs");
            for (JsonElement childElement : childsArray) {
                JsonObject childObject = childElement.getAsJsonObject();
                processChildElements(childObject, urls);
            }
        } else {
            String obj = jsonObject.has("url") ? jsonObject.get("url").getAsString() : " ";
            if (!obj.isEmpty() && !obj.startsWith("https://vmeste.wildberries.ru") && !obj.startsWith("https://travel.wildberries.ru") && !obj.startsWith("https://digital.wildberries.ru")) {
                String shard = jsonObject.has("shard") && !jsonObject.get("shard").isJsonNull() ? jsonObject.get("shard").getAsString() : "";
                if (Objects.equals(shard, "")) {
                    return urls;
                }
                String query = jsonObject.has("query") && !jsonObject.get("query").isJsonNull() ? jsonObject.get("query").getAsString() : "";

                obj += "?sort=popular&page=1&ffeedbackpoints=1";
                obj = ensureUrlStartsWithPrefix(obj);
                urls.add(new String[]{obj, shard, query});
            }
        }
        return urls;
    }

    public static String ensureUrlStartsWithPrefix(String url) {
        String prefixDigital = "https://www.wildberries.ru";
        String prefixVmeste = "https://vmeste.wildberries.ru/";

        if (url.startsWith(prefixDigital) || url.startsWith(prefixVmeste)) {
            return url;
        }
        return prefixDigital + url;
    }
    private static List<String> repeatCheck(String articule){
        List<String> sent = new ArrayList<>();
        try {
            String jsonUrl = "https://card.wb.ru/cards/v2/detail?appType=1&curr=rub&dest=-5923914&spp=30&ab_testing=false&nm="+ articule;
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
                        int total = sizeObject.getAsJsonObject("price").has("total") ? sizeObject.getAsJsonObject("price").get("total").getAsInt() : 0;
                        total = total/100;
                        sent.add(itemName);
                        sent.add(String.valueOf(total));
                        sent.add(feedBackSum);
                        sent.add(totalQuery);
                        return sent;
                    }
                }
            }
        }catch (Exception e){
            queueError.add(articule);
        }
        return sent;
    }
    private static void test2(String UrlPage) throws InterruptedException, IOException {
        try {
            Connection connection = Jsoup.connect(UrlPage)
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
            List<String> newInem = new ArrayList<>();
            for (JsonElement productElement : productsArray) {
                JsonObject productObject = productElement.getAsJsonObject();

                String itemName = productObject.has("name") ? productObject.get("name").getAsString() : " ";
                String feedBackSum = productObject.has("feedbackPoints") ? productObject.get("feedbackPoints").getAsString() : "0";
                String articule = productObject.has("id") ? productObject.get("id").getAsString() : "0";
                String totalQuery = productObject.has("totalQuantity") ? productObject.get("totalQuantity").getAsString() : "0";
                JsonArray sizesArray = productObject.getAsJsonArray("sizes");
                for (JsonElement sizeElement : sizesArray) {
                    if (!newInem.contains(articule)) {
                        newInem.add(articule);
                        JsonObject sizeObject = sizeElement.getAsJsonObject();
                        int total = sizeObject.getAsJsonObject("price").has("total") ? sizeObject.getAsJsonObject("price").get("total").getAsInt() : 0;
                        total = total/100;
                        readTxtFile(itemName, String.valueOf(total), feedBackSum, articule, totalQuery);
                    }
                }
            }
        }catch (Exception e){
            queueError.add(UrlPage);
        }
    }


    private static Set<String> hasPoint(Set<String> articles,String fileName) throws IOException {
        ExecutorService executorService = Executors.newFixedThreadPool(100);
        if(articles.isEmpty()){
            try (BufferedReader reader = new BufferedReader(new FileReader(fileName))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    articles.add(line);
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        for (String article: articles) {
            executorService.submit(() -> {
                boolean checkArticle = false;
                try {
                    checkArticle = hasFeedbackPoints(article);
                } catch (IOException e) {
                    e.printStackTrace();
                }
                if(!checkArticle){
                    articles.remove(article);
                }
            });
        }
        executorService.shutdown();
        while (!executorService.isTerminated());
        try(BufferedWriter writer = new BufferedWriter(new FileWriter(fileName))) {
            for(String article : articles){
                writer.write(article+"\n");
                writer.flush();
            }
        }
        return articles;
    }
}