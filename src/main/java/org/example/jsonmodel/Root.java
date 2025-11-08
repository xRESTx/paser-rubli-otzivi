package org.example.jsonmodel;

/**
 * Модель для ответа API с структурой: {"metadata": {...}, "data": {"total": X, "filters": [...], "products": [...]}}
 */
public class Root {
    public SearchResponse.SearchMetadata metadata;
    public Data data;
}
