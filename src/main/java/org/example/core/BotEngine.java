package org.example.core;

import com.google.gson.Gson;
import org.example.config.AppConfig;
import org.example.http.WbHttpClient;
import org.example.jsonmodel.DetailProduct;
import org.example.jsonmodel.DetailResponse;
import org.example.jsonmodel.Product;
import org.example.messaging.OutgoingMessage;
import org.example.messaging.TelegramDispatcher;
import org.example.parser.ProductParser;
import org.example.service.RubliService;
import org.example.service.RubliService.ProductContext;
import org.example.storage.SqliteStorage;
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
    private final SqliteStorage storage;
    private final TelegramDispatcher dispatcher;
    private BlockingQueue<PageTask> pageQueue;
    private final BlockingQueue<OutgoingMessage> outgoingQueue;
    private ExecutorService catalogExecutor;
    private ExecutorService productExecutor;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Set<String> supplierBlacklist;
    private final Map<String, String> sessionCookies;
    private final Set<String> scheduledPageKeys = ConcurrentHashMap.newKeySet();
    private final Set<String> scheduledCategoryKeys = ConcurrentHashMap.newKeySet();
    private final AtomicInteger activePageWorkers = new AtomicInteger();
    private final AtomicLong productsMatched = new AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong pagesProcessed = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong pageErrors = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong categoriesProcessed = new java.util.concurrent.atomic.AtomicLong();

    public BotEngine(AppConfig config,
                     WbHttpClient httpClient,
                     RubliService rubliService,
                     SqliteStorage storage,
                     BlockingQueue<OutgoingMessage> outgoingQueue,
                     TelegramDispatcher dispatcher,
                     Set<String> supplierBlacklist,
                     Map<String, String> sessionCookies) {
        this.config = config;
        this.httpClient = httpClient;
        this.rubliService = rubliService;
        this.storage = storage;
        this.dispatcher = dispatcher;
        this.parser = new ProductParser(new Gson());
        this.outgoingQueue = outgoingQueue == null ? dispatcher.getQueue() : outgoingQueue;
        this.supplierBlacklist = supplierBlacklist;
        this.sessionCookies = sessionCookies == null ? Map.of() : sessionCookies;
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
        
        storage.start();
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
        storage.stop();
        pageQueue = null;
        log.info("BotEngine stopped");
    }

    private void catalogLoop() {
        while (running.get()) {
            try {
                long cycleStartTime = System.currentTimeMillis();
                waitForPagesToDrain();
                scheduledPageKeys.clear();
                scheduledCategoryKeys.clear();

                List<CategoryTask> categories = CategoryTask.load(sessionCookies);
                if (categories.isEmpty()) {
                    log.warn("CategoryTask returned empty list – check cookies / promo JSON availability");
                    TimeUnit.SECONDS.sleep(config.getCatalogRefreshSeconds());
                    continue;
                }

                CountDownLatch latch = new CountDownLatch(categories.size());
                for (CategoryTask category : categories) {
                    catalogExecutor.submit(() -> {
                        try {
                            if (!running.get()) {
                                return;
                            }
                            if (scheduledCategoryKeys.add(category.categoryUrl())) {
                                fetchFirstPage(category);
                            }
                        } finally {
                            latch.countDown();
                        }
                    });
                }
                latch.await();
                waitForPagesToDrain();
                long cycleDuration = System.currentTimeMillis() - cycleStartTime;
                log.info("Cycle completed: categories={}, pages={}, errors={}, products={}, duration={}ms",
                        categoriesProcessed.getAndSet(0),
                        pagesProcessed.getAndSet(0),
                        pageErrors.getAndSet(0),
                        productsMatched.getAndSet(0),
                        cycleDuration);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                log.error("Catalog loop failed", e);
                try {
                    TimeUnit.SECONDS.sleep(5);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    private void fetchFirstPage(CategoryTask category) {
        try {
            if (!scheduledPageKeys.add(pageKey(category, 1))) {
                return;
            }
            String url = category.buildPageUrl(1);
            HttpResponse<String> response = httpClient.get(url, category.defaultHeaders());
            if (response.statusCode() != 200) {
                log.warn("Category {} first page responded with status {}", category.categoryUrl(), response.statusCode());
                pageErrors.incrementAndGet();
                return;
            }
            pagesProcessed.incrementAndGet();
            ProductParser.CatalogPage catalogPage = parser.parseCatalog(response.body());
            processProducts(category, catalogPage.getProducts());
            categoriesProcessed.incrementAndGet();
            int totalProducts = catalogPage.getTotalProducts();
            int totalPages = Math.min(config.getMaxPagesPerCategory(), Math.max(1, (int) Math.ceil(totalProducts / 100.0)));
            if (pageQueue != null && running.get()) {
                for (int page = 2; page <= totalPages; page++) {
                    if (scheduledPageKeys.add(pageKey(category, page))) {
                        pageQueue.offer(new PageTask(category, page));
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            // Нормальное завершение потока при остановке - не логируем
        } catch (Exception e) {
            if (isBenignHttpException(e)) {
                log.trace("Benign HTTP issue on first page {}: {}", category.categoryUrl(), e.getMessage());
            } else {
                pageErrors.incrementAndGet();
            }
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
                try {
                    String url = task.category().buildPageUrl(task.page());
                    HttpResponse<String> response = httpClient.get(url, task.category().defaultHeaders());
                    if (response.statusCode() != 200) {
                        pageErrors.incrementAndGet();
                        continue;
                    }
                    ProductParser.CatalogPage catalogPage = parser.parseCatalog(response.body());
                    pagesProcessed.incrementAndGet();
                    processProducts(task.category(), catalogPage.getProducts());
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
                    || lower.contains("request timed out")) {
                return true;
            }
        }
        return isBenignHttpException(throwable.getCause());
    }
    
    private void waitForPagesToDrain() throws InterruptedException {
        if (pageQueue == null) {
            return;
        }
        while (running.get()) {
            if (pageQueue.isEmpty() && activePageWorkers.get() == 0) {
                return;
            }
            Thread.sleep(200);
        }
    }
    
    private String pageKey(CategoryTask category, int page) {
        return category.categoryUrl() + "#" + page;
    }

    private void processProducts(CategoryTask categoryTask, List<Product> products) {
        if (products == null || products.isEmpty()) {
            return;
        }
        long now = Instant.now().toEpochMilli();
        for (Product product : products) {
            if (product == null || product.id == null || product.feedbackPoints == null) {
                continue;
            }
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
            List<OutgoingMessage> messages = rubliService.evaluate(context);
            if (messages.isEmpty()) {
                continue;
            }
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
            enqueueMessages(attachSnapshot(messages, snapshot));
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
            try {
                outgoingQueue.put(message);
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
    
    /**
     * Проверяет товар по детальной карточке и обновляет БД.
     * @return true если товар был обновлен или удален, false если без изменений
     */
    public boolean checkAndUpdateProduct(long nmId, double oldPercent) {
        try {
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
                return false;
            }
            
            Gson gson = new Gson();
            DetailResponse detailResponse = gson.fromJson(response.body(), DetailResponse.class);
            if (detailResponse == null || detailResponse.products == null || detailResponse.products.isEmpty()) {
                storage.deleteProduct(nmId);
                return true;
            }
            
            DetailProduct product = detailResponse.products.get(0);
            if (product.sizes == null || product.sizes.isEmpty() || product.sizes.get(0).price == null) {
                storage.deleteProduct(nmId);
                return true;
            }
            
            DetailProduct.DetailPrice price = product.sizes.get(0).price;
            long currentPrice = price.product != null ? price.product / 100 : 0;
            long currentFeedback = product.feedbackPoints != null ? Long.parseLong(product.feedbackPoints) : 0;
            
            if (currentPrice <= 0 || currentFeedback <= 0) {
                storage.deleteProduct(nmId);
                return true;
            }
            
            double currentPercent = (double) currentFeedback / currentPrice;
            double roundedCurrentPercent = Math.round(currentPercent * 10000.0) / 10000.0;
            double roundedOldPercent = Math.round(oldPercent * 10000.0) / 10000.0;
            
            // Если процент изменился более чем на 0.15 (15%), отправляем в группу
            if (Math.abs(roundedCurrentPercent - roundedOldPercent) > 0.15) {
                log.info("Product {} percent changed from {} to {}, sending to channels", 
                        nmId, roundedOldPercent, roundedCurrentPercent);
                
                // Создаем контекст для отправки
                ProductContext context = new ProductContext(
                        String.valueOf(nmId),
                        product.name != null ? product.name : "Unknown",
                        "", // categoryUrl неизвестен
                        currentPrice,
                        currentFeedback,
                        "0", // stock неизвестен
                        roundedCurrentPercent
                );
                
                List<OutgoingMessage> messages = rubliService.evaluate(context);
                if (!messages.isEmpty()) {
                    long now = Instant.now().toEpochMilli();
                    int channelMask = buildMask(messages);
                    ProductSnapshot snapshot = new ProductSnapshot(
                            nmId,
                            product.name != null ? product.name : "Unknown",
                            0, // supplierId неизвестен
                            null, // supplierName неизвестен
                            "", // categoryUrl неизвестен
                            now,
                            currentPrice,
                            currentFeedback,
                            0, // stock неизвестен
                            roundedCurrentPercent,
                            channelMask
                    );
                    enqueueMessages(attachSnapshot(messages, snapshot));
                    return true;
                }
            }
            
            return false;
        } catch (Exception e) {
            log.warn("Failed to check product {}: {}", nmId, e.getMessage());
            return false;
        }
    }
}


