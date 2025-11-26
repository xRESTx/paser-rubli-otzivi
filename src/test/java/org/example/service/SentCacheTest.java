package org.example.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SentCacheTest {

    @Test
    void shouldSendOnlyOncePerChannel() {
        SentCache cache = new SentCache();
        String article = "999";

        boolean first = cache.shouldSend(article, ChannelType.HUNDRED, 1.0, 0.15, 1000, 0.15);
        boolean second = cache.shouldSend(article, ChannelType.HUNDRED, 1.0, 0.15, 1000, 0.15);

        assertTrue(first, "Первый вызов должен разрешать отправку");
        assertFalse(second, "Второй вызов с тем же процентом должен блокироваться");
    }

    @Test
    void allowsResendWhenPriceDropsFifteenPercent() {
        SentCache cache = new SentCache();
        String article = "price-change";

        boolean first = cache.shouldSend(article, ChannelType.BIG, 0.6, 0.15, 2000, 0.15);
        boolean second = cache.shouldSend(article, ChannelType.BIG, 0.6, 0.15, 1600, 0.15);

        assertTrue(first);
        assertTrue(second, "Должны переслать товар при изменении цены >=15%");
    }

    @Test
    void warmupPreventsImmediateDuplicates() {
        SentCache cache = new SentCache();
        cache.warmup(List.of(new SentCache.WarmupRecord(
                "111",
                ChannelType.HUNDRED,
                1.2,
                500
        )));

        boolean shouldSendSame = cache.shouldSend("111", ChannelType.HUNDRED, 1.2, 0.15, 500, 0.15);
        assertFalse(shouldSendSame, "Warmup data must block duplicate sends");

        boolean shouldSendPriceDrop = cache.shouldSend("111", ChannelType.HUNDRED, 1.2, 0.15, 400, 0.15);
        assertTrue(shouldSendPriceDrop, "Price drop should bypass warmup record");
    }
}


