package org.example.jsonmodel;

import java.util.List;

/**
 * Модель для ответа API поиска Wildberries
 * Структура: {"metadata": {...}, "products": [], "total": 0}
 */
public class SearchResponse {
    public SearchMetadata metadata;
    public List<Product> products;
    public int total;
    
    public static class SearchMetadata {
        public String catalog_type;
        public String catalog_value;
        public Object search_result;
        public String name;
        public String rmi;
        public String title;
        public String rs;
    }
}

