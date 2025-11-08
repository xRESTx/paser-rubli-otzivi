package org.example;

import com.google.gson.Gson;
import org.example.jsonmodel.Root;
import org.example.jsonmodel.SearchResponse;
import org.example.jsonmodel.UrlFetcher;
import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.net.HttpCookie;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Генератор URL для API поиска на основе JSON каталога из Wildberries
 */
public class SearchUrlGenerator {
    
    private static final Logger log = LoggerFactory.getLogger(SearchUrlGenerator.class);
    private static final String CATALOG_JSON_URL = 
        "https://static-basket-01.wbbasket.ru/vol0/data/main-menu-ru-ru-v3.json";
    // Поля для хранения authorization токена и userid (могут быть установлены из командной строки или файла)
    private static String globalAuthorizationToken = null;
    private static String globalUserId = null;
    // Флаг для отслеживания, было ли уже выведено предупреждение о недостающих данных
    private static boolean warningShown = false;
    
    private final Map<Integer, UrlBuilder.QueryType> queryTypeMapping;
    private final List<SearchUrlInfo> generatedUrls;
    private final Map<UrlBuilder.QueryType, List<String>> queryTypeExamples;
    // Маппинг: catalogUrl (с параметрами) -> список API URL
    private final Map<String, List<String>> catalogToApiUrlMapping;
    // Статистика использования dest (для диагностики)
    private final Map<String, Integer> destUsageStats = new HashMap<>();
    
    public SearchUrlGenerator() {
        this.queryTypeMapping = new HashMap<>();
        this.generatedUrls = new ArrayList<>();
        this.queryTypeExamples = new HashMap<>();
        this.catalogToApiUrlMapping = new LinkedHashMap<>();
    }
    
    /**
     * Получает JSON каталога из API и генерирует URL для всех категорий
     */
    public void generateUrlsFromCatalogApi(Set<HttpCookie> cookies) throws IOException {
        log.info("Fetching catalog JSON from: {}", CATALOG_JSON_URL);
        
        Connection connection = Jsoup.connect(CATALOG_JSON_URL)
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:132.0) Gecko/20100101 Firefox/132.0")
                .method(Connection.Method.GET)
                .ignoreContentType(true)
                .timeout(30_000);
        
        // Добавляем cookies, если есть
        if (cookies != null) {
            for (HttpCookie cookie : cookies) {
                connection.cookie(cookie.getName(), cookie.getValue());
            }
        }
        
        String json = connection.execute().body();
        log.info("Received catalog JSON, size: {} bytes", json.length());
        
        // Парсим JSON
        Gson gson = new Gson();
        UrlFetcher.RootItem[] items = gson.fromJson(json, UrlFetcher.RootItem[].class);
        
        log.info("Found {} root categories", items.length);
        
        // Сбрасываем статистику dest перед генерацией
        destUsageStats.clear();
        
        // Обрабатываем все категории
        for (UrlFetcher.RootItem item : items) {
            processRootItem(item);
        }
        
        // Выводим статистику использования dest
        System.out.println("\n=== Dest Usage Statistics ===");
        for (Map.Entry<String, Integer> entry : destUsageStats.entrySet()) {
            System.out.println(String.format("  %s: %d categories", entry.getKey(), entry.getValue()));
        }
        
        // Статистика по типам query
        Map<UrlBuilder.QueryType, Integer> queryTypeStats = new HashMap<>();
        for (SearchUrlInfo urlInfo : generatedUrls) {
            queryTypeStats.put(urlInfo.queryType, 
                queryTypeStats.getOrDefault(urlInfo.queryType, 0) + 1);
        }
        
        log.info("Generated URLs for {} categories", generatedUrls.size());
        log.info("Query type distribution: {}", queryTypeStats);


        for (Map.Entry<UrlBuilder.QueryType, List<String>> entry : queryTypeExamples.entrySet()) {
            for (String example : entry.getValue()) {
                System.out.println("  " + example);
            }
        }
        
        // Статистика по маппингу
        int mappedCount = 0;
        int defaultCount = 0;
        for (SearchUrlInfo urlInfo : generatedUrls) {
            if (queryTypeMapping.containsKey(urlInfo.categoryId)) {
                mappedCount++;
            } else {
                defaultCount++;
            }
        }
    }
    
    /**
     * Обрабатывает корневой элемент каталога
     */
    private void processRootItem(UrlFetcher.RootItem item) {
        if (item == null) return;
        
        // Извлекаем dest из JSON (берем первый элемент массива, если есть)
        int dest = extractDest(item.dest);
        
        // Логируем использование dest по умолчанию для диагностики
        if (item.dest == null || item.dest.length == 0) {
            destUsageStats.put("default_" + dest, destUsageStats.getOrDefault("default_" + dest, 0) + 1);
        } else {
            destUsageStats.put("from_json_" + dest, destUsageStats.getOrDefault("from_json_" + dest, 0) + 1);
        }
        
        // Генерируем URL для текущего элемента, если есть ID и название
        if (item.id > 0 && item.name != null && !item.name.isEmpty()) {
            generateSearchUrls(item.id, item.name, item.url, item.shard, item.query, item.searchQuery, dest);
        }
        
        // Обрабатываем дочерние элементы
        if (item.children != null) {
            for (UrlFetcher.Child child : item.children) {
                processChild(child, dest); // Передаем dest родителя как fallback
            }
        }
    }
    
    /**
     * Рекурсивно обрабатывает дочерний элемент
     * @param child Дочерний элемент для обработки
     * @param parentDest Dest родительского элемента (используется как fallback)
     */
    private void processChild(UrlFetcher.Child child, int parentDest) {
        if (child == null) return;
        
        // Извлекаем dest из JSON (берем первый элемент массива, если есть, иначе используем dest родителя)
        int dest = extractDest(child.dest, parentDest);
        
        // Логируем использование dest для диагностики
        if (child.dest == null || child.dest.length == 0) {
            // Используется dest родителя или значение по умолчанию
            if (dest == parentDest) {
                destUsageStats.put("from_parent_" + dest, destUsageStats.getOrDefault("from_parent_" + dest, 0) + 1);
            } else {
                destUsageStats.put("default_" + dest, destUsageStats.getOrDefault("default_" + dest, 0) + 1);
            }
        } else {
            destUsageStats.put("from_json_" + dest, destUsageStats.getOrDefault("from_json_" + dest, 0) + 1);
        }
        
        // Генерируем URL для текущего элемента, если есть ID и название
        if (child.id > 0 && child.name != null && !child.name.isEmpty()) {
            generateSearchUrls(child.id, child.name, child.url, child.shard, child.query, child.searchQuery, dest);
        }
        
        // Обрабатываем вложенные дочерние элементы
        if (child.children != null) {
            for (UrlFetcher.Child nested : child.children) {
                processChild(nested, dest); // Передаем dest текущего элемента как fallback
            }
        }
    }
    
    /**
     * Извлекает dest из массива (берет первый элемент)
     * @param destArray Массив dest из JSON
     * @return Первый элемент массива или значение по умолчанию -8337854
     */
    private int extractDest(int[] destArray) {
        return extractDest(destArray, -8337854);
    }
    
    /**
     * Извлекает dest из массива (берет первый элемент)
     * @param destArray Массив dest из JSON
     * @param defaultValue Значение по умолчанию, если массив пуст или null
     * @return Первый элемент массива или defaultValue
     */
    private int extractDest(int[] destArray, int defaultValue) {
        if (destArray != null && destArray.length > 0) {
            return destArray[0];
        }
        return defaultValue;
    }
    
    /**
     * Строит URL для search API в правильном формате Wildberries
     * @param queryParam Параметр query (menu_v3_ID название или menu_mined_subject_v2_ID)
     * @param dest Регион доставки
     * @param page Номер страницы
     * @return Полный URL
     */
    private String buildSearchUrl(String queryParam, int dest, int page) {
        return buildSearchUrl(queryParam, dest, page, "catalog");
    }
    
    private String buildSearchUrl(String queryParam, int dest, int page, String resultset) {
        // Убеждаемся, что resultset задан (по умолчанию catalog)
        if (resultset == null || resultset.trim().isEmpty()) {
            resultset = "catalog";
        }
        return buildSearchUrl(queryParam, dest, page, resultset, true);
    }
    
    /**
     * Строит URL для search API
     * @param queryParam Параметр query
     * @param dest Регион доставки
     * @param page Номер страницы
     * @param resultset Тип результата (filters или catalog)
     * @param withFeedbackPointsFilter Использовать ли фильтр ffeedbackpoints=1
     */
    private String buildSearchUrl(String queryParam, int dest, int page, String resultset, boolean withFeedbackPointsFilter) {
        // Убеждаемся, что resultset задан (по умолчанию catalog)
        if (resultset == null || resultset.trim().isEmpty()) {
            resultset = "catalog";
        }
        
        StringBuilder apiUrl = new StringBuilder(
            "https://www.wildberries.ru/__internal/search/exactmatch/ru/common/v18/search?"
        );
        
        apiUrl.append("ab_testing=false&");
        apiUrl.append("ab_testing=false&");
        apiUrl.append("appType=1&");
        apiUrl.append("autoselectFilters=false&");
        apiUrl.append("curr=rub&");
        apiUrl.append("dest=").append(dest).append("&");
        if (withFeedbackPointsFilter) {
            apiUrl.append("ffeedbackpoints=1&");
        }
        apiUrl.append("hide_dtype=11&");
        apiUrl.append("lang=ru&");
        apiUrl.append("mdg=2&");
        // Важно: query должен быть перед resultset, а resultset должен быть перед page
        apiUrl.append("query=").append(java.net.URLEncoder.encode(queryParam, java.nio.charset.StandardCharsets.UTF_8)).append("&");
        apiUrl.append("resultset=").append(resultset).append("&");
        // page добавляется только если resultset=catalog (для filters page не нужен)
        if ("catalog".equals(resultset)) {
            apiUrl.append("page=").append(page).append("&");
        }
        apiUrl.append("sort=popular&");
        apiUrl.append("spp=30&");
        apiUrl.append("suppressSpellcheck=false&");
        apiUrl.append("uclusters=1");
        
        return apiUrl.toString();
    }
    
    /**
     * Строит URL для search API с различными параметрами (старый формат, для тестирования)
     * @param queryParam Параметр query
     * @param dest Регион доставки
     * @param resultset Тип результата (filters или catalog)
     * @param useAbTestId Использовать ab_testid=new_optim вместо ab_testing=false
     * @param useFeedbackPoints Использовать ffeedbackpoints=1
     * @return Полный URL
     */
    private String buildSearchUrlOld(String queryParam, int dest, String resultset, 
                                 boolean useAbTestId, boolean useFeedbackPoints) {
        StringBuilder apiUrl = new StringBuilder(
            "https://www.wildberries.ru/__internal/search/exactmatch/ru/common/v18/search?"
        );
        
        if (useAbTestId) {
            apiUrl.append("ab_testid=new_optim&");
            apiUrl.append("ab_testing=false&");
        } else {
            apiUrl.append("ab_testing=false&");
            apiUrl.append("ab_testing=false&"); // Дублируется в примерах
        }
        
        apiUrl.append("appType=1&");
        apiUrl.append("autoselectFilters=false&");
        apiUrl.append("curr=rub&");
        apiUrl.append("dest=").append(dest).append("&");
        
        if (useFeedbackPoints) {
            apiUrl.append("ffeedbackpoints=1&");
        }
        
        apiUrl.append("hide_dtype=11&");
        apiUrl.append("lang=ru&");
        apiUrl.append("query=").append(java.net.URLEncoder.encode(queryParam, java.nio.charset.StandardCharsets.UTF_8)).append("&");
        apiUrl.append("resultset=").append(resultset).append("&");
        apiUrl.append("spp=30&");
        apiUrl.append("suppressSpellcheck=false");
        
        return apiUrl.toString();
    }
    
    /**
     * Генерирует URL для catalog API (правильный формат Wildberries)
     * Использует searchQuery из JSON если есть, иначе использует query (cat=ID или subject=ID)
     * @param dest Регион доставки из JSON (или значение по умолчанию)
     */
    private void generateSearchUrls(int categoryId, String categoryName, String catalogUrl, 
                                   String shard, String query, String searchQuery, int dest) {
        if (categoryId <= 0 || categoryName == null || categoryName.isEmpty()) {
            return;
        }
        
        // Параметры из правильного формата Wildberries
        int page = 1;
        
        String apiUrl;
        UrlBuilder.QueryType queryType;
        
        // Если есть searchQuery, используем search API с ним
        if (searchQuery != null && !searchQuery.trim().isEmpty()) {
            // Используем searchQuery из JSON (например: "menu_v3_261 коврики в ванную")
            queryType = determineQueryTypeFromSearchQuery(searchQuery);
            
            // Для MENU_REDIRECT_SUBJECT_V2 и MENU_V3 убираем название из searchQuery, если оно есть
            // Тесты показывают, что формат без названия работает лучше
            String finalQuery = searchQuery;
            if (queryType == UrlBuilder.QueryType.MENU_REDIRECT_SUBJECT_V2 || 
                queryType == UrlBuilder.QueryType.MENU_V3) {
                // Если searchQuery содержит пробел после ID, обрезаем до ID
                // Пример: "menu_redirect_subject_v2_8127 название" -> "menu_redirect_subject_v2_8127"
                //         "menu_v3_1234 название" -> "menu_v3_1234"
                String prefix = queryType.getPrefix() + "_";
                if (searchQuery.startsWith(prefix)) {
                    String afterPrefix = searchQuery.substring(prefix.length());
                    int spaceIndex = afterPrefix.indexOf(' ');
                    if (spaceIndex > 0) {
                        // Есть пробел, обрезаем до ID
                        String categoryIdStr = afterPrefix.substring(0, spaceIndex);
                        finalQuery = prefix + categoryIdStr;
                    }
                }
            }
            // Для MENU_MINED_SUBJECT_V2 оставляем как есть (уже работает хорошо)
            
            apiUrl = buildSearchUrl(finalQuery, dest, page);
        } else if (query != null && !query.trim().isEmpty()) {
            // Проверяем, содержит ли query preset=...
            // Если да, то Catalog API с preset часто возвращает 0 товаров, поэтому используем Search API
            if (query.startsWith("preset=")) {
                // Для preset используем Search API вместо Catalog API (Catalog API с preset часто возвращает 0)
                // Используем формат без названия, так как для preset категорий он часто работает лучше
                queryType = UrlBuilder.QueryType.MENU_V3;
                String queryParam = queryType.getPrefix() + "_" + categoryId; // Без названия категории
                apiUrl = buildSearchUrl(queryParam, dest, page);
            } else {
                // Для cat=ID или subject=ID используем catalog API
                queryType = UrlBuilder.QueryType.MENU_V3; // По умолчанию
                apiUrl = buildCatalogUrl(shard, query, dest, page);
            }
        } else {
            // Fallback: используем search API с menu_v3
            queryType = UrlBuilder.QueryType.MENU_V3;
            String queryParam = buildQueryParam(categoryId, categoryName, queryType);
            apiUrl = buildSearchUrl(queryParam, dest, page);
        }
        
        // Формируем catalogUrl с параметрами (если catalogUrl есть)
        String catalogUrlWithParams = null;
        if (catalogUrl != null && !catalogUrl.isEmpty()) {
            String fullCatalogUrl = catalogUrl;
            if (!catalogUrl.startsWith("http")) {
                if (catalogUrl.startsWith("/")) {
                    fullCatalogUrl = "https://www.wildberries.ru" + catalogUrl;
                } else {
                    fullCatalogUrl = "https://www.wildberries.ru/" + catalogUrl;
                }
            }
            
            StringBuilder catalogUrlBuilder = new StringBuilder(fullCatalogUrl);
            if (fullCatalogUrl.contains("?")) {
                catalogUrlBuilder.append("&");
            } else {
                catalogUrlBuilder.append("?");
            }
            catalogUrlBuilder.append("sort=popular&page=1&ffeedbackpoints=1");
            catalogUrlWithParams = catalogUrlBuilder.toString();
            
            catalogToApiUrlMapping.computeIfAbsent(catalogUrlWithParams, k -> new ArrayList<>()).add(apiUrl);
        }
        
        // Логируем примеры query параметров для разных типов (первые 3 каждого типа)
        if (queryTypeExamples.get(queryType) == null) {
            queryTypeExamples.put(queryType, new ArrayList<>());
        }
        if (queryTypeExamples.get(queryType).size() < 3) {
            String queryExample = searchQuery != null ? searchQuery : (query != null ? query : "N/A");
            queryTypeExamples.get(queryType).add(String.format("[%d] %s -> query: %s", 
                categoryId, categoryName, queryExample));
        }
        
        generatedUrls.add(new SearchUrlInfo(
            categoryId,
            categoryName,
            catalogUrlWithParams != null ? catalogUrlWithParams : (catalogUrl != null ? catalogUrl : ""),
            queryType,
            dest,
            "catalog",
            apiUrl
        ));
    }
    
    /**
     * Строит URL для catalog API (правильный формат Wildberries)
     * Формат: https://www.wildberries.ru/__internal/catalog/catalog/{shard}/v4/catalog?cat=ID&...
     * или: https://www.wildberries.ru/__internal/catalog/catalog/promo/bucket_28/v4/catalog?preset=ID&...
     */
    private String buildCatalogUrl(String shard, String query, int dest, int page) {
        return buildCatalogUrl(shard, query, dest, page, true);
    }
    
    /**
     * Строит URL для catalog API
     * @param shard Shard для URL
     * @param query Query параметр (cat=ID, subject=ID или preset=ID)
     * @param dest Регион доставки
     * @param page Номер страницы
     * @param withFeedbackPointsFilter Использовать ли фильтр ffeedbackpoints=1
     */
    private String buildCatalogUrl(String shard, String query, int dest, int page, boolean withFeedbackPointsFilter) {
        StringBuilder apiUrl = new StringBuilder(
            "https://www.wildberries.ru/__internal/catalog/catalog/"
        );
        
        // Определяем shard или используем promo/bucket_* для preset
        if (shard != null && !shard.trim().isEmpty()) {
            apiUrl.append(shard);
        } else if (query != null && query.startsWith("preset=")) {
            // Для preset используем promo/bucket_28 (или другой bucket)
            apiUrl.append("promo/bucket_28");
        } else {
            // Fallback shard
            apiUrl.append("blackhole");
        }
        
        apiUrl.append("/v4/catalog?");
        
        // Базовые параметры
        apiUrl.append("ab_testing=false&");
        apiUrl.append("ab_testing=false&");
        apiUrl.append("appType=1&");
        apiUrl.append("curr=rub&");
        apiUrl.append("dest=").append(dest).append("&");
        if (withFeedbackPointsFilter) {
            apiUrl.append("ffeedbackpoints=1&");
        }
        apiUrl.append("hide_dtype=11&");
        apiUrl.append("lang=ru&");
        apiUrl.append("mdg=2&");
        apiUrl.append("page=").append(page).append("&");
        
        // Добавляем query (cat=ID, subject=ID или preset=ID)
        if (query != null && !query.trim().isEmpty()) {
            apiUrl.append(query).append("&");
        }
        
        // Дополнительные параметры
        apiUrl.append("sort=popular&");
        apiUrl.append("spp=30&");
        apiUrl.append("uclusters=1");
        
        return apiUrl.toString();
    }
    
    /**
     * Определяет тип query из searchQuery строки
     */
    private UrlBuilder.QueryType determineQueryTypeFromSearchQuery(String searchQuery) {
        if (searchQuery == null || searchQuery.trim().isEmpty()) {
            return UrlBuilder.QueryType.MENU_V3;
        }
        
        if (searchQuery.startsWith("menu_redirect_subject_v2")) {
            return UrlBuilder.QueryType.MENU_REDIRECT_SUBJECT_V2;
        } else if (searchQuery.startsWith("menu_mined_subject_v2") || searchQuery.startsWith("menu_mined_")) {
            return UrlBuilder.QueryType.MENU_MINED_SUBJECT_V2;
        } else if (searchQuery.startsWith("menu_merged_")) {
            return UrlBuilder.QueryType.MENU_V3; // Fallback
        } else {
            return UrlBuilder.QueryType.MENU_V3;
        }
    }
    
    /**
     * Тестирует разные варианты URL на выборке категорий и возвращает лучший
     */
    public void testAndSelectBestUrlVariant(Set<HttpCookie> cookies, int sampleSize) {
        // Создаем тестовые категории
        List<SearchUrlInfo> testCategories = new ArrayList<>();
        int count = 0;
        for (SearchUrlInfo urlInfo : generatedUrls) {
            if (count >= sampleSize) break;
            testCategories.add(urlInfo);
            count++;
        }
        
        // Результаты тестирования
        Map<String, ValidationStats> results = new HashMap<>();
        
        // Сохраняем оригинальные URLs
        List<SearchUrlInfo> originalUrls = new ArrayList<>(generatedUrls);
        
        // Варианты для тестирования (старый формат)
        List<UrlVariant> variants = new ArrayList<>();
        variants.add(new UrlVariant("old_current", -1257786, "filters", false, true));
        variants.add(new UrlVariant("old_ab_testid", -1257786, "filters", true, true));
        variants.add(new UrlVariant("old_no_feedback", -1257786, "filters", false, false));
        variants.add(new UrlVariant("old_catalog", -1257786, "catalog", false, true));
        variants.add(new UrlVariant("old_dest_other", -8337854, "filters", false, true));
        
        // Добавляем новый правильный формат
        List<SearchUrlInfo> newFormatUrls = new ArrayList<>();
        for (SearchUrlInfo original : testCategories) {
            String queryParam = buildQueryParam(original.categoryId, original.categoryName, original.queryType);
            String apiUrl = buildSearchUrl(queryParam, -8337854, 1);
            newFormatUrls.add(new SearchUrlInfo(
                original.categoryId,
                original.categoryName,
                original.catalogUrl,
                original.queryType,
                -8337854,
                "catalog",
                apiUrl
            ));
        }
        
        // Временно заменяем generatedUrls
        generatedUrls.clear();
        generatedUrls.addAll(newFormatUrls);
        
        // Валидируем новый формат
        ValidationStats newFormatStats = validateAllUrls(cookies, 100); // Увеличено до 100 потоков для быстрой обработки
        results.put("new_format", newFormatStats);
        
        // Восстанавливаем
        generatedUrls.clear();
        generatedUrls.addAll(originalUrls);

        for (UrlVariant variant : variants) {

            // Генерируем URL для тестовых категорий с этим вариантом
            List<SearchUrlInfo> testUrls = new ArrayList<>();
            for (SearchUrlInfo original : testCategories) {
                String queryParam = buildQueryParam(original.categoryId, original.categoryName, original.queryType);
                String apiUrl = buildSearchUrlOld(queryParam, variant.dest, variant.resultset, 
                                              variant.useAbTestId, variant.useFeedbackPoints);
                
                testUrls.add(new SearchUrlInfo(
                    original.categoryId,
                    original.categoryName,
                    original.catalogUrl,
                    original.queryType,
                    variant.dest,
                    variant.resultset,
                    apiUrl
                ));
            }
            
            // Временно заменяем generatedUrls
            generatedUrls.clear();
            generatedUrls.addAll(testUrls);
            
            // Валидируем
            ValidationStats stats = validateAllUrls(cookies, 100); // Увеличено до 100 потоков для быстрой обработки
            results.put(variant.name, stats);
            
            // Восстанавливаем
            generatedUrls.clear();
            generatedUrls.addAll(originalUrls);
            
            System.out.println(String.format("  Valid: %d (%.2f%%)", stats.valid, stats.valid * 100.0 / stats.total));
            System.out.println(String.format("  With products: %d (%.2f%%)", stats.urlsWithProducts, 
                stats.urlsWithProducts * 100.0 / stats.total));
            System.out.println(String.format("  Total products: %d", stats.totalProducts));
        }
        
        // Находим лучший вариант
        String bestVariant = results.entrySet().stream()
            .max(Comparator.comparingInt(e -> e.getValue().urlsWithProducts))
            .map(Map.Entry::getKey)
            .orElse("current");
        
        System.out.println("\n=== Best variant: " + bestVariant + " ===");
        ValidationStats bestStats = results.get(bestVariant);
        System.out.println(String.format("  With products: %d (%.2f%%)", bestStats.urlsWithProducts, 
            bestStats.urlsWithProducts * 100.0 / bestStats.total));
    }
    
    /**
     * Вспомогательный класс для вариантов URL
     */
    private static class UrlVariant {
        String name;
        int dest;
        String resultset;
        boolean useAbTestId;
        boolean useFeedbackPoints;
        
        UrlVariant(String name, int dest, String resultset, boolean useAbTestId, boolean useFeedbackPoints) {
            this.name = name;
            this.dest = dest;
            this.resultset = resultset;
            this.useAbTestId = useAbTestId;
            this.useFeedbackPoints = useFeedbackPoints;
        }
    }
    
    /**
     * Формирует query параметр
     */
    private String buildQueryParam(int categoryId, String categoryName, UrlBuilder.QueryType queryType) {
        switch (queryType) {
            case MENU_V3:
                // Используем формат БЕЗ названия категории (тесты показывают, что это работает лучше)
                // Пример: menu_v3_1234 (без названия) -> 22 товара, а с названием -> 0 товаров
                return queryType.getPrefix() + "_" + categoryId;
            case MENU_REDIRECT_SUBJECT_V2:
                // Используем формат БЕЗ названия категории (для улучшения результатов)
                // Тесты показывают, что формат без названия работает лучше
                return queryType.getPrefix() + "_" + categoryId;
            case MENU_MINED_SUBJECT_V2:
                // Оставляем как есть - этот тип уже работает хорошо (81.58%)
                return queryType.getPrefix() + "_" + categoryId;
            default:
                // По умолчанию используем menu_v3 без названия
                return "menu_v3_" + categoryId;
        }
    }
    
    /**
     * Тестовый метод для определения лучшего варианта URL
     * Проверяет разные варианты на выборке категорий
     */
    public ValidationStats testUrlVariants(Set<HttpCookie> cookies, int sampleSize) {
        log.info("=== Тестирование вариантов URL на выборке из {} категорий ===", sampleSize);
        
        // Берем первые N категорий для теста
        List<SearchUrlInfo> testUrls = new ArrayList<>();
        int[] dests = {-1257786, -8337854};
        String[] resultsets = {"catalog", "filters"};
        
        int tested = 0;
        for (SearchUrlInfo original : generatedUrls) {
            if (tested >= sampleSize) break;
            
            // Генерируем все варианты для этой категории
            for (int dest : dests) {
                for (String resultset : resultsets) {
                    Integer page = "catalog".equals(resultset) ? 1 : null;
                    
                    String apiUrl = UrlBuilder.buildSearchUrl(
                        original.categoryId,
                        original.categoryName,
                        original.queryType,
                        dest,
                        resultset,
                        page
                    );
                    
                    testUrls.add(new SearchUrlInfo(
                        original.categoryId,
                        original.categoryName,
                        original.catalogUrl,
                        original.queryType,
                        dest,
                        resultset,
                        apiUrl
                    ));
                }
            }
            tested++;
        }
        
        log.info("Создано {} тестовых URL для {} категорий", testUrls.size(), tested);
        
        // Группируем по вариантам
        Map<String, List<SearchUrlInfo>> byVariant = new HashMap<>();
        for (SearchUrlInfo urlInfo : testUrls) {
            String key = "dest=" + urlInfo.dest + ",resultset=" + urlInfo.resultset;
            byVariant.computeIfAbsent(key, k -> new ArrayList<>()).add(urlInfo);
        }
        
        // Проверяем каждый вариант
        Map<String, ValidationStats> variantStats = new HashMap<>();
        for (Map.Entry<String, List<SearchUrlInfo>> entry : byVariant.entrySet()) {
            log.info("Проверка варианта: {}", entry.getKey());
            
            // Временно заменяем generatedUrls для проверки
            List<SearchUrlInfo> originalUrls = new ArrayList<>(generatedUrls);
            generatedUrls.clear();
            generatedUrls.addAll(entry.getValue());
            
            ValidationStats stats = validateAllUrls(cookies, 100); // Увеличено до 100 потоков для быстрой обработки
            variantStats.put(entry.getKey(), stats);
            
            // Восстанавливаем
            generatedUrls.clear();
            generatedUrls.addAll(originalUrls);
        }
        
        // Выводим результаты
        log.info("\n=== Результаты тестирования вариантов ===");
        for (Map.Entry<String, ValidationStats> entry : variantStats.entrySet()) {
            ValidationStats stats = entry.getValue();
            log.info("Вариант: {}", entry.getKey());
            log.info("  Валидных: {} ({}%)", stats.valid, 
                String.format("%.2f", stats.valid * 100.0 / stats.total));
            log.info("  С товарами: {} ({}%)", stats.urlsWithProducts,
                String.format("%.2f", stats.urlsWithProducts * 100.0 / stats.total));
            log.info("  Всего товаров: {}", stats.totalProducts);
        }
        
        // Находим лучший вариант (максимум валидных с товарами)
        String bestVariant = variantStats.entrySet().stream()
            .max(Comparator.comparingInt(e -> e.getValue().urlsWithProducts))
            .map(Map.Entry::getKey)
            .orElse("dest=-1257786,resultset=catalog");
        
        log.info("\n=== Лучший вариант: {} ===", bestVariant);
        
        return variantStats.get(bestVariant);
    }
    
    /**
     * Определяет тип query для категории
     * На основе статистики: menu_mined_subject_v2 работает лучше всего (84% успешных)
     */
    private UrlBuilder.QueryType determineQueryType(int categoryId) {
        // Сначала проверяем маппинг
        UrlBuilder.QueryType mappedType = queryTypeMapping.get(categoryId);
        if (mappedType != null) {
            // Если в маппинге указан menu_mined_subject_v2, используем его
            if (mappedType == UrlBuilder.QueryType.MENU_MINED_SUBJECT_V2) {
                return mappedType;
            }
            // Для menu_v3 и menu_redirect_subject_v2 пробуем использовать menu_mined_subject_v2
            // так как они работают плохо (0.58% и 0% успешных)
            // Но сначала проверим, есть ли в маппинге menu_mined_subject_v2 для этой категории
            // Если нет, используем то, что указано в маппинге
            return mappedType;
        }
        
        // Если маппинга нет, пробуем использовать menu_mined_subject_v2
        // так как он работает лучше всего (84% успешных)
        // Но для этого нужно проверить, существует ли такой query для категории
        // Пока используем menu_v3 по умолчанию, но логируем для анализа
        return UrlBuilder.QueryType.MENU_V3;
    }
    
    /**
     * Возвращает список всех сгенерированных URL
     */
    public List<SearchUrlInfo> getGeneratedUrls() {
        return new ArrayList<>(generatedUrls);
    }
    
    // Храним результаты валидации для каждого URL
    private final Map<String, UrlValidationResult> urlValidationResults = new ConcurrentHashMap<>();
    
    /**
     * Проверяет валидность всех сгенерированных URL
     * @param cookies Cookies для запросов
     * @param maxConcurrentRequests Максимальное количество одновременных запросов
     * @return Статистика проверки
     */
    public ValidationStats validateAllUrls(Set<HttpCookie> cookies, int maxConcurrentRequests) {
        log.info("Starting validation of {} URLs", generatedUrls.size());
        System.out.println("Starting validation of " + generatedUrls.size() + " URLs...");
        
        // Сбрасываем счетчики ошибок и флаг предупреждений
        errorStats.clear();
        warningShown = false; // Сбрасываем флаг, чтобы предупреждения показывались для каждой новой валидации
        errorSampleCount.set(0);
        debugRequestCount.set(0);
        zeroTotalCount.set(0);
        zeroTotalJsonFilesCount.set(0);
        urlValidationResults.clear();
        
        ValidationStats stats = new ValidationStats();
        stats.total = generatedUrls.size();
        
        ExecutorService executor = Executors.newFixedThreadPool(maxConcurrentRequests);
        List<Future<Pair<String, UrlValidationResult>>> futures = new ArrayList<>();
        
        long startTime = System.currentTimeMillis();
        AtomicInteger processed = new AtomicInteger(0);
        
        // Создаем задачи для проверки с задержками между запросами
        AtomicInteger requestCounter = new AtomicInteger(0);
        for (SearchUrlInfo urlInfo : generatedUrls) {
            String url = urlInfo.apiUrl;
            Future<Pair<String, UrlValidationResult>> future = executor.submit(() -> {
                // Добавляем небольшую случайную задержку перед каждым запросом (1-5 мс)
                // для снижения нагрузки на сервер (уменьшено, так как потоков больше)
                int requestNum = requestCounter.incrementAndGet();
                if (requestNum > 1) {
                    int delay = 1 + (int)(Math.random() * 4);
                    try {
                        Thread.sleep(delay);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                
                UrlValidationResult result = validateUrl(url, cookies);
                
                // Сохраняем результат для этого URL
                urlValidationResults.put(url, result);
                
                // Логируем прогресс каждые 100 URL
                int current = processed.incrementAndGet();
                if (current % 100 == 0 || current == stats.total) {
                    double progress = (current * 100.0) / stats.total;
                    System.out.println(String.format("Progress: %d/%d (%.1f%%)", current, stats.total, progress));
                    log.info("Validation progress: {}/{} ({}%)", current, stats.total, String.format("%.1f", progress));
                }
                
                return new Pair<>(url, result);
            });
            futures.add(future);
        }
        
        // Ждем завершения всех проверок
        System.out.println("Waiting for validation to complete...");
        for (Future<Pair<String, UrlValidationResult>> future : futures) {
            try {
                Pair<String, UrlValidationResult> pair = future.get(30, TimeUnit.SECONDS);
                UrlValidationResult result = pair.second;
                if (result.isValid) {
                    stats.valid++;
                    stats.totalProducts += result.productsCount;
                    if (result.productsCount > 0) {
                        stats.urlsWithProducts++;
                    }
                    if (result.queryMismatch) {
                        stats.queryMismatches++;
                    }
                } else {
                    stats.invalid++;
                }
            } catch (TimeoutException e) {
                stats.invalid++;
                stats.errors++;
                log.warn("Timeout while validating URL");
            } catch (Exception e) {
                stats.invalid++;
                stats.errors++;
                log.warn("Error while validating URL: {}", e.getMessage());
            }
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
        
        long duration = System.currentTimeMillis() - startTime;
        stats.durationMs = duration;
        
        log.info("=== URL Validation Results ===");
        log.info("Total URLs: {}", stats.total);
        log.info("Valid (status 200): {} ({}%)", stats.valid, String.format("%.2f", stats.valid * 100.0 / stats.total));
        log.info("  - With products (total > 0): {} ({}%)", stats.urlsWithProducts, 
            String.format("%.2f", stats.urlsWithProducts * 100.0 / stats.total));
        log.info("  - Total products in all valid URLs: {}", stats.totalProducts);
        log.info("  - Query mismatches (metadata.name != requested query): {} ({}%)", stats.queryMismatches, 
            stats.valid > 0 ? String.format("%.2f", stats.queryMismatches * 100.0 / stats.valid) : "0.00");
        log.info("Invalid: {} ({}%)", stats.invalid, String.format("%.2f", stats.invalid * 100.0 / stats.total));
        log.info("Errors: {}", stats.errors);
        log.info("Validation time: {} ms ({} sec)", duration, String.format("%.2f", duration / 1000.0));
        
        // Анализ категорий с 0 товарами
        int validWithZeroProducts = stats.valid - stats.urlsWithProducts;
        if (validWithZeroProducts > 0) {
            log.info("\n=== Analysis: Categories with 0 products ===");
            log.info("Valid URLs with 0 products: {} ({}% of valid URLs)", validWithZeroProducts,
                stats.valid > 0 ? String.format("%.2f", validWithZeroProducts * 100.0 / stats.valid) : "0.00");
            log.info("NOTE: These categories are validated with ffeedbackpoints=1 filter (only products with cashback).");
            log.info("      Many categories may have products, but without cashback, so they show 0 products.");
            
            // Группируем по типам query
            Map<UrlBuilder.QueryType, Integer> zeroProductsByType = new HashMap<>();
            Map<UrlBuilder.QueryType, Integer> totalByType = new HashMap<>();
            
            for (SearchUrlInfo urlInfo : generatedUrls) {
                UrlValidationResult result = urlValidationResults.get(urlInfo.apiUrl);
                if (result != null && result.isValid) {
                    UrlBuilder.QueryType type = urlInfo.queryType;
                    totalByType.put(type, totalByType.getOrDefault(type, 0) + 1);
                    if (result.productsCount == 0) {
                        zeroProductsByType.put(type, zeroProductsByType.getOrDefault(type, 0) + 1);
                    }
                }
            }
            
            if (!zeroProductsByType.isEmpty()) {
                log.info("\nCategories with 0 products by query type:");
                zeroProductsByType.entrySet().stream()
                    .sorted(Map.Entry.<UrlBuilder.QueryType, Integer>comparingByValue().reversed())
                    .forEach(entry -> {
                        UrlBuilder.QueryType type = entry.getKey();
                        int zeroCount = entry.getValue();
                        int totalCount = totalByType.getOrDefault(type, 0);
                        double percentage = totalCount > 0 ? (zeroCount * 100.0 / totalCount) : 0.0;
                        log.info("  {}: {} / {} ({}%)", type, zeroCount, totalCount, String.format("%.2f", percentage));
                    });
            }
            
            // Записываем все URL с 0 товарами в файл для ручной проверки
            String outputFile = "urls_with_zero_products.txt";
            java.io.File file = new java.io.File(outputFile);
            String absolutePath = file.getAbsolutePath();
            
            System.out.println("\n" + "=".repeat(80));
            System.out.println("Writing URLs with 0 products to file...");
            System.out.println("File path: " + absolutePath);
            
            try (BufferedWriter writer = new BufferedWriter(new FileWriter(outputFile))) {
                writer.write("=== API URLs with 0 products (with ffeedbackpoints=1 filter) ===\n");
                writer.write("Total: " + validWithZeroProducts + " URLs\n");
                writer.write("Generated: " + new java.util.Date() + "\n\n");
                writer.write("Format: [Category ID] Category Name | Query Type | API URL\n");
                writer.write("================================================================================\n\n");
                
                int count = 0;
                for (SearchUrlInfo urlInfo : generatedUrls) {
                    UrlValidationResult result = urlValidationResults.get(urlInfo.apiUrl);
                    if (result != null && result.isValid && result.productsCount == 0) {
                        count++;
                        writer.write(String.format("[%d] %s\n", urlInfo.categoryId, urlInfo.categoryName));
                        writer.write("Query Type: " + urlInfo.queryType + "\n");
                        writer.write("API URL: " + urlInfo.apiUrl + "\n");
                        if (urlInfo.catalogUrl != null && !urlInfo.catalogUrl.isEmpty()) {
                            writer.write("Catalog URL: " + urlInfo.catalogUrl + "\n");
                        }
                        writer.write("Status: " + result.statusCode + " (Valid but 0 products)\n");
                        writer.write("--------------------------------------------------------------------------------\n");
                    }
                }
                
                writer.write("\n=== Summary ===\n");
                writer.write("Total URLs written: " + count + "\n");
                writer.write("Note: These URLs use ffeedbackpoints=1 filter (only products with cashback).\n");
                writer.write("      Check manually if these categories have products without cashback.\n");
                
                writer.flush(); // Принудительно сбрасываем буфер
                
                log.info("Written {} URLs with 0 products to file: {}", count, absolutePath);
                System.out.println(String.format("SUCCESS: Written %d URLs with 0 products to file!", count));
                System.out.println("File location: " + absolutePath);
                System.out.println("=".repeat(80) + "\n");
            } catch (IOException e) {
                log.error("Error writing URLs with 0 products to file: {}", absolutePath, e);
                System.err.println("ERROR: Failed to write URLs with 0 products to file!");
                System.err.println("File path: " + absolutePath);
                System.err.println("Error: " + e.getMessage());
                e.printStackTrace();
            }
        }
        
        // Выводим статистику ошибок
        if (!errorStats.isEmpty()) {
            log.info("\n=== Error Statistics ===");
            errorStats.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .forEach(entry -> log.info("  {}: {}", entry.getKey(), entry.getValue()));
        }
        
        // Выводим статистику по несоответствиям query
        if (stats.queryMismatches > 0) {
            System.out.println("\n=== Query Mismatches (metadata.name != requested query) ===");
            System.out.println(String.format("Total mismatches: %d (%.2f%% of valid URLs)", 
                stats.queryMismatches, stats.valid > 0 ? stats.queryMismatches * 100.0 / stats.valid : 0.0));
            System.out.println("This means API returned a different preset than requested.");
            System.out.println("Example: Requesting 'menu_v3_9153' but getting 'menu_v3_128453' (same preset)");
            
            // Выводим примеры несоответствий
            int mismatchExamples = 0;
            System.out.println("\nSample query mismatches (first 10):");
            for (SearchUrlInfo urlInfo : generatedUrls) {
                if (mismatchExamples >= 10) break;
                UrlValidationResult result = urlValidationResults.get(urlInfo.apiUrl);
                if (result != null && result.isValid && result.queryMismatch) {
                    System.out.println(String.format("  [%d] %s", urlInfo.categoryId, urlInfo.categoryName));
                    System.out.println(String.format("      Requested: '%s'", result.requestedQuery));
                    System.out.println(String.format("      Got: '%s'", result.actualMetadataName));
                    System.out.println(String.format("      Products: %d", result.productsCount));
                    mismatchExamples++;
                }
            }
        }
        
        // Выводим статистику по категориям с 0 товаров
        System.out.println("\n=== URLs with 0 products - Analysis ===");
        int zeroProductsCount = 0;
        Map<UrlBuilder.QueryType, Integer> zeroProductsByType = new HashMap<>();
        List<String> zeroProductsExamples = new ArrayList<>();
        
        for (SearchUrlInfo urlInfo : generatedUrls) {
            UrlValidationResult result = urlValidationResults.get(urlInfo.apiUrl);
            if (result != null && result.isValid && result.productsCount == 0) {
                zeroProductsCount++;
                
                // Статистика по типам
                zeroProductsByType.put(urlInfo.queryType, 
                    zeroProductsByType.getOrDefault(urlInfo.queryType, 0) + 1);
                
                // Извлекаем query параметр из URL
                String queryParam = "N/A";
                try {
                    int queryIndex = urlInfo.apiUrl.indexOf("query=");
                    if (queryIndex > 0) {
                        String queryPart = urlInfo.apiUrl.substring(queryIndex + 6);
                        if (queryPart.contains("&")) {
                            queryPart = queryPart.substring(0, queryPart.indexOf("&"));
                        }
                        queryParam = java.net.URLDecoder.decode(queryPart, "UTF-8");
                    }
                } catch (Exception e) {
                    // Игнорируем
                }
                
                // Сохраняем примеры (первые 10)
                if (zeroProductsExamples.size() < 10) {
                    zeroProductsExamples.add(String.format("[%d] %s | Type: %s | Query: %s", 
                        urlInfo.categoryId, urlInfo.categoryName, urlInfo.queryType, queryParam));
                }
            }
        }
        
        // Выводим статистику
        System.out.println(String.format("\nTotal URLs with 0 products: %d", zeroProductsCount));
        System.out.println("\nZero products by query type:");
        for (Map.Entry<UrlBuilder.QueryType, Integer> entry : zeroProductsByType.entrySet()) {
            System.out.println(String.format("  %s: %d categories", entry.getKey(), entry.getValue()));
        }
        
        // Собираем все категории с 0 товаров для детального вывода
        List<SearchUrlInfo> allZeroProducts = new ArrayList<>();
        for (SearchUrlInfo urlInfo : generatedUrls) {
            UrlValidationResult result = urlValidationResults.get(urlInfo.apiUrl);
            if (result != null && result.isValid && result.productsCount == 0) {
                allZeroProducts.add(urlInfo);
            }
        }
        
        // Рекомендация: попробовать menu_mined_subject_v2 для категорий, которые не работают
        System.out.println("\n=== Recommendation: Try menu_mined_subject_v2 for failed categories ===");
        int candidatesForMined = 0;
        List<String> candidateExamples = new ArrayList<>();
        for (SearchUrlInfo urlInfo : generatedUrls) {
            UrlValidationResult result = urlValidationResults.get(urlInfo.apiUrl);
            if (result != null && result.isValid && result.productsCount == 0) {
                // Если категория не работает с menu_v3 или menu_redirect_subject_v2,
                // и в маппинге нет menu_mined_subject_v2, можно попробовать его
                boolean isCandidate = false;
                if (urlInfo.queryType == UrlBuilder.QueryType.MENU_V3 || 
                    urlInfo.queryType == UrlBuilder.QueryType.MENU_REDIRECT_SUBJECT_V2) {
                    if (!queryTypeMapping.containsKey(urlInfo.categoryId) ||
                        queryTypeMapping.get(urlInfo.categoryId) != UrlBuilder.QueryType.MENU_MINED_SUBJECT_V2) {
                        isCandidate = true;
                    }
                }
                
                if (isCandidate) {
                    candidatesForMined++;
                    if (candidateExamples.size() < 5) {
                        String queryParam = "N/A";
                        try {
                            int queryIndex = urlInfo.apiUrl.indexOf("query=");
                            if (queryIndex > 0) {
                                String queryPart = urlInfo.apiUrl.substring(queryIndex + 6);
                                if (queryPart.contains("&")) {
                                    queryPart = queryPart.substring(0, queryPart.indexOf("&"));
                                }
                                queryParam = java.net.URLDecoder.decode(queryPart, "UTF-8");
                            }
                        } catch (Exception e) {
                            // Игнорируем
                        }
                        candidateExamples.add(String.format("[%d] %s | Current: %s | Try: menu_mined_subject_v2_%d", 
                            urlInfo.categoryId, urlInfo.categoryName, queryParam, urlInfo.categoryId));
                    }
                }
            }
        }
        System.out.println(String.format("Categories that could try menu_mined_subject_v2: %d", candidatesForMined));
        System.out.println("(These categories currently use menu_v3 or menu_redirect_subject_v2 and return 0 products)");
        if (!candidateExamples.isEmpty()) {
            System.out.println("Sample candidates:");
            for (String example : candidateExamples) {
                System.out.println("  " + example);
            }
        }
        
        // Тестируем альтернативные варианты для первых нескольких неуспешных категорий
        System.out.println("\n=== Testing alternative formats for failed categories ===");
        int testedAlternatives = 0;
        final int MAX_ALTERNATIVE_TESTS = 5; // Уменьшаем, так как будем тестировать несколько вариантов для каждой
        
        for (SearchUrlInfo urlInfo : generatedUrls) {
            if (testedAlternatives >= MAX_ALTERNATIVE_TESTS) break;
            
            UrlValidationResult result = urlValidationResults.get(urlInfo.apiUrl);
            if (result != null && result.isValid && result.productsCount == 0) {
                // Тестируем только для menu_v3 и menu_redirect_subject_v2
                if (urlInfo.queryType == UrlBuilder.QueryType.MENU_V3 || 
                    urlInfo.queryType == UrlBuilder.QueryType.MENU_REDIRECT_SUBJECT_V2) {
                    
                    System.out.println(String.format("\n=== Testing alternatives for [%d] %s ===", 
                        urlInfo.categoryId, urlInfo.categoryName));
                    
                    // Определяем текущий формат query 
                    String currentQuery = "N/A";
                    try {
                        int queryIndex = urlInfo.apiUrl.indexOf("query=");
                        if (queryIndex > 0) {
                            String queryPart = urlInfo.apiUrl.substring(queryIndex + 6);
                            if (queryPart.contains("&")) {
                                queryPart = queryPart.substring(0, queryPart.indexOf("&"));
                            }
                            currentQuery = java.net.URLDecoder.decode(queryPart, "UTF-8");
                        }
                    } catch (Exception e) {
                        // Игнорируем
                    }
                    System.out.println(String.format("Current: %s (Products: 0)", currentQuery));
                    
                    // Вариант 1: menu_mined_subject_v2 (уже пробовали, но покажем результат)
                    String alt1Query = "menu_mined_subject_v2_" + urlInfo.categoryId;
                    String alt1Url = buildSearchUrl(alt1Query, urlInfo.dest, 1);
                    UrlValidationResult alt1Result = validateUrl(alt1Url, cookies);
                    System.out.println(String.format("  Variant 1: %s -> %s, Products: %d", 
                        alt1Query, alt1Result.isValid ? "Valid" : "Invalid", alt1Result.productsCount));
                    
                    // Вариант 2: menu_v3 только с ID (без названия категории)
                    if (urlInfo.queryType == UrlBuilder.QueryType.MENU_V3) {
                        String alt2Query = "menu_v3_" + urlInfo.categoryId;
                        String alt2Url = buildSearchUrl(alt2Query, urlInfo.dest, 1);
                        UrlValidationResult alt2Result = validateUrl(alt2Url, cookies);
                        System.out.println(String.format("  Variant 2: %s (without name) -> %s, Products: %d", 
                            alt2Query, alt2Result.isValid ? "Valid" : "Invalid", alt2Result.productsCount));
                    }
                    
                    // Вариант 2 для MENU_REDIRECT_SUBJECT_V2: правильный формат с названием
                    if (urlInfo.queryType == UrlBuilder.QueryType.MENU_REDIRECT_SUBJECT_V2) {
                        String alt2Query = "menu_redirect_subject_v2_" + urlInfo.categoryId + " " + urlInfo.categoryName;
                        String alt2Url = buildSearchUrl(alt2Query, urlInfo.dest, 1);
                        UrlValidationResult alt2Result = validateUrl(alt2Url, cookies);
                        System.out.println(String.format("  Variant 2: %s (with name) -> %s, Products: %d", 
                            alt2Query, alt2Result.isValid ? "Valid" : "Invalid", alt2Result.productsCount));
                    }
                    
                    // Вариант 3: Только ID категории как query (для обратной совместимости)
                    String alt3Query = String.valueOf(urlInfo.categoryId);
                    String alt3Url = buildSearchUrl(alt3Query, urlInfo.dest, 1);
                    UrlValidationResult alt3Result = validateUrl(alt3Url, cookies);
                    System.out.println(String.format("  Variant 3: %s (category ID only) -> %s, Products: %d", 
                        alt3Query, alt3Result.isValid ? "Valid" : "Invalid", alt3Result.productsCount));
                    
                    // Вариант 4: menu_v3 с другим resultset (filters вместо catalog) - только для тестирования
                    // ВАЖНО: resultset=filters не возвращает товары, только фильтры, поэтому не используем для основной валидации
                    if (urlInfo.queryType == UrlBuilder.QueryType.MENU_V3) {
                        String alt4Query = "menu_v3_" + urlInfo.categoryId + " " + urlInfo.categoryName;
                        String alt4Url = buildSearchUrl(alt4Query, urlInfo.dest, 1, "filters");
                        UrlValidationResult alt4Result = validateUrl(alt4Url, cookies);
                        System.out.println(String.format("  Variant 4: %s (resultset=filters) -> %s, Products: %d", 
                            alt4Query, alt4Result.isValid ? "Valid" : "Invalid", alt4Result.productsCount));
                        System.out.println("    [NOTE] resultset=filters returns only filters, not products. This is expected.");
                    }
                    
                    testedAlternatives++;
                }
            }
        }
        
        System.out.println("\nSample categories with 0 products (first 10):");
        for (String example : zeroProductsExamples) {
            System.out.println("  " + example);
        }
        
        // Подробная статистика по категориям с 0 товарами и альтернативным методам
        System.out.println("\n=== Detailed Statistics: Categories with 0 products and Alternative Methods ===");
        
        // Собираем категории с 0 товарами по типам
        List<SearchUrlInfo> zeroProductsMENU_V3 = new ArrayList<>();
        List<SearchUrlInfo> zeroProductsMENU_MINED_SUBJECT_V2 = new ArrayList<>();
        
        for (SearchUrlInfo urlInfo : generatedUrls) {
            UrlValidationResult result = urlValidationResults.get(urlInfo.apiUrl);
            if (result != null && result.isValid && result.productsCount == 0) {
                if (urlInfo.queryType == UrlBuilder.QueryType.MENU_V3) {
                    zeroProductsMENU_V3.add(urlInfo);
                } else if (urlInfo.queryType == UrlBuilder.QueryType.MENU_MINED_SUBJECT_V2) {
                    zeroProductsMENU_MINED_SUBJECT_V2.add(urlInfo);
                }
            }
        }
        
        // Статистика для MENU_V3
        System.out.println(String.format("\nMENU_V3: %d categories with 0 products", zeroProductsMENU_V3.size()));
        if (!zeroProductsMENU_V3.isEmpty()) {
            System.out.println("Testing alternative methods for all MENU_V3 categories with 0 products...");
            
            Map<String, Integer> variant1Stats = new HashMap<>(); // menu_mined_subject_v2
            Map<String, Integer> variant2Stats = new HashMap<>(); // menu_v3 without name
            Map<String, Integer> variant3Stats = new HashMap<>(); // category ID only
            
            int variant1Total = 0, variant2Total = 0, variant3Total = 0;
            int variant1Found = 0, variant2Found = 0, variant3Found = 0;
            
            for (SearchUrlInfo urlInfo : zeroProductsMENU_V3) {
                // Variant 1: menu_mined_subject_v2
                String alt1Query = "menu_mined_subject_v2_" + urlInfo.categoryId;
                String alt1Url = buildSearchUrl(alt1Query, urlInfo.dest, 1);
                UrlValidationResult alt1Result = validateUrl(alt1Url, cookies);
                variant1Total++;
                if (alt1Result.isValid && alt1Result.productsCount > 0) {
                    variant1Found++;
                }
                variant1Stats.put(String.valueOf(urlInfo.categoryId), alt1Result.productsCount);
                
                // Variant 2: menu_v3 without name
                String alt2Query = "menu_v3_" + urlInfo.categoryId;
                String alt2Url = buildSearchUrl(alt2Query, urlInfo.dest, 1);
                UrlValidationResult alt2Result = validateUrl(alt2Url, cookies);
                variant2Total++;
                if (alt2Result.isValid && alt2Result.productsCount > 0) {
                    variant2Found++;
                }
                variant2Stats.put(String.valueOf(urlInfo.categoryId), alt2Result.productsCount);
                
                // Variant 3: category ID only
                String alt3Query = String.valueOf(urlInfo.categoryId);
                String alt3Url = buildSearchUrl(alt3Query, urlInfo.dest, 1);
                UrlValidationResult alt3Result = validateUrl(alt3Url, cookies);
                variant3Total++;
                if (alt3Result.isValid && alt3Result.productsCount > 0) {
                    variant3Found++;
                }
                variant3Stats.put(String.valueOf(urlInfo.categoryId), alt3Result.productsCount);
            }
            
            System.out.println(String.format("  Variant 1 (menu_mined_subject_v2): %d/%d found products (%.2f%%)", 
                variant1Found, variant1Total, variant1Total > 0 ? variant1Found * 100.0 / variant1Total : 0));
            System.out.println(String.format("  Variant 2 (menu_v3 without name): %d/%d found products (%.2f%%)", 
                variant2Found, variant2Total, variant2Total > 0 ? variant2Found * 100.0 / variant2Total : 0));
            System.out.println(String.format("  Variant 3 (category ID only): %d/%d found products (%.2f%%)", 
                variant3Found, variant3Total, variant3Total > 0 ? variant3Found * 100.0 / variant3Total : 0));
            
            // Подсчитываем общее количество товаров для каждого варианта
            int variant1Products = variant1Stats.values().stream().mapToInt(Integer::intValue).sum();
            int variant2Products = variant2Stats.values().stream().mapToInt(Integer::intValue).sum();
            int variant3Products = variant3Stats.values().stream().mapToInt(Integer::intValue).sum();
            
            System.out.println(String.format("  Total products found:"));
            System.out.println(String.format("    Variant 1: %d products", variant1Products));
            System.out.println(String.format("    Variant 2: %d products", variant2Products));
            System.out.println(String.format("    Variant 3: %d products", variant3Products));
        }
        
        // Статистика для MENU_MINED_SUBJECT_V2
        System.out.println(String.format("\nMENU_MINED_SUBJECT_V2: %d categories with 0 products", zeroProductsMENU_MINED_SUBJECT_V2.size()));
        if (!zeroProductsMENU_MINED_SUBJECT_V2.isEmpty()) {
            System.out.println("Testing alternative methods for all MENU_MINED_SUBJECT_V2 categories with 0 products...");
            
            Map<String, Integer> variant1Stats = new HashMap<>(); // menu_v3
            Map<String, Integer> variant2Stats = new HashMap<>(); // menu_redirect_subject_v2
            Map<String, Integer> variant3Stats = new HashMap<>(); // category ID only
            
            int variant1Total = 0, variant2Total = 0, variant3Total = 0;
            int variant1Found = 0, variant2Found = 0, variant3Found = 0;
            
            for (SearchUrlInfo urlInfo : zeroProductsMENU_MINED_SUBJECT_V2) {
                // Variant 1: menu_v3
                String alt1Query = "menu_v3_" + urlInfo.categoryId;
                String alt1Url = buildSearchUrl(alt1Query, urlInfo.dest, 1);
                UrlValidationResult alt1Result = validateUrl(alt1Url, cookies);
                variant1Total++;
                if (alt1Result.isValid && alt1Result.productsCount > 0) {
                    variant1Found++;
                }
                variant1Stats.put(String.valueOf(urlInfo.categoryId), alt1Result.productsCount);
                
                // Variant 2: menu_redirect_subject_v2
                String alt2Query = "menu_redirect_subject_v2_" + urlInfo.categoryId;
                String alt2Url = buildSearchUrl(alt2Query, urlInfo.dest, 1);
                UrlValidationResult alt2Result = validateUrl(alt2Url, cookies);
                variant2Total++;
                if (alt2Result.isValid && alt2Result.productsCount > 0) {
                    variant2Found++;
                }
                variant2Stats.put(String.valueOf(urlInfo.categoryId), alt2Result.productsCount);
                
                // Variant 3: category ID only
                String alt3Query = String.valueOf(urlInfo.categoryId);
                String alt3Url = buildSearchUrl(alt3Query, urlInfo.dest, 1);
                UrlValidationResult alt3Result = validateUrl(alt3Url, cookies);
                variant3Total++;
                if (alt3Result.isValid && alt3Result.productsCount > 0) {
                    variant3Found++;
                }
                variant3Stats.put(String.valueOf(urlInfo.categoryId), alt3Result.productsCount);
            }
            
            System.out.println(String.format("  Variant 1 (menu_v3): %d/%d found products (%.2f%%)", 
                variant1Found, variant1Total, variant1Total > 0 ? variant1Found * 100.0 / variant1Total : 0));
            System.out.println(String.format("  Variant 2 (menu_redirect_subject_v2): %d/%d found products (%.2f%%)", 
                variant2Found, variant2Total, variant2Total > 0 ? variant2Found * 100.0 / variant2Total : 0));
            System.out.println(String.format("  Variant 3 (category ID only): %d/%d found products (%.2f%%)", 
                variant3Found, variant3Total, variant3Total > 0 ? variant3Found * 100.0 / variant3Total : 0));
            
            // Подсчитываем общее количество товаров для каждого варианта
            int variant1Products = variant1Stats.values().stream().mapToInt(Integer::intValue).sum();
            int variant2Products = variant2Stats.values().stream().mapToInt(Integer::intValue).sum();
            int variant3Products = variant3Stats.values().stream().mapToInt(Integer::intValue).sum();
            
            System.out.println(String.format("  Total products found:"));
            System.out.println(String.format("    Variant 1: %d products", variant1Products));
            System.out.println(String.format("    Variant 2: %d products", variant2Products));
            System.out.println(String.format("    Variant 3: %d products", variant3Products));
        }
        
        // Анализ успешности по типам query
        System.out.println("\n=== Success Rate by Query Type ===");
        Map<UrlBuilder.QueryType, Integer> totalByType = new HashMap<>();
        Map<UrlBuilder.QueryType, Integer> withProductsByType = new HashMap<>();
        
        for (SearchUrlInfo urlInfo : generatedUrls) {
            totalByType.put(urlInfo.queryType, totalByType.getOrDefault(urlInfo.queryType, 0) + 1);
            UrlValidationResult result = urlValidationResults.get(urlInfo.apiUrl);
            if (result != null && result.isValid && result.productsCount > 0) {
                withProductsByType.put(urlInfo.queryType, 
                    withProductsByType.getOrDefault(urlInfo.queryType, 0) + 1);
            }
        }
        
        for (UrlBuilder.QueryType type : totalByType.keySet()) {
            int total = totalByType.get(type);
            int withProducts = withProductsByType.getOrDefault(type, 0);
            double successRate = total > 0 ? (withProducts * 100.0 / total) : 0;
            System.out.println(String.format("  %s: %d/%d with products (%.2f%%)", 
                type, withProducts, total, successRate));
        }
        
        // Примеры успешных категорий для каждого типа
        System.out.println("\n=== Successful Categories Examples ===");
        Map<UrlBuilder.QueryType, List<String>> successfulExamples = new HashMap<>();
        for (SearchUrlInfo urlInfo : generatedUrls) {
            UrlValidationResult result = urlValidationResults.get(urlInfo.apiUrl);
            if (result != null && result.isValid && result.productsCount > 0) {
                if (!successfulExamples.containsKey(urlInfo.queryType)) {
                    successfulExamples.put(urlInfo.queryType, new ArrayList<>());
                }
                if (successfulExamples.get(urlInfo.queryType).size() < 5) {
                    String queryParam = "N/A";
                    try {
                        int queryIndex = urlInfo.apiUrl.indexOf("query=");
                        if (queryIndex > 0) {
                            String queryPart = urlInfo.apiUrl.substring(queryIndex + 6);
                            if (queryPart.contains("&")) {
                                queryPart = queryPart.substring(0, queryPart.indexOf("&"));
                            }
                            queryParam = java.net.URLDecoder.decode(queryPart, "UTF-8");
                        }
                    } catch (Exception e) {
                        // Игнорируем
                    }
                    successfulExamples.get(urlInfo.queryType).add(
                        String.format("[%d] %s | Query: %s | Products: %d", 
                            urlInfo.categoryId, urlInfo.categoryName, queryParam, result.productsCount));
                }
            }
        }
        
        for (Map.Entry<UrlBuilder.QueryType, List<String>> entry : successfulExamples.entrySet()) {
            System.out.println(String.format("\n%s successful examples:", entry.getKey()));
            for (String example : entry.getValue()) {
                System.out.println("  " + example);
            }
        }
        
        // Анализ: какие категории успешны для menu_v3 (их очень мало)
        System.out.println("\n=== Analysis: Why MENU_V3 works for some categories ===");
        List<SearchUrlInfo> successfulMenuV3 = new ArrayList<>();
        for (SearchUrlInfo urlInfo : generatedUrls) {
            if (urlInfo.queryType == UrlBuilder.QueryType.MENU_V3) {
                UrlValidationResult result = urlValidationResults.get(urlInfo.apiUrl);
                if (result != null && result.isValid && result.productsCount > 0) {
                    successfulMenuV3.add(urlInfo);
                }
            }
        }
        
        System.out.println(String.format("Successful MENU_V3 categories: %d", successfulMenuV3.size()));
        if (!successfulMenuV3.isEmpty()) {
            System.out.println("Sample successful MENU_V3 categories:");
            for (int i = 0; i < Math.min(10, successfulMenuV3.size()); i++) {
                SearchUrlInfo urlInfo = successfulMenuV3.get(i);
                String queryParam = "N/A";
                try {
                    int queryIndex = urlInfo.apiUrl.indexOf("query=");
                    if (queryIndex > 0) {
                        String queryPart = urlInfo.apiUrl.substring(queryIndex + 6);
                        if (queryPart.contains("&")) {
                            queryPart = queryPart.substring(0, queryPart.indexOf("&"));
                        }
                        queryParam = java.net.URLDecoder.decode(queryPart, "UTF-8");
                    }
                } catch (Exception e) {
                    // Игнорируем
                }
                UrlValidationResult result = urlValidationResults.get(urlInfo.apiUrl);
                System.out.println(String.format("  [%d] %s | Query: %s | Products: %d", 
                    urlInfo.categoryId, urlInfo.categoryName, queryParam, 
                    result != null ? result.productsCount : 0));
            }
        }
        
        // Анализ: почему menu_redirect_subject_v2 не работает
        System.out.println("\n=== Analysis: Why MENU_REDIRECT_SUBJECT_V2 doesn't work ===");
        List<SearchUrlInfo> failedRedirect = new ArrayList<>();
        for (SearchUrlInfo urlInfo : generatedUrls) {
            if (urlInfo.queryType == UrlBuilder.QueryType.MENU_REDIRECT_SUBJECT_V2) {
                UrlValidationResult result = urlValidationResults.get(urlInfo.apiUrl);
                if (result != null && result.isValid && result.productsCount == 0) {
                    failedRedirect.add(urlInfo);
                }
            }
        }
        
        System.out.println(String.format("Failed MENU_REDIRECT_SUBJECT_V2 categories: %d", failedRedirect.size()));
        if (!failedRedirect.isEmpty()) {
            System.out.println("Sample failed MENU_REDIRECT_SUBJECT_V2 categories (first 5):");
            for (int i = 0; i < Math.min(5, failedRedirect.size()); i++) {
                SearchUrlInfo urlInfo = failedRedirect.get(i);
                String queryParam = "N/A";
                try {
                    int queryIndex = urlInfo.apiUrl.indexOf("query=");
                    if (queryIndex > 0) {
                        String queryPart = urlInfo.apiUrl.substring(queryIndex + 6);
                        if (queryPart.contains("&")) {
                            queryPart = queryPart.substring(0, queryPart.indexOf("&"));
                        }
                        queryParam = java.net.URLDecoder.decode(queryPart, "UTF-8");
                    }
                } catch (Exception e) {
                    // Игнорируем
                }
                System.out.println(String.format("  [%d] %s | Query: %s", 
                    urlInfo.categoryId, urlInfo.categoryName, queryParam));
            }
        }
        
        System.out.println(String.format("\nTotal URLs with 0 products: %d", zeroProductsCount));
        log.info("URLs with 0 products: {}", zeroProductsCount);
        
        // Генерируем URL для всех страниц категорий с товарами
        System.out.println("\n" + "=".repeat(80));
        System.out.println("=== Generating URLs for all pages ===");
        System.out.println("=".repeat(80));
        generateUrlsForAllPages();
        System.out.println("=".repeat(80));
        
        return stats;
    }
    
    /**
     * Генерирует URL для всех страниц категорий с товарами
     * На основе количества товаров определяет количество страниц и генерирует URL для каждой
     */
    private void generateUrlsForAllPages() {
        // Количество товаров на странице (обычно 100 для Wildberries)
        final int PRODUCTS_PER_PAGE = 100;
        
        // Список URL для всех страниц
        List<PageUrlInfo> allPageUrls = new ArrayList<>();
        
        // Счетчики
        int categoriesWithProducts = 0;
        int totalPages = 0;
        
        System.out.println("Generating page URLs for categories with products...");
        System.out.println(String.format("Products per page: %d", PRODUCTS_PER_PAGE));
        
        for (SearchUrlInfo urlInfo : generatedUrls) {
            UrlValidationResult result = urlValidationResults.get(urlInfo.apiUrl);
            if (result != null && result.isValid && result.productsCount > 0) {
                categoriesWithProducts++;
                
                // Вычисляем количество страниц (округление вверх)
                int totalProducts = result.productsCount;
                int pages = (totalProducts + PRODUCTS_PER_PAGE - 1) / PRODUCTS_PER_PAGE;
                
                // Определяем тип API и извлекаем параметры
                boolean isCatalogApi = urlInfo.apiUrl.contains("/catalog/catalog/");
                String pageUrl;
                
                if (isCatalogApi) {
                    // Для catalog API извлекаем shard и query
                    String shard = extractShardFromUrl(urlInfo.apiUrl);
                    String query = extractCatalogQueryFromUrl(urlInfo.apiUrl);
                    if (shard == null || query == null) {
                        log.warn("Failed to extract shard or query from catalog URL: {}", urlInfo.apiUrl);
                        continue;
                    }
                    
                    // Генерируем URL для каждой страницы
                    for (int page = 1; page <= pages; page++) {
                        pageUrl = buildCatalogUrl(shard, query, urlInfo.dest, page);
                        allPageUrls.add(new PageUrlInfo(
                            urlInfo.categoryId,
                            urlInfo.categoryName,
                            urlInfo.queryType,
                            page,
                            pages,
                            totalProducts,
                            pageUrl
                        ));
                        totalPages++;
                    }
                } else {
                    // Для search API извлекаем query параметр
                    String queryParam = extractQueryParam(urlInfo.apiUrl);
                    if (queryParam.isEmpty()) {
                        log.warn("Failed to extract query param from URL: {}", urlInfo.apiUrl);
                        continue;
                    }
                    
                    // Генерируем URL для каждой страницы
                    for (int page = 1; page <= pages; page++) {
                        pageUrl = buildSearchUrl(queryParam, urlInfo.dest, page);
                        allPageUrls.add(new PageUrlInfo(
                            urlInfo.categoryId,
                            urlInfo.categoryName,
                            urlInfo.queryType,
                            page,
                            pages,
                            totalProducts,
                            pageUrl
                        ));
                        totalPages++;
                    }
                }
            }
        }
        
        System.out.println(String.format("\nGenerated %d page URLs for %d categories with products", 
            totalPages, categoriesWithProducts));
        
        if (categoriesWithProducts == 0) {
            System.out.println("WARNING: No categories with products found! Cannot generate page URLs.");            System.out.println("This might happen if validation hasn't completed yet or all categories have 0 products.");
            return;
        }
        
        // Выводим примеры сгенерированных страниц
        if (!allPageUrls.isEmpty()) {
            System.out.println("\nSample page URLs (first 5):");
            for (int i = 0; i < Math.min(5, allPageUrls.size()); i++) {
                PageUrlInfo pageInfo = allPageUrls.get(i);
                System.out.println(String.format("  [%d] %s | Page %d/%d | Products: %d | URL: %s",
                    pageInfo.categoryId, pageInfo.categoryName, pageInfo.page, 
                    pageInfo.totalPages, pageInfo.totalProducts, pageInfo.url));
            }
        }
        
        // Выводим информацию о сгенерированных URL для всех страниц
        if (allPageUrls.isEmpty()) {
            System.out.println("WARNING: No page URLs generated!");
            return;
        }
        
        System.out.println("\n=== Generated page URLs ===");
        System.out.println(String.format("Total page URLs: %d", allPageUrls.size()));
    }
    
    /**
     * Извлекает query параметр из URL (для search API)
     */
    private String extractQueryParam(String url) {
        try {
            int queryIndex = url.indexOf("query=");
            if (queryIndex > 0) {
                String queryPart = url.substring(queryIndex + 6);
                if (queryPart.contains("&")) {
                    queryPart = queryPart.substring(0, queryPart.indexOf("&"));
                }
                return java.net.URLDecoder.decode(queryPart, "UTF-8");
            }
        } catch (Exception e) {
            // Игнорируем
        }
        return "";
    }
    
    /**
     * Извлекает shard из catalog API URL
     */
    private String extractShardFromUrl(String url) {
        try {
            // Формат: .../catalog/catalog/{shard}/v4/catalog?...
            int catalogIndex = url.indexOf("/catalog/catalog/");
            if (catalogIndex > 0) {
                String afterCatalog = url.substring(catalogIndex + 17);
                int v4Index = afterCatalog.indexOf("/v4/catalog");
                if (v4Index > 0) {
                    return afterCatalog.substring(0, v4Index);
                }
            }
        } catch (Exception e) {
            // Игнорируем
        }
        return null;
    }
    
    /**
     * Извлекает query (cat=ID, subject=ID или preset=ID) из catalog API URL
     */
    private String extractCatalogQueryFromUrl(String url) {
        try {
            if (url.contains("cat=")) {
                int catIndex = url.indexOf("cat=");
                String catPart = url.substring(catIndex + 4);
                if (catPart.contains("&")) {
                    catPart = catPart.substring(0, catPart.indexOf("&"));
                }
                return "cat=" + catPart;
            } else if (url.contains("preset=")) {
                int presetIndex = url.indexOf("preset=");
                String presetPart = url.substring(presetIndex + 7);
                if (presetPart.contains("&")) {
                    presetPart = presetPart.substring(0, presetPart.indexOf("&"));
                }
                return "preset=" + presetPart;
            } else if (url.contains("subject=")) {
                int subjectIndex = url.indexOf("subject=");
                String subjectPart = url.substring(subjectIndex + 8);
                if (subjectPart.contains("&")) {
                    subjectPart = subjectPart.substring(0, subjectPart.indexOf("&"));
                }
                return "subject=" + subjectPart;
            }
        } catch (Exception e) {
            // Игнорируем
        }
        return null;
    }
    
    /**
     * Информация о URL страницы
     */
    private static class PageUrlInfo {
        final int categoryId;
        final String categoryName;
        final UrlBuilder.QueryType queryType;
        final int page;
        final int totalPages;
        final int totalProducts;
        final String url;
        
        PageUrlInfo(int categoryId, String categoryName, UrlBuilder.QueryType queryType,
                   int page, int totalPages, int totalProducts, String url) {
            this.categoryId = categoryId;
            this.categoryName = categoryName;
            this.queryType = queryType;
            this.page = page;
            this.totalPages = totalPages;
            this.totalProducts = totalProducts;
            this.url = url;
        }
    }
    
    /**
     * Простой класс-пара для хранения пары значений
     */
    private static class Pair<T, U> {
        final T first;
        final U second;
        
        Pair(T first, U second) {
            this.first = first;
            this.second = second;
        }
    }
    
    /**
     * Результат проверки одного URL
     */
    private static class UrlValidationResult {
        boolean isValid;
        int productsCount;
        int statusCode;
        String errorMessage;
        boolean queryMismatch;  // true если metadata.name не соответствует запрошенному query
        String requestedQuery;   // запрошенный query параметр
        String actualMetadataName;  // фактический metadata.name из ответа
        String presetId;  // preset ID из catalog_value (например, "602820788")
        
        UrlValidationResult(boolean isValid, int productsCount, int statusCode, String errorMessage) {
            this.isValid = isValid;
            this.productsCount = productsCount;
            this.statusCode = statusCode;
            this.errorMessage = errorMessage;
            this.queryMismatch = false;
            this.requestedQuery = null;
            this.actualMetadataName = null;
            this.presetId = null;
        }
        
        UrlValidationResult(boolean isValid, int productsCount, int statusCode, String errorMessage, 
                          boolean queryMismatch, String requestedQuery, String actualMetadataName, String presetId) {
            this.isValid = isValid;
            this.productsCount = productsCount;
            this.statusCode = statusCode;
            this.errorMessage = errorMessage;
            this.queryMismatch = queryMismatch;
            this.requestedQuery = requestedQuery;
            this.actualMetadataName = actualMetadataName;
            this.presetId = presetId;
        }
    }
    
    // Счетчики для статистики ошибок
    private final Map<String, Integer> errorStats = new HashMap<>();
    private final AtomicInteger errorSampleCount = new AtomicInteger(0);
    private static final int MAX_ERROR_SAMPLES = 5; // Показываем первые 5 ошибок каждого типа
    
    // Счетчик для логирования первых запросов
    private final AtomicInteger debugRequestCount = new AtomicInteger(0);
    private static final int MAX_DEBUG_REQUESTS = 5; // Логируем первые 5 запросов
    
    // Счетчик для логирования случаев с total=0
    private final AtomicInteger zeroTotalCount = new AtomicInteger(0);
    private static final int MAX_ZERO_TOTAL_SAMPLES = 20; // Логируем первые 20 случаев с total=0
    
    // Счетчик для записи JSON ответов в файлы
    private final AtomicInteger zeroTotalJsonFilesCount = new AtomicInteger(0);
    private static final int MAX_ZERO_TOTAL_JSON_FILES = 10; // Записываем первые 10 JSON ответов
    
    // Временное хранилище cookies для доступа в parseJsonResponse
    private Set<HttpCookie> currentCookies = null;
    
    /**
     * Проверяет валидность одного URL: статус 200 и количество товаров
     * С retry механизмом для пустых ответов (из-за высокой нагрузки)
     */
    private UrlValidationResult validateUrl(String url, Set<HttpCookie> cookies) {
        return validateUrlWithRetry(url, cookies, 3); // 3 попытки
    }
    
    /**
     * Проверяет валидность URL с retry механизмом
     */
    private UrlValidationResult validateUrlWithRetry(String url, Set<HttpCookie> cookies, int maxRetries) {
        // Сохраняем cookies для доступа в parseJsonResponse
        currentCookies = cookies;
        
        // Проверяем, что для search API есть параметр resultset
        if (url != null && url.contains("/search/exactmatch/") && !url.contains("resultset=")) {
            // Добавляем resultset=catalog если его нет
            if (url.contains("?")) {
                url = url + "&resultset=catalog";
            } else {
                url = url + "?resultset=catalog";
            }
        }
        
        UrlValidationResult lastResult = null;
        
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                // Добавляем случайную задержку между попытками (50-200 мс)
                if (attempt > 1) {
                    int delay = 50 + (int)(Math.random() * 150);
                    Thread.sleep(delay);
                }
                
                Connection connection = Jsoup.connect(url)
                        .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 YaBrowser/25.8.0.0 Safari/537.36")
                        .method(Connection.Method.GET)
                        .ignoreContentType(true)
                        .timeout(15_000) // Увеличиваем таймаут
                        .followRedirects(true)
                        // Используем те же заголовки, что и браузер для API запросов
                        .header("Accept", "*/*")
                        .header("Accept-Language", "ru,en;q=0.9")
                        .header("Accept-Encoding", "gzip, deflate, br, zstd")
                        .header("Referer", "https://www.wildberries.ru/")
                        .header("Origin", "https://www.wildberries.ru")
                        .header("Connection", "keep-alive")
                        .header("Sec-Fetch-Dest", "empty")
                        .header("Sec-Fetch-Mode", "cors")
                        .header("Sec-Fetch-Site", "same-origin")
                        .header("Sec-Ch-Ua", "\"Not)A;Brand\";v=\"8\", \"Chromium\";v=\"138\", \"YaBrowser\";v=\"25.8\", \"Yowser\";v=\"2.5\"")
                        .header("Sec-Ch-Ua-Mobile", "?0")
                        .header("Sec-Ch-Ua-Platform", "\"Windows\"")
                        .header("Priority", "u=1, i")
                        .header("X-Requested-With", "XMLHttpRequest")
                        .header("X-Spa-Version", "13.12.0");
                
                // Добавляем специальные заголовки из cookies, если есть
                String deviceId = null;
                String userId = null;
                String authorizationToken = null;
                
                if (cookies != null && !cookies.isEmpty()) {
                    for (HttpCookie cookie : cookies) {
                        String cookieName = cookie.getName();
                        String cookieValue = cookie.getValue();
                        
                        // Ищем deviceid в cookies
                        if ("device_id_guru".equals(cookieName) && cookieValue != null && !cookieValue.isEmpty()) {
                            deviceId = "site_" + cookieValue;
                        }
                        
                        // Ищем userid - может быть в _wbauid (первые 8 цифр)
                        if ("_wbauid".equals(cookieName) && cookieValue != null && cookieValue.length() >= 8) {
                            try {
                                // Пробуем извлечь userid из начала _wbauid
                                // В примере пользователя: _wbauid=700446811740643844, x-userid=55050572
                                // Это разные значения, поэтому userid может быть в другом cookie
                                // Но попробуем использовать _wbauid как fallback
                            } catch (Exception e) {
                                // Игнорируем
                            }
                        }
                        
                        // Ищем authorization токен - обычно это JWT в специальном cookie или localStorage
                        // Но попробуем найти в cookies (может быть в x_wbaas_token или другом)
                        if (cookieName.toLowerCase().contains("token") || cookieName.toLowerCase().contains("auth")) {
                            // Если это JWT токен (начинается с eyJ), используем его
                            if (cookieValue != null && cookieValue.startsWith("eyJ")) {
                                authorizationToken = cookieValue;
                            }
                        }
                    }
                }
                
                // Добавляем deviceid заголовок
                if (deviceId != null) {
                    connection.header("Deviceid", deviceId);
                }
                
                // Добавляем authorization заголовок (Bearer токен)
                // Сначала проверяем глобальный токен (из командной строки или файла), затем из cookies
                boolean hasAuthToken = false;
                if (globalAuthorizationToken != null) {
                    connection.header("Authorization", "Bearer " + globalAuthorizationToken);
                    hasAuthToken = true;
                    if (debugRequestCount.get() < MAX_DEBUG_REQUESTS && !warningShown) {
                        System.out.println("  [INFO] Using authorization token from command line/file");
                    }
                } else if (authorizationToken != null) {
                    connection.header("Authorization", "Bearer " + authorizationToken);
                    hasAuthToken = true;
                    if (debugRequestCount.get() < MAX_DEBUG_REQUESTS && !warningShown) {
                        System.out.println("  [INFO] Using authorization token from cookies");
                    }
                } else {
                    // Выводим предупреждение только один раз
                    if (!warningShown) {
                        System.out.println("  [WARNING] No authorization token found! API may return empty results.");
                        System.out.println("  [WARNING] Use --authorization <token> or add #AUTHORIZATION=Bearer <token> to cookies file");
                        warningShown = true;
                    }
                }
                
                // Добавляем x-userid заголовок
                // Сначала проверяем глобальный userid (из командной строки или файла), затем из cookies
                boolean hasUserId = false;
                if (globalUserId != null) {
                    connection.header("X-Userid", globalUserId);
                    hasUserId = true;
                    if (debugRequestCount.get() < MAX_DEBUG_REQUESTS && !warningShown) {
                        System.out.println("  [INFO] Using userid from command line/file: " + globalUserId);
                    }
                } else if (userId != null) {
                    connection.header("X-Userid", userId);
                    hasUserId = true;
                    if (debugRequestCount.get() < MAX_DEBUG_REQUESTS && !warningShown) {
                        System.out.println("  [INFO] Using userid from cookies: " + userId);
                    }
                } else {
                    // Выводим предупреждение только один раз
                    if (!warningShown) {
                        System.out.println("  [WARNING] No userid found! Use --userid <id> or add #USERID=<id> to cookies file");
                    }
                }
                
                // Генерируем x-queryid (обычно это timestamp + случайное число)
                String queryId = "qid" + System.currentTimeMillis() + (int)(Math.random() * 1000000);
                connection.header("X-Queryid", queryId);
                
                // x-pow заголовок - это proof-of-work, сложный для генерации
                // Пока пропускаем, возможно он не обязателен для всех запросов
                
                // Добавляем cookies, если есть
                if (cookies != null && !cookies.isEmpty()) {
                    int cookieCount = 0;
                    for (HttpCookie cookie : cookies) {
                        connection.cookie(cookie.getName(), cookie.getValue());
                        cookieCount++;
                    }
                    // Логируем количество cookies только для первого запроса
                    if (debugRequestCount.get() == 0 && !warningShown) {
                        System.out.println(String.format("  [INFO] Added %d cookies to request", cookieCount));
                        // Показываем важные cookies
                        for (HttpCookie cookie : cookies) {
                            String name = cookie.getName();
                            if (name.contains("device") || name.contains("_wbauid") || name.contains("token")) {
                                System.out.println(String.format("    Cookie: %s = %s", name, 
                                    cookie.getValue() != null && cookie.getValue().length() > 50 
                                        ? cookie.getValue().substring(0, 50) + "..." 
                                        : cookie.getValue()));
                            }
                        }
                    }
                } else {
                    // Логируем, если cookies не используются (только один раз)
                    if (!warningShown) {
                        System.out.println("  [WARNING] No cookies provided! This may cause different API responses.");
                    }
                }
                
                Connection.Response response = connection.execute();
                int statusCode = response.statusCode();
                
                // Получаем тело ответа
                // ВАЖНО: Jsoup может не распаковывать gzip автоматически, особенно для больших ответов
                // Поэтому всегда получаем байты и распаковываем вручную
                String body = null;
                try {
                    // Получаем байты ответа напрямую
                    byte[] responseBytes = response.bodyAsBytes();
                    
                    if (responseBytes == null || responseBytes.length == 0) {
                        body = "";
                    } else {
                        // Проверяем заголовок Content-Encoding
                        String contentEncoding = response.header("Content-Encoding");
                        boolean isGzip = false;
                        boolean isBrotli = false;
                        
                        if (contentEncoding != null) {
                            String enc = contentEncoding.toLowerCase();
                            if (enc.contains("gzip")) {
                                isGzip = true;
                            } else if (enc.contains("br") || enc.contains("brotli")) {
                                isBrotli = true;
                            } else if (enc.contains("deflate")) {
                                isGzip = true; // deflate можно обработать через GZIPInputStream
                            }
                        }
                        
                        // Если заголовок не указан, проверяем magic number
                        if (!isGzip && !isBrotli && responseBytes.length >= 2) {
                            if (responseBytes[0] == 0x1F && responseBytes[1] == (byte)0x8B) {
                                // Gzip magic number (1F 8B)
                                isGzip = true;
                            } else {
                                // Проверяем, не является ли это бинарными данными (не JSON)
                                // Если первые символы не читаемые ASCII, вероятно это сжатые данные
                                boolean looksLikeBinary = true;
                                for (int i = 0; i < Math.min(10, responseBytes.length); i++) {
                                    byte b = responseBytes[i];
                                    if ((b >= 32 && b <= 126) || b == 9 || b == 10 || b == 13) {
                                        // Читаемый ASCII символ
                                        if (i > 0 || b == '{' || b == '[') {
                                            looksLikeBinary = false;
                                            break;
                                        }
                                    }
                                }
                                
                                // Если похоже на бинарные данные и не начинается с { или [, пробуем распаковать как gzip
                                if (looksLikeBinary && responseBytes.length > 10) {
                                    isGzip = true; // Пробуем распаковать
                                }
                            }
                        }
                        
                        if (isBrotli) {
                            // Распаковываем Brotli
                            try {
                                // Загружаем нативные библиотеки Brotli
                                try {
                                    Class<?> loaderClass = Class.forName("com.aayushatharva.brotli4j.Brotli4jLoader");
                                    java.lang.reflect.Method ensureMethod = loaderClass.getMethod("ensureAvailability");
                                    ensureMethod.invoke(null);
                                } catch (Exception e) {
                                    // Библиотека не найдена, пропускаем
                                }
                                
                                // Распаковываем Brotli через InputStream
                                try {
                                    Class<?> streamClass = Class.forName("com.aayushatharva.brotli4j.decoder.BrotliInputStream");
                                    java.io.InputStream brotliIn = (java.io.InputStream) streamClass
                                        .getConstructor(java.io.InputStream.class)
                                        .newInstance(new java.io.ByteArrayInputStream(responseBytes));
                                    
                                    java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                                    byte[] buffer = new byte[8192];
                                    int len;
                                    while ((len = brotliIn.read(buffer)) != -1) {
                                        baos.write(buffer, 0, len);
                                    }
                                    body = baos.toString("UTF-8");
                                    brotliIn.close();
                                    baos.close();
                                    
                                    if (debugRequestCount.get() < MAX_DEBUG_REQUESTS) {
                                        System.out.println("  [INFO] Decompressed Brotli response (" + responseBytes.length + " -> " + body.length() + " bytes)");
                                    }
                                } catch (ClassNotFoundException e) {
                                    throw new Exception("Brotli library not found");
                                }
                            } catch (Exception e) {
                                // Если не получилось распаковать, пробуем как обычный текст
                                if (debugRequestCount.get() < MAX_DEBUG_REQUESTS) {
                                    System.out.println("  [WARNING] Failed to decompress Brotli, trying as plain text: " + e.getClass().getSimpleName() + " - " + e.getMessage());
                                }
                                body = new String(responseBytes, "UTF-8");
                            }
                        } else if (isGzip) {
                            // Распаковываем gzip
                            try {
                                java.util.zip.GZIPInputStream gzipIn = new java.util.zip.GZIPInputStream(
                                    new java.io.ByteArrayInputStream(responseBytes));
                                java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                                byte[] buffer = new byte[8192];
                                int len;
                                while ((len = gzipIn.read(buffer)) != -1) {
                                    baos.write(buffer, 0, len);
                                }
                                body = baos.toString("UTF-8");
                                gzipIn.close();
                                baos.close();
                                
                                if (debugRequestCount.get() < MAX_DEBUG_REQUESTS) {
                                    System.out.println("  [INFO] Decompressed gzip response (" + responseBytes.length + " -> " + body.length() + " bytes)");
                                }
                            } catch (java.util.zip.ZipException e) {
                                // Если это не gzip, пробуем как обычный текст
                                if (debugRequestCount.get() < MAX_DEBUG_REQUESTS) {
                                    System.out.println("  [INFO] Not gzip format, using as plain text");
                                }
                                body = new String(responseBytes, "UTF-8");
                            } catch (Exception e) {
                                // Если не получилось распаковать, пробуем как обычный текст
                                if (debugRequestCount.get() < MAX_DEBUG_REQUESTS) {
                                    System.out.println("  [WARNING] Failed to decompress, trying as plain text: " + e.getClass().getSimpleName());
                                }
                                body = new String(responseBytes, "UTF-8");
                            }
                        } else {
                            // Не сжато, просто конвертируем в строку
                            body = new String(responseBytes, "UTF-8");
                        }
                    }
                } catch (Exception e) {
                    // Fallback: пробуем получить как строку через Jsoup
                    try {
                        body = response.body();
                        if (body == null) {
                            body = "";
                        }
                        if (debugRequestCount.get() < MAX_DEBUG_REQUESTS) {
                            System.out.println("  [INFO] Used Jsoup body() fallback");
                        }
                    } catch (Exception e2) {
                        log.error("Failed to get response body: {}", e2.getMessage());
                        body = "";
                    }
                }
                
                // Если статус не 200, возвращаем ошибку
                if (statusCode != 200) {
                    String errorKey = "HTTP_" + statusCode;
                    errorStats.put(errorKey, errorStats.getOrDefault(errorKey, 0) + 1);
                    return new UrlValidationResult(false, 0, statusCode, 
                        "HTTP status: " + statusCode);
                }
                
                // Проверяем наличие тела ответа
                if (body == null || body.trim().isEmpty()) {
                    if (attempt < maxRetries) {
                        // Пробуем еще раз
                        continue;
                    }
                    errorStats.put("EMPTY_BODY", errorStats.getOrDefault("EMPTY_BODY", 0) + 1);
                    return new UrlValidationResult(false, 0, statusCode, 
                        "Empty response after " + maxRetries + " attempts");
                }
                
                // Проверяем, не является ли ответ пустым объектом {}
                String trimmedBody = body.trim();
                if (trimmedBody.equals("{}") || trimmedBody.equals("null")) {
                    if (attempt < maxRetries) {
                        // Пробуем еще раз
                        continue;
                    }
                    errorStats.put("EMPTY_JSON", errorStats.getOrDefault("EMPTY_JSON", 0) + 1);
                    return new UrlValidationResult(false, 0, statusCode, 
                        "Empty JSON object after " + maxRetries + " attempts");
                }
                
                // Парсим JSON и проверяем количество товаров
                UrlValidationResult result = parseJsonResponse(body, statusCode, url);
                
                // Если получили 0 товаров, но это валидный ответ, пробуем еще раз (возможно, из-за нагрузки)
                if (result.isValid && result.productsCount == 0 && attempt < maxRetries) {
                    // Проверяем, не пустой ли JSON объект (может быть пустой data)
                    try {
                        Gson gson = new Gson();
                        com.google.gson.JsonObject jsonObject = gson.fromJson(body, com.google.gson.JsonObject.class);
                        if (jsonObject != null && jsonObject.has("data")) {
                            com.google.gson.JsonElement dataElement = jsonObject.get("data");
                            if (dataElement.isJsonObject()) {
                                com.google.gson.JsonObject dataObj = dataElement.getAsJsonObject();
                                // Если data пустой или очень маленький, пробуем еще раз
                                if (dataObj.entrySet().isEmpty() || 
                                    (dataObj.has("total") && dataObj.get("total").getAsInt() == 0 && 
                                     (!dataObj.has("products") || dataObj.getAsJsonArray("products").size() == 0))) {
                                    // Пробуем еще раз с задержкой
                                    continue;
                                }
                            }
                        }
                    } catch (Exception e) {
                        // Игнорируем ошибки парсинга
                    }
                }
                
                // Сохраняем результат последней попытки
                lastResult = result;
                
                // Если получили валидный ответ с товарами или валидный ответ с 0 товаров (но не пустой JSON), возвращаем
                if (result.isValid) {
                    if (attempt > 1) {
                        log.debug("Successfully validated URL after {} attempts", attempt);
                    }
                    return result;
                }
                
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new UrlValidationResult(false, 0, 0, "Interrupted");
            } catch (Exception e) {
                if (attempt < maxRetries) {
                    // Пробуем еще раз
                    continue;
                }
                errorStats.put("REQUEST_ERROR", errorStats.getOrDefault("REQUEST_ERROR", 0) + 1);
                
                // Логируем первые несколько ошибок для диагностики
                int count = errorSampleCount.incrementAndGet();
                if (count <= MAX_ERROR_SAMPLES) {
                    log.warn("Request error sample [{}]: URL={}, Error={}", count, url, e.getMessage());
                }
                
                return new UrlValidationResult(false, 0, 0, 
                    "Request error after " + maxRetries + " attempts: " + e.getMessage());
            }
        }
        
        // Если все попытки не увенчались успехом, возвращаем последний результат
        return lastResult != null ? lastResult : new UrlValidationResult(false, 0, 0, 
            "Failed after " + maxRetries + " attempts");
    }
    
    /**
     * Парсит JSON ответ и извлекает количество товаров
     */
    private UrlValidationResult parseJsonResponse(String body, int statusCode, String url) {
        try {
            
            // Логируем первые несколько запросов для отладки (без полного URL)
            int debugCount = debugRequestCount.getAndIncrement();
            if (debugCount < MAX_DEBUG_REQUESTS) {
                // Извлекаем query параметр из URL для логирования
                String queryFromUrl = "N/A";
                boolean isCatalogApi = url.contains("/catalog/catalog/");
                try {
                    if (isCatalogApi) {
                        // Для catalog API: cat=ID, subject=ID или preset=ID
                        if (url.contains("cat=")) {
                            int catIndex = url.indexOf("cat=");
                            String catPart = url.substring(catIndex + 4);
                            if (catPart.contains("&")) {
                                catPart = catPart.substring(0, catPart.indexOf("&"));
                            }
                            queryFromUrl = "cat=" + catPart;
                        } else if (url.contains("preset=")) {
                            int presetIndex = url.indexOf("preset=");
                            String presetPart = url.substring(presetIndex + 7);
                            if (presetPart.contains("&")) {
                                presetPart = presetPart.substring(0, presetPart.indexOf("&"));
                            }
                            queryFromUrl = "preset=" + presetPart;
                        } else if (url.contains("subject=")) {
                            int subjectIndex = url.indexOf("subject=");
                            String subjectPart = url.substring(subjectIndex + 8);
                            if (subjectPart.contains("&")) {
                                subjectPart = subjectPart.substring(0, subjectPart.indexOf("&"));
                            }
                            queryFromUrl = "subject=" + subjectPart;
                        }
                    } else {
                        // Для search API: query=...
                        int queryIndex = url.indexOf("query=");
                        if (queryIndex > 0) {
                            String queryPart = url.substring(queryIndex + 6);
                            if (queryPart.contains("&")) {
                                queryPart = queryPart.substring(0, queryPart.indexOf("&"));
                            }
                            queryFromUrl = java.net.URLDecoder.decode(queryPart, "UTF-8");
                        }
                    }
                } catch (Exception e) {
                    // Игнорируем
                }
                
                System.out.println(String.format("\n[DEBUG Request %d]", debugCount + 1));
                System.out.println(String.format("API Type: %s", isCatalogApi ? "Catalog API" : "Search API"));
                System.out.println(String.format("Query param: %s", queryFromUrl));
                System.out.println(String.format("Status: %d", statusCode));
                if (body != null && body.length() > 0) {
                    int bodyPreview = Math.min(500, body.length());
                    System.out.println(String.format("Response preview (%d chars): %s...", body.length(), body.substring(0, bodyPreview)));
                }
            }
            
            // Парсим JSON и проверяем количество товаров
            // Реальная структура для resultset=filters: {"metadata": {...}, "data": {"filters": [...], "total": X}}
            // Реальная структура для resultset=catalog: {"metadata": {...}, "data": {"products": [...], "total": X}}
            // Также возможна: {"metadata": {...}, "products": [], "total": X} (старый формат)
            // ВАЖНО: Для resultset=filters в data может быть только filters и total, без products массива
            try {
                Gson gson = new Gson();
                
                // Используем JsonObject для более гибкого парсинга
                com.google.gson.JsonObject jsonObject = gson.fromJson(body, com.google.gson.JsonObject.class);
                if (jsonObject == null || jsonObject.isJsonNull()) {
                    errorStats.put("NO_DATA", errorStats.getOrDefault("NO_DATA", 0) + 1);
                    return new UrlValidationResult(false, 0, statusCode, 
                        "JSON is null");
                }
                
                // Проверяем, не пустой ли объект
                if (jsonObject.entrySet().isEmpty()) {
                    errorStats.put("EMPTY_JSON", errorStats.getOrDefault("EMPTY_JSON", 0) + 1);
                    return new UrlValidationResult(false, 0, statusCode, 
                        "Empty JSON object");
                }
                
                // Извлекаем query параметр из URL для проверки соответствия
                String queryFromUrl = null;
                boolean isCatalogApi = url.contains("/catalog/catalog/");
                try {
                    if (isCatalogApi) {
                        // Для catalog API: cat=ID, subject=ID или preset=ID
                        if (url.contains("cat=")) {
                            int catIndex = url.indexOf("cat=");
                            String catPart = url.substring(catIndex + 4);
                            if (catPart.contains("&")) {
                                catPart = catPart.substring(0, catPart.indexOf("&"));
                            }
                            queryFromUrl = "cat=" + catPart;
                        } else if (url.contains("preset=")) {
                            int presetIndex = url.indexOf("preset=");
                            String presetPart = url.substring(presetIndex + 7);
                            if (presetPart.contains("&")) {
                                presetPart = presetPart.substring(0, presetPart.indexOf("&"));
                            }
                            queryFromUrl = "preset=" + presetPart;
                        } else if (url.contains("subject=")) {
                            int subjectIndex = url.indexOf("subject=");
                            String subjectPart = url.substring(subjectIndex + 8);
                            if (subjectPart.contains("&")) {
                                subjectPart = subjectPart.substring(0, subjectPart.indexOf("&"));
                            }
                            queryFromUrl = "subject=" + subjectPart;
                        }
                    } else {
                        // Для search API: query=...
                        int queryIndex = url.indexOf("query=");
                        if (queryIndex > 0) {
                            String queryPart = url.substring(queryIndex + 6);
                            if (queryPart.contains("&")) {
                                queryPart = queryPart.substring(0, queryPart.indexOf("&"));
                            }
                            queryFromUrl = java.net.URLDecoder.decode(queryPart, "UTF-8");
                        }
                    }
                } catch (Exception e) {
                    // Игнорируем
                }
                
                // Проверяем соответствие metadata.name запрошенному query
                String metadataName = null;
                String catalogValue = null;
                String presetId = null;
                if (jsonObject.has("metadata") && jsonObject.get("metadata").isJsonObject()) {
                    com.google.gson.JsonObject metadataObj = jsonObject.getAsJsonObject("metadata");
                    if (metadataObj.has("name") && metadataObj.get("name").isJsonPrimitive()) {
                        metadataName = metadataObj.get("name").getAsString();
                    }
                    if (metadataObj.has("catalog_value") && metadataObj.get("catalog_value").isJsonPrimitive()) {
                        catalogValue = metadataObj.get("catalog_value").getAsString();
                        // Извлекаем preset ID из catalog_value (формат: "preset=602820788")
                        if (catalogValue != null && catalogValue.startsWith("preset=")) {
                            presetId = catalogValue.substring(7);
                        }
                        // Также может быть формат с закодированными значениями (_st0=...)
                        // В этом случае presetId остается null, но catalogValue сохраняется для анализа
                    }
                }
                
                // Декодируем metadata.name, так как он может быть URL-encoded
                String decodedMetadataName = metadataName;
                if (metadataName != null) {
                    try {
                        decodedMetadataName = java.net.URLDecoder.decode(metadataName, "UTF-8");
                    } catch (Exception e) {
                        // Если не получилось декодировать, используем исходное значение
                    }
                }
                
                // Проверяем, соответствует ли metadata.name запрошенному query (только для search API)
                boolean queryMismatch = false;
                if (!isCatalogApi && queryFromUrl != null && decodedMetadataName != null) {
                    if (!queryFromUrl.equals(decodedMetadataName)) {
                        queryMismatch = true;
                        // Логируем несоответствие
                        log.warn("Query mismatch detected! Requested: '{}', but got metadata.name: '{}' (decoded: '{}'), catalog_value: '{}'", 
                            queryFromUrl, metadataName, decodedMetadataName, catalogValue);
                        if (debugCount < MAX_DEBUG_REQUESTS) {
                            System.out.println(String.format("  [WARNING] Query mismatch! Requested: '%s', but got: '%s' (decoded: '%s', preset: %s)", 
                                queryFromUrl, metadataName, decodedMetadataName, catalogValue));
                        }
                    }
                }
                
                // Логируем структуру JSON для первых запросов
                if (debugCount < MAX_DEBUG_REQUESTS) {
                    System.out.println("  JSON structure analysis:");
                    System.out.println(String.format("    Root keys: %s", jsonObject.keySet()));
                    if (metadataName != null) {
                        // Декодируем metadata.name для отображения
                        String decodedName = metadataName;
                        try {
                            decodedName = java.net.URLDecoder.decode(metadataName, "UTF-8");
                        } catch (Exception e) {
                            // Игнорируем
                        }
                        System.out.println(String.format("    metadata.name: %s (decoded: %s)", metadataName, decodedName));
                    }
                    if (catalogValue != null) {
                        System.out.println(String.format("    metadata.catalog_value: %s", catalogValue));
                    }
                    if (presetId != null) {
                        System.out.println(String.format("    Preset ID: %s", presetId));
                    }
                    if (queryFromUrl != null) {
                        System.out.println(String.format("    Requested query: %s", queryFromUrl));
                        if (queryMismatch) {
                            System.out.println(String.format("    [MISMATCH] API returned different query!"));
                        }
                    }
                    if (jsonObject.has("data")) {
                        com.google.gson.JsonElement dataElement = jsonObject.get("data");
                        if (dataElement.isJsonObject()) {
                            com.google.gson.JsonObject dataObj = dataElement.getAsJsonObject();
                            System.out.println(String.format("    data keys: %s", dataObj.keySet()));
                            if (dataObj.has("total")) {
                                System.out.println(String.format("    data.total exists: %s", dataObj.get("total")));
                            }
                            if (dataObj.has("products")) {
                                System.out.println(String.format("    data.products exists: array with %d items", 
                                    dataObj.getAsJsonArray("products").size()));
                            }
                        }
                    }
                    if (jsonObject.has("total")) {
                        System.out.println(String.format("    root.total exists: %s", jsonObject.get("total")));
                    }
                    if (jsonObject.has("products")) {
                        System.out.println(String.format("    root.products exists: array with %d items", 
                            jsonObject.getAsJsonArray("products").size()));
                    }
                }
                
                // Пробуем извлечь total из data.total (основной путь для resultset=filters)
                if (jsonObject.has("data")) {
                    com.google.gson.JsonElement dataElement = jsonObject.get("data");
                    if (dataElement != null && dataElement.isJsonObject()) {
                        com.google.gson.JsonObject dataObj = dataElement.getAsJsonObject();
                        if (dataObj != null && dataObj.has("total")) {
                            com.google.gson.JsonElement totalElement = dataObj.get("total");
                            if (totalElement != null && !totalElement.isJsonNull()) {
                                // Пробуем как число (int или double)
                                if (totalElement.isJsonPrimitive()) {
                                    try {
                                        int total = totalElement.getAsInt();
                                        
                                        // Логируем успешное извлечение total (включая случаи с 0)
                                        if (debugCount < MAX_DEBUG_REQUESTS) {
                                            System.out.println(String.format("  [SUCCESS] Extracted total from data.total: %d", total));
                                        }
                                        
                                        // Если total = 0, проверяем, может быть есть products массив с товарами
                                        if (total == 0) {
                                            // ВАЖНО: Не сохраняем примеры для resultset=filters, так как они не возвращают товары
                                            // Для получения товаров нужен resultset=catalog
                                            boolean isFiltersResultset = url != null && url.contains("resultset=filters");
                                            
                                            int zeroTotalSample = zeroTotalCount.incrementAndGet();
                                            
                                            // Записываем JSON ответ в файл (первые 10), но только если это не resultset=filters
                                            // Или если это resultset=filters, но мы хотим показать, что нужно использовать catalog
                                            int jsonFileCount = zeroTotalJsonFilesCount.incrementAndGet();
                                            if (jsonFileCount <= MAX_ZERO_TOTAL_JSON_FILES && body != null && !body.trim().isEmpty() && !isFiltersResultset) {
                                                try {
                                                    String fileName = String.format("zero_total_response_%d.json", jsonFileCount);
                                                    java.io.File file = new java.io.File(fileName);
                                                    try (java.io.BufferedWriter writer = new java.io.BufferedWriter(new java.io.FileWriter(file))) {
                                                        writer.write("// URL: " + url + "\n");
                                                        writer.write("// Sample: " + zeroTotalSample + "\n");
                                                        writer.write("// Status: " + statusCode + "\n");
                                                        writer.write("// Cookies used: " + (currentCookies != null && !currentCookies.isEmpty() ? currentCookies.size() + " cookies" : "NO COOKIES") + "\n");
                                                        if (currentCookies != null && !currentCookies.isEmpty()) {
                                                            writer.write("// Cookie names: ");
                                                            boolean first = true;
                                                            for (HttpCookie cookie : currentCookies) {
                                                                if (!first) writer.write(", ");
                                                                writer.write(cookie.getName());
                                                                first = false;
                                                            }
                                                            writer.write("\n");
                                                        }
                                                        writer.write("// Date: " + new java.util.Date() + "\n\n");
                                                        writer.write(body);
                                                        writer.flush();
                                                    }
                                                    System.out.println(String.format("  [SAVED] JSON response saved to: %s", file.getAbsolutePath()));
                                                    System.out.println(String.format("  [INFO] Cookies used: %s", 
                                                        currentCookies != null && !currentCookies.isEmpty() ? currentCookies.size() + " cookies" : "NO COOKIES"));
                                                } catch (Exception e) {
                                                    System.err.println(String.format("  [ERROR] Failed to save JSON to file: %s", e.getMessage()));
                                                }
                                            }
                                            
                                            // Выводим примеры только если это не resultset=filters (они не возвращают товары)
                                            if (zeroTotalSample <= MAX_ZERO_TOTAL_SAMPLES && !isFiltersResultset) {
                                                System.out.println(String.format("\n[ZERO_TOTAL Sample %d]", zeroTotalSample));
                                                // Выводим полный URL для диагностики
                                                System.out.println(String.format("  Full URL: %s", url));
                                                // Проверяем наличие resultset в URL
                                                boolean hasResultset = url.contains("resultset=");
                                                System.out.println(String.format("  Has resultset parameter: %s", hasResultset));
                                                if (hasResultset) {
                                                    // Извлекаем значение resultset
                                                    try {
                                                        int resultsetIndex = url.indexOf("resultset=");
                                                        if (resultsetIndex > 0) {
                                                            String resultsetPart = url.substring(resultsetIndex + 10);
                                                            if (resultsetPart.contains("&")) {
                                                                resultsetPart = resultsetPart.substring(0, resultsetPart.indexOf("&"));
                                                            }
                                                            System.out.println(String.format("  resultset value: %s", resultsetPart));
                                                            
                                                            // Если resultset=catalog, но total=0, объясняем возможные причины
                                                            if ("catalog".equals(resultsetPart)) {
                                                                System.out.println("  [NOTE] This URL uses resultset=catalog (correct for getting products)");
                                                                System.out.println("  [NOTE] If you see products in browser but program shows 0, possible reasons:");
                                                                System.out.println("    1. Browser has authorization token (program doesn't)");
                                                                System.out.println("    2. Filter ffeedbackpoints=1 (only products with cashback)");
                                                                System.out.println("    3. Different dest parameter or other filters");
                                                            }
                                                        }
                                                    } catch (Exception e) {
                                                        System.out.println(String.format("  Error extracting resultset: %s", e.getMessage()));
                                                    }
                                                }
                                                // Выводим реальное значение data.total из JSON (проверяем еще раз, так как мы уже в блоке total == 0)
                                                // Также логируем часть JSON для диагностики
                                                System.out.println(String.format("  JSON body preview (last 500 chars): %s", 
                                                    body.length() > 500 ? body.substring(body.length() - 500) : body));
                                                
                                                if (dataObj.has("total")) {
                                                    try {
                                                        com.google.gson.JsonElement totalElementCheck = dataObj.get("total");
                                                        if (totalElementCheck != null && !totalElementCheck.isJsonNull()) {
                                                            if (totalElementCheck.isJsonPrimitive()) {
                                                                int actualTotal = totalElementCheck.getAsInt();
                                                                System.out.println(String.format("  data.total actual value from JSON: %d", actualTotal));
                                                                // Если total > 0, но мы попали в блок с total == 0, значит была ошибка парсинга
                                                                if (actualTotal > 0) {
                                                                    System.out.println(String.format("  [WARNING] Found total=%d but code thinks it's 0! This is a parsing bug.", actualTotal));
                                                                    // Исправляем и возвращаем правильное значение
                                                                    return new UrlValidationResult(true, actualTotal, statusCode, null, 
                                                                        queryMismatch, queryFromUrl, decodedMetadataName, presetId);
                                                                }
                                                            } else {
                                                                System.out.println(String.format("  data.total exists but is not a number: %s", totalElementCheck));
                                                            }
                                                        } else {
                                                            System.out.println("  data.total is null or JsonNull");
                                                        }
                                                    } catch (Exception e) {
                                                        System.out.println(String.format("  Error reading data.total: %s", e.getMessage()));
                                                        e.printStackTrace();
                                                    }
                                                } else {
                                                    System.out.println("  data.total: not found in data object");
                                                }
                                                if (dataObj.has("products")) {
                                                    if (dataObj.get("products").isJsonArray()) {
                                                        int productsCount = dataObj.getAsJsonArray("products").size();
                                                        System.out.println(String.format("  data.products array size: %d", productsCount));
                                                        if (productsCount > 0) {
                                                            System.out.println(String.format("  [FIX] Using products count instead of total: %d", productsCount));
                                                            return new UrlValidationResult(true, productsCount, statusCode, null, 
                                                                queryMismatch, queryFromUrl, decodedMetadataName, presetId);
                                                        }
                                                    } else {
                                                        System.out.println(String.format("  data.products exists but is not an array: %s", 
                                                            dataObj.get("products").getClass().getSimpleName()));
                                                    }
                                                } else {
                                                    System.out.println("  data.products: not found");
                                                }
                                                System.out.println(String.format("  data keys: %s", dataObj.keySet()));
                                            }
                                            
                                            // Проверяем products массив даже если не логируем
                                            if (dataObj.has("products") && dataObj.get("products").isJsonArray()) {
                                                int productsCount = dataObj.getAsJsonArray("products").size();
                                                if (productsCount > 0) {
                                                    // Используем количество товаров из массива, если total = 0, но есть товары
                                                    if (zeroTotalSample <= MAX_ZERO_TOTAL_SAMPLES) {
                                                        System.out.println(String.format("  [INFO] data.total=0 but found %d products in array, using products count", productsCount));
                                                    }
                                                    return new UrlValidationResult(true, productsCount, statusCode, null, 
                                                        queryMismatch, queryFromUrl, decodedMetadataName, presetId);
                                                }
                                            }
                                        }
                                        
                                        return new UrlValidationResult(true, total, statusCode, null, 
                                            queryMismatch, queryFromUrl, decodedMetadataName, presetId);
                                    } catch (NumberFormatException e) {
                                        // Если не int, пробуем double и округляем
                                        try {
                                            double totalDouble = totalElement.getAsDouble();
                                            int total = (int) totalDouble;
                                            
                                            // Аналогичная проверка для double
                                            if (total == 0 && dataObj.has("products") && dataObj.get("products").isJsonArray()) {
                                                int productsCount = dataObj.getAsJsonArray("products").size();
                                                if (productsCount > 0) {
                                                    if (debugCount < MAX_DEBUG_REQUESTS) {
                                                        System.out.println(String.format("  [INFO] data.total=0 (double) but found %d products in array, using products count", productsCount));
                                                    }
                                                    return new UrlValidationResult(true, productsCount, statusCode, null, 
                                                        queryMismatch, queryFromUrl, decodedMetadataName, presetId);
                                                }
                                            }
                                            
                                            return new UrlValidationResult(true, total, statusCode, null, 
                                                queryMismatch, queryFromUrl, decodedMetadataName, presetId);
                                        } catch (Exception e2) {
                                            // Игнорируем и пробуем дальше
                                            if (debugCount < MAX_DEBUG_REQUESTS) {
                                                System.out.println(String.format("  [WARNING] Failed to parse data.total as number: %s", e2.getMessage()));
                                            }
                                        }
                                    }
                                } else {
                                    // total не является примитивом
                                    if (debugCount < MAX_DEBUG_REQUESTS) {
                                        System.out.println(String.format("  [WARNING] data.total exists but is not a primitive: %s", totalElement.getClass().getSimpleName()));
                                    }
                                }
                            } else {
                                // totalElement is null или JsonNull
                                if (debugCount < MAX_DEBUG_REQUESTS) {
                                    System.out.println("  [WARNING] data.total exists but is null");
                                }
                            }
                        } else {
                            // data.total не существует
                            if (debugCount < MAX_DEBUG_REQUESTS) {
                                System.out.println(String.format("  [INFO] data.total not found in data object. Data keys: %s", dataObj.keySet()));
                            }
                        }
                    }
                }
                
                // Пробуем total в корне
                if (jsonObject.has("total")) {
                    com.google.gson.JsonElement totalElement = jsonObject.get("total");
                    if (totalElement != null && !totalElement.isJsonNull() && totalElement.isJsonPrimitive()) {
                        try {
                            int total = totalElement.getAsInt();
                            if (debugCount < MAX_DEBUG_REQUESTS) {
                                System.out.println(String.format("  [SUCCESS] Extracted total from root: %d", total));
                            }
                            return new UrlValidationResult(true, total, statusCode, null, 
                                queryMismatch, queryFromUrl, decodedMetadataName, presetId);
                        } catch (NumberFormatException e) {
                            try {
                                double totalDouble = totalElement.getAsDouble();
                                int total = (int) totalDouble;
                                if (debugCount < MAX_DEBUG_REQUESTS) {
                                    System.out.println(String.format("  [SUCCESS] Extracted total from root (double): %d", total));
                                }
                                return new UrlValidationResult(true, total, statusCode, null, 
                                    queryMismatch, queryFromUrl, metadataName, presetId);
                            } catch (Exception e2) {
                                // Игнорируем и пробуем дальше
                            }
                        }
                    }
                }
                
                // Для resultset=catalog может быть структура с products массивом
                // Проверяем data.products.length как fallback
                if (jsonObject.has("data") && jsonObject.get("data").isJsonObject()) {
                    com.google.gson.JsonObject dataObj = jsonObject.getAsJsonObject("data");
                    if (dataObj != null && dataObj.has("products") && dataObj.get("products").isJsonArray()) {
                        int productsCount = dataObj.getAsJsonArray("products").size();
                        if (debugCount < MAX_DEBUG_REQUESTS) {
                            System.out.println(String.format("  [INFO] Found products array with %d items", productsCount));
                        }
                        // Если есть products, но нет total или total = 0, используем количество продуктов
                        boolean hasTotal = dataObj.has("total");
                        boolean totalIsZero = false;
                        if (hasTotal) {
                            try {
                                com.google.gson.JsonElement totalElement = dataObj.get("total");
                                if (totalElement != null && !totalElement.isJsonNull() && totalElement.isJsonPrimitive()) {
                                    int totalValue = totalElement.getAsInt();
                                    totalIsZero = (totalValue == 0);
                                }
                            } catch (Exception e) {
                                // Игнорируем
                            }
                        }
                        
                        if (productsCount > 0 && (!hasTotal || totalIsZero)) {
                            if (debugCount < MAX_DEBUG_REQUESTS) {
                                System.out.println(String.format("  [FALLBACK] Using products array size as total: %d (data.total %s)", 
                                    productsCount, hasTotal ? "= 0" : "not found"));
                            }
                            return new UrlValidationResult(true, productsCount, statusCode, null, 
                                queryMismatch, queryFromUrl, decodedMetadataName, presetId);
                        }
                    }
                }
                
                // Если не нашли total через JsonObject, пробуем стандартные модели как fallback
                Root root = gson.fromJson(body, Root.class);
                if (root != null && root.data != null) {
                    int total = root.data.total;
                    return new UrlValidationResult(true, total, statusCode, null, 
                        queryMismatch, queryFromUrl, decodedMetadataName, presetId);
                }
                
                SearchResponse searchResponse = gson.fromJson(body, SearchResponse.class);
                if (searchResponse != null) {
                    int total = searchResponse.total;
                    return new UrlValidationResult(true, total, statusCode, null, 
                        queryMismatch, queryFromUrl, decodedMetadataName, presetId);
                }
                
                // Если все попытки не увенчались успехом
                errorStats.put("NO_DATA", errorStats.getOrDefault("NO_DATA", 0) + 1);
                return new UrlValidationResult(false, 0, statusCode, 
                    "Failed to parse JSON or data is missing");
                
            } catch (Exception parseException) {
                errorStats.put("PARSE_ERROR", errorStats.getOrDefault("PARSE_ERROR", 0) + 1);
                return new UrlValidationResult(false, 0, statusCode, 
                    "JSON parse error: " + parseException.getMessage());
            }
            
        } catch (Exception e) {
            errorStats.put("REQUEST_ERROR", errorStats.getOrDefault("REQUEST_ERROR", 0) + 1);
            
            // Логируем первые несколько ошибок для диагностики
            int count = errorSampleCount.incrementAndGet();
            if (count <= MAX_ERROR_SAMPLES) {
                log.warn("Request error sample [{}]: URL={}, Error={}", count, url, e.getMessage());
            }
            
            return new UrlValidationResult(false, 0, 0, 
                "Request error: " + e.getMessage());
        }
    }
    
    /**
     * Статистика проверки валидности
     */
    public static class ValidationStats {
        public int total = 0;
        public int valid = 0;              // Валидных URL (статус 200)
        public int urlsWithProducts = 0;   // URL с товарами (total > 0) - С ФИЛЬТРОМ ffeedbackpoints=1
        public int invalid = 0;
        public int errors = 0;
        public long totalProducts = 0;     // Общее количество товаров во всех валидных URL - С ФИЛЬТРОМ ffeedbackpoints=1
        public long durationMs = 0;
        public int queryMismatches = 0;    // Количество URL с несоответствием query (metadata.name != запрошенный query)
    }
    
    /**
     * Класс для хранения информации о URL
     */
    public static class SearchUrlInfo {
        public final int categoryId;
        public final String categoryName;
        public final String catalogUrl;
        public final UrlBuilder.QueryType queryType;
        public final int dest;
        public final String resultset;
        public final String apiUrl;
        
        SearchUrlInfo(int categoryId, String categoryName, String catalogUrl,
                     UrlBuilder.QueryType queryType, int dest, String resultset, String apiUrl) {
            this.categoryId = categoryId;
            this.categoryName = categoryName;
            this.catalogUrl = catalogUrl;
            this.queryType = queryType;
            this.dest = dest;
            this.resultset = resultset;
            this.apiUrl = apiUrl;
        }
    }
    
    /**
     * Тестирует конкретные URL из примеров пользователя
     */
    public void testExampleUrls(Set<HttpCookie> cookies) {
        System.out.println("\n=== Testing example URLs from user ===");
        
        String[] exampleUrls = {
            "https://www.wildberries.ru/__internal/recom/personal/ru/male/v8/search?ab_testing=false&ab_testing=false&appType=1&curr=rub&dest=-8337854&hide_dtype=11&lang=ru&mdg=2&page=1&query=55050572&resultset=catalog&spp=30&suppressSpellcheck=false&uclusters=1",
            "https://www.wildberries.ru/__internal/search/exactmatch/ru/common/v18/search?ab_testing=false&ab_testing=false&appType=1&curr=rub&dest=-8337854&ffeedbackpoints=1&hide_dtype=11&lang=ru&mdg=2&page=1&query=menu_v3_9172%20%D0%BA%D0%BD%D0%B8%D0%B3%D0%B8%20%D0%B4%D0%BB%D1%8F%20%D0%B4%D0%B5%D1%82%D0%B5%D0%B9%20%D1%8D%D0%BD%D1%86%D0%B8%D0%BA%D0%BB%D0%BE%D0%BF%D0%B5%D0%B4%D0%B8%D0%B8&resultset=catalog&sort=popular&spp=30&suppressSpellcheck=false&uclusters=1",
            "https://www.wildberries.ru/__internal/search/exactmatch/ru/common/v18/search?ab_testing=false&ab_testing=false&appType=1&curr=rub&dest=-8337854&ffeedbackpoints=1&hide_dtype=11&lang=ru&mdg=2&page=1&query=menu_mined_subject_v2_9113&resultset=catalog&sort=popular&spp=30&suppressSpellcheck=false&uclusters=1",
            "https://www.wildberries.ru/__internal/search/exactmatch/ru/common/v18/search?ab_testing=false&ab_testing=false&appType=1&curr=rub&dest=-8337854&ffeedbackpoints=1&hide_dtype=11&lang=ru&mdg=2&page=1&query=menu_v3_131703%20%D0%B2%D0%B0%D0%BD%D0%BD%D0%B0&resultset=catalog&sort=popular&spp=30&suppressSpellcheck=false&uclusters=1"
        };
        
        for (int i = 0; i < exampleUrls.length; i++) {
            String url = exampleUrls[i];
            System.out.println(String.format("\n[Example URL %d]", i + 1));
            System.out.println("URL: " + url);
            
            UrlValidationResult result = validateUrl(url, cookies);
            System.out.println(String.format("Status: %d", result.statusCode));
            System.out.println(String.format("Valid: %s", result.isValid));
            System.out.println(String.format("Products: %d", result.productsCount));
            if (result.errorMessage != null) {
                System.out.println("Error: " + result.errorMessage);
            }
        }
    }
    
    /**
     * Загружает cookies из файла
     * Формат файла: каждая строка содержит cookie в формате "name=value" или "name=value; domain=.wildberries.ru"
     */
    private static Set<HttpCookie> loadCookiesFromFile(String filePath) {
        Set<HttpCookie> cookies = new HashSet<>();
        try {
            java.io.File file = new java.io.File(filePath);
            if (!file.exists()) {
                System.out.println("Cookie file not found: " + filePath);
                return cookies;
            }
            
            java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.FileReader(file));
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }
                
                // Проверяем специальные директивы
                if (line.startsWith("#AUTHORIZATION=")) {
                    globalAuthorizationToken = line.substring(15).trim();
                    System.out.println("Loaded authorization token from file");
                    continue;
                } else if (line.startsWith("#USERID=")) {
                    globalUserId = line.substring(8).trim();
                    System.out.println("Loaded userid from file: " + globalUserId);
                    continue;
                } else if (line.startsWith("#")) {
                    continue; // Пропускаем обычные комментарии
                }
                
                // Парсим строку формата "name=value" или "name=value; domain=..."
                String[] parts = line.split(";");
                String nameValue = parts[0].trim();
                if (nameValue.contains("=")) {
                    String[] nv = nameValue.split("=", 2);
                    String name = nv[0].trim();
                    String value = nv.length > 1 ? nv[1].trim() : "";
                    
                    HttpCookie cookie = new HttpCookie(name, value);
                    cookie.setDomain(".wildberries.ru");
                    cookie.setPath("/");
                    cookie.setVersion(0);
                    
                    // Парсим дополнительные параметры (domain, path, etc.)
                    for (int i = 1; i < parts.length; i++) {
                        String param = parts[i].trim();
                        if (param.toLowerCase().startsWith("domain=")) {
                            cookie.setDomain(param.substring(7).trim());
                        } else if (param.toLowerCase().startsWith("path=")) {
                            cookie.setPath(param.substring(5).trim());
                        }
                    }
                    
                    cookies.add(cookie);
                }
            }
            reader.close();
            System.out.println("Loaded " + cookies.size() + " cookies from file: " + filePath);
        } catch (Exception e) {
            System.err.println("Error loading cookies from file: " + e.getMessage());
            e.printStackTrace();
        }
        return cookies;
    }
    
    /**
     * Читает cookies из базы данных Chrome/Edge
     * Путь к базе: %LOCALAPPDATA%\Google\Chrome\User Data\Default\Cookies
     * или: %LOCALAPPDATA%\Microsoft\Edge\User Data\Default\Cookies
     */
    private static Set<HttpCookie> loadCookiesFromChrome(String browserPath) {
        Set<HttpCookie> cookies = new HashSet<>();
        try {
            java.io.File cookieFile = new java.io.File(browserPath);
            if (!cookieFile.exists()) {
                System.out.println("Chrome cookies file not found: " + browserPath);
                return cookies;
            }
            
            // Копируем файл, так как Chrome может блокировать доступ к нему
            java.io.File tempFile = java.io.File.createTempFile("chrome_cookies", ".db");
            try {
                java.nio.file.Files.copy(cookieFile.toPath(), tempFile.toPath(), 
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                
                // Подключаемся к SQLite базе
                java.sql.Connection conn = java.sql.DriverManager.getConnection("jdbc:sqlite:" + tempFile.getAbsolutePath());
                java.sql.Statement stmt = conn.createStatement();
                
                // Читаем cookies для wildberries.ru
                String sql = "SELECT name, value, host_key, path, expires_utc, is_secure, is_httponly " +
                             "FROM cookies WHERE host_key LIKE '%wildberries.ru%'";
                java.sql.ResultSet rs = stmt.executeQuery(sql);
                
                int count = 0;
                while (rs.next()) {
                    String name = rs.getString("name");
                    String value = rs.getString("value");
                    String domain = rs.getString("host_key");
                    String path = rs.getString("path");
                    long expires = rs.getLong("expires_utc");
                    boolean secure = rs.getBoolean("is_secure");
                    boolean httpOnly = rs.getBoolean("is_httponly");
                    
                    // Chrome хранит expires_utc как WebKit timestamp (микросекунды с 1601-01-01)
                    // Конвертируем в секунды с 1970-01-01
                    long expiresSeconds = 0;
                    if (expires > 0) {
                        expiresSeconds = (expires / 1000000) - 11644473600L;
                    }
                    
                    HttpCookie cookie = new HttpCookie(name, value);
                    cookie.setDomain(domain.startsWith(".") ? domain : "." + domain);
                    cookie.setPath(path != null ? path : "/");
                    if (expiresSeconds > 0) {
                        cookie.setMaxAge(expiresSeconds - (System.currentTimeMillis() / 1000));
                    }
                    cookie.setSecure(secure);
                    cookie.setHttpOnly(httpOnly);
                    
                    cookies.add(cookie);
                    count++;
                }
                
                rs.close();
                stmt.close();
                conn.close();
                
                System.out.println("Loaded " + count + " cookies from Chrome database: " + browserPath);
            } finally {
                tempFile.delete();
            }
        } catch (Exception e) {
            System.err.println("Error loading cookies from Chrome: " + e.getMessage());
            e.printStackTrace();
        }
        return cookies;
    }
    
    /**
     * Автоматически находит и загружает cookies из Chrome или Edge
     */
    private static Set<HttpCookie> loadCookiesFromBrowser() {
        Set<HttpCookie> cookies = new HashSet<>();
        
        // Стандартные пути к базам данных cookies в Windows
        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData == null) {
            System.out.println("LOCALAPPDATA environment variable not found. Cannot auto-detect browser cookies.");
            return cookies;
        }
        
        // Пробуем Chrome
        String chromePath = localAppData + "\\Google\\Chrome\\User Data\\Default\\Cookies";
        java.io.File chromeFile = new java.io.File(chromePath);
        if (chromeFile.exists()) {
            System.out.println("Found Chrome cookies database, loading...");
            cookies = loadCookiesFromChrome(chromePath);
            if (!cookies.isEmpty()) {
                return cookies;
            }
        }
        
        // Пробуем Edge
        String edgePath = localAppData + "\\Microsoft\\Edge\\User Data\\Default\\Cookies";
        java.io.File edgeFile = new java.io.File(edgePath);
        if (edgeFile.exists()) {
            System.out.println("Found Edge cookies database, loading...");
            cookies = loadCookiesFromChrome(edgePath);
            if (!cookies.isEmpty()) {
                return cookies;
            }
        }
        
        System.out.println("No browser cookies database found. Will try HTTP request instead.");
        return cookies;
    }
    
    /**
     * Получает cookies автоматически через HTTP запрос к Wildberries
     * Делает несколько запросов для установки сессии
     */
    private static Set<HttpCookie> getCookiesFromWildberries() {
        Set<HttpCookie> cookies = new HashSet<>();
        try {
            java.net.CookieManager cookieManager = new java.net.CookieManager();
            cookieManager.setCookiePolicy(java.net.CookiePolicy.ACCEPT_ALL);
            
            java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                    .cookieHandler(cookieManager)
                    .build();
            
            // Делаем несколько запросов для установки сессии
            String[] urls = {
                "https://www.wildberries.ru/",
                "https://www.wildberries.ru/catalog/0/search.aspx"
            };
            
            for (String url : urls) {
                try {
                    java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                            .uri(java.net.URI.create(url))
                            .GET()
                            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 YaBrowser/25.8.0.0 Safari/537.36")
                            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                            .header("Accept-Language", "ru,en;q=0.9")
                            .build();
                    
                    java.net.http.HttpResponse<String> response = client.send(request, 
                            java.net.http.HttpResponse.BodyHandlers.ofString());
                    
                    if (response.statusCode() == 200) {
                        cookies.addAll(cookieManager.getCookieStore().getCookies());
                    }
                    
                    // Небольшая задержка между запросами
                    Thread.sleep(500);
                } catch (Exception e) {
                    // Игнорируем ошибки отдельных запросов
                }
            }
            
            if (!cookies.isEmpty()) {
                System.out.println("Automatically loaded " + cookies.size() + " cookies from Wildberries via HTTP");
            }
        } catch (Exception e) {
            System.err.println("Error getting cookies from Wildberries: " + e.getMessage());
        }
        return cookies;
    }
    
    /**
     * Основной метод для запуска генерации
     */
    public static void main(String[] args) {
        SearchUrlGenerator generator = new SearchUrlGenerator();
        
        try {
            // Получаем cookies
            Set<HttpCookie> cookies = new HashSet<>();
            
            // Проверяем аргументы командной строки для загрузки cookies
            if (args.length > 0) {
                // Если первый аргумент - путь к файлу с cookies
                if (args[0].endsWith(".txt") || args[0].endsWith(".cookies")) {
                    cookies = loadCookiesFromFile(args[0]);
                    // Сдвигаем аргументы, если нужно
                    String[] newArgs = new String[args.length - 1];
                    System.arraycopy(args, 1, newArgs, 0, args.length - 1);
                    args = newArgs;
                } else if ("--cookies-auto".equals(args[0])) {
                    // Автоматически получаем cookies
                    cookies = getCookiesFromWildberries();
                    // Сдвигаем аргументы
                    String[] newArgs = new String[args.length - 1];
                    System.arraycopy(args, 1, newArgs, 0, args.length - 1);
                    args = newArgs;
                } else if ("--cookies-browser".equals(args[0])) {
                    // Загружаем cookies из браузера (Chrome/Edge)
                    cookies = loadCookiesFromBrowser();
                    // Если не получилось из браузера, пробуем HTTP
                    if (cookies.isEmpty()) {
                        System.out.println("Failed to load from browser, trying HTTP request...");
                        cookies = getCookiesFromWildberries();
                    }
                    // Сдвигаем аргументы
                    String[] newArgs = new String[args.length - 1];
                    System.arraycopy(args, 1, newArgs, 0, args.length - 1);
                    args = newArgs;
                } else if (args.length > 1 && "--cookies-file".equals(args[0])) {
                    // Загружаем cookies из файла, указанного следующим аргументом
                    cookies = loadCookiesFromFile(args[1]);
                    // Сдвигаем аргументы
                    String[] newArgs = new String[args.length - 2];
                    System.arraycopy(args, 2, newArgs, 0, args.length - 2);
                    args = newArgs;
                } else if (args.length > 1 && "--authorization".equals(args[0])) {
                    // Устанавливаем authorization токен из командной строки
                    globalAuthorizationToken = args[1];
                    System.out.println("Authorization token set from command line");
                    // Сдвигаем аргументы
                    String[] newArgs = new String[args.length - 2];
                    System.arraycopy(args, 2, newArgs, 0, args.length - 2);
                    args = newArgs;
                } else if (args.length > 1 && "--userid".equals(args[0])) {
                    // Устанавливаем userid из командной строки
                    globalUserId = args[1];
                    System.out.println("Userid set from command line: " + globalUserId);
                    // Сдвигаем аргументы
                    String[] newArgs = new String[args.length - 2];
                    System.arraycopy(args, 2, newArgs, 0, args.length - 2);
                    args = newArgs;
                }
            }
            
            // Если cookies не загружены, пробуем загрузить из браузера, затем HTTP
            if (cookies.isEmpty()) {
                System.out.println("No cookies provided. Trying to load from browser...");
                cookies = loadCookiesFromBrowser();
                // Если не получилось из браузера, пробуем HTTP
                if (cookies.isEmpty()) {
                    System.out.println("Failed to load from browser, trying HTTP request...");
                    cookies = getCookiesFromWildberries();
                }
            }
            
            // Выводим информацию о загруженных данных
            System.out.println("\n=== Loaded Configuration ===");
            System.out.println("Cookies loaded: " + cookies.size());
            System.out.println("Authorization token: " + (globalAuthorizationToken != null ? "YES (from command line/file)" : "NO"));
            System.out.println("Userid: " + (globalUserId != null ? globalUserId + " (from command line/file)" : "NO"));
            
            if (cookies.isEmpty() && globalAuthorizationToken == null) {
                System.out.println("\n[CRITICAL WARNING] No cookies and no authorization token!");
                System.out.println("The API will likely return empty results (total: 0) for all categories.");
                System.out.println("\nTo fix this:");
                System.out.println("1. Get authorization token from browser:");
                System.out.println("   - Open DevTools (F12) -> Application -> Local Storage -> https://www.wildberries.ru");
                System.out.println("   - Find token key (may be named 'token', 'auth', 'authorization', etc.)");
                System.out.println("   - Copy the value");
                System.out.println("2. Run with: --authorization <token> --userid <userid>");
                System.out.println("   OR add to cookies.txt:");
                System.out.println("   #AUTHORIZATION=Bearer <token>");
                System.out.println("   #USERID=<userid>");
                System.out.println();
            }
            
            // Если передан аргумент "examples", тестируем примеры URL
            if (args.length > 0 && "examples".equals(args[0])) {
                generator.testExampleUrls(cookies);
                return;
            }
            
            // Генерируем URL из API каталога (только один вариант на категорию)
            generator.generateUrlsFromCatalogApi(cookies);
            
            System.out.println("\nGenerated " + generator.getGeneratedUrls().size() + " URLs (1 per category)");
            
            // Если передан аргумент "test", тестируем разные варианты URL
            if (args.length > 0 && "test".equals(args[0])) {
                System.out.println("\n=== Testing different URL variants ===");
                generator.testAndSelectBestUrlVariant(cookies, 50); // Тестируем на 50 категориях
                return;
            }
            
            // Проверяем валидность всех URL
            // Уменьшаем количество одновременных запросов для снижения нагрузки
            System.out.println("\n=== Starting URL validation ===");
            ValidationStats stats = generator.validateAllUrls(cookies, 100); // 100 concurrent requests для быстрой обработки
            
            System.out.println("\n=== Generation and validation completed! ===");
            System.out.println("\nValidation statistics:");
            System.out.println("  Total URLs: " + stats.total);
            System.out.println("  Valid (status 200): " + stats.valid + " (" + String.format("%.2f", stats.valid * 100.0 / stats.total) + "%)");
            System.out.println("  With products (total > 0): " + stats.urlsWithProducts + " (" + String.format("%.2f", stats.urlsWithProducts * 100.0 / stats.total) + "%)");
            System.out.println("  Total products in all valid URLs: " + stats.totalProducts);
            System.out.println("  Invalid: " + stats.invalid + " (" + String.format("%.2f", stats.invalid * 100.0 / stats.total) + "%)");
            
            // Дополнительная информация о категориях с 0 товарами
            int validWithZeroProducts = stats.valid - stats.urlsWithProducts;
            if (validWithZeroProducts > 0) {
                System.out.println("\n=== Analysis: Categories with 0 products ===");
                System.out.println("  Valid URLs with 0 products: " + validWithZeroProducts + " (" + 
                    String.format("%.2f", stats.valid > 0 ? validWithZeroProducts * 100.0 / stats.valid : 0.0) + "% of valid URLs)");
                System.out.println("  NOTE: Validation uses ffeedbackpoints=1 filter (only products with cashback).");
                System.out.println("        Many categories may have products, but without cashback, so they show 0 products.");
                System.out.println("        This is expected behavior if you want to monitor only products with cashback.");
            }
            
        } catch (Exception e) {
            System.err.println("Error generating URLs: " + e.getMessage());
            e.printStackTrace();
        }
    }
}


