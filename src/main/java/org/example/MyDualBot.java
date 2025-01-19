package org.example;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.HttpCookie;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.text.DecimalFormat;
import java.util.*;
import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.concurrent.*;

public class MyDualBot extends TelegramLongPollingBot {
    private static final String FILE_PATH = "sent_articles";
    private static final String FILE_PATH_COMMUNITY = "sent_articles_community.txt";

    private Thread consumer100;
    private Thread consumer90;
    private Thread consumer80;
    private Thread consumerBig;
    private Thread consumerMyChat;

    private static List<String> sentArticles100 = new ArrayList<>();
    private static List<String> sentArticles90 = new ArrayList<>();
    private static List<String> sentArticles80 = new ArrayList<>();
    private static List<String> sentArticlesBig = new ArrayList<>();

    private static List<String> sentArticlesCommunity = new ArrayList<>();
    private static final BlockingQueue<String> queue100 = new LinkedBlockingQueue<>();
    private static final BlockingQueue<String> queue90 = new LinkedBlockingQueue<>();
    private static final BlockingQueue<String> queue80 = new LinkedBlockingQueue<>();
    private static final BlockingQueue<String> queueBig = new LinkedBlockingQueue<>();
    private static final BlockingQueue<String> queueMyChat = new LinkedBlockingQueue<>();


    private final TelegramBot pengradBot;
    private Thread taskThread;
    private volatile boolean running = false;
    private static Set<HttpCookie> Cookies;
    public static List<String> pidory = new ArrayList<>(Arrays.asList("elena novvv вечерние и свадебные украшения","FOVERE AROMA","elena novvv колье","Славянский Дворъ"));

    public MyDualBot(String pengradBotToken) {
        this.pengradBot = new TelegramBot(pengradBotToken);
    }

    @Override
    public String getBotUsername() {
        return "shovel_seller_bot";
    }

    @Override
    public String getBotToken() {
        return "BT";
//        return System.getenv("botToken");
    }

    @Override
    public void onUpdateReceived(Update update) {
        if (update.hasMessage() && update.getMessage().getText() != null) {
            String messageText = update.getMessage().getText();
            long chatId = update.getMessage().getChatId();

            if (messageText.equals("/start")) {
                startTask(chatId);
            } else if (messageText.equals("/stap")) {
                stopTask(chatId);
            }
        }
    }

    private void startTask(long chatId) {
        if (running) {
            sendPengradMessage(String.valueOf(chatId), 0, "Task is already running.");
            return;
        }
        sentArticles100 = readSentArticles(FILE_PATH + "100.txt");
        sentArticles90 = readSentArticles(FILE_PATH + "90.txt");
        sentArticles80 = readSentArticles(FILE_PATH + "80.txt");
        sentArticlesBig = readSentArticles(FILE_PATH + "Big.txt");
        sentArticlesCommunity = readSentArticles(FILE_PATH_COMMUNITY);


        consumer100 = new Thread(() -> {
            try {
                sentMessege100();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        });
        consumer100.setDaemon(true);
        consumer100.start();

        consumer90 = new Thread(() -> {
            try {
                sentMessege90();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        });
        consumer90.setDaemon(true);
        consumer90.start();

        consumer80 = new Thread(() -> {
            try {
                sentMessege80();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        });
        consumer80.setDaemon(true);
        consumer80.start();

        consumerBig = new Thread(() -> {
            try {
                sentMessegeBig();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        });
        consumerBig.setDaemon(true);
        consumerBig.start();

        consumerMyChat = new Thread(() -> {
            try {
                sentMessegeMyChat();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        });
        consumerMyChat.setDaemon(true);
        consumerMyChat.start();

        running = true;
        taskThread = new Thread(() -> {
            try {
                while (running) {
                    mainOld(Cookies);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
        taskThread.start();
        sendPengradMessage(String.valueOf(chatId), 0, "Task started.");
    }

    private void stopTask(long chatId) {
        if (!running) {
            sendPengradMessage(String.valueOf(chatId), 0, "Task is not running.");
            return;
        }

        running = false;
        try {
            consumer100.join();
            consumer90.join();
            consumer80.join();
            consumerBig.join();
            consumerMyChat.join();
            taskThread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        sendPengradMessage(String.valueOf(chatId), 0, "Task stopped.");
    }

    private void sendPengradMessage(String chatId, Integer messageThreadId, String messageText) {
        boolean sent = false;
        while (!sent) {
            SendMessage request = new SendMessage(chatId, messageText).parseMode(ParseMode.Markdown);
            if (messageThreadId != 0) {
                request = request.replyToMessageId(messageThreadId);
            }
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
    public void mainOld(Set<HttpCookie> Cookies) throws InterruptedException, IOException {
        ExecutorService executorService = Executors.newFixedThreadPool(100);
        ExecutorService executorService1 = Executors.newFixedThreadPool(400);
        List<String[]> urls = getURL(Cookies);
        long startTime = System.currentTimeMillis();
        List<String> urlsPage = new ArrayList<>();
        for (String[] url : urls) {
            executorService.submit(() -> {
                try {
                    Connection connectionPage = Jsoup.connect("https://catalog.wb.ru/catalog/" + url[1] + "/v6/filters?ab_testing=false&appType=1&" + url[2] + "&curr=rub&dest=-5551776&ffeedbackpoints=1&spp=30")
                            .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:132.0) Gecko/20100101 Firefox/132.0")
                            .method(Connection.Method.GET)
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
                    throw new RuntimeException(e);
                }
            });
        }
        executorService.shutdown();
        while (!executorService.isTerminated()) {
        }
        for (String url : urlsPage) {
            executorService1.submit(() -> {
                try {
                     test2(Cookies,url);
                } catch (IOException | InterruptedException e) {
                    throw new RuntimeException(e);
                }
            });
        }
        executorService1.shutdown();
        while (!executorService1.isTerminated()) {
        }
        System.out.println(urlsPage.size());
        long endTime = System.currentTimeMillis();
        long timeElapsed = endTime - startTime;
        System.out.println(timeElapsed);
    }

    private static List<String> readSentArticles(String FILE_PATH) {
        List<String> sentArticles = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(FILE_PATH))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sentArticles.add(line);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return sentArticles;
    }

    public static void main(String[] args) throws InterruptedException {

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
            if (response.statusCode() == 200) {
                System.out.println(response.statusCode());
            } else {
                System.out.println(response.statusCode());
            }

            // Извлечение cookies
            Cookies = new HashSet<>(cookieManager.getCookieStore().getCookies());
            Cookies.forEach(cookie -> System.out.println(cookie));

        } catch (Exception e) {
            e.printStackTrace();
        }

        // Регистрация бота Telegram
        try {
            TelegramBotsApi botsApi = new TelegramBotsApi(DefaultBotSession.class);
            botsApi.registerBot(new MyDualBot("BT"));
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

    public static void readTxtFile(String itemName, String itemCost, String itemfFeedBackCost, String article, Set<HttpCookie> Cookies, String totalQuery) throws IOException, InterruptedException {
        if (!sentArticles100.contains(article) && !sentArticles90.contains(article) && !sentArticles80.contains(article) && !sentArticlesBig.contains(article)) {
            String messege;

            double percent = Double.parseDouble(itemfFeedBackCost) / Integer.parseInt(itemCost);
            if (((percent > 0.49 && Integer.parseInt(itemfFeedBackCost) >= 1000 && Integer.parseInt(itemfFeedBackCost) < 2500)
                    || (percent > 0.59 && Integer.parseInt(itemfFeedBackCost) >= 699 && Integer.parseInt(itemfFeedBackCost) < 1000 && percent < 0.9)
                    || (percent >= 0.4 && Integer.parseInt(itemfFeedBackCost) >= 2500))) {
                boolean bol = hasFeedbackPoints(article,Cookies);
                if (!bol) {
                    return;
                }
                messege = createMessege(itemName, itemCost, itemfFeedBackCost, article,percent,totalQuery);
                queueBig.add(messege);
            }
            if (percent >= 1) {
                boolean bol = hasFeedbackPoints(article,Cookies);
                if (!bol) {
                    return;
                }
                messege = createMessege(itemName, itemCost, itemfFeedBackCost, article,percent,totalQuery);
                queue100.add(messege);
            } else if (percent >= 0.9 && percent < 1) {
                boolean bol = hasFeedbackPoints(article,Cookies);
                if (!bol) {
                    return;
                }
                messege = createMessege(itemName, itemCost, itemfFeedBackCost, article,percent,totalQuery);
                queue90.add(messege);
            } else if (percent >= 0.8 && percent < 0.9) {
                boolean bol = hasFeedbackPoints(article,Cookies);
                if (!bol) {
                    return;
                }
                messege = createMessege(itemName, itemCost, itemfFeedBackCost, article,percent,totalQuery);
                queue80.add(messege);
            }
        }
        if (!sentArticlesCommunity.contains(article)) {
            double percent = Double.parseDouble(itemfFeedBackCost) / Integer.parseInt(itemCost);
            String messege;

            if (percent >= 1.5 || (Double.parseDouble(itemfFeedBackCost) - Double.parseDouble(itemCost) >= 199 && percent > 1)) {
                boolean bol = hasFeedbackPoints(article, Cookies);
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
        MyDualBot tgBot = new MyDualBot("BT");
        try(BufferedWriter writer = new BufferedWriter(new FileWriter(FILE_PATH + "100.txt",true))){
            while (true) {
                String data = queue100.take(); // Извлечение данных из очереди
                String[] parts = data.split(":", 2);
                String article = parts[0];
                String productInfo = parts[1];
                if (!sentArticles100.contains(article)) { // Проверка уникальности
                    sentArticles100.add(article);
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
        MyDualBot tgBot = new MyDualBot("BT");
        try(BufferedWriter writer = new BufferedWriter(new FileWriter(FILE_PATH + "90.txt",true ))){
            while (true) {
                String data = queue90.take(); // Извлечение данных из очереди
                String[] parts = data.split(":", 2);
                String article = parts[0];
                String productInfo = parts[1];
                if (!sentArticles90.contains(article)) { // Проверка уникальности
                    sentArticles90.add(article);
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
        MyDualBot tgBot = new MyDualBot("BT");
        try(BufferedWriter writer = new BufferedWriter(new FileWriter(FILE_PATH + "80.txt",true ))){
            while (true) {
                String data = queue80.take(); // Извлечение данных из очереди
                String[] parts = data.split(":", 2);
                String article = parts[0];
                String productInfo = parts[1];
                if (!sentArticles80.contains(article)) { // Проверка уникальности
                    sentArticles80.add(article);
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
        MyDualBot tgBot = new MyDualBot("BT");
        try(BufferedWriter writer = new BufferedWriter(new FileWriter(FILE_PATH + "Big.txt",true ))){
            while (true) {

                String data = queueBig.take(); // Извлечение данных из очереди
                String[] parts = data.split(":", 2);
                String article = parts[0];
                String productInfo = parts[1];
                if (!sentArticlesBig.contains(article)) { // Проверка уникальности
                    sentArticlesBig.add(article);
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
        MyDualBot tgBot = new MyDualBot("BT");
        try(BufferedWriter writer = new BufferedWriter(new FileWriter(FILE_PATH_COMMUNITY, true))){
            while (true) {

                String data = queueMyChat.take(); // Извлечение данных из очереди
                String[] parts = data.split(":", 2);
                String article = parts[0];
                String productInfo = parts[1];
                if (!sentArticlesCommunity.contains(article)) { // Проверка уникальности
                    sentArticlesCommunity.add(article);
                    tgBot.sendMessage(chatId, 8, productInfo);
                    writer.write(article + "\n");
                    writer.flush();
                }
            }
        }catch (IOException e) {
            e.printStackTrace();
        }
    }


    public static boolean hasFeedbackPoints(String url1, Set<HttpCookie> Cookies) throws IOException, InterruptedException {
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
        String formattedString = article+ ":" + itemName + "\n\uD83D\uDCB8Price " + itemCost + "\u20BD\n" +
                "\uD83C\uDFB0Cashback " + itemfFeedBackCost + "\u20BD\n" +
                "\uD83D\uDCAFPercent " + df.format(percent * 100) + "%\n" +
                "\uD83C\uDFB2Quantity " + totalQuery + "\n"+ href;
        return formattedString;
    }
    public static List<String[]> getURL(Set<HttpCookie> Cookies) throws IOException {
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
            urls = processChildElements(productObject, urls);
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
        return prefixDigital + url + "";
    }

    public static void test2(Set<HttpCookie> Cookies, String UrlPage) throws InterruptedException, IOException {
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
                    readTxtFile(itemName, String.valueOf(total), feedBackSum, articule, Cookies, totalQuery);
                }
            }
        }
    }
}