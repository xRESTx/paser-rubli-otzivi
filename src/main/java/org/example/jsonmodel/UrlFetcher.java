package org.example.jsonmodel;

import com.google.gson.annotations.SerializedName;

import java.util.List;

public class UrlFetcher {
    public static class Root {
        public Promo promo;
        public List<MenuNode> menu;
    }

    public static class Promo {
        public int id;
        public String name;
    }

    public static class MenuNode {
        public int id;
        public String name;
        public String url;
        @SerializedName("childNodes")
        public List<CategoryNode> childNodes;
    }

    public static class CategoryNode {
        public int id;
        public int parent;
        public String name;
        public String url;
        @SerializedName("shardKey")
        public String shardKey;
        public String query;
        @SerializedName("childNodes")
        public List<CategoryNode> childNodes;
    }
}
