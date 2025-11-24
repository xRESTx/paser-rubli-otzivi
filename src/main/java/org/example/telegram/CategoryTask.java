package org.example.telegram;

import com.google.gson.Gson;
import org.example.jsonmodel.UrlFetcher;
import org.jsoup.Connection;
import org.jsoup.Jsoup;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class CategoryTask {

    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 YaBrowser/25.8.0.0 Safari/537.36";
    private static volatile String clicksHeader;

    private final String categoryUrl;
    private final String shardKey;
    private final String query;
    private final String action;

    public CategoryTask(String categoryUrl, String shardKey, String query, String action) {
        this.categoryUrl = categoryUrl;
        this.shardKey = shardKey;
        this.query = query;
        this.action = action;
    }

    public String categoryUrl() {
        return categoryUrl;
    }

    public String buildPageUrl(int page) {
        StringBuilder params = new StringBuilder();
        params.append("ab_testing=false&action=").append(action);
        params.append("&appType=1&curr=rub&dest=-1257786&hide_dtype=11&lang=ru");
        if (query != null && !query.isBlank()) {
            params.append('&').append(query);
        }
        params.append("&page=").append(page);
        params.append("&sort=popular&spp=30");
        return "https://www.wildberries.ru/__internal/u-catalog/catalog/"
                + shardKey + "/v4/catalog?" + params;
    }

    public Map<String, String> defaultHeaders() {
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", USER_AGENT);
        headers.put("Accept", "*/*");
        headers.put("Accept-Language", "ru,en;q=0.9");
        headers.put("Accept-Encoding", "identity");
        headers.put("Referer", categoryUrl);
        headers.put("Origin", "https://www.wildberries.ru");
        // Connection header is restricted by Java HttpClient, skip it
        headers.put("Sec-Fetch-Dest", "empty");
        headers.put("Sec-Fetch-Mode", "cors");
        headers.put("Sec-Fetch-Site", "same-origin");
        headers.put("Sec-CH-UA", "\"Not)A;Brand\";v=\"8\", \"Chromium\";v=\"138\", \"YaBrowser\";v=\"25.8\", \"Yowser\";v=\"2.5\"");
        headers.put("Sec-CH-UA-Mobile", "?0");
        headers.put("Sec-CH-UA-Platform", "\"Windows\"");
        headers.put("Priority", "u=1, i");
        if (clicksHeader != null && !clicksHeader.isBlank()) {
            headers.put("clicks", clicksHeader);
        }
        headers.put("x-requested-with", "XMLHttpRequest");
        headers.put("x-spa-version", "13.14.1");
        headers.put("x-userid", "0");
        headers.put("deviceid", "site_2bc3dd7d2f1a4eb28539e17ff17c894a");
        return headers;
    }

    public static List<CategoryTask> load(Map<String, String> cookies) throws IOException {
        String json = fetchPromotionsJson(cookies);
        UrlFetcher.Root root = new Gson().fromJson(json, UrlFetcher.Root.class);
        if (root == null || root.menu == null || root.menu.isEmpty()) {
            return List.of();
        }
        int action = root.promo == null ? 0 : root.promo.id;
        List<CategoryTask> result = new ArrayList<>();
        for (UrlFetcher.MenuNode menuNode : root.menu) {
            if (menuNode.childNodes == null) {
                continue;
            }
            for (UrlFetcher.CategoryNode categoryNode : menuNode.childNodes) {
                processCategoryNode(categoryNode, action, result);
            }
        }
        return result;
    }

    private static void processCategoryNode(UrlFetcher.CategoryNode node, int action, List<CategoryTask> result) {
        addCategory(node, action, result);
        if (node.childNodes == null) {
            return;
        }
        for (UrlFetcher.CategoryNode child : node.childNodes) {
            processCategoryNode(child, action, result);
        }
    }

    private static void addCategory(UrlFetcher.CategoryNode node, int action, List<CategoryTask> result) {
        if (node.url == null || node.url.isBlank()) return;
        if (node.url.startsWith("https://vmeste.wildberries.ru")
                || node.url.startsWith("https://travel.wildberries.ru")
                || node.url.startsWith("https://digital.wildberries.ru")) return;
        if (node.shardKey == null || node.shardKey.isBlank()) return;
        if ("blackhole".equals(node.shardKey)) return;

        String cleanedQuery = cleanupQuery(node.query);
        String normalizedUrl = ensurePrefix(node.url);
        boolean exists = result.stream().anyMatch(ct -> ct.categoryUrl.equals(normalizedUrl));
        if (!exists) {
            result.add(new CategoryTask(normalizedUrl, node.shardKey, cleanedQuery, String.valueOf(action)));
        }
    }

    private static String cleanupQuery(String query) {
        if (query == null || query.isBlank()) {
            return "";
        }
        String cleaned = query.replaceAll("&?action=\\d+", "").replaceAll("action=\\d+&?", "");
        while (cleaned.startsWith("&")) cleaned = cleaned.substring(1);
        while (cleaned.endsWith("&")) cleaned = cleaned.substring(0, cleaned.length() - 1);
        return cleaned;
    }

    private static String ensurePrefix(String url) {
        String prefixDigital = "https://www.wildberries.ru";
        String prefixVmeste = "https://vmeste.wildberries.ru/";
        if (url.startsWith(prefixDigital) || url.startsWith(prefixVmeste)) {
            return url;
        }
        return prefixDigital + url;
    }

    private static String fetchPromotionsJson(Map<String, String> cookies) throws IOException {
        Connection connection = Jsoup.connect("https://static-basket-01.wbbasket.ru/vol0/data/promotions/rubli-za-otzyvy-v3.json")
                .userAgent(USER_AGENT)
                .header("Accept", "*/*")
                .header("Accept-Language", "en-US,en;q=0.5")
                .header("Referer", "https://www.wildberries.ru/")
                .header("Origin", "https://www.wildberries.ru")
                .header("Sec-Fetch-Dest", "empty")
                .header("Sec-Fetch-Mode", "cors")
                .header("Sec-Fetch-Site", "same-origin")
                .header("x-requested-with", "XMLHttpRequest")
                .method(Connection.Method.GET)
                .ignoreContentType(true)
                .timeout(20_000)
                .followRedirects(true);

        if (cookies != null && !cookies.isEmpty()) {
            cookies.forEach(connection::cookie);
        }

        return connection.execute().body();
    }

    public static void setClicksHeader(String header) {
        clicksHeader = (header == null || header.isBlank()) ? null : header;
    }
}


