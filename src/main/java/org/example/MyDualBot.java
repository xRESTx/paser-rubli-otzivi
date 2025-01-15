package org.example;

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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MyDualBot extends TelegramLongPollingBot {
    private static final String FILE_PATH = "sent_articles.txt";
    private static final String FILE_PATH_COMMUNITY = "sent_articles_community.txt";
    private List<String> sentArticles = new ArrayList<>();
    private List<String> sentArticlesCommunity = new ArrayList<>();
    private final TelegramBot pengradBot;
    private Thread taskThread;
    private volatile boolean running = false;
    private static Set<HttpCookie> Cookies;
    
    public MyDualBot(String pengradBotToken) {
        this.pengradBot = new TelegramBot(pengradBotToken);
    }

    @Override
    public String getBotUsername() {
        return "shovel_seller_bot";
    }

    @Override
    public String getBotToken() {
        return "botToken";
//        return System.getenv("botToken");
    }

    @Override
    public void onUpdateReceived(Update update) {
        if (update.hasMessage() && update.getMessage().getText() != null) {
            String messageText = update.getMessage().getText();
            long chatId = update.getMessage().getChatId();

            if (messageText.equals("/q")) {
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
        sentArticles = readSentArticles(FILE_PATH);
        sentArticlesCommunity = readSentArticles(FILE_PATH_COMMUNITY);

        ExecutorService executorService = Executors.newFixedThreadPool(100);
        ExecutorService executorService1 = Executors.newFixedThreadPool(400);
        List<String[]> urls = TestMessege.getURL(Cookies);
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


        try (BufferedWriter writerCommunity = new BufferedWriter(new FileWriter(FILE_PATH, true))) {
            for (String url : urlsPage) {
                executorService1.submit(() -> {
                    try {
                         TestMessege.test2(Cookies,url, sentArticles,sentArticlesCommunity,this,writerCommunity);
                    } catch (IOException | InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                });
            }
            executorService1.shutdown();
            while (!executorService1.isTerminated()) {
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }


        System.out.println(urlsPage.size());
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(FILE_PATH_COMMUNITY))) {
            for (String item : sentArticlesCommunity) {
                writer.write(item + "\n");
                writer.flush();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
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
            botsApi.registerBot(new MyDualBot("botToken"));
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
}