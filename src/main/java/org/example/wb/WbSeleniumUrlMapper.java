package org.example.wb;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Многопоточный парсер для сопоставления URL категорий WB с JSON URL через Selenium
 * Автоматически загружает категории из Wildberries и записывает результаты в файл
 * Формат файла: ключ (URL категории) = значение (JSON URL)
 */
public class WbSeleniumUrlMapper {
    private static final Logger log = LoggerFactory.getLogger(WbSeleniumUrlMapper.class);
    
    private final List<String[]> categories; // [url, shard, query, name]
    private final String outputFile;
    private final String errorFile; // Файл для ссылок с ошибками
    private final int threadCount;
    private final WbUrlBuilder urlBuilder;
    private volatile boolean stopped = false;
    private final boolean readingFromErrorFile; // Флаг: читаем ли из файла ошибок
    
    // Синхронизированная запись в файл
    private final Object fileLock = new Object();
    private final Object errorFileLock = new Object();
    
    // ThreadLocal для переиспользования парсеров в потоках
    private final ThreadLocal<WbSeleniumParser> parserThreadLocal = new ThreadLocal<>();
    
    // Статистика обработки (сохраняется после завершения start())
    private volatile int successCount = 0;
    private volatile int errorCount = 0;
    
    /**
     * Создает маппер с автоматической загрузкой категорий из Wildberries
     * @param outputFile путь к выходному файлу
     * @param threadCount количество потоков для парсинга
     */
    public WbSeleniumUrlMapper(String outputFile, int threadCount) {
        this.outputFile = outputFile;
        // Создаем имя файла для ошибок на основе основного файла
        this.errorFile = outputFile.replace(".txt", "_errors.txt");
        this.threadCount = threadCount;
        this.urlBuilder = new WbUrlBuilder();
        this.readingFromErrorFile = false;
        // Автоматически загружаем категории из Wildberries
        this.categories = loadCategoriesFromWB();
    }
    
    /**
     * Создает маппер для обработки ссылок из файла ошибок
     * @param inputFile путь к файлу с ошибками для чтения
     * @param outputFile путь к выходному файлу (для записи успешных результатов)
     * @param threadCount количество потоков для парсинга
     */
    public WbSeleniumUrlMapper(String inputFile, String outputFile, int threadCount) {
        // Читаем из wb_url_mapping_errors.txt
        // Успешные результаты дописываем в КОНЕЦ wb_url_mapping.txt
        // Новые ошибки пишем в wb_url_mapping_errors.txt (заменяя старые)
        this.outputFile = outputFile; // "wb_url_mapping.txt" - дописываем в конец
        this.errorFile = "wb_url_mapping_errors.txt"; // Всегда используем этот файл для ошибок
        this.threadCount = threadCount;
        this.urlBuilder = new WbUrlBuilder();
        this.readingFromErrorFile = true; // Указываем, что читаем из файла
        
        // Загружаем категории из файла (может быть формат url№jsonUrl или просто url)
        this.categories = loadCategoriesFromFile(inputFile);
    }
    
    /**
     * Создает маппер с предзагруженным списком категорий
     * @param categories список категорий [url, shard, query, name]
     * @param outputFile путь к выходному файлу
     * @param threadCount количество потоков для парсинга
     */
    public WbSeleniumUrlMapper(List<String[]> categories, String outputFile, int threadCount) {
        this.categories = categories;
        this.outputFile = outputFile;
        // Создаем имя файла для ошибок на основе основного файла
        this.errorFile = outputFile.replace(".txt", "_errors.txt");
        this.threadCount = threadCount;
        this.urlBuilder = new WbUrlBuilder();
        this.readingFromErrorFile = false;
    }
    
    /**
     * Загружает список категорий автоматически из Wildberries
     */
    private List<String[]> loadCategoriesFromWB() {
        log.info("Loading categories from Wildberries...");
        List<String[]> urls = new java.util.ArrayList<>();
        
        try {
            // Загружаем cookies
            java.util.Set<java.net.HttpCookie> cookies = loadCookies();
            
            // Загружаем JSON с категориями
            org.jsoup.Connection connection = org.jsoup.Jsoup.connect(
                    "https://static-basket-01.wbbasket.ru/vol0/data/main-menu-ru-ru-v3.json")
                    .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:132.0) Gecko/20100101 Firefox/132.0")
                    .method(org.jsoup.Connection.Method.GET)
                    .ignoreContentType(true);
            
            // Добавляем cookies если есть
            for (java.net.HttpCookie cookie : cookies) {
                connection.cookie(cookie.getName(), cookie.getValue());
            }
            
            String json = connection.execute().body();
            
            com.google.gson.Gson gson = new com.google.gson.Gson();
            org.example.jsonmodel.UrlFetcher.RootItem[] items = gson.fromJson(json, 
                    org.example.jsonmodel.UrlFetcher.RootItem[].class);
            
            // Рекурсивно обрабатываем все категории
            for (org.example.jsonmodel.UrlFetcher.RootItem item : items) {
                processChildGson(item, urls);
            }
            
            log.info("Successfully loaded {} categories from Wildberries", urls.size());
            
        } catch (Exception e) {
            log.error("Failed to load categories from Wildberries", e);
        }
        
        return urls;
    }
    
    /**
     * Загружает категории из файла (может быть формат url№jsonUrl или просто url)
     * @param inputFile путь к входному файлу
     * @return список категорий в формате [url, shard, query, name]
     */
    private List<String[]> loadCategoriesFromFile(String inputFile) {
        log.info("Loading categories from file: {}", inputFile);
        List<String[]> urls = new java.util.ArrayList<>();
        
        try {
            Path path = Paths.get(inputFile);
            if (!Files.exists(path)) {
                log.error("Input file not found: {} (absolute path: {})", inputFile, path.toAbsolutePath());
                return urls;
            }
            
            List<String> lines = Files.readAllLines(path);
            for (String line : lines) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue; // Пропускаем пустые строки и комментарии
                }
                
                // Файл может содержать формат: url№jsonUrl или просто url
                // Нам нужен только URL категории (первая часть до № или вся строка)
                String categoryUrl = line;
                if (line.contains("№")) {
                    categoryUrl = line.substring(0, line.indexOf("№"));
                }
                
                // Остальные поля будут пустыми, т.к. при поиске JSON URL через Selenium они не нужны
                urls.add(new String[]{categoryUrl, "", "", ""});
            }
            
            log.info("Successfully loaded {} categories from file", urls.size());
            
        } catch (Exception e) {
            log.error("Failed to load categories from file: {}", inputFile, e);
        }
        
        return urls;
    }
    
    /**
     * Загружает cookies с сайта Wildberries
     */
    private java.util.Set<java.net.HttpCookie> loadCookies() {
        try {
            java.net.CookieManager cookieManager = new java.net.CookieManager();
            cookieManager.setCookiePolicy(java.net.CookiePolicy.ACCEPT_ALL);
            
            java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                    .cookieHandler(cookieManager)
                    .build();
            
            String urlWb = "https://www.wildberries.ru/";
            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(urlWb))
                    .GET()
                    .build();
            
            client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
            
            return new java.util.HashSet<>(cookieManager.getCookieStore().getCookies());
        } catch (Exception e) {
            log.debug("Failed to load cookies: {}", e.getMessage());
            return new java.util.HashSet<>();
        }
    }
    
    /**
     * Рекурсивно обрабатывает элементы меню
     */
    private void processChildGson(org.example.jsonmodel.UrlFetcher.RootItem item, List<String[]> urls) {
        addUrlIfValid(item.url, item.shard, item.query, item.name, urls);
        
        if (item.children != null) {
            for (org.example.jsonmodel.UrlFetcher.Child child : item.children) {
                processChildGson(child, urls);
            }
        }
    }
    
    /**
     * Рекурсивно обрабатывает дочерние элементы
     */
    private void processChildGson(org.example.jsonmodel.UrlFetcher.Child child, List<String[]> urls) {
        // Используем searchQuery если есть, иначе name
        String categoryName = (child.searchQuery != null && !child.searchQuery.isEmpty()) 
                ? child.searchQuery 
                : (child.name != null ? child.name : "");
        addUrlIfValid(child.url, child.shard, child.query, categoryName, urls);
        
        if (child.children != null) {
            for (org.example.jsonmodel.UrlFetcher.Child nested : child.children) {
                processChildGson(nested, urls);
            }
        }
    }
    
    /**
     * Добавляет URL если он валиден
     */
    private void addUrlIfValid(String url, String shard, String query, String name, List<String[]> urls) {
        if (url == null || url.isEmpty()) return;
        if (url.startsWith("https://vmeste.wildberries.ru")
                || url.startsWith("https://travel.wildberries.ru")
                || url.startsWith("https://digital.wildberries.ru")) return;
        if (shard == null || shard.isEmpty()) return;
        
        if (query == null) query = "";
        if (name == null) name = "";
        
        url += "?sort=popular&page=1&ffeedbackpoints=1";
        url = urlBuilder.ensurePrefix(url);
        
        String finalUrl = url;
        boolean exists = urls.stream().anyMatch(u -> u[0].equals(finalUrl));
        if (!exists) {
            // Массив: [url, shard, query, name]
            urls.add(new String[]{url, shard, query, name});
        }
    }
    
    /**
     * Запускает многопоточный парсинг
     */
    public void start() {
        log.info("Starting Selenium URL mapping: {} categories, {} threads, output: {}", 
                categories.size(), threadCount, outputFile);
        
        // Создаем или очищаем файлы
        try {
            // Основной файл
            Path path = Paths.get(outputFile);
            if (readingFromErrorFile) {
                // Если читаем из файла ошибок, не удаляем основной файл - будем дописывать в конец
                if (!Files.exists(path)) {
                    Files.createFile(path);
                }
                log.info("Output file: {} (will append to existing file)", outputFile);
            } else {
                // Обычный режим - создаем новый файл
                if (Files.exists(path)) {
                    Files.delete(path);
                }
                Files.createFile(path);
                log.info("Output file: {} (new file)", outputFile);
            }
            
            // Файл для ошибок
            Path errorPath = Paths.get(errorFile);
            if (readingFromErrorFile) {
                // Если читаем из файла ошибок, очищаем файл ошибок для записи новых ошибок
                // Старые ошибки уже обработаны, новые будут записаны сюда
                if (Files.exists(errorPath)) {
                    Files.delete(errorPath);
                }
                Files.createFile(errorPath);
                log.info("Error file: {} (cleared, will contain only new errors from this iteration)", errorFile);
            } else {
                // Обычный режим - создаем новый файл ошибок
                if (Files.exists(errorPath)) {
                    Files.delete(errorPath);
                }
                Files.createFile(errorPath);
                log.info("Error file: {} (new file)", errorFile);
            }
            
        } catch (IOException e) {
            log.error("Failed to create output files: {}", outputFile, e);
            return;
        }
        
        // Создаем пул потоков
        ExecutorService executor = Executors.newFixedThreadPool(threadCount, r -> {
            Thread t = new Thread(r, "selenium-mapper-" + System.nanoTime());
            t.setDaemon(true);
            return t;
        });
        
        AtomicInteger processed = new AtomicInteger(0);
        AtomicInteger success = new AtomicInteger(0);
        AtomicInteger errors = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(categories.size());
        
        long startTime = System.currentTimeMillis();
        
        // Запускаем задачи для каждой категории
        for (String[] category : categories) {
            if (stopped) break;
            
            executor.submit(() -> {
                try {
                    processCategory(category, processed, success, errors);
                } catch (Exception e) {
                    errors.incrementAndGet();
                    processed.incrementAndGet();
                    log.debug("Error processing category: {}", category[0], e);
                } finally {
                    latch.countDown();
                    // Периодически выводим прогресс
                    int p = processed.get();
                    if (p % 50 == 0 || p == categories.size()) {
                        log.info("Progress: {}/{} processed, {} success, {} errors", 
                                p, categories.size(), success.get(), errors.get());
                    }
                }
            });
        }
        
        // Запускаем задачу для периодического вывода прогресса
        ScheduledExecutorService progressScheduler = Executors.newScheduledThreadPool(1);
        progressScheduler.scheduleAtFixedRate(() -> {
            int p = processed.get();
            int s = success.get();
            int e = errors.get();
            if (p > 0) {
                log.info("Progress: {}/{} processed ({}%), {} success, {} errors", 
                        p, categories.size(), (p * 100 / categories.size()), s, e);
            }
        }, 30, 30, TimeUnit.SECONDS);
        
        // Ждем завершения всех задач
        try {
            latch.await(30, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while waiting for tasks completion");
        } finally {
            progressScheduler.shutdown();
        }
        
        executor.shutdown();
        try {
            if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        
        // Закрываем все парсеры из всех потоков
        // Примечание: ThreadLocal парсеры будут закрыты автоматически при завершении потоков
        // Но мы можем попробовать закрыть их явно, если они еще активны
        cleanupParsers();
        
        long duration = System.currentTimeMillis() - startTime;
        
        // Сохраняем финальную статистику
        this.successCount = success.get();
        this.errorCount = errors.get();
        
        log.info("Mapping completed: processed={}, success={}, errors={}, duration={}ms", 
                processed.get(), success.get(), errors.get(), duration);
        log.info("Results saved to: {}", outputFile);
        log.info("Errors saved to: {}", errorFile);
    }
    
    /**
     * Обрабатывает одну категорию: заходит на страницу и ищет JSON URL через Selenium
     * Если переданный URL уже является JSON endpoint, использует его напрямую
     */
    private void processCategory(String[] category, AtomicInteger processed, 
                                 AtomicInteger success, AtomicInteger errors) {
        String categoryUrl = category[0];
        
        // Проверяем, является ли переданный URL уже JSON endpoint
        // Если да, используем его напрямую без поиска через Selenium
        if (isJsonEndpoint(categoryUrl)) {
            // Проверяем, что URL валидный (возвращает JSON)
            if (checkJsonUrlViaHttp(categoryUrl)) {
                // URL уже является валидным JSON endpoint - записываем его
                // В этом случае categoryUrl и jsonUrl одинаковые
                writeToFile(categoryUrl, categoryUrl);
                success.incrementAndGet();
                if (success.get() % 10 == 0) {
                    log.info("Progress: {} success, {} errors", success.get(), errors.get());
                }
            } else {
                // URL имеет формат JSON endpoint, но не возвращает валидный JSON
                errors.incrementAndGet();
                writeErrorToFile(categoryUrl);
                if (errors.get() % 50 == 0) {
                    log.warn("JSON endpoint URL is invalid (error #{}): {} ...", errors.get(), categoryUrl);
                }
            }
            processed.incrementAndGet();
            return;
        }
        
        // Префикс JSON URL для поиска (может быть неполным, поиск будет гибким)
        // Поддерживаем несколько форматов: catalog, search/exactmatch, u-search/exactmatch
        // Префикс используется только для приоритизации, не для обязательного совпадения
        String jsonUrlPrefix = "https://www.wildberries.ru/__internal/";
        
        // Используем Selenium для поиска JSON URL на странице категории
        WbSeleniumParser parser = getParserForThread();
        
        try {
            String jsonUrl = parser.findJsonUrlOnPage(categoryUrl, jsonUrlPrefix);
            
            // Если Selenium не нашел JSON URL, пробуем через HTTP (fallback)
            if ((jsonUrl == null || jsonUrl.isEmpty()) && !isJsonEndpoint(categoryUrl)) {
                jsonUrl = findJsonUrlViaHttp(categoryUrl);
            }
            
            if (jsonUrl != null && !jsonUrl.isEmpty()) {
                // Нашли JSON URL - записываем в файл
                writeToFile(categoryUrl, jsonUrl);
                success.incrementAndGet();
                // Логируем только каждую 10-ю успешную операцию для производительности
                if (success.get() % 10 == 0) {
                    log.info("Progress: {} success, {} errors", success.get(), errors.get());
                }
            } else {
                // Не нашли JSON URL - записываем в файл ошибок
                errors.incrementAndGet();
                writeErrorToFile(categoryUrl);
                // Логируем только каждую 50-ю ошибку для производительности
                if (errors.get() % 50 == 0) {
                    log.warn("JSON URL not found (error #{}): {} ...", errors.get(), categoryUrl);
                }
            }
            
        } catch (Exception e) {
            errors.incrementAndGet();
            // Записываем в файл ошибок при исключении
            writeErrorToFile(categoryUrl);
            log.debug("Error processing category {}: {}", categoryUrl, e.getMessage());
        }
        
        processed.incrementAndGet();
    }
    
    /**
     * Проверяет, является ли URL уже JSON endpoint
     * @param url URL для проверки
     * @return true если URL содержит паттерны JSON endpoint
     */
    private boolean isJsonEndpoint(String url) {
        if (url == null || url.isEmpty()) {
            return false;
        }
        // Проверяем, содержит ли URL паттерны JSON endpoint
        return url.contains("__internal/catalog/catalog") ||
               url.contains("__internal/search/exactmatch") ||
               url.contains("__internal/u-search/exactmatch");
    }
    
    /**
     * Пытается найти JSON URL через HTTP запрос (fallback метод, если Selenium не нашел)
     * @param categoryUrl URL категории
     * @return найденный JSON URL или null
     */
    private String findJsonUrlViaHttp(String categoryUrl) {
        try {
            // Загружаем страницу через HTTP
            java.util.Set<java.net.HttpCookie> cookies = loadCookies();
            org.jsoup.Connection connection = org.jsoup.Jsoup.connect(categoryUrl)
                    .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:132.0) Gecko/20100101 Firefox/132.0")
                    .method(org.jsoup.Connection.Method.GET)
                    .timeout(10000)
                    .ignoreContentType(false);
            
            // Добавляем cookies если есть
            for (java.net.HttpCookie cookie : cookies) {
                connection.cookie(cookie.getName(), cookie.getValue());
            }
            
            org.jsoup.nodes.Document doc = connection.get();
            String html = doc.html();
            
            // Ищем JSON URL в HTML
            String[] patterns = {
                "__internal/catalog/catalog",
                "__internal/search/exactmatch",
                "__internal/u-search/exactmatch"
            };
            
            for (String pattern : patterns) {
                // Ищем URL с паттерном
                java.util.regex.Pattern urlPattern = java.util.regex.Pattern.compile(
                    "https?:\\/\\/[^\"'\\s<>]*" + java.util.regex.Pattern.quote(pattern) + "[^\"'\\s<>]*"
                );
                java.util.regex.Matcher matcher = urlPattern.matcher(html);
                
                if (matcher.find()) {
                    String foundUrl = matcher.group(0);
                    // Убираем возможные завершающие символы
                    foundUrl = foundUrl.replaceAll("[\\\"'<>\\s]+$", "");
                    
                    // Проверяем, что это валидный JSON URL
                    if (checkJsonUrlViaHttp(foundUrl)) {
                        log.debug("Found JSON URL via HTTP fallback: {}", foundUrl);
                        return foundUrl;
                    }
                }
            }
            
        } catch (Exception e) {
            log.debug("Error finding JSON URL via HTTP for {}: {}", categoryUrl, e.getMessage());
        }
        
        return null;
    }
    
    /**
     * Быстрая проверка JSON URL через HTTP запрос (без Selenium)
     * @param jsonUrl URL для проверки
     * @return true если URL возвращает валидный JSON (даже если товаров нет)
     */
    private boolean checkJsonUrlViaHttp(String jsonUrl) {
        try {
            org.jsoup.Connection connection = org.jsoup.Jsoup.connect(jsonUrl)
                    .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:132.0) Gecko/20100101 Firefox/132.0")
                    .method(org.jsoup.Connection.Method.GET)
                    .ignoreContentType(true)
                    .timeout(10000); // Увеличил таймаут до 10 секунд
            
            // Добавляем cookies если есть
            java.util.Set<java.net.HttpCookie> cookies = loadCookies();
            for (java.net.HttpCookie cookie : cookies) {
                connection.cookie(cookie.getName(), cookie.getValue());
            }
            
            org.jsoup.Connection.Response response = connection.execute();
            int statusCode = response.statusCode();
            
            // Проверяем статус код
            if (statusCode != 200) {
                log.debug("HTTP status {} for URL: {}", statusCode, jsonUrl);
                return false;
            }
            
            String jsonBody = response.body();
            
            if (jsonBody == null || jsonBody.isEmpty()) {
                log.debug("Empty response body for URL: {}", jsonUrl);
                return false;
            }
            
            // Пробуем распарсить JSON - пробуем оба формата (новый и старый)
            com.google.gson.Gson gson = new com.google.gson.Gson();
            
            // Пробуем новый формат: SearchRoot (products[], total)
            try {
                org.example.jsonmodel.SearchRoot searchRoot = gson.fromJson(jsonBody, org.example.jsonmodel.SearchRoot.class);
                if (searchRoot != null) {
                    // Новый формат распарсился - это успех
                    return true;
                }
            } catch (Exception e) {
                // Не новый формат, пробуем старый
            }
            
            // Пробуем старый формат: Root (data.products[], data.total)
            try {
                org.example.jsonmodel.Root root = gson.fromJson(jsonBody, org.example.jsonmodel.Root.class);
                if (root != null && root.data != null) {
                    // Старый формат распарсился - это успех
                    return true;
                }
            } catch (Exception e) {
                // Не старый формат тоже
            }
            
            // Если оба формата не подошли, проверяем что это хотя бы валидный JSON объект
            try {
                com.google.gson.JsonObject jsonObject = com.google.gson.JsonParser.parseString(jsonBody).getAsJsonObject();
                // Если это валидный JSON объект - считаем успехом
                return jsonObject != null;
            } catch (Exception e) {
                // Не валидный JSON
                return false;
            }
            
        } catch (com.google.gson.JsonSyntaxException e) {
            // Невалидный JSON
            log.debug("Invalid JSON for URL {}: {}", jsonUrl, e.getMessage());
            return false;
        } catch (java.net.SocketTimeoutException e) {
            // Таймаут
            log.debug("Timeout for URL: {}", jsonUrl);
            return false;
        } catch (Exception e) {
            // Другие ошибки
            log.debug("HTTP error for URL {}: {}", jsonUrl, e.getMessage());
            return false;
        }
    }
    
    /**
     * Записывает пару ключ-значение в файл (синхронизированно, всегда в конец файла)
     */
    private void writeToFile(String categoryUrl, String jsonUrl) {
        synchronized (fileLock) {
            try (BufferedWriter writer = new BufferedWriter(
                    new FileWriter(outputFile, true))) { // true = append mode
                writer.write(categoryUrl + "№" + jsonUrl);
                writer.newLine();
                writer.flush();
            } catch (IOException e) {
                log.error("Failed to write to file: {}", outputFile, e);
            }
        }
    }
    
    /**
     * Записывает URL с ошибкой в отдельный файл (синхронизированно)
     */
    private void writeErrorToFile(String categoryUrl) {
        synchronized (errorFileLock) {
            try (BufferedWriter writer = new BufferedWriter(
                    new FileWriter(errorFile, true))) {
                writer.write(categoryUrl);
                writer.newLine();
                writer.flush();
            } catch (IOException e) {
                log.error("Failed to write to error file: {}", errorFile, e);
            }
        }
    }
    
    /**
     * Останавливает парсинг
     */
    public void stop() {
        stopped = true;
    }
    
    /**
     * Проверяет, остановлен ли парсинг
     */
    public boolean isStopped() {
        return stopped;
    }
    
    /**
     * Возвращает количество загруженных категорий
     */
    public int getCategoriesCount() {
        return categories != null ? categories.size() : 0;
    }
    
    /**
     * Возвращает количество успешно обработанных URL
     */
    public int getSuccessCount() {
        return successCount;
    }
    
    /**
     * Возвращает количество URL с ошибками
     */
    public int getErrorCount() {
        return errorCount;
    }
    
    /**
     * Получает парсер для текущего потока (переиспользует существующий)
     */
    private WbSeleniumParser getParserForThread() {
        WbSeleniumParser parser = parserThreadLocal.get();
        if (parser == null) {
            parser = new WbSeleniumParser();
            parserThreadLocal.set(parser);
        }
        return parser;
    }
    
    /**
     * Очищает ThreadLocal парсеров (вызывается при завершении)
     * Примечание: ThreadLocal автоматически очистится при завершении потоков
     * Парсеры будут закрыты автоматически при завершении работы потоков
     */
    private void cleanupParsers() {
        // ThreadLocal автоматически очистится при завершении потоков
        // Явное удаление здесь не нужно, но можно оставить для ясности
        try {
            parserThreadLocal.remove();
        } catch (Exception e) {
            log.debug("Error cleaning up parsers", e);
        }
    }
}

