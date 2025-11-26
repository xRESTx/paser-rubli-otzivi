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
import java.util.List;
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
    private final TelegramDispatcher dispatcher;
    private final BotEngine engine;
    private final SentCache sentCache;
    private final Set<String> admins;
    private final Set<Long> waitingForBlacklist = ConcurrentHashMap.newKeySet();
    private final Set<String> supplierBlacklist = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile WbHttpClient httpClient;
    private volatile Map<String, String> sessionCookies;

    public MyDualBot(AppConfig config) {
        this.config = config;
        this.pengradBot = new TelegramBot(config.getBotToken());
        BlockingQueue<OutgoingMessage> paidQueue = new LinkedBlockingQueue<>(config.getTelegramQueueCapacity());
        BlockingQueue<OutgoingMessage> freeQueue = new LinkedBlockingQueue<>(config.getTelegramQueueCapacity());
        
        // Получаем cookies и заголовки один раз при старте через Selenium
        // Статические cookies будут использованы как fallback для критических cookies (_wbauid, x_wbaas_token)
        this.sessionCookies = new java.util.HashMap<>(config.getStaticCookies());
        try {
            log.info("Fetching cookies from Wildberries via Selenium...");
            WbCookieFetcher.WbSessionData sessionData = WbCookieFetcher.fetchCookiesAndHeaders(config.getStaticCookies());
            // Объединяем: сначала статические (приоритет для критических), потом из Selenium
            Map<String, String> seleniumCookies = sessionData.cookies();
            for (Map.Entry<String, String> entry : seleniumCookies.entrySet()) {
                // Перезаписываем только если cookie не критический или его нет в статических
                if (!this.sessionCookies.containsKey(entry.getKey()) || 
                    (!entry.getKey().equals("_wbauid") && !entry.getKey().equals("x_wbaas_token"))) {
                    this.sessionCookies.put(entry.getKey(), entry.getValue());
                }
            }
            this.httpClient = new WbHttpClient(
                    Duration.ofSeconds(config.getHttpTimeoutSeconds()),
                    config.getHttpMaxRetries(),
                    config.getHttpBaseBackoffMillis(),
                    config.getHttpRateLimitMillis(),
                    this.sessionCookies,
                    sessionData.userAgent(),
                    sessionData.requestHeaders()
            );
        } catch (Exception e) {
            log.warn("Falling back to default HTTP client without Selenium cookies: {}", e.getMessage());
            if (this.sessionCookies.isEmpty()) {
                this.httpClient = new WbHttpClient(
                        Duration.ofSeconds(config.getHttpTimeoutSeconds()),
                        config.getHttpMaxRetries(),
                        config.getHttpBaseBackoffMillis(),
                        config.getHttpRateLimitMillis()
                );
            } else {
                this.httpClient = new WbHttpClient(
                        Duration.ofSeconds(config.getHttpTimeoutSeconds()),
                        config.getHttpMaxRetries(),
                        config.getHttpBaseBackoffMillis(),
                        config.getHttpRateLimitMillis(),
                        this.sessionCookies,
                        null
                );
            }
        }
        
        this.sentCache = new SentCache();
        // Предзагружаем кэш данными из текстового файла sent_products.txt
        try {
            log.info("Loading sent products from file for cache warmup...");
            this.sentCache.warmup();
            log.info("Cache warmup completed");
        } catch (Exception e) {
            log.error("Failed to warmup SentCache from file, continuing without cache preload", e);
        }
        
        this.dispatcher = new TelegramDispatcher(
                pengradBot,
                paidQueue,
                freeQueue,
                httpClient,
                sessionCookies,
                new MessageFormatter(),
                this.sentCache
        );

        Set<String> food = BotEngine.loadCategoryFile("Food.txt");
        Set<String> children = BotEngine.loadCategoryFile("detyam.txt");
        
        supplierBlacklist.addAll(BotEngine.loadSupplierBlacklist("pidory.txt"));

        RubliService rubliService = new RubliService(
                new MessageFormatter(),
                this.sentCache,
                Collections.unmodifiableSet(food),
                Collections.unmodifiableSet(children)
        );
        
        CategoryTask.setClicksHeader(config.getClicksHeader());

        // Создаем callback для обновления cookies после создания engine
        java.util.concurrent.atomic.AtomicReference<BotEngine> engineRef = new java.util.concurrent.atomic.AtomicReference<>();
        Runnable cookieRefreshCallback = () -> {
            try {
                log.info("Refreshing cookies via Selenium due to HTTP 498 errors...");
                WbCookieFetcher.WbSessionData sessionData = WbCookieFetcher.fetchCookiesAndHeaders(config.getStaticCookies());
                // Объединяем: сначала статические (приоритет для критических), потом из Selenium
                Map<String, String> newCookies = new java.util.HashMap<>(config.getStaticCookies());
                Map<String, String> seleniumCookies = sessionData.cookies();
                for (Map.Entry<String, String> entry : seleniumCookies.entrySet()) {
                    // Перезаписываем только если cookie не критический или его нет в статических
                    if (!newCookies.containsKey(entry.getKey()) || 
                        (!entry.getKey().equals("_wbauid") && !entry.getKey().equals("x_wbaas_token"))) {
                        newCookies.put(entry.getKey(), entry.getValue());
                    }
                }
                log.debug("Fetched {} cookies from Selenium", newCookies.size());
                this.sessionCookies = newCookies;
                this.httpClient.updateCookiesAndHeaders(newCookies, sessionData.userAgent(), sessionData.requestHeaders());
                // Обновляем cookies в BotEngine, чтобы CategoryTask.load() использовал новые cookies
                BotEngine engine = engineRef.get();
                if (engine != null) {
                    engine.updateSessionCookies(newCookies);
                }
                // Небольшая задержка после обновления cookies, чтобы они успели примениться
                Thread.sleep(1000);
                log.info("Cookies refreshed successfully ({} cookies, user-agent: {})", 
                        newCookies.size(), sessionData.userAgent() != null ? "set" : "not set");
            } catch (Exception e) {
                log.error("Failed to refresh cookies: {}", e.getMessage(), e);
                throw new RuntimeException("Cookie refresh failed", e);
            }
        };
        
        this.engine = new BotEngine(
                config,
                httpClient,
                rubliService,
                paidQueue,
                freeQueue,
                dispatcher,
                supplierBlacklist,
                sessionCookies,
                cookieRefreshCallback
        );
        engineRef.set(this.engine);
        this.admins = Set.of("1027094894", "1039378955");
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
            // Игнорируем сообщения от не-администраторов
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
            case "/help" -> {
                String helpText = """
                        Доступные команды:
                        
                        /run - Запустить сканер
                        /stop - Остановить сканер
                        /status - Проверить статус сканера
                        /pidory - Добавить продавца в блок-лист
                        /clear - Проверить товары из БД и обновить акции
                        /help - Показать это сообщение
                        """;
                sendPengradMessage(String.valueOf(chatId), helpText);
            }
            case "/clear" -> clearDatabase(chatId);
            default -> sendPengradMessage(String.valueOf(chatId), "Команда не распознана. Используйте /help для списка команд.");
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
    
    private void clearDatabase(long chatId) {
        boolean wasRunning = running.get();
        
        if (wasRunning) {
            // Если бот запущен - останавливаем, очищаем, запускаем заново
            sendPengradMessage(String.valueOf(chatId), "Бот запущен. Останавливаю для очистки файлов...");
            engine.stop();
            running.set(false);
            
            // Ждем немного, чтобы все потоки завершились
            try {
                Thread.sleep(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        } else {
            sendPengradMessage(String.valueOf(chatId), "Начинаю проверку товаров на наличие плашки...");
        }
        
        new Thread(() -> {
            try {
                int removedCount = sentCache.clearAllFiles(httpClient);
                
                String resultMessage = String.format(
                        "Проверка завершена!\n\nПроверено товаров из кэша.\nУдалено товаров без плашки: %d\n\nФайлы НЕ удаляются - они будут очищаться через проверку каждого товара.",
                        removedCount);
                
                if (wasRunning) {
                    // Запускаем бот заново
                    sendPengradMessage(String.valueOf(chatId), resultMessage + "\n\nЗапускаю бот заново...");
                    running.set(true);
                    engine.start();
                    sendPengradMessage(String.valueOf(chatId), "Бот перезапущен.");
                } else {
                    sendPengradMessage(String.valueOf(chatId), resultMessage);
                                }
                            } catch (Exception e) {
                log.error("Failed to clear files", e);
                sendPengradMessage(String.valueOf(chatId), "Ошибка при очистке файлов: " + e.getMessage());
                if (wasRunning) {
                    // Пытаемся запустить бот даже при ошибке
                    try {
                        running.set(true);
                        engine.start();
                        sendPengradMessage(String.valueOf(chatId), "Бот перезапущен после ошибки.");
                    } catch (Exception restartError) {
                        log.error("Failed to restart bot after clear error", restartError);
                        sendPengradMessage(String.valueOf(chatId), "Не удалось перезапустить бот. Используйте /run вручную.");
                    }
                }
            }
        }, "clear-files-worker").start();
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
            MyDualBot bot = new MyDualBot(config);
            botsApi.registerBot(bot);
            log.info("Bot registered successfully. Bot is ready to receive commands.");
        } catch (TelegramApiException e) {
            if (e.getMessage() != null && e.getMessage().contains("409")) {
                log.error("Failed to start Telegram bot: Another instance is already running!", e);
                log.error("Please stop the other bot instance before starting this one.");
                System.exit(1);
            } else {
                log.error("Failed to start Telegram bot", e);
                System.exit(1);
            }
        }
    }
}


