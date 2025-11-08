package org.example.jsonmodel;

import com.google.gson.annotations.SerializedName;

import java.util.List;

public class UrlFetcher {
    public static class RootItem {
        public int id;
        public String name;
        public String url;
        public String shard;
        public String query;
        public String searchQuery;
        boolean dynamic;
        public int[] dest; // Массив регионов доставки

        @SerializedName("childs")
        public List<Child> children;
    }

    public static class Child {
        public int id;
        int parent;
        public String name;
        String seo;
        public String url;
        public String shard;
        public String query;
        String snippet;
        public String searchQuery;
        public int[] dest; // Массив регионов доставки

        @SerializedName("childs")
        public List<Child> children;
    }
}
