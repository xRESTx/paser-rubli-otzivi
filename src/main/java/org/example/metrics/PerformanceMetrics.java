package org.example.metrics;

import org.example.service.ChannelType;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.Collectors;

/**
 * Lightweight metrics accumulator to spot parser bottlenecks without heavy deps.
 * All record* methods are lock-free and can be called from hot paths.
 */
public final class PerformanceMetrics {

    private final LongAdder firstPageCount = new LongAdder();
    private final LongAdder firstPageTime = new LongAdder();

    private final LongAdder pageCount = new LongAdder();
    private final LongAdder pageTime = new LongAdder();

    private final LongAdder productBatchCount = new LongAdder();
    private final LongAdder productItemCount = new LongAdder();
    private final LongAdder productTime = new LongAdder();

    private final LongAdder paidQueueDrops = new LongAdder();
    private final LongAdder freeQueueDrops = new LongAdder();

    private final ConcurrentMap<Integer, LongAdder> httpStatusCounters = new ConcurrentHashMap<>();
    private final LongAdder httpExceptions = new LongAdder();
    
    private final LongAdder productsEvaluated = new LongAdder();
    private final LongAdder productsBlockedByCache = new LongAdder();
    private final LongAdder productsEnqueued = new LongAdder();

    public void recordFirstPage(long durationNanos) {
        if (durationNanos < 0) {
            return;
        }
        firstPageCount.increment();
        firstPageTime.add(durationNanos);
    }

    public void recordPage(long durationNanos) {
        if (durationNanos < 0) {
            return;
        }
        pageCount.increment();
        pageTime.add(durationNanos);
    }

    public void recordProductProcessing(long durationNanos, int processedItems) {
        if (durationNanos < 0) {
            return;
        }
        productBatchCount.increment();
        if (processedItems > 0) {
            productItemCount.add(processedItems);
        }
        productTime.add(durationNanos);
    }

    public void recordHttpStatus(int statusCode) {
        httpStatusCounters
                .computeIfAbsent(statusCode, key -> new LongAdder())
                .increment();
    }

    public void recordHttpException() {
        httpExceptions.increment();
    }

    public void incrementQueueDrop(ChannelType type) {
        if (type == ChannelType.FREE) {
            freeQueueDrops.increment();
        } else {
            paidQueueDrops.increment();
        }
    }
    
    public void recordProductEvaluated() {
        productsEvaluated.increment();
    }
    
    public void recordProductBlockedByCache() {
        productsBlockedByCache.increment();
    }
    
    public void recordProductEnqueued(int count) {
        if (count > 0) {
            productsEnqueued.add(count);
        }
    }

    public MetricsSnapshot snapshotAndReset() {
        long firstPageCountValue = firstPageCount.sumThenReset();
        long firstPageTimeValue = firstPageTime.sumThenReset();

        long pageCountValue = pageCount.sumThenReset();
        long pageTimeValue = pageTime.sumThenReset();

        long productBatchCountValue = productBatchCount.sumThenReset();
        long productItemCountValue = productItemCount.sumThenReset();
        long productTimeValue = productTime.sumThenReset();

        long paidDrops = paidQueueDrops.sumThenReset();
        long freeDrops = freeQueueDrops.sumThenReset();

        long httpExceptionCount = httpExceptions.sumThenReset();
        Map<Integer, Long> statusMap = httpStatusCounters.entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> entry.getValue().sumThenReset()
                ));
        
        long evaluated = productsEvaluated.sumThenReset();
        long blocked = productsBlockedByCache.sumThenReset();
        long enqueued = productsEnqueued.sumThenReset();

        return new MetricsSnapshot(
                firstPageCountValue,
                firstPageTimeValue,
                pageCountValue,
                pageTimeValue,
                productBatchCountValue,
                productItemCountValue,
                productTimeValue,
                paidDrops,
                freeDrops,
                statusMap,
                httpExceptionCount,
                evaluated,
                blocked,
                enqueued
        );
    }

    public record MetricsSnapshot(
            long firstPageCount,
            long firstPageTimeNanos,
            long pageCount,
            long pageTimeNanos,
            long productBatchCount,
            long productItemCount,
            long productTimeNanos,
            long paidQueueDrops,
            long freeQueueDrops,
            Map<Integer, Long> httpStatuses,
            long httpExceptions,
            long productsEvaluated,
            long productsBlockedByCache,
            long productsEnqueued
    ) {
        private static double avgMs(long nanos, long count) {
            if (count == 0) {
                return 0.0;
            }
            return nanos / 1_000_000.0 / count;
        }

        public String format() {
            String statusSummary = httpStatuses.isEmpty()
                    ? "{}"
                    : httpStatuses.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .map(entry -> entry.getKey() + "=" + entry.getValue())
                    .collect(Collectors.joining(", ", "{", "}"));

            return String.format(
                    "firstPage(avg=%.1fms,count=%d) page(avg=%.1fms,count=%d) products(avg=%.2fms,batches=%d,items=%d) queueDrops(paid=%d,free=%d) http=%s exceptions=%d cache(evaluated=%d,blocked=%d,enqueued=%d)",
                    avgMs(firstPageTimeNanos, firstPageCount),
                    firstPageCount,
                    avgMs(pageTimeNanos, pageCount),
                    pageCount,
                    avgMs(productTimeNanos, productBatchCount),
                    productBatchCount,
                    productItemCount,
                    paidQueueDrops,
                    freeQueueDrops,
                    statusSummary,
                    httpExceptions,
                    productsEvaluated,
                    productsBlockedByCache,
                    productsEnqueued
            );
        }
    }
}


