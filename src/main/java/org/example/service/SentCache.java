package org.example.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class SentCache {

    private static final Logger log = LoggerFactory.getLogger(SentCache.class);
    private static final double DEFAULT_PRICE_THRESHOLD = 0.15;

    private final Cache<String, Map<ChannelType, Entry>> cache =
            Caffeine.newBuilder()
                    .maximumSize(100_000)
                    .expireAfterWrite(Duration.ofHours(6))
                    .build();

    public boolean shouldSend(String article,
                              ChannelType type,
                              double percent,
                              double percentThreshold,
                              double price,
                              double priceThreshold) {
        // Округляем percent до 4 знаков для совпадения с сохраненным значением
        double roundedPercent = Math.round(percent * 10000.0) / 10000.0;
        Map<ChannelType, Entry> map = cache.get(article, key -> new ConcurrentHashMap<>());
        Entry prev = map.get(type);
        if (prev == null) {
            map.put(type, new Entry(roundedPercent, price));
            return true;
        }
        // Проверяем, отличается ли percent более чем на порог
        // Используем относительное сравнение для percent, если он > 0
        boolean percentDiffers;
        if (prev.percent > 0 && roundedPercent > 0) {
            double percentDelta = Math.abs(prev.percent - roundedPercent) / Math.max(prev.percent, roundedPercent);
            percentDiffers = percentDelta > percentThreshold;
        } else {
            percentDiffers = Math.abs(prev.percent - roundedPercent) > percentThreshold;
        }
        boolean priceDiffers = differsByPercentage(prev.price, price, priceThreshold <= 0 ? DEFAULT_PRICE_THRESHOLD : priceThreshold);
        if (percentDiffers || priceDiffers) {
            map.put(type, new Entry(roundedPercent, price));
            return true;
        }
        // Товар уже был отправлен с такими же параметрами - не отправляем повторно
        return false;
    }

    private boolean differsByPercentage(double previous, double current, double threshold) {
        if (previous <= 0 || current <= 0) {
            return true;
        }
        double delta = Math.abs(previous - current) / previous;
        return delta >= threshold;
    }

    /**
     * Предзагружает кэш данными из БД.
     * @param records список записей для предзагрузки
     */
    public void warmup(List<WarmupRecord> records) {
        if (records == null || records.isEmpty()) {
            return;
        }
        int loaded = 0;
        for (WarmupRecord record : records) {
            Map<ChannelType, Entry> map = cache.get(record.article(), key -> new ConcurrentHashMap<>());
            // Добавляем запись только если её еще нет (чтобы не перезаписать более свежие данные)
            if (!map.containsKey(record.channelType())) {
                map.put(record.channelType(), new Entry(record.percent(), record.price()));
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
    
    private record Entry(double percent, double price) {
    }
}


