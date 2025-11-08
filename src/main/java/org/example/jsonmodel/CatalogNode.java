package org.example.jsonmodel;

import com.google.gson.annotations.SerializedName;
import java.util.List;

/**
 * Модель для узла каталога из JSON
 */
public class CatalogNode {
    public int id;
    public String name;
    public String url;
    public String shardKey;
    public String rawQuery;
    public String query;
    
    @SerializedName("childrenOnly")
    public Boolean childrenOnly;
    
    public CatalogMetadata metadata;
    public List<CatalogNode> nodes;
    
    public static class CatalogMetadata {
        public String snippet;
    }
}

