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
        if (data == null || data.products == null || data.products.isEmpty()) {
            return CatalogPage.empty();
        }
        int totalProducts = data.total > 0 ? data.total : data.products.size();
        return new CatalogPage(data.products, totalProducts);
    }

    private Data parseAsData(String json) {
        try {
            Data direct = gson.fromJson(json, Data.class);
            if (direct != null && direct.products != null && !direct.products.isEmpty()) {
                return direct;
            }
        } catch (Exception ignored) {
        }
        try {
            Root wrapped = gson.fromJson(json, Root.class);
            if (wrapped != null && wrapped.data != null && wrapped.data.products != null) {
                return wrapped.data;
            }
        } catch (Exception ignored) {
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
                    data.total = products.size();
                }
                return data;
            }
        } catch (Exception ignored) {
        }
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


