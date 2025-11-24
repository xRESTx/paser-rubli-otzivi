package org.example.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class SentCache {

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
        Map<ChannelType, Entry> map = cache.get(article, key -> new ConcurrentHashMap<>());
        Entry prev = map.get(type);
        if (prev == null) {
            map.put(type, new Entry(percent, price));
            return true;
        }
        boolean percentDiffers = Math.abs(prev.percent - percent) > percentThreshold;
        boolean priceDiffers = differsByPercentage(prev.price, price, priceThreshold <= 0 ? DEFAULT_PRICE_THRESHOLD : priceThreshold);
        if (percentDiffers || priceDiffers) {
            map.put(type, new Entry(percent, price));
            return true;
        }
        return false;
    }

    private boolean differsByPercentage(double previous, double current, double threshold) {
        if (previous <= 0 || current <= 0) {
            return true;
        }
        double delta = Math.abs(previous - current) / previous;
        return delta >= threshold;
    }

    private record Entry(double percent, double price) {
    }
}


