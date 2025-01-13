package org.example;

import com.pengrad.telegrambot.TelegramBot;
import com.pengrad.telegrambot.model.request.ParseMode;
import com.pengrad.telegrambot.request.SendMessage;
import com.pengrad.telegrambot.response.SendResponse;

import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

import org.openqa.selenium.Cookie;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.firefox.FirefoxDriver;
import org.openqa.selenium.firefox.FirefoxOptions;


import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class MyDualBot extends TelegramLongPollingBot {
    private static final String FILE_PATH = "sent_articles.txt";
    private static final String FILE_PATH_COMMUNITY = "sent_articles_community.txt";
    private List<String> sentArticles = new ArrayList<>();
    private List<String> sentArticlesCommunity = new ArrayList<>();
    private final TelegramBot pengradBot;
    private Thread taskThread;
    private volatile boolean running = false;
    private static Set<Cookie> seleniumCookies;

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

            if (messageText.equals("/start")) {
                startTask(chatId);
            } else if (messageText.equals("/stop")) {
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
                    mainOld(seleniumCookies);
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
//
//            if (response.isOk()) {
//                sent = true;
//            } else {
//                int retryAfter = getRetryAfter(response);
//                if (retryAfter > 0) {
//                    try {
//                        Thread.sleep(retryAfter * 1000L);
//                    } catch (InterruptedException e) {
//                        e.printStackTrace();
//                        break;
//                    }
//                } else {
//                    break;
//                }
//            }
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
    public void mainOld(Set<Cookie> seleniumCookies) throws InterruptedException, IOException {
        sentArticles = readSentArticles(FILE_PATH);
        sentArticlesCommunity = readSentArticles(FILE_PATH_COMMUNITY);

        ExecutorService executorService = Executors.newFixedThreadPool(200);
        AtomicInteger size = new AtomicInteger();
        try (BufferedWriter writerCommunity = new BufferedWriter(new FileWriter(FILE_PATH, true))) {
            List<String[]> urls = TestMessege.getURL(seleniumCookies);
            for (String[] url : urls) {
                executorService.submit(() -> {
                    String jsonPage = "https://catalog.wb.ru/catalog/" + url[1] + "/v6/filters?ab_testing=false&appType=1&" + url[2];
                    try {
                        int localizes = TestMessege.test2(seleniumCookies, url[1], url[2], jsonPage, sentArticles, sentArticlesCommunity, this, writerCommunity);
                        size.addAndGet(localizes);
                    } catch (InterruptedException | IOException e) {
                        throw new RuntimeException(e);
                    }
                });
            }
            executorService.shutdown();
            while (!executorService.isTerminated()) {
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(FILE_PATH_COMMUNITY))) {
            for (String item : sentArticlesCommunity) {
                writer.write(item + "\n");
                writer.flush();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
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

        FirefoxOptions options = new FirefoxOptions();
        options.addArguments("--headless");

        WebDriver driver = new FirefoxDriver(options);

        String urlWb = "https://www.wildberries.ru/";

        driver.get(urlWb);
        Thread.sleep(1000);

        seleniumCookies = driver.manage().getCookies();
        driver.quit();
        try {
            TelegramBotsApi botsApi = new TelegramBotsApi(DefaultBotSession.class);
            botsApi.registerBot(new MyDualBot("7564492259:AAHJFWRqVvJQuuUIVd5584h8ePoFxsg7YVc"));
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }

    public void sendMessage(String chatId, Integer messageThreadId, String messageText) throws IOException {// ByteArrayInputStream bytePhoto
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
