package org.example.core;

import com.google.gson.Gson;
import org.example.config.AppConfig;
import org.example.http.WbHttpClient;
import org.example.jsonmodel.Product;
import org.example.messaging.OutgoingMessage;
import org.example.messaging.TelegramDispatcher;
import org.example.metrics.PerformanceMetrics;
import org.example.parser.ProductParser;
import org.example.service.ChannelType;
import org.example.service.RubliService;
import org.example.service.RubliService.ProductContext;
import org.example.storage.records.ProductSnapshot;
import org.example.telegram.CategoryTask;
import org.example.telegram.PageTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Coordinates fetching, parsing, business logic and persistence without tying to Telegram update API.
 */
public final class BotEngine {

    private static final Logger log = LoggerFactory.getLogger(BotEngine.class);

    private final AppConfig config;
    private final WbHttpClient httpClient;
    private final ProductParser parser;
    private final RubliService rubliService;
    private final TelegramDispatcher dispatcher;
    private BlockingQueue<PageTask> pageQueue;
    private final BlockingQueue<OutgoingMessage> paidQueue;
    private final BlockingQueue<OutgoingMessage> freeQueue;
    private ExecutorService catalogExecutor;
    private ExecutorService productExecutor;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Set<String> supplierBlacklist;
    private volatile Map<String, String> sessionCookies;
    private final Set<String> scheduledPageKeys = ConcurrentHashMap.newKeySet();
    private final Set<String> scheduledCategoryKeys = ConcurrentHashMap.newKeySet();
    private final Set<String> coldCategories = ConcurrentHashMap.newKeySet();
    private long lastColdCategoriesClearTime = 0L;
    private static final long COLD_CATEGORIES_CLEAR_INTERVAL_MS = 300_000; // 5 минут - очищаем холодные категории
    private final AtomicInteger activePageWorkers = new AtomicInteger();
    private final AtomicLong productsMatched = new AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong pagesProcessed = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong pageErrors = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong categoriesProcessed = new java.util.concurrent.atomic.AtomicLong();
    private final PerformanceMetrics performanceMetrics = new PerformanceMetrics();
    private final java.util.concurrent.atomic.AtomicLong http498Errors = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong totalHttpRequests = new java.util.concurrent.atomic.AtomicLong();
    private volatile long lastCookieRefreshTime = 0L;
    private static final long MIN_COOKIE_REFRESH_INTERVAL_MS = 60_000; // Минимум 1 минута между обновлениями
    private static final double HTTP498_THRESHOLD = 0.3; // 30% ошибок 498 - порог для обновления cookies
    private final Runnable cookieRefreshCallback;

    public BotEngine(AppConfig config,
                     WbHttpClient httpClient,
                     RubliService rubliService,
                     BlockingQueue<OutgoingMessage> paidQueue,
                     BlockingQueue<OutgoingMessage> freeQueue,
                     TelegramDispatcher dispatcher,
                     Set<String> supplierBlacklist,
                     Map<String, String> sessionCookies,
                     Runnable cookieRefreshCallback) {
        this.config = config;
        this.httpClient = httpClient;
        this.rubliService = rubliService;
        this.dispatcher = dispatcher;
        this.parser = new ProductParser(new Gson());
        this.paidQueue = paidQueue;
        this.freeQueue = freeQueue;
        this.supplierBlacklist = supplierBlacklist;
        this.sessionCookies = sessionCookies == null ? new java.util.concurrent.ConcurrentHashMap<>() : new java.util.HashMap<>(sessionCookies);
        this.cookieRefreshCallback = cookieRefreshCallback;
    }
    
    /**
     * Обновляет session cookies в BotEngine.
     * Вызывается после обновления cookies через Selenium.
     */
    public void updateSessionCookies(Map<String, String> newCookies) {
        this.sessionCookies = newCookies != null ? new java.util.HashMap<>(newCookies) : Map.of();
        log.info("BotEngine: session cookies updated ({} cookies)", this.sessionCookies.size());
    }

    public void start() {
        if (!running.compareAndSet(false, true)) {
            log.warn("BotEngine already running, ignoring start()");
            return;
        }
        // Создаем новые executor'ы при каждом запуске
        this.pageQueue = new LinkedBlockingQueue<>();
        this.catalogExecutor = Executors.newFixedThreadPool(config.getCatalogThreads(), r -> {
            Thread t = new Thread(r, "catalog-fetch");
            t.setDaemon(true);
            return t;
        });
        this.productExecutor = Executors.newFixedThreadPool(config.getProductThreads(), r -> {
            Thread t = new Thread(r, "product-worker");
            t.setDaemon(true);
            return t;
        });
        
        dispatcher.start();
        for (int i = 0; i < config.getProductThreads(); i++) {
            productExecutor.submit(this::productLoop);
        }
        catalogExecutor.submit(this::catalogLoop);
        log.info("BotEngine started with {} catalog threads and {} product threads", 
                config.getCatalogThreads(), config.getProductThreads());
    }

    public void stop() {
        if (!running.compareAndSet(true, false)) {
            log.warn("BotEngine not running, ignoring stop()");
            return;
        }
        log.info("Stopping BotEngine...");
        
        // Останавливаем executor'ы
        if (catalogExecutor != null) {
            catalogExecutor.shutdownNow();
            try {
                if (!catalogExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    log.warn("Catalog executor did not terminate in time");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                catalogExecutor.shutdownNow();
            }
            catalogExecutor = null;
        }
        
        if (productExecutor != null) {
            productExecutor.shutdownNow();
            try {
                if (!productExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    log.warn("Product executor did not terminate in time");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                productExecutor.shutdownNow();
            }
            productExecutor = null;
        }
        
        dispatcher.stop();
        pageQueue = null;
        log.info("BotEngine stopped");
    }

    private void catalogLoop() {
        while (running.get()) {
            try {
                long cycleStartTime = System.currentTimeMillis();
                waitForPagesToDrain(60_000); // Максимум 60 секунд на ожидание завершения предыдущего цикла
                scheduledPageKeys.clear();
                scheduledCategoryKeys.clear();

                List<CategoryTask> categories;
                try {
                    categories = CategoryTask.load(sessionCookies);
                    log.info("Loaded {} categories from CategoryTask.load()", categories.size());
                } catch (Exception e) {
                    log.error("Failed to load categories: {}", e.getMessage(), e);
                    TimeUnit.SECONDS.sleep(1);
                    continue;
                }
                if (categories.isEmpty()) {
                    log.warn("CategoryTask.load() returned empty list - no categories to process");
                    TimeUnit.SECONDS.sleep(1);
                    continue;
                }
                // Периодически очищаем холодные категории, чтобы проверить их снова
                long currentTime = System.currentTimeMillis();
                if (currentTime - lastColdCategoriesClearTime > COLD_CATEGORIES_CLEAR_INTERVAL_MS) {
                    int clearedCount = coldCategories.size();
                    coldCategories.clear();
                    lastColdCategoriesClearTime = currentTime;
                    if (clearedCount > 0) {
                        log.info("Cleared {} cold categories after {} minutes, will re-check them", 
                                clearedCount, COLD_CATEGORIES_CLEAR_INTERVAL_MS / 60_000);
                    }
                }
                
                if (!coldCategories.isEmpty()) {
                    int before = categories.size();
                    int coldCount = coldCategories.size();
                    categories.removeIf(task -> coldCategories.contains(task.categoryUrl()));
                    if (categories.isEmpty()) {
                        long timeUntilClear = COLD_CATEGORIES_CLEAR_INTERVAL_MS - (currentTime - lastColdCategoriesClearTime);
                        log.warn("All {} categories skipped as cold this cycle (total cold: {}). Will clear cold categories in {} minutes.", 
                                before, coldCount, timeUntilClear / 60_000);
                        TimeUnit.SECONDS.sleep(1);
                        continue;
                    }
                    int skipped = before - categories.size();
                    if (skipped > 0) {
                        log.info("Skipping {} cold categories this cycle ({} -> {}, total cold: {})", skipped, before, categories.size(), coldCount);
                    }
                }

                int totalCategories = categories.size();
                log.info("Starting cycle: processing {} categories (after filtering cold categories)", totalCategories);
                categoriesProcessed.addAndGet(totalCategories);
                
                if (totalCategories == 0) {
                    log.warn("No categories to process after filtering cold categories, skipping cycle");
                    TimeUnit.SECONDS.sleep(1);
                    continue;
                }
                
                AtomicInteger completedCount = new AtomicInteger(0);
                CountDownLatch latch = new CountDownLatch(totalCategories);
                final int totalToProcess = totalCategories;
                
                // Запускаем поток для логирования прогресса
                Thread progressLogger = new Thread(() -> {
                    while (!Thread.currentThread().isInterrupted() && latch.getCount() > 0) {
                        try {
                            Thread.sleep(5_000); // Логируем каждые 5 секунд
                            int completed = completedCount.get();
                            int remaining = (int) latch.getCount();
                            int total = totalToProcess;
                            if (total > 0) {
                                log.info("Progress: {}/{} categories completed ({}%), {} remaining", 
                                        completed, total, (completed * 100 / total), remaining);
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }, "progress-logger");
                progressLogger.setDaemon(true);
                progressLogger.start();
                
                int submitted = 0;
                AtomicInteger startedCount = new AtomicInteger(0);
                for (CategoryTask category : categories) {
                    final CategoryTask cat = category; // Для использования в лямбде
                    catalogExecutor.submit(() -> {
                        int started = startedCount.incrementAndGet();
                        String apiUrl = cat.buildPageUrl(1);
                        if (started <= 10 || started % 100 == 0) {
                            log.info("Task #{} STARTED for API: {} (thread: {})", 
                                    started, apiUrl, Thread.currentThread().getName());
                        }
                        long taskStartTime = System.currentTimeMillis();
                        try {
                            if (!running.get()) {
                                log.debug("BotEngine stopped, skipping API {}", apiUrl);
                                return;
                            }
                            if (scheduledCategoryKeys.add(cat.categoryUrl())) {
                                log.debug("Fetching first page for API: {}", apiUrl);
                                fetchFirstPage(cat);
                                completedCount.incrementAndGet();
                                long taskDuration = System.currentTimeMillis() - taskStartTime;
                                if (taskDuration > 10_000) {
                                    log.warn("Slow category processing: {} took {}ms", apiUrl, taskDuration);
                                }
                                int completed = completedCount.get();
                                if (completed <= 10 || completed % 100 == 0) {
                                    log.info("Category #{} COMPLETED: {} ({}ms)", 
                                            completed, apiUrl, taskDuration);
                                }
                            } else {
                                log.debug("Category {} already scheduled, skipping", apiUrl);
                            }
                        } catch (Exception e) {
                            long taskDuration = System.currentTimeMillis() - taskStartTime;
                            log.error("Error processing category {} after {}ms: {}", 
                                    apiUrl, taskDuration, e.getMessage(), e);
                        } finally {
                            latch.countDown();
                        }
                    });
                    submitted++;
                    // Логируем каждые 500 категорий для отслеживания отправки
                    if (submitted % 500 == 0) {
                        log.info("Submitted {} tasks to executor so far...", submitted);
                    }
                }
                
                log.info("Submitted {} category tasks to executor, waiting for completion...", submitted);
                
                // Ждем завершения обработки всех категорий с таймаутом
                boolean completed = latch.await(300, TimeUnit.SECONDS); // Максимум 5 минут на все категории
                progressLogger.interrupt();
                
                if (!completed) {
                    log.warn("Timeout waiting for categories to complete: {} categories still processing (submitted: {}, completed: {})", 
                            latch.getCount(), submitted, completedCount.get());
                } else {
                    log.info("All {} categories processed successfully (completed: {})", submitted, completedCount.get());
                }
                
                // Ждем завершения обработки страниц с таймаутом
                long waitStartTime = System.currentTimeMillis();
                waitForPagesToDrain(60_000); // Максимум 60 секунд на ожидание
                long waitDuration = System.currentTimeMillis() - waitStartTime;
                if (waitDuration > 10_000) {
                    log.warn("Waited {} seconds for pages to drain (queue size: {}, active workers: {})", 
                            waitDuration / 1000, 
                            pageQueue != null ? pageQueue.size() : 0, 
                            activePageWorkers.get());
                }
                
                long cycleDuration = System.currentTimeMillis() - cycleStartTime;
                log.info("Cycle: categories={}, pages={}, errors={}, products={}, time={}ms",
                        categoriesProcessed.getAndSet(0),
                        pagesProcessed.getAndSet(0),
                        pageErrors.getAndSet(0),
                        productsMatched.getAndSet(0),
                        cycleDuration);
                PerformanceMetrics.MetricsSnapshot snapshot = performanceMetrics.snapshotAndReset();
                log.info("PerfMetrics: {}", snapshot.format());
                
                // Проверяем порог 498 ошибок и обновляем cookies при необходимости
                long totalRequests = totalHttpRequests.get();
                long errors498 = http498Errors.get();
                if (totalRequests > 100 && errors498 > 0) { // Минимум 100 запросов для статистики
                    double errorRate = (double) errors498 / totalRequests;
                    if (errorRate >= HTTP498_THRESHOLD) {
                        long now = System.currentTimeMillis();
                        if (now - lastCookieRefreshTime > MIN_COOKIE_REFRESH_INTERVAL_MS) {
                            log.warn("HTTP 498 error rate is {}% ({} out of {} requests), refreshing cookies...", 
                                    String.format("%.1f", errorRate * 100), errors498, totalRequests);
                            if (cookieRefreshCallback != null) {
                                try {
                                    cookieRefreshCallback.run();
                                    lastCookieRefreshTime = now;
                                    // Сбрасываем счетчики после обновления cookies
                                    http498Errors.set(0);
                                    totalHttpRequests.set(0);
                                    log.info("Cookies refreshed successfully, resetting error counters");
                                } catch (Exception e) {
                                    log.error("Failed to refresh cookies: {}", e.getMessage(), e);
                                }
                            }
                        } else {
                            log.debug("Cookie refresh skipped (last refresh was {}ms ago, min interval is {}ms)", 
                                    now - lastCookieRefreshTime, MIN_COOKIE_REFRESH_INTERVAL_MS);
                        }
                    }
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                try {
                    TimeUnit.SECONDS.sleep(1);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    private void fetchFirstPage(CategoryTask category) {
        String url = category.buildPageUrl(1);
        if (!scheduledPageKeys.add(pageKey(category, 1))) {
            log.debug("Page already scheduled for API: {}", url);
            return;
        }
        long start = System.nanoTime();
        long requestStartTime = System.currentTimeMillis();
        try {
            log.trace("Fetching first page for API: {}", url);
            HttpResponse<String> response;
            long httpStartTime = System.currentTimeMillis();
            try {
                response = httpClient.get(url, category.defaultHeaders());
            } catch (Exception e) {
                long requestDuration = System.currentTimeMillis() - requestStartTime;
                long httpDuration = System.currentTimeMillis() - httpStartTime;
                log.error("HTTP request failed for API {} after {}ms (HTTP call: {}ms): {} - {}", 
                        url, requestDuration, httpDuration, e.getClass().getSimpleName(), e.getMessage());
                if (e.getCause() != null) {
                    log.error("  Caused by: {} - {}", e.getCause().getClass().getSimpleName(), e.getCause().getMessage());
                }
                throw e;
            }
            long httpDuration = System.currentTimeMillis() - httpStartTime;
            long requestDuration = System.currentTimeMillis() - requestStartTime;
            if (requestDuration > 5_000 || httpDuration > 5_000) {
                log.warn("Slow HTTP request for API {}: total={}ms, http={}ms, status={}", 
                        url, requestDuration, httpDuration, response.statusCode());
            }
            log.trace("Received response for API: {} (status: {}, duration: {}ms)", 
                    url, response.statusCode(), requestDuration);
            int statusCode = response.statusCode();
            performanceMetrics.recordHttpStatus(statusCode);
            totalHttpRequests.incrementAndGet();
            if (statusCode == 498) {
                http498Errors.incrementAndGet();
            }
            String responseBody = response.body();
            
            if (statusCode != 200) {
                pageErrors.incrementAndGet();
                if (statusCode == 498) {
                    http498Errors.incrementAndGet();
                }
                return;
            }
            
            // Проверяем, что ответ начинается с JSON (либо { либо [)
            if (responseBody == null || responseBody.trim().isEmpty()) {
                log.debug("Empty response body for URL: {}", url);
                pageErrors.incrementAndGet();
                return;
            }
            
            String trimmed = responseBody.trim();
            if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) {
                log.warn("Response is not JSON for URL: {}", url);
                pageErrors.incrementAndGet();
                return;
            }
            
            long parseStartTime = System.currentTimeMillis();
            ProductParser.CatalogPage catalogPage = parser.parseCatalog(responseBody);
            long parseDuration = System.currentTimeMillis() - parseStartTime;
            int totalProducts = catalogPage.getTotalProducts();
            
            // Логируем медленный парсинг
            if (parseDuration > 1000) {
                log.warn("Slow parsing for API {}: {}ms (response size: {} bytes)", 
                        url, parseDuration, responseBody != null ? responseBody.length() : 0);
            }
            int productsCount = catalogPage.getProducts() != null ? catalogPage.getProducts().size() : 0;
            if (totalProducts <= 0 || catalogPage.getProducts() == null || catalogPage.getProducts().isEmpty()) {
                coldCategories.add(category.categoryUrl());
                log.debug("API {} has zero products (total={}, products={}), marking as cold", 
                        url, totalProducts, productsCount);
                // Ограничиваем размер coldCategories - если больше 1000, очищаем половину
                if (coldCategories.size() > 1000) {
                    int toRemove = coldCategories.size() / 2;
                    var iterator = coldCategories.iterator();
                    int removed = 0;
                    while (iterator.hasNext() && removed < toRemove) {
                        iterator.next();
                        iterator.remove();
                        removed++;
                    }
                    log.info("Cleared {} old cold categories (size was >1000)", removed);
                }
                return;
            }
            pagesProcessed.incrementAndGet();
            log.debug("API {} has {} products (total={}), processing...", url, productsCount, totalProducts);
            coldCategories.remove(category.categoryUrl());
            processProducts(category, catalogPage.getProducts());
            categoriesProcessed.incrementAndGet();
            int totalPages = Math.min(config.getMaxPagesPerCategory(), Math.max(1, (int) Math.ceil(totalProducts / 100.0)));
            if (pageQueue != null && running.get()) {
                int pagesAdded = 0;
                for (int page = 2; page <= totalPages; page++) {
                    if (scheduledPageKeys.add(pageKey(category, page))) {
                        if (pageQueue.offer(new PageTask(category, page))) {
                            pagesAdded++;
                        } else {
                            log.warn("Failed to add page {} to queue for API {} (queue full?)", page, url);
                        }
                    }
                }
                if (totalPages > 1 && pagesAdded > 0) {
                    log.debug("Added {} pages (2-{}) to queue for API {}", pagesAdded, totalPages, url);
                }
            } else {
                if (pageQueue == null) {
                    log.warn("pageQueue is null, cannot add pages for API {}", url);
                }
                if (!running.get()) {
                    log.debug("BotEngine not running, skipping page queue for API {}", url);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            // Нормальное завершение потока при остановке - не логируем
        } catch (Exception e) {
            if (isBenignHttpException(e)) {
                log.trace("Benign HTTP issue on first page {}: {}", url, e.getMessage());
            } else {
                pageErrors.incrementAndGet();
                performanceMetrics.recordHttpException();
            }
        } finally {
            performanceMetrics.recordFirstPage(System.nanoTime() - start);
        }
    }

    private void productLoop() {
        while (running.get()) {
            try {
                if (pageQueue == null) {
                    break;
                }
                PageTask task = pageQueue.poll(500, TimeUnit.MILLISECONDS);
                if (task == null) {
                    continue;
                }
                activePageWorkers.incrementAndGet();
                String url = task.category().buildPageUrl(task.page());
                log.debug("Processing page {} for API {}", task.page(), url);
                try {
                    long pageStart = System.nanoTime();
                    try {
                        HttpResponse<String> response = httpClient.get(url, task.category().defaultHeaders());
                        int statusCode = response.statusCode();
                        performanceMetrics.recordHttpStatus(statusCode);
                        totalHttpRequests.incrementAndGet();
                        if (statusCode == 498) {
                            http498Errors.incrementAndGet();
                        }
                        if (statusCode != 200) {
                            pageErrors.incrementAndGet();
                            continue;
                        }
                        ProductParser.CatalogPage catalogPage = parser.parseCatalog(response.body());
                        pagesProcessed.incrementAndGet();
                        processProducts(task.category(), catalogPage.getProducts());
                    } finally {
                        performanceMetrics.recordPage(System.nanoTime() - pageStart);
                    }
                } finally {
                    activePageWorkers.decrementAndGet();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                // Нормальное завершение потока при остановке - выходим из цикла
                break;
            } catch (Exception e) {
                if (isBenignHttpException(e)) {
                    log.trace("Benign HTTP issue on product page: {}", e.getMessage());
                    continue;
                }
                pageErrors.incrementAndGet();
                performanceMetrics.recordHttpException();
                log.error("Product loop error", e);
            }
        }
    }
    
    private boolean isBenignHttpException(Throwable throwable) {
        if (throwable == null) {
            return false;
        }
        // HttpTimeoutException - это не критичная ошибка, просто таймаут запроса
        if (throwable instanceof java.net.http.HttpTimeoutException) {
            return true;
        }
        // EOFException - сервер закрыл соединение преждевременно, не критично
        if (throwable instanceof java.io.EOFException) {
            return true;
        }
        // IOException с EOF в сообщении
        if (throwable instanceof java.io.IOException) {
            String message = throwable.getMessage();
            if (message != null && (message.contains("EOF") || message.contains("EOF reached"))) {
                return true;
            }
        }
        String message = throwable.getMessage();
        if (message != null) {
            String lower = message.toLowerCase();
            if (lower.contains("too many concurrent streams")
                    || lower.contains("goaway")
                    || lower.contains("http_429")
                    || lower.contains("status code 429")
                    || lower.contains("rst_stream")
                    || lower.contains("stream not processed")
                    || lower.contains("connection reset")
                    || lower.contains("request timed out")
                    || lower.contains("buffer_underflow")
                    || lower.contains("eof reached")) {
                return true;
            }
        }
        return isBenignHttpException(throwable.getCause());
    }
    
    private void waitForPagesToDrain(long timeoutMs) throws InterruptedException {
        if (pageQueue == null) {
            return;
        }
        long startTime = System.currentTimeMillis();
        long lastLogTime = 0;
        while (running.get() && (System.currentTimeMillis() - startTime) < timeoutMs) {
            int queueSize = pageQueue.size();
            int activeWorkers = activePageWorkers.get();
            if (queueSize == 0 && activeWorkers == 0) {
                return;
            }
            
            // Логируем каждые 5 секунд, если еще ждем
            long now = System.currentTimeMillis();
            if (now - lastLogTime > 5_000) {
                log.debug("Waiting for pages to drain: queue={}, activeWorkers={}, waited={}s", 
                        queueSize, activeWorkers, (now - startTime) / 1000);
                lastLogTime = now;
            }
            
            Thread.sleep(100);
        }
        
        // Если таймаут истек, логируем предупреждение
        if (System.currentTimeMillis() - startTime >= timeoutMs) {
            log.warn("Timeout waiting for pages to drain: queue={}, activeWorkers={}, timeout={}s", 
                    pageQueue.size(), activePageWorkers.get(), timeoutMs / 1000);
        }
    }
    
    private String pageKey(CategoryTask category, int page) {
        return category.categoryUrl() + "#" + page;
    }

    private void processProducts(CategoryTask categoryTask, List<Product> products) {
        long start = System.nanoTime();
        int processedCount = 0;
        try {
            if (products == null || products.isEmpty()) {
                return;
            }
            long now = Instant.now().toEpochMilli();
            for (Product product : products) {
                if (product == null || product.id == null || product.feedbackPoints == null) {
                    continue;
                }
                processedCount++;
                if (isSupplierBlocked(product)) {
                    continue;
                }
                double cashback = Double.parseDouble(product.feedbackPoints);
                double price = extractPrice(product);
                if (price <= 0) {
                    continue;
                }
                double percent = cashback / price;
                long stock = parseLongSafe(product.totalQuantity);
                ProductContext context = new ProductContext(
                        product.id,
                        product.name,
                        categoryTask.categoryUrl(),
                        price,
                        cashback,
                        Long.toString(stock),
                        percent
                );
                performanceMetrics.recordProductEvaluated();
                List<OutgoingMessage> messages = rubliService.evaluate(context);
                if (messages.isEmpty()) {
                    performanceMetrics.recordProductBlockedByCache();
                    log.debug("Product {} blocked by cache (no messages to send)", product.id);
                    continue;
                }
                log.debug("ENQUEUE: article={}, category={}, channels={}, messages={}", 
                        product.id,
                        categoryTask.categoryUrl(),
                        messages.stream().map(m -> m.getChannelType().toString()).toList(),
                        messages.size());
                int channelMask = buildMask(messages);
                ProductSnapshot snapshot = new ProductSnapshot(
                        Long.parseLong(product.id),
                        product.name,
                        product.supplierId == null ? 0 : product.supplierId,
                        product.supplier,
                        categoryTask.categoryUrl(),
                        now,
                        (long) price,
                        (long) cashback,
                        stock,
                        percent,
                        channelMask
                );
                productsMatched.addAndGet(messages.size());
                performanceMetrics.recordProductEnqueued(messages.size());
                enqueueMessages(attachSnapshot(messages, snapshot));
            }
        } finally {
            performanceMetrics.recordProductProcessing(System.nanoTime() - start, processedCount);
        }
    }

    private List<OutgoingMessage> attachSnapshot(List<OutgoingMessage> messages, ProductSnapshot snapshot) {
        if (messages.isEmpty()) {
            return messages;
        }
        List<OutgoingMessage> enriched = new ArrayList<>(messages.size());
        for (int i = 0; i < messages.size(); i++) {
            OutgoingMessage message = messages.get(i);
            if (i == 0) {
                enriched.add(message.withSnapshot(snapshot));
            } else {
                enriched.add(message);
            }
        }
        return enriched;
    }

    private boolean isSupplierBlocked(Product product) {
        if (product.supplierId != null && supplierBlacklist.contains(String.valueOf(product.supplierId))) {
            return true;
        }
        if (product.supplier != null) {
            return supplierBlacklist.contains(product.supplier.toLowerCase());
        }
        return false;
    }

    private long parseLongSafe(String value) {
        if (value == null || value.isBlank()) {
            return 0L;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private void enqueueMessages(List<OutgoingMessage> messages) {
        for (OutgoingMessage message : messages) {
            BlockingQueue<OutgoingMessage> targetQueue =
                    message.getChannelType() == ChannelType.FREE ? freeQueue : paidQueue;
            if (targetQueue == null) {
                continue;
            }
            try {
                if (!targetQueue.offer(message, 100, TimeUnit.MILLISECONDS)) {
                    log.warn("Queue {} full, dropping article {}", message.getChannelType(), message.getArticle());
                    performanceMetrics.incrementQueueDrop(message.getChannelType());
                } else if (message.getChannelType() == ChannelType.FREE) {
                    log.debug("FREE queue: added article {} with delay {}s", message.getArticle(), message.getDelaySeconds());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private long extractPrice(Product product) {
        if (product.sizes == null) {
            return 0;
        }
        return product.sizes.stream()
                .filter(size -> size.price != null && size.price.product > 0)
                .mapToLong(size -> size.price.product / 100)
                .findFirst()
                .orElse(0);
    }

    private int buildMask(List<OutgoingMessage> messages) {
        int mask = 0;
        for (OutgoingMessage message : messages) {
            mask |= (1 << message.getChannelType().ordinal());
        }
        return mask;
    }

    public static Set<String> loadCategoryFile(String fileName) {
        Set<String> result = new HashSet<>();
        File file = new File(fileName);
        if (!file.exists()) {
            return result;
        }
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String value = line.trim();
                if (!value.isEmpty()) {
                    result.add(value);
                }
            }
        } catch (IOException ignored) {
        }
        return result;
    }

    public static Set<String> loadSupplierBlacklist(String fileName) {
        Set<String> result = new HashSet<>();
        File file = new File(fileName);
        if (!file.exists()) {
            return result;
        }
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String value = line.trim();
                if (!value.isEmpty()) {
                    result.add(value.toLowerCase());
                }
            }
        } catch (IOException ignored) {
        }
        return result;
    }
}


