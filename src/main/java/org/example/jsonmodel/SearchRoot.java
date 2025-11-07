package org.example.jsonmodel;

import java.util.List;

/**
 * Модель для нового формата JSON из endpoint /__internal/u-search/exactmatch
 */
public class SearchRoot {
    public Metadata metadata;
    public List<SearchProduct> products;
    public int total;
    
    public static class Metadata {
        public String catalog_type;
        public String catalog_value;
        public String normquery;
        public String name;
        public String rmi;
        public String title;
        public String rs;
    }
}

