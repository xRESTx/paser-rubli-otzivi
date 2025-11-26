package org.example.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.example.http.WbHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public final class SentCache {

    private static final Logger log = LoggerFactory.getLogger(SentCache.class);
    
    // Отдельные кэши для каждого канала: канал -> (артикул -> процент)
    private final Map<ChannelType, Cache<String, Double>> channelCaches;
    
    private final Map<ChannelType, Path> channelFilePaths;
    private final Map<ChannelType, ReentrantReadWriteLock> fileLocks;
    
    public SentCache() {
        this.channelFilePaths = Map.of(
            ChannelType.HUNDRED, Paths.get("sent_articles100.txt"),
            ChannelType.NINETY, Paths.get("sent_articles90.txt"),
            ChannelType.EIGHTY, Paths.get("sent_articles80.txt"),
            ChannelType.BIG, Paths.get("sent_articlesBig.txt"),
            ChannelType.FOOD, Paths.get("sent_articlesFood.txt"),
            ChannelType.COMMUNITY, Paths.get("sent_articles_communiti.txt"),
            ChannelType.CHILDREN, Paths.get("sent_articlesdetyam.txt"),
            ChannelType.FREE, Paths.get("sent_articlesFree.txt")
        );
        this.fileLocks = new ConcurrentHashMap<>();
        this.channelCaches = new ConcurrentHashMap<>();
        
        // Создаем отдельный кэш для каждого канала
        for (ChannelType type : ChannelType.values()) {
            fileLocks.put(type, new ReentrantReadWriteLock());
            channelCaches.put(type, Caffeine.newBuilder()
                    .maximumSize(100_000)
                    .expireAfterWrite(Duration.ofHours(6))
                    .build());
        }
    }

    public boolean shouldSend(String article,
                              ChannelType type,
                              double percent,
                              double percentThreshold,
                              double price,
                              double priceThreshold) {
        return shouldSend(article, type, percent, percentThreshold, price, priceThreshold, true);
    }
    
    /**
     * Проверяет, был ли товар уже отправлен в ЛЮБОМ канале с похожим процентом.
     * Это предотвращает дублирование товара в разных каналах.
     */
    private boolean isAlreadySentInAnyChannel(String article, double roundedPercent, double percentThreshold) {
        for (Map.Entry<ChannelType, Cache<String, Double>> entry : channelCaches.entrySet()) {
            Cache<String, Double> cache = entry.getValue();
            Double cachedPercent = cache.getIfPresent(article);
            if (cachedPercent != null && Math.abs(cachedPercent - roundedPercent) <= percentThreshold) {
                // Товар уже был отправлен в этом канале с похожим процентом
                return true;
            }
        }
        return false;
    }
    
    public boolean shouldSend(String article,
                              ChannelType type,
                              double percent,
                              double percentThreshold,
                              double price,
                              double priceThreshold,
                              boolean saveToFile) {
        // Округляем percent до 4 знаков для совпадения с сохраненным значением
        double roundedPercent = Math.round(percent * 10000.0) / 10000.0;
        
        // Получаем кэш для конкретного канала
        Cache<String, Double> channelCache = channelCaches.get(type);
        if (channelCache == null) {
            log.warn("Channel cache not found for type: {}", type);
            return true; // Если кэш не найден, разрешаем отправку
        }
        
        // КРИТИЧНО: Используем синхронизацию по артикулу для предотвращения race condition
        // Используем intern() для получения канонического представления строки для синхронизации
        // Это гарантирует, что все потоки используют одну и ту же блокировку для одного артикула
        String syncKey = article.intern();
        synchronized (syncKey) {
            // КРИТИЧНО: Проверяем, был ли товар уже отправлен в ЛЮБОМ канале с похожим процентом
            // Это предотвращает дублирование товара в разных каналах
            if (isAlreadySentInAnyChannel(article, roundedPercent, percentThreshold)) {
                log.debug("BLOCKED: article={}, channel={}, percent={} (already sent in another channel with similar percent)", 
                        article, type, roundedPercent);
                return false;
            }
            
            // Получаем предыдущее значение процента для этого артикула в этом канале
            Double oldPercent = channelCache.getIfPresent(article);
            
            // Простая проверка: если записи нет ИЛИ процент изменился больше чем на порог
            boolean absent = oldPercent == null;
            boolean changed = oldPercent != null && Math.abs(oldPercent - roundedPercent) > percentThreshold;
            boolean shouldSend = absent || changed;
            
            if (shouldSend) {
                // КРИТИЧНО: Double-check locking - проверяем еще раз после получения блокировки
                // Это предотвращает добавление дубликата, если другой поток уже добавил товар
                Double currentPercent = channelCache.getIfPresent(article);
                if (currentPercent == null || Math.abs(currentPercent - roundedPercent) > percentThreshold) {
                    // Проверяем еще раз, не был ли товар добавлен в другой канал пока мы ждали блокировку
                    if (!isAlreadySentInAnyChannel(article, roundedPercent, percentThreshold)) {
                        // КРИТИЧНО: ВСЕГДА добавляем в кэш, чтобы предотвратить повторную отправку
                        // Даже для FREE канала с saveToFile=false - кэш нужен для предотвращения дублирования
                        channelCache.put(article, roundedPercent);
                        
                        // Сохраняем в файл только если указано saveToFile=true
                        // Для FREE канала файл будет сохранен после фактической отправки в TelegramDispatcher
                        if (saveToFile) {
                            saveToFile(type, article, roundedPercent);
                        }
                        
                        if (type == ChannelType.FREE && !saveToFile) {
                            log.debug("SENT: article={}, channel={}, percent={}, prev={}, absent={}, changed={} (FREE, cached but not saved to file yet)", 
                                    article, type, roundedPercent, oldPercent != null ? oldPercent : "null", absent, changed);
                        } else {
                            log.debug("SENT: article={}, channel={}, percent={}, prev={}, absent={}, changed={}", 
                                    article, type, roundedPercent, oldPercent != null ? oldPercent : "null", absent, changed);
                        }
                    } else {
                        // Товар был добавлен в другой канал пока мы ждали блокировку - дубликат!
                        log.debug("BLOCKED: article={}, channel={}, percent={} (duplicate detected - added to another channel)", 
                                article, type, roundedPercent);
                        return false;
                    }
                } else {
                    // Товар был добавлен другим потоком в этот же канал пока мы ждали блокировку - дубликат!
                    log.debug("BLOCKED: article={}, channel={}, percent={}, prevPercent={} (duplicate detected by another thread)", 
                            article, type, roundedPercent, currentPercent);
                    return false;
                }
            } else {
                log.debug("BLOCKED: article={}, channel={}, percent={}, prevPercent={} (already sent with similar percent)", 
                        article, type, roundedPercent, oldPercent);
            }
            
            return shouldSend;
        }
    }
    
    private void saveToFile(ChannelType type, String article, double percent) {
        Path filePath = channelFilePaths.get(type);
        if (filePath == null) {
            log.warn("SAVE_TO_FILE: channel {} has no file path configured", type);
            return;
        }
        
        ReentrantReadWriteLock lock = fileLocks.get(type);
        if (lock == null) {
            log.warn("SAVE_TO_FILE: channel {} has no lock configured", type);
            return;
        }
        
        lock.writeLock().lock();
        try {
            // Округляем percent до 4 знаков
            double roundedPercent = Math.round(percent * 10000.0) / 10000.0;
            
            // КРИТИЧНО: Проверяем, есть ли уже такая запись в файле перед сохранением
            // Это предотвращает дублирование товаров в файле
            boolean alreadyExists = false;
            if (Files.exists(filePath)) {
                try (BufferedReader reader = Files.newBufferedReader(filePath)) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        line = line.trim();
                        if (line.isEmpty() || line.startsWith("#")) {
                            continue;
                        }
                        String[] parts = line.split("\\s+");
                        if (parts.length >= 2 && parts[0].equals(article)) {
                            // Товар уже есть в файле
                            double existingPercent = Double.parseDouble(parts[1]);
                            double roundedExistingPercent = Math.round(existingPercent * 10000.0) / 10000.0;
                            // Если процент совпадает (с учетом округления), это дубликат
                            if (Math.abs(roundedExistingPercent - roundedPercent) < 0.0001) {
                                alreadyExists = true;
                                log.debug("SAVE_TO_FILE: article={}, channel={}, percent={} already exists in file, skipping", 
                                        article, type, roundedPercent);
                                break;
                            }
                        }
                    }
                } catch (IOException | NumberFormatException e) {
                    log.warn("SAVE_TO_FILE: failed to check existing records in {}: {}", filePath, e.getMessage());
                    // При ошибке чтения продолжаем сохранение (лучше сохранить, чем потерять)
                }
            }
            
            // Сохраняем только если записи еще нет
            if (!alreadyExists) {
                // Формат: article percent (например: 238413114 20.0)
                String line = article + " " + roundedPercent + "\n";
                Files.write(filePath, line.getBytes(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                log.debug("SAVE_TO_FILE: saved article={}, channel={}, percent={} to {}", 
                        article, type, roundedPercent, filePath.toAbsolutePath());
            }
        } catch (IOException e) {
            log.error("SAVE_TO_FILE: failed to save article={}, channel={}, percent={} to {}: {}", 
                    article, type, percent, filePath.toAbsolutePath(), e.getMessage(), e);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Предзагружает кэш данными из текстовых файлов для всех каналов.
     * Формат файла: article percent (например: 238413114 20.0)
     */
    public void warmup() {
        log.debug("WARMUP: current working directory: {}", System.getProperty("user.dir"));
        int totalLoaded = 0;
        int totalFiles = 0;
        
        for (Map.Entry<ChannelType, Path> entry : channelFilePaths.entrySet()) {
            ChannelType type = entry.getKey();
            Path filePath = entry.getValue();
            
            log.trace("WARMUP: checking file for channel {}: {}", type, filePath.toAbsolutePath());
            if (!Files.exists(filePath)) {
                log.trace("WARMUP: file {} does not exist, skipping", filePath.toAbsolutePath());
                continue; // Файл не существует - пропускаем
            }
            try {
                long fileSize = Files.size(filePath);
                log.debug("WARMUP: file {} exists, size={} bytes", filePath.toAbsolutePath(), fileSize);
            } catch (IOException e) {
                log.debug("WARMUP: failed to get size of file {}: {}", filePath.toAbsolutePath(), e.getMessage());
            }
            
            ReentrantReadWriteLock lock = fileLocks.get(type);
            if (lock == null) {
                continue;
            }
            
            lock.readLock().lock();
            try {
                // Читаем все строки и берем последнюю запись для каждого артикула
                Map<String, Double> articlePercentMap = new HashMap<>();
                int linesRead = 0;
                int linesParsed = 0;
                
                try (BufferedReader reader = Files.newBufferedReader(filePath)) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        linesRead++;
                        line = line.trim();
                        if (line.isEmpty() || line.startsWith("#")) {
                            continue; // Пропускаем пустые строки и комментарии
                        }
                        
                        String[] parts = line.split("\\s+");
                        if (parts.length < 2) {
                            log.trace("WARMUP: skipping invalid line in {}: {}", filePath, line);
                            continue; // Пропускаем некорректные строки
                        }
                        
                        try {
                            String article = parts[0];
                            double percent = Double.parseDouble(parts[1]);
                            // Округляем до 4 знаков для совпадения с логикой shouldSend
                            double roundedPercent = Math.round(percent * 10000.0) / 10000.0;
                            // Берем последнюю запись для каждого артикула (перезаписываем предыдущую)
                            articlePercentMap.put(article, roundedPercent);
                            linesParsed++;
                        } catch (NumberFormatException e) {
                            log.warn("WARMUP: failed to parse line {} in {}: {}", linesRead, filePath, line);
                        }
                    }
                }
                log.trace("WARMUP: read {} lines from {}, parsed {} unique articles", linesRead, filePath, linesParsed);
                
                // Загружаем в кэш для этого канала
                Cache<String, Double> channelCache = channelCaches.get(type);
                if (channelCache == null) {
                    log.warn("WARMUP: channel cache not found for type: {}", type);
                    continue;
                }
                
                int loaded = 0;
                for (Map.Entry<String, Double> articleEntry : articlePercentMap.entrySet()) {
                    String article = articleEntry.getKey();
                    double percent = articleEntry.getValue();
                    
                    // Добавляем в кэш канала (если еще нет)
                    if (channelCache.getIfPresent(article) == null) {
                        channelCache.put(article, percent);
                        loaded++;
                        log.trace("WARMUP: loaded article={}, channel={}, percent={} from {}", article, type, percent, filePath);
                    } else {
                        log.trace("WARMUP: article={}, channel={} already in cache, skipping", article, type);
                    }
                }
                
                if (loaded > 0) {
                    totalLoaded += loaded;
                    totalFiles++;
                    log.debug("WARMUP: loaded {} records from {} ({} unique articles total)", loaded, filePath, articlePercentMap.size());
                } else if (articlePercentMap.size() > 0) {
                    log.debug("WARMUP: file {} has {} articles but none were loaded (already in cache)", filePath, articlePercentMap.size());
                }
            } catch (IOException e) {
                log.warn("Failed to load sent products from {}: {}", filePath, e.getMessage());
            } finally {
                lock.readLock().unlock();
            }
        }
        
        if (totalLoaded > 0) {
            log.info("SentCache: warmed up with {} total records from {} files", totalLoaded, totalFiles);
        } else {
            log.info("SentCache: no sent products files found, starting with empty cache");
        }
    }
    
    /**
     * Проверяет каждый товар из кэша через HTTP запрос и удаляет из кэша те,
     * у которых плашка "рубли за отзывы" исчезла.
     * Файлы НЕ удаляются.
     * @param httpClient HTTP клиент для выполнения запросов
     * @return количество удаленных товаров из кэша
     */
    public int checkAndCleanCache(WbHttpClient httpClient) {
        int removedCount = 0;
        int checkedCount = 0;
        
        log.info("Starting cache cleanup: checking products for promotion badge...");
        
        // Проходим по всем каналам
        for (Map.Entry<ChannelType, Cache<String, Double>> entry : channelCaches.entrySet()) {
            ChannelType type = entry.getKey();
            Cache<String, Double> channelCache = entry.getValue();
            
            // Получаем все артикулы из кэша этого канала
            Map<String, Double> articles = new HashMap<>();
            channelCache.asMap().forEach((article, percent) -> articles.put(article, percent));
            
            log.info("Checking {} articles in channel {}...", articles.size(), type);
            
            for (Map.Entry<String, Double> articleEntry : articles.entrySet()) {
                String article = articleEntry.getKey();
                checkedCount++;
                
                try {
                    // Проверяем наличие плашки через API деталей товара
                    if (!hasPromotionBadge(httpClient, article)) {
                        // Плашка исчезла - удаляем из кэша
                        channelCache.invalidate(article);
                        removedCount++;
                        log.debug("Removed article {} from {} cache (promotion badge disappeared)", article, type);
                    }
                } catch (Exception e) {
                    log.warn("Failed to check article {} in channel {}: {}", article, type, e.getMessage());
                    // При ошибке не удаляем товар из кэша (может быть временная проблема)
                }
                
                // Небольшая задержка между запросами, чтобы не перегружать сервер
                if (checkedCount % 10 == 0) {
                    try {
                        Thread.sleep(100); // 100ms задержка каждые 10 товаров
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        
        log.info("Cache cleanup completed: checked {} articles, removed {} articles without promotion badge", 
                checkedCount, removedCount);
        
        return removedCount;
    }
    
    /**
     * Проверяет наличие плашки "рубли за отзывы" для товара через API деталей.
     * @param httpClient HTTP клиент
     * @param article артикул товара
     * @return true если плашка есть, false если нет
     */
    private boolean hasPromotionBadge(WbHttpClient httpClient, String article) {
        try {
            long nmId = Long.parseLong(article);
            String url = String.format(
                    "https://www.wildberries.ru/__internal/u-card/cards/v4/detail?appType=1&curr=rub&dest=-1255987&spp=30&hide_dtype=11&ab_testid=popular_sort&lang=ru&nm=%d",
                    nmId
            );
            
            HttpResponse<String> response = httpClient.get(url, Collections.emptyMap());
            
            if (response.statusCode() != 200) {
                log.debug("Article {} API returned status {}", article, response.statusCode());
                return false; // Если запрос не удался, считаем что плашки нет
            }
            
            String body = response.body();
            if (body == null || body.isEmpty()) {
                return false;
            }
            
            // Парсим JSON ответ
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            
            // Проверяем наличие поля feedbackPoints или других признаков акции
            // Если есть feedbackPoints и он больше 0, значит товар участвует в акции
            if (json.has("data")) {
                JsonObject data = json.getAsJsonObject("data");
                if (data.has("products") && data.get("products").isJsonArray()) {
                    var productsArray = data.getAsJsonArray("products");
                    if (productsArray.size() > 0) {
                        JsonObject product = productsArray.get(0).getAsJsonObject();
                        // Проверяем наличие feedbackPoints (рубли за отзывы)
                        if (product.has("feedbackPoints")) {
                            String feedbackPoints = product.get("feedbackPoints").getAsString();
                            if (feedbackPoints != null && !feedbackPoints.isEmpty() && !feedbackPoints.equals("0")) {
                                return true; // Плашка есть
                            }
                        }
                    }
                }
            }
            
            return false; // Плашки нет
        } catch (Exception e) {
            log.debug("Error checking promotion badge for article {}: {}", article, e.getMessage());
            return false; // При ошибке считаем что плашки нет
        }
    }
    
    /**
     * Очищает кэш через проверку каждого товара на наличие плашки.
     * Файлы НЕ удаляются - они будут очищаться через проверку каждого товара.
     * @param httpClient HTTP клиент для выполнения запросов
     * @return количество удаленных товаров из кэша
     */
    public int clearAllFiles(WbHttpClient httpClient) {
        // Не удаляем файлы - только проверяем товары через HTTP запросы
        return checkAndCleanCache(httpClient);
    }
    
    /**
     * Предзагружает кэш данными из БД (для обратной совместимости).
     * @param records список записей для предзагрузки
     * @deprecated Используйте warmup() для загрузки из текстового файла
     */
    @Deprecated
    public void warmup(List<WarmupRecord> records) {
        if (records == null || records.isEmpty()) {
            return;
        }
        int loaded = 0;
        for (WarmupRecord record : records) {
            Cache<String, Double> channelCache = channelCaches.get(record.channelType());
            if (channelCache != null && channelCache.getIfPresent(record.article()) == null) {
                channelCache.put(record.article(), record.percent());
                loaded++;
            }
        }
        log.info("SentCache: warmed up with {} records from database", loaded);
    }
    
    /**
     * Запись для предзагрузки кэша.
     */
    public record WarmupRecord(String article, ChannelType channelType, double percent, double price) {
    }
}


