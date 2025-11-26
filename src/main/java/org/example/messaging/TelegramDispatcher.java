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
import org.example.service.SentCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Dedicated dispatcher that sends messages to Telegram using a bounded queue.
 */
public final class TelegramDispatcher {

    private static final Logger log = LoggerFactory.getLogger(TelegramDispatcher.class);

    private final TelegramBot telegramBot;
    private final BlockingQueue<OutgoingMessage> paidQueue;
    private final BlockingQueue<OutgoingMessage> freeQueue;
    private final WbHttpClient httpClient;
    private final Map<String, String> sessionCookies;
    private final MessageFormatter messageFormatter;
    private final SentCache sentCache;
    private Thread paidWorker;
    private Thread freeWorker;
    private ScheduledExecutorService scheduler;
    private final Map<String, ProductInfo> mapOnSent = new ConcurrentHashMap<>();
    private volatile boolean running;
    
    private static class ProductInfo {
        private final OutgoingMessage message;
        private final long time;
        
        ProductInfo(OutgoingMessage message, long time) {
            this.message = message;
            this.time = time;
        }
        
        OutgoingMessage getMessage() {
            return message;
        }
        
        long getTime() {
            return time;
        }
    }

    public TelegramDispatcher(TelegramBot telegramBot,
                              BlockingQueue<OutgoingMessage> paidQueue,
                              BlockingQueue<OutgoingMessage> freeQueue,
                              WbHttpClient httpClient,
                              Map<String, String> sessionCookies,
                              MessageFormatter messageFormatter,
                              SentCache sentCache) {
        this.telegramBot = telegramBot;
        this.paidQueue = paidQueue;
        this.freeQueue = freeQueue;
        this.httpClient = httpClient;
        this.sessionCookies = sessionCookies;
        this.messageFormatter = messageFormatter;
        this.sentCache = sentCache;
    }

    public void start() {
        if (running) {
            return;
        }
        running = true;
        scheduler = Executors.newScheduledThreadPool(1, r -> {
            Thread t = new Thread(r, "tg-free-scheduler");
            t.setDaemon(true);
            return t;
        });
        
        // Планировщик для отправки в FREE канал с задержкой 2 мин 20 сек
        scheduler.scheduleWithFixedDelay(() -> {
            try {
                long now = System.currentTimeMillis();
                mapOnSent.entrySet().removeIf(e -> {
                    long age = now - e.getValue().getTime();
                    if (age > TimeUnit.MINUTES.toMillis(2) + 20_000) { // 2 мин 20 сек (140 секунд)
                        String article = e.getKey();
                        ProductInfo productInfo = e.getValue();
                        // Проверяем, не был ли товар уже отправлен в free chat
                        // Порог изменения процента = 0.1 (10%), как в старой логике
                        // ВАЖНО: для FREE канала проверяем БЕЗ сохранения в файл (saveToFile=false)
                        // Сохранение произойдет только после фактической отправки
                        if (sentCache.shouldSend(article, productInfo.getMessage().getChannelType(), 
                                productInfo.getMessage().getPercent(), 0.1, 
                                productInfo.getMessage().getPrice(), 0.15, false)) {
                            try {
                                processFreeMessage(productInfo.getMessage());
                                // Товар успешно отправлен - удаляем из mapOnSent
                                // Сохранение в кэш произошло в sendImmediate() после успешной отправки
                            } catch (InterruptedException ex) {
                                Thread.currentThread().interrupt();
                                // Не удаляем из mapOnSent при прерывании - попробуем снова позже
                                return false;
                            } catch (Exception ex) {
                                log.error("Failed to send delayed FREE message for article {}: {}", article, ex.getMessage(), ex);
                                // Не удаляем из mapOnSent при ошибке - попробуем снова позже
                                // Товар НЕ был сохранен в кэш, так как отправка не удалась
                                return false;
                            }
                        } else {
                            log.debug("FREE channel: article={} blocked by cache (already sent)", article);
                        }
                        // Удаляем из mapOnSent только если товар был отправлен или заблокирован кэшем
                        return true;
                    }
                    return false;
                });
            } catch (Throwable t) {
                log.warn("Error in FREE scheduler", t);
            }
        }, 0, 10, TimeUnit.SECONDS);
        
        paidWorker = new Thread(this::runPaidLoop, "tg-paid-dispatcher");
        paidWorker.setDaemon(true);
        paidWorker.start();
        freeWorker = new Thread(this::runFreeLoop, "tg-free-dispatcher");
        freeWorker.setDaemon(true);
        freeWorker.start();
    }

    public void stop() {
        running = false;
        if (scheduler != null) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        if (paidWorker != null) {
            paidWorker.interrupt();
        }
        if (freeWorker != null) {
            freeWorker.interrupt();
        }
    }

    private void runPaidLoop() {
        while (running || (paidQueue != null && !paidQueue.isEmpty())) {
            try {
                OutgoingMessage message = paidQueue.poll(500, TimeUnit.MILLISECONDS);
                if (message == null) {
                    continue;
                }
                send(message);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("Failed to dispatch paid telegram message", e);
            }
        }
    }

    private void runFreeLoop() {
        while (running || (freeQueue != null && !freeQueue.isEmpty()) || !mapOnSent.isEmpty()) {
            try {
                OutgoingMessage message = freeQueue.poll(500, TimeUnit.MILLISECONDS);
                if (message != null) {
                    // КРИТИЧНО: Используем putIfAbsent для атомарной проверки и добавления
                    // Это предотвращает race condition, когда два потока одновременно проверяют и добавляют товар
                    String article = message.getArticle();
                    ProductInfo existing = mapOnSent.putIfAbsent(article, new ProductInfo(message, System.currentTimeMillis()));
                    if (existing == null) {
                        // Товар успешно добавлен (его не было в mapOnSent)
                        log.debug("FREE channel: article={}, percent={} added to mapOnSent for delayed sending (2m 20s)", 
                                article, message.getPercent());
                    } else {
                        // Товар уже был в mapOnSent - это дубликат, пропускаем
                        log.debug("FREE channel: article {} already in mapOnSent (percent={}), skipping duplicate (new percent={})", 
                                article, existing.getMessage().getPercent(), message.getPercent());
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("Failed to dispatch free telegram message", e);
            }
        }
    }

    private void processFreeMessage(OutgoingMessage message) throws InterruptedException {
        if (message == null) {
            return;
        }
        try {
            // Проверка shouldSend уже выполнена в планировщике перед вызовом этого метода
            OutgoingMessage verifiedMessage = verifyAndUpdateMessage(message);
            if (verifiedMessage == null) {
                log.debug("FREE channel: article {} verification failed (product unavailable or no longer on sale)", message.getArticle());
                return;
            }
            log.debug("FREE channel: sending article={} after delay", message.getArticle());
            sendImmediate(verifiedMessage);
        } catch (InterruptedException e) {
            throw e;
        } catch (Exception e) {
            if (!isTimeoutException(e)) {
                log.warn("FREE channel send failed for {}: {}", message.getArticle(), e.getMessage());
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
        int retryCount = 0;
        final int maxRetries = 3;
        
        while (!sent && retryCount < maxRetries) {
            try {
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

                    // Для FREE канала сохраняем в файл только после фактической отправки
                    if (message.getChannelType() == org.example.service.ChannelType.FREE) {
                        sentCache.shouldSend(message.getArticle(), message.getChannelType(), 
                                message.getPercent(), 0.1, message.getPrice(), 0.15, true);
                    }
                    // Для других каналов сохранение происходит автоматически в SentCache.shouldSend()
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
                        throw new RuntimeException("Telegram send failed: " + response.description());
                    }
                    retryCount++;
                    log.debug("Telegram send failed, retrying after {} seconds (attempt {}/{})", retryAfter, retryCount, maxRetries);
                    TimeUnit.SECONDS.sleep(retryAfter);
                }
            } catch (Exception e) {
                retryCount++;
                if (retryCount >= maxRetries) {
                    log.error("Telegram send failed after {} attempts for article {}: {}", 
                            maxRetries, message.getArticle(), e.getMessage());
                    throw new RuntimeException("Failed to send message after " + maxRetries + " attempts", e);
                }
                // Для сетевых ошибок делаем задержку перед повтором
                if (e.getCause() instanceof java.net.ConnectException || 
                    e.getMessage() != null && (e.getMessage().contains("Failed to connect") || 
                                              e.getMessage().contains("Connection timed out"))) {
                    int backoffSeconds = Math.min(retryCount * 5, 30); // Экспоненциальная задержка до 30 сек
                    log.warn("Network error sending article {}, retrying in {} seconds (attempt {}/{}): {}", 
                            message.getArticle(), backoffSeconds, retryCount, maxRetries, e.getMessage());
                    TimeUnit.SECONDS.sleep(backoffSeconds);
                } else {
                    // Для других ошибок пробуем сразу
                    log.warn("Error sending article {}, retrying immediately (attempt {}/{}): {}", 
                            message.getArticle(), retryCount, maxRetries, e.getMessage());
                }
            }
        }
        
        if (!sent) {
            throw new RuntimeException("Failed to send message after " + maxRetries + " attempts");
        }
    }

    private boolean shouldRoute(OutgoingMessage message) {
        return sentCache.shouldSend(
                message.getArticle(),
                message.getChannelType(),
                message.getPercent(),
                0.15, // resendThreshold
                message.getPrice(),
                0.15  // priceResendThreshold
        );
    }
    
    private boolean isTimeoutException(Throwable e) {
        if (e == null) {
            return false;
        }
        String message = e.getMessage();
        if (message != null) {
            String lower = message.toLowerCase();
            if (lower.contains("timeout") || lower.contains("timed out") || lower.contains("connect timed")) {
                return true;
            }
        }
        return isTimeoutException(e.getCause());
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


