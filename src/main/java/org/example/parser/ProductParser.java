package org.example.parser;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import org.example.jsonmodel.Data;
import org.example.jsonmodel.Product;
import org.example.jsonmodel.Root;

import java.lang.reflect.Type;
import java.util.Collections;
import java.util.List;

/**
 * Centralized JSON parsing for Wildberries catalog responses.
 */
public final class ProductParser {

    private static final Type PRODUCT_LIST_TYPE = new TypeToken<List<Product>>() {}.getType();
    private final Gson gson;

    public ProductParser(Gson gson) {
        this.gson = gson;
    }

    public CatalogPage parseCatalog(String json) {
        if (json == null || json.isBlank()) {
            return CatalogPage.empty();
        }
        Data data = parseAsData(json);
        if (data == null) {
            org.slf4j.LoggerFactory.getLogger(ProductParser.class).warn("Failed to parse catalog JSON: data is null");
            return CatalogPage.empty();
        }
        if (data.products == null || data.products.isEmpty()) {
            org.slf4j.LoggerFactory.getLogger(ProductParser.class).debug("Catalog JSON parsed but products list is null or empty (total={})", data.total);
            return CatalogPage.empty();
        }
        int totalProducts = data.total > 0 ? data.total : data.products.size();
        org.slf4j.LoggerFactory.getLogger(ProductParser.class).debug("Parsed catalog: {} products, total={}", data.products.size(), totalProducts);
        return new CatalogPage(data.products, totalProducts);
    }

    private Data parseAsData(String json) {
        org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ProductParser.class);
        try {
            Data direct = gson.fromJson(json, Data.class);
            if (direct != null && direct.products != null && !direct.products.isEmpty()) {
                log.debug("Parsed as direct Data: {} products", direct.products.size());
                return direct;
            }
        } catch (Exception e) {
            log.trace("Failed to parse as direct Data: {}", e.getMessage());
        }
        try {
            Root wrapped = gson.fromJson(json, Root.class);
            if (wrapped != null && wrapped.data != null && wrapped.data.products != null) {
                log.debug("Parsed as Root wrapper: {} products", wrapped.data.products.size());
                return wrapped.data;
            }
        } catch (Exception e) {
            log.trace("Failed to parse as Root wrapper: {}", e.getMessage());
        }
        try {
            JsonObject object = gson.fromJson(json, JsonObject.class);
            if (object != null && object.has("products")) {
                JsonArray array = object.getAsJsonArray("products");
                List<Product> products = gson.fromJson(array, PRODUCT_LIST_TYPE);
                Data data = new Data();
                data.products = products;
                if (object.has("total") && !object.get("total").isJsonNull()) {
                    data.total = object.get("total").getAsInt();
                } else {
                    data.total = products != null ? products.size() : 0;
                }
                log.debug("Parsed as JsonObject: {} products, total={}", products != null ? products.size() : 0, data.total);
                return data;
            } else {
                log.warn("JSON object does not have 'products' field. Available keys: {}", object != null ? object.keySet() : "null");
            }
        } catch (Exception e) {
            log.warn("Failed to parse as JsonObject: {}", e.getMessage());
        }
        log.warn("All parsing attempts failed for catalog JSON (length: {})", json != null ? json.length() : 0);
        return null;
    }

    public static final class CatalogPage {
        private final List<Product> products;
        private final int totalProducts;

        public CatalogPage(List<Product> products, int totalProducts) {
            this.products = products == null ? Collections.emptyList() : products;
            this.totalProducts = totalProducts;
        }

        public static CatalogPage empty() {
            return new CatalogPage(Collections.emptyList(), 0);
        }

        public List<Product> getProducts() {
            return products;
        }

        public int getTotalProducts() {
            return totalProducts;
        }
    }
}


