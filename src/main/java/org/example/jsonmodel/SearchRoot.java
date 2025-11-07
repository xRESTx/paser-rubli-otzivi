package org.example.jsonmodel;

import java.util.List;

/**
 * Модель для нового формата JSON из endpoint /__internal/u-search/exactmatch
 * Поддерживает два формата:
 * 1. Прямой формат: {"metadata": {...}, "products": [...], "total": 123}
 * 2. Вложенный формат: {"metadata": {...}, "data": {"products": [...], "total": 123, "filters": [...]}}
 */
public class SearchRoot {
    public Metadata metadata;
    public List<SearchProduct> products;  // Прямой формат
    public int total;                     // Прямой формат
    public DataWrapper data;              // Вложенный формат
    
    public static class Metadata {
        public String catalog_type;
        public String catalog_value;
        public String normquery;
        public String name;
        public String rmi;
        public String title;
        public String rs;
    }
    
    public static class DataWrapper {
        public List<SearchProduct> products;
        public int total;
        // filters и другие поля игнорируются
    }
    
    /**
     * Получает список товаров (из прямого формата или из data)
     */
    public List<SearchProduct> getProducts() {
        if (products != null) {
            return products;
        }
        if (data != null && data.products != null) {
            return data.products;
        }
        return null;
    }
    
    /**
     * Получает общее количество товаров (из прямого формата или из data)
     */
    public int getTotal() {
        // Если есть data объект, используем его (приоритет вложенному формату)
        if (data != null) {
            return data.total;
        }
        // Иначе используем прямой формат
        return total;
    }
}

