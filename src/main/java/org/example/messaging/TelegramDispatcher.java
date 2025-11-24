package org.example.messaging;

import com.google.gson.Gson;
import com.pengrad.telegrambot.TelegramBot;
import com.pengrad.telegrambot.model.request.ParseMode;
import com.pengrad.telegrambot.request.SendMessage;
import com.pengrad.telegrambot.request.SendPhoto;
import com.pengrad.telegrambot.response.SendResponse;
import org.example.http.WbHttpClient;
import org.example.jsonmodel.DetailProduct;
import org.example.jsonmodel.DetailResponse;
import org.example.storage.SqliteStorage;
import org.example.storage.records.SentRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Dedicated dispatcher that sends messages to Telegram using a bounded queue.
 */
public final class TelegramDispatcher {

    private static final Logger log = LoggerFactory.getLogger(TelegramDispatcher.class);

    private final TelegramBot telegramBot;
    private final BlockingQueue<OutgoingMessage> queue;
    private final SqliteStorage storage;
    private final int workerCount;
    private final WbHttpClient httpClient;
    private final Map<String, String> sessionCookies;
    private final MessageFormatter messageFormatter;
    private ExecutorService executor;
    private ExecutorService delayedSenderExecutor;
    private final ConcurrentLinkedQueue<DelayedMessage> delayedQueue = new ConcurrentLinkedQueue<>();
    private Thread delayedCheckerThread;
    private volatile boolean running;

    public TelegramDispatcher(TelegramBot telegramBot,
                              BlockingQueue<OutgoingMessage> queue,
                              SqliteStorage storage,
                              int workerCount,
                              WbHttpClient httpClient,
                              Map<String, String> sessionCookies,
                              MessageFormatter messageFormatter) {
        this.telegramBot = telegramBot;
        this.queue = queue;
        this.storage = storage;
        this.workerCount = workerCount;
        this.httpClient = httpClient;
        this.sessionCookies = sessionCookies;
        this.messageFormatter = messageFormatter;
    }
    
    private static class DelayedMessage {
        final OutgoingMessage message;
        final long createdAtMillis;
        final int delaySeconds;
        
        DelayedMessage(OutgoingMessage message, int delaySeconds) {
            this.message = message;
            this.createdAtMillis = System.currentTimeMillis();
            this.delaySeconds = delaySeconds;
        }
        
        boolean isReady() {
            long elapsed = (System.currentTimeMillis() - createdAtMillis) / 1000;
            return elapsed >= delaySeconds;
        }
    }

    public void start() {
        if (running) {
            return;
        }
        running = true;
        executor = Executors.newFixedThreadPool(workerCount, r -> {
            Thread t = new Thread(r, "tg-dispatcher");
            t.setDaemon(true);
            return t;
        });
        // Отдельный executor для отправки отложенных сообщений
        delayedSenderExecutor = Executors.newFixedThreadPool(5, r -> {
            Thread t = new Thread(r, "tg-delayed-sender");
            t.setDaemon(true);
            return t;
        });
        // Поток для проверки отложенных сообщений каждые 10 секунд
        delayedCheckerThread = new Thread(this::delayedMessageChecker, "tg-delayed-checker");
        delayedCheckerThread.setDaemon(true);
        delayedCheckerThread.start();
        for (int i = 0; i < workerCount; i++) {
            executor.submit(this::runLoop);
        }
    }

    public BlockingQueue<OutgoingMessage> getQueue() {
        return queue;
    }

    public void stop() {
        running = false;
        if (delayedCheckerThread != null) {
            delayedCheckerThread.interrupt();
        }
        if (delayedSenderExecutor != null) {
            delayedSenderExecutor.shutdown();
            try {
                delayedSenderExecutor.awaitTermination(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (executor != null) {
            executor.shutdown();
            try {
                executor.awaitTermination(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void runLoop() {
        while (running || !queue.isEmpty()) {
            try {
                OutgoingMessage message = queue.poll(500, TimeUnit.MILLISECONDS);
                if (message == null) {
                    continue;
                }
                // Если есть задержка, добавляем в очередь отложенных сообщений
                if (message.getDelaySeconds() > 0) {
                    delayedQueue.offer(new DelayedMessage(message, message.getDelaySeconds()));
                } else {
                    send(message);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("Failed to dispatch telegram message", e);
            }
        }
    }
    
    private void delayedMessageChecker() {
        while (running) {
            try {
                Thread.sleep(10_000); // Проверка каждые 10 секунд
                
                // Проверяем все отложенные сообщения
                var iterator = delayedQueue.iterator();
                while (iterator.hasNext()) {
                    DelayedMessage delayed = iterator.next();
                    if (delayed.isReady()) {
                        iterator.remove();
                        // Проверяем товар перед отправкой в бесплатный чат и обновляем сообщение
                        OutgoingMessage messageToSend = delayed.message;
                        if (delayed.message.getChannelType() == org.example.service.ChannelType.FREE) {
                            OutgoingMessage verifiedMessage = verifyAndUpdateMessage(delayed.message);
                            if (verifiedMessage == null) {
                                continue;
                            }
                            messageToSend = verifiedMessage;
                        }
                        // Отправляем в отдельном потоке, чтобы не блокировать поток проверки
                        final OutgoingMessage finalMessage = messageToSend;
                        delayedSenderExecutor.submit(() -> {
                            try {
                                sendImmediate(finalMessage);
                            } catch (Exception e) {
                                log.error("Failed to send delayed message", e);
                            }
                        });
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("Error in delayed message checker", e);
            }
        }
    }
    
    /**
     * Проверяет товар перед отправкой в бесплатный чат и обновляет сообщение с актуальными данными.
     * @param message исходное сообщение
     * @return обновленное сообщение с актуальными данными или null если товар недоступен
     */
    private OutgoingMessage verifyAndUpdateMessage(OutgoingMessage message) {
        try {
            long nmId = Long.parseLong(message.getArticle());
            String url = String.format(
                    "https://www.wildberries.ru/__internal/u-card/cards/v4/detail?appType=1&curr=rub&dest=-1255987&spp=30&hide_dtype=11&ab_testid=popular_sort&lang=ru&nm=%d",
                    nmId
            );
            Map<String, String> headers = new java.util.HashMap<>();
            if (sessionCookies != null && !sessionCookies.isEmpty()) {
                StringBuilder cookieHeader = new StringBuilder();
                for (Map.Entry<String, String> entry : sessionCookies.entrySet()) {
                    if (cookieHeader.length() > 0) {
                        cookieHeader.append("; ");
                    }
                    cookieHeader.append(entry.getKey()).append("=").append(entry.getValue());
                }
                headers.put("Cookie", cookieHeader.toString());
            }
            
            HttpResponse<String> response = httpClient.get(url, headers);
            if (response.statusCode() != 200) {
                return null;
            }
            
            Gson gson = new Gson();
            DetailResponse detailResponse = gson.fromJson(response.body(), DetailResponse.class);
            if (detailResponse == null || detailResponse.products == null || detailResponse.products.isEmpty()) {
                return null;
            }
            
            DetailProduct product = detailResponse.products.get(0);
            if (product.sizes == null || product.sizes.isEmpty() || product.sizes.get(0).price == null) {
                return null;
            }
            
            DetailProduct.DetailPrice price = product.sizes.get(0).price;
            long currentPrice = price.product != null ? price.product / 100 : 0;
            long currentFeedback = product.feedbackPoints != null ? Long.parseLong(product.feedbackPoints) : 0;
            
            // Проверяем наличие акции (кешбэка)
            if (currentPrice <= 0 || currentFeedback <= 0) {
                return null;
            }
            
            // Проверяем наличие товара на складе (если есть информация о stock)
            // В DetailProduct нет информации о stock, но мы можем проверить наличие sizes
            if (product.sizes.isEmpty()) {
                return null;
            }
            
            // Формируем новое сообщение с актуальными данными
            double currentPercent = (double) currentFeedback / currentPrice;
            String productName = product.name != null ? product.name : "Unknown";
            
            // Подсчитываем общее количество товара (суммируем все размеры)
            // В DetailProduct нет явного поля stock, но можно попробовать получить из sizes
            // Пока используем "0" или можно попробовать посчитать количество размеров
            String stockInfo = String.valueOf(product.sizes.size()); // Временное решение
            
            // Формируем новый payload с актуальными данными
            String basePayload = messageFormatter.format(
                    message.getArticle(),
                    productName,
                    currentPrice,
                    currentFeedback,
                    currentPercent,
                    stockInfo
            );
            
            // Добавляем ссылку на группу для бесплатного чата
            String updatedPayload = basePayload + "\n\n<a href=\"https://t.me/WB_Jackpot/3793\">💰Товар найден группой WB_Jackpot. Присоединяйтесь!💰</a>";
            
            // Скачиваем фото товара
            byte[] imageBytes = downloadProductImage(message.getArticle());
            
            // Возвращаем обновленное сообщение с фото
            OutgoingMessage updatedMessage = message.withUpdatedPayload(updatedPayload);
            if (imageBytes != null && imageBytes.length > 0) {
                updatedMessage = updatedMessage.withImageBytes(imageBytes);
            }
            return updatedMessage;
        } catch (Exception e) {
            log.warn("Failed to verify product {} before sending: {}", message.getArticle(), e.getMessage());
            return null;
        }
    }
    
    /**
     * Скачивает фото товара с серверов Wildberries.
     * Пробует разные серверы (basket-01 до basket-31) пока не найдет доступное фото.
     * @param article артикул товара
     * @return байты изображения или null если фото не найдено
     */
    private byte[] downloadProductImage(String article) {
        if (article == null || article.length() < 6) {
            return null;
        }
        
        try {
            String vol = article.substring(0, article.length() - 5);
            String part = article.substring(0, article.length() - 3);
            
            // Пробуем серверы от basket-01 до basket-31
            for (int i = 1; i <= 31; i++) {
                String serverNum = i < 10 ? "0" + i : String.valueOf(i);
                String imageUrl = String.format(
                        "https://basket-%s.wbbasket.ru/vol%s/part%s/%s/images/c516x688/1.webp",
                        serverNum, vol, part, article
                );
                
                try {
                    byte[] imageBytes = downloadImageToBuffer(imageUrl);
                    if (imageBytes != null && imageBytes.length > 0) {
                        return imageBytes;
                    }
                } catch (IOException e) {
                    // Пробуем следующий сервер
                }
            }
            
            return null;
        } catch (Exception e) {
            log.warn("Failed to download image for article {}: {}", article, e.getMessage());
            return null;
        }
    }
    
    /**
     * Скачивает изображение по URL в байтовый массив.
     */
    private static byte[] downloadImageToBuffer(String imageUrl) throws IOException {
        java.net.URI uri = java.net.URI.create(imageUrl);
        URL url = uri.toURL();
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(5000);
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
        
        int statusCode = connection.getResponseCode();
        if (statusCode != 200) {
            connection.disconnect();
            throw new IOException("HTTP " + statusCode);
        }
        
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

    private void send(OutgoingMessage message) throws InterruptedException {
        sendImmediate(message);
    }
    
    private void sendImmediate(OutgoingMessage message) throws InterruptedException {
        boolean sent = false;
        while (!sent) {
            SendResponse response;
            
            // Фото отправляется только для FREE канала (фото скачивается только для FREE)
            // Если есть фото и это FREE канал, отправляем фото, иначе текст
            if (message.getImageBytes() != null && message.getImageBytes().length > 0 
                    && message.getChannelType() == org.example.service.ChannelType.FREE) {
                SendPhoto sendPhoto = new SendPhoto(message.getChatId(), message.getImageBytes());
                sendPhoto.fileName("photo.jpg");
                sendPhoto.caption(message.getPayload());
                sendPhoto.parseMode(ParseMode.HTML);
                if (message.getThreadId() != null) {
                    sendPhoto.messageThreadId(message.getThreadId());
                }
                response = telegramBot.execute(sendPhoto);
            } else {
                SendMessage main = new SendMessage(message.getChatId(), message.getPayload())
                        .parseMode(ParseMode.HTML);
                if (message.getThreadId() != null) {
                    main.messageThreadId(message.getThreadId());
                }
                response = telegramBot.execute(main);
            }
            
            if (response.isOk()) {
                sent = true;
                if (message.getSnapshot() != null) {
                    storage.enqueueSnapshot(message.getSnapshot());
                }
                SentRecord sentRecord = new SentRecord(
                        message.getArticle(),
                        message.getChannelType(),
                        message.getChatId(),
                        Instant.now().toEpochMilli(),
                        message.getPercent()
                );
                storage.enqueueSentRecord(sentRecord);
                if (message.getSecondaryChatId() != null) {
                    // Фото отправляется только для FREE канала
                    if (message.getImageBytes() != null && message.getImageBytes().length > 0 
                            && message.getChannelType() == org.example.service.ChannelType.FREE) {
                        SendPhoto secondPhoto = new SendPhoto(message.getSecondaryChatId(), message.getImageBytes());
                        secondPhoto.fileName("photo.jpg");
                        secondPhoto.caption(message.getPayload());
                        secondPhoto.parseMode(ParseMode.HTML);
                        telegramBot.execute(secondPhoto);
                    } else {
                        SendMessage second = new SendMessage(message.getSecondaryChatId(), message.getPayload())
                                .parseMode(ParseMode.HTML);
                        telegramBot.execute(second);
                    }
                }
            } else {
                int retryAfter = parseRetryAfter(response);
                if (retryAfter <= 0) {
                    log.warn("Telegram send failed without retryAfter: {}", response.description());
                    return;
                }
                TimeUnit.SECONDS.sleep(retryAfter);
            }
        }
    }

    private int parseRetryAfter(SendResponse response) {
        String description = response.description();
        if (description == null) {
            return 0;
        }
        String needle = "retry after";
        int idx = description.indexOf(needle);
        if (idx < 0) {
            return 0;
        }
        try {
            String tail = description.substring(idx + needle.length()).trim();
            return Integer.parseInt(tail);
        } catch (NumberFormatException ex) {
            return 0;
        }
    }
}


