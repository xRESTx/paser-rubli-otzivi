package org.example;

import com.pengrad.telegrambot.TelegramBot;
import com.pengrad.telegrambot.model.request.ParseMode;
import com.pengrad.telegrambot.request.SendMessage;
import com.pengrad.telegrambot.response.SendResponse;
import org.openqa.selenium.Cookie;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.firefox.FirefoxDriver;
import org.openqa.selenium.firefox.FirefoxOptions;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

import java.io.*;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class MyDualBot extends TelegramLongPollingBot {
    private static final String FILE_PATH = "error/sent_articles.txt";
    private static final String FILE_PATH_COMMUNITY = "error/sent_articles_community.txt";
    private static final String ALL_ITEM = "error/all_items.txt";
    private List<String> sentArticles = new ArrayList<>();
    private List<String> sentArticlesCommunity = new ArrayList<>();
    private final int[] salfetka6 = new int[6];
    private final TelegramBot pengradBot;
    private Thread taskThread;
    private volatile boolean running = false;

    public MyDualBot(String pengradBotToken) {
        this.pengradBot = new TelegramBot(pengradBotToken);
    }

    @Override
    public String getBotUsername() {
        return "shovel_seller_bot";
    }

    @Override
    public String getBotToken() {
        return System.getenv("botToken");
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
            } else {
                sendPengradMessage(String.valueOf(chatId), 0, "Unknown command.");
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
                FirefoxOptions options = new FirefoxOptions();
                options.addArguments("--headless");

                WebDriver driver = new FirefoxDriver(options);

                String urlWb = "https://www.wildberries.ru/";

                driver.get(urlWb);
                Thread.sleep(1000);

                Set<Cookie> seleniumCookies = driver.manage().getCookies();
                driver.quit();

                while (running) {
                    mainOld(new String[]{}, seleniumCookies);
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
                System.out.println("Message sent successfully");
                sent = true;
            } else {
                System.out.println("Failed to send message: " + response.errorCode() + " - " + response.description());
                int retryAfter = getRetryAfter(response);
                if (retryAfter > 0) {
                    System.out.println("Too Many Requests: retry after " + retryAfter + " seconds");
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
    public void mainOld(String[] args, Set<Cookie> seleniumCookies) throws InterruptedException, IOException {
        sentArticles = readSentArticles(FILE_PATH);
        sentArticlesCommunity = readSentArticles(FILE_PATH_COMMUNITY);
        Arrays.fill(salfetka6, 0);

        ExecutorService executorService = Executors.newFixedThreadPool(25);
        AtomicInteger size = new AtomicInteger();
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(ALL_ITEM));
             BufferedWriter writerCommunity = new BufferedWriter(new FileWriter(FILE_PATH, true))) {
            List<String[]> urls = TestMessege.getURL(seleniumCookies);
            for (String[] url : urls) {
                executorService.submit(() -> {
                    String jsonPage = "https://catalog.wb.ru/catalog/" + url[1] + "/v6/filters?ab_testing=false&appType=1&" + url[2] + "&curr=rub&dest=-5551776&ffeedbackpoints=1&spp=30";
                    try {
//                        writer.write(url[0] + "\n");
                        int localizes = TestMessege.test2(url[0], seleniumCookies, url[1], url[2], jsonPage, writer, sentArticles, sentArticlesCommunity, salfetka6, this, writerCommunity);
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
        System.out.println(size.get());

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(FILE_PATH_COMMUNITY))) {
            for (String item : sentArticlesCommunity) {
                writer.write(item + "\n");
                writer.flush();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

        System.out.println("All tasks completed, My Lord");
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

    public static void main(String[] args) {
        try {
            TelegramBotsApi botsApi = new TelegramBotsApi(DefaultBotSession.class);
            botsApi.registerBot(new MyDualBot(System.getenv("botToken")));
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }

    public void sendMessage(String chatId, Integer messageThreadId, String messageText) {
        boolean sent = false;
        while (!sent) {
            SendMessage request = new SendMessage(chatId, messageText);
            if (messageThreadId != 0) {
                request = request.replyToMessageId(messageThreadId);
            }
            SendResponse response = pengradBot.execute(request);

            if (response.isOk()) {
                System.out.println("Message sent successfully");
                sent = true;
            } else {
                System.out.println("Failed to send message: " + response.errorCode() + " - " + response.description());
                int retryAfter = getRetryAfter(response);
                if (retryAfter > 0) {
                    System.out.println("Too Many Requests: retry after " + retryAfter + " seconds");
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
