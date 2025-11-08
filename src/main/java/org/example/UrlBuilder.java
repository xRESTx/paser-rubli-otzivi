package org.example;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Утилита для формирования URL API поиска Wildberries из JSON каталога
 */
public class UrlBuilder {
    
    // Базовый URL API поиска
    private static final String BASE_SEARCH_URL = 
        "https://www.wildberries.ru/__internal/search/exactmatch/ru/common/v18/search";
    
    // Типы query параметров
    public enum QueryType {
        MENU_V3("menu_v3"),
        MENU_REDIRECT_SUBJECT_V2("menu_redirect_subject_v2"),
        MENU_MINED_SUBJECT_V2("menu_mined_subject_v2");
        
        private final String prefix;
        
        QueryType(String prefix) {
            this.prefix = prefix;
        }
        
        public String getPrefix() {
            return prefix;
        }
    }
    
    /**
     * Формирует URL для API поиска
     * 
     * @param categoryId ID категории из JSON
     * @param categoryName Название категории из JSON
     * @param queryType Тип query (menu_v3, menu_redirect_subject_v2, menu_mined_subject_v2)
     * @param dest Регион доставки (например, -1257786)
     * @param resultset Тип результата (catalog или filters)
     * @param page Номер страницы (для resultset=catalog)
     * @return Полный URL для API поиска
     */
    public static String buildSearchUrl(int categoryId, String categoryName, 
                                       QueryType queryType, int dest, 
                                       String resultset, Integer page) {
        StringBuilder url = new StringBuilder(BASE_SEARCH_URL);
        url.append("?");
        
        // Базовые параметры
        url.append("ab_testing=false&");
        url.append("appType=1&");
        url.append("curr=rub&");
        url.append("dest=").append(dest).append("&");
        url.append("ffeedbackpoints=1&");
        url.append("hide_dtype=11&");
        url.append("lang=ru&");
        
        // Формируем query параметр
        String queryParam = buildQueryParam(categoryId, categoryName, queryType);
        url.append("query=").append(URLEncoder.encode(queryParam, StandardCharsets.UTF_8)).append("&");
        
        // Тип результата
        url.append("resultset=").append(resultset).append("&");
        
        // Номер страницы (только для catalog)
        if (page != null && "catalog".equals(resultset)) {
            url.append("page=").append(page).append("&");
        }
        
        // Дополнительные параметры
        url.append("sort=popular&");
        url.append("spp=30&");
        url.append("suppressSpellcheck=false");
        
        return url.toString();
    }
    
    /**
     * Формирует query параметр в зависимости от типа
     */
    private static String buildQueryParam(int categoryId, String categoryName, QueryType queryType) {
        switch (queryType) {
            case MENU_V3:
            case MENU_REDIRECT_SUBJECT_V2:
                // Для этих типов добавляем название категории
                return queryType.getPrefix() + "_" + categoryId + " " + categoryName;
            case MENU_MINED_SUBJECT_V2:
                // Для этого типа название не добавляем
                return queryType.getPrefix() + "_" + categoryId;
            default:
                throw new IllegalArgumentException("Unknown query type: " + queryType);
        }
    }
    
    /**
     * Формирует все три варианта URL для категории (для тестирования)
     */
    public static Map<QueryType, String> buildAllVariants(int categoryId, String categoryName, 
                                                          int dest, String resultset, Integer page) {
        Map<QueryType, String> variants = new HashMap<>();
        
        for (QueryType type : QueryType.values()) {
            variants.put(type, buildSearchUrl(categoryId, categoryName, type, dest, resultset, page));
        }
        
        return variants;
    }
}

