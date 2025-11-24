package org.example.jsonmodel;

import java.util.List;

public class SearchResponse {
    public Metadata metadata;
    public List<Product> products;
    
    public static class Metadata {
        public String name;
        public String catalog_type;
        public String catalog_value;
    }
}

