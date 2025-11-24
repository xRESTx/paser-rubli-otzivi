package org.example;

import com.pengrad.telegrambot.TelegramBot;
import com.pengrad.telegrambot.request.SendMessage;
import org.example.config.AppConfig;
import org.example.core.BotEngine;
import org.example.http.WbCookieFetcher;
import org.example.http.WbHttpClient;
import org.example.messaging.MessageFormatter;
import org.example.messaging.OutgoingMessage;
import org.example.messaging.TelegramDispatcher;
import org.example.service.RubliService;
import org.example.service.SentCache;
import org.example.storage.SqliteStorage;
import org.example.telegram.CategoryTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

import java.time.Duration;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

public class MyDualBot extends TelegramLongPollingBot {

    private static final Logger log = LoggerFactory.getLogger(MyDualBot.class);

    private final AppConfig config;
    private final TelegramBot pengradBot;
    private final SqliteStorage storage;
    private final TelegramDispatcher dispatcher;
    private final BotEngine engine;
    private final Set<String> admins;
    private final Set<Long> waitingForBlacklist = ConcurrentHashMap.newKeySet();
    private final Set<String> supplierBlacklist = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean running = new AtomicBoolean(false);

    public MyDualBot(AppConfig config) {
        this.config = config;
        this.pengradBot = new TelegramBot(config.getBotToken());
        this.storage = new SqliteStorage(
                config.getSqlitePath(),
                config.getStorageQueueCapacity(),
                config.getStorageBatchSize());
        BlockingQueue<OutgoingMessage> messageQueue = new LinkedBlockingQueue<>(5_000);
        this.dispatcher = new TelegramDispatcher(pengradBot, messageQueue, storage, config.getTelegramThreads());

        Set<String> food = BotEngine.loadCategoryFile("Food.txt");
        Set<String> children = BotEngine.loadCategoryFile("detyam.txt");
        supplierBlacklist.addAll(BotEngine.loadSupplierBlacklist("pidory.txt"));

        RubliService rubliService = new RubliService(
                new MessageFormatter(),
                new SentCache(),
                Collections.unmodifiableSet(food),
                Collections.unmodifiableSet(children)
        );
        
        CategoryTask.setClicksHeader(config.getClicksHeader());

        // Получаем cookies и заголовки один раз при старте через Selenium
        WbHttpClient httpClient;
        Map<String, String> sessionCookies = new java.util.HashMap<>(config.getStaticCookies());
        try {
            log.info("Fetching cookies from Wildberries via Selenium...");
            WbCookieFetcher.WbSessionData sessionData = WbCookieFetcher.fetchCookiesAndHeaders();
            sessionCookies.putAll(sessionData.cookies());
            httpClient = new WbHttpClient(
                    Duration.ofSeconds(config.getHttpTimeoutSeconds()),
                    config.getHttpMaxRetries(),
                    config.getHttpBaseBackoffMillis(),
                    config.getHttpRateLimitMillis(),
                    sessionCookies,
                    sessionData.userAgent()
            );
        } catch (Exception e) {
            log.warn("Falling back to default HTTP client without Selenium cookies: {}", e.getMessage());
            if (sessionCookies.isEmpty()) {
                httpClient = new WbHttpClient(
                        Duration.ofSeconds(config.getHttpTimeoutSeconds()),
                        config.getHttpMaxRetries(),
                        config.getHttpBaseBackoffMillis(),
                        config.getHttpRateLimitMillis()
                );
            } else {
                httpClient = new WbHttpClient(
                        Duration.ofSeconds(config.getHttpTimeoutSeconds()),
                        config.getHttpMaxRetries(),
                        config.getHttpBaseBackoffMillis(),
                        config.getHttpRateLimitMillis(),
                        sessionCookies,
                        null
                );
            }
        }
        this.engine = new BotEngine(
                config,
                httpClient,
                rubliService,
                storage,
                messageQueue,
                dispatcher,
                supplierBlacklist,
                sessionCookies
        );
        this.admins = Set.of("1027094894", "1039378955", "5392268853");
    }

    @Override
    public String getBotUsername() {
        return config.getBotUsername();
    }

    @Override
    public String getBotToken() {
        return config.getBotToken();
    }

    @Override
    public void onUpdateReceived(Update update) {
        if (!update.hasMessage() || update.getMessage().getText() == null) {
                        return;
                    }
        long chatId = update.getMessage().getChatId();
        String text = update.getMessage().getText().trim();

        if (waitingForBlacklist.contains(chatId)) {
            handleBlacklistEntry(chatId, text);
            return;
        }

        if (!admins.contains(String.valueOf(chatId))) {
            return;
        }

        switch (text) {
            case "/run" -> startEngine(chatId);
            case "/stop" -> stopEngine(chatId);
            case "/status" -> sendPengradMessage(String.valueOf(chatId),
                    running.get() ? "Сканер активен" : "Сканер остановлен");
            case "/pidory" -> {
                waitingForBlacklist.add(chatId);
                sendPengradMessage(String.valueOf(chatId), "Пришли имя продавца или ссылку https://www.wildberries.ru/seller/ID");
            }
            default -> sendPengradMessage(String.valueOf(chatId), "Команда не распознана.");
        }
    }

    private void handleBlacklistEntry(long chatId, String raw) {
        waitingForBlacklist.remove(chatId);
        String normalized = normalizeBlockedSupplierInput(raw);
        if (normalized == null) {
            sendPengradMessage(String.valueOf(chatId), "Не удалось распознать продавца.");
            return;
        }
        String key = normalized.toLowerCase();
        if (supplierBlacklist.contains(key)) {
            sendPengradMessage(String.valueOf(chatId), "Продавец уже в блок-листе.");
                return;
            }
        supplierBlacklist.add(key);
        persistSupplier(normalized);
        sendPengradMessage(String.valueOf(chatId), "Добавлен в блок-лист: " + normalized);
        }
        
    private void startEngine(long chatId) {
        if (!running.compareAndSet(false, true)) {
            sendPengradMessage(String.valueOf(chatId), "Уже запущено.");
            return;
        }
        engine.start();
        sendPengradMessage(String.valueOf(chatId), "Старт прошёл успешно.");
    }

    private void stopEngine(long chatId) {
        if (!running.compareAndSet(true, false)) {
            sendPengradMessage(String.valueOf(chatId), "И так остановлено.");
                        return;
                    }
        engine.stop();
        sendPengradMessage(String.valueOf(chatId), "Всё остановлено.");
    }

    private void sendPengradMessage(String chatId, String messageText) {
        pengradBot.execute(new SendMessage(chatId, messageText));
    }

    private static String normalizeBlockedSupplierInput(String rawInput) {
        if (rawInput == null) {
            return null;
        }
        String trimmed = rawInput.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.matches("\\d+")) {
            return trimmed;
        }
        int idx = trimmed.indexOf("/seller/");
        if (idx >= 0) {
            String digits = trimmed.substring(idx + 8).replaceAll("[^0-9]", "");
            return digits.isEmpty() ? null : digits;
        }
        return trimmed.toLowerCase();
    }

    private void persistSupplier(String value) {
        try {
            java.nio.file.Path path = java.nio.file.Path.of("pidory.txt");
            if (!java.nio.file.Files.exists(path)) {
                java.nio.file.Files.createFile(path);
            }
            java.nio.file.Files.writeString(path, value + System.lineSeparator(), java.nio.file.StandardOpenOption.APPEND);
        } catch (Exception e) {
            log.warn("Failed to persist supplier {}", value, e);
        }
    }

    public static void main(String[] args) {
        AppConfig config = AppConfig.load();
        try {
            TelegramBotsApi botsApi = new TelegramBotsApi(DefaultBotSession.class);
            botsApi.registerBot(new MyDualBot(config));
        } catch (TelegramApiException e) {
            log.error("Failed to start Telegram bot", e);
        }
    }
}


