package org.example.jsonmodel;

import com.google.gson.annotations.SerializedName;

import java.util.List;

public class UrlFetcher {
    public static class RootItem {
        int id;
        public String name;
        public String url;
        public String shard;
        public String query;
        boolean dynamic;

        @SerializedName("childs")
        public List<Child> children;
    }

    public static class Child {
        int id;
        int parent;
        public String name;
        String seo;
        public String url;
        public String shard;
        public String query;
        String snippet;
        public String searchQuery;

        @SerializedName("childs")
        public List<Child> children;
    }
}
