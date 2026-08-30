package org.example;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.example.http.WbCookieFetcher;
import org.example.http.WbHttpClient;
import org.example.http.ProxyConfig;
import org.example.http.ProxyLoader;
import org.example.http.RotatingCookieJar;
import org.example.jsonmodel.Data;
import org.example.jsonmodel.Product;
import org.example.jsonmodel.Root;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ProductParsingIntegrationTest {

    private static final Gson GSON = new Gson();

    @TempDir
    Path tempDir;

    @Test
    void wbClientSendsExpectedRequestDataAndParsesCatalogResponse() throws IOException {
        String body = Files.readString(Path.of("src", "test", "resources", "wb_catalog_example.json"));
        AtomicReference<Headers> capturedHeaders = new AtomicReference<>();
        AtomicReference<String> capturedQuery = new AtomicReference<>();

        HttpServer server = startServer(200, body, capturedHeaders, capturedQuery);
        try {
            int port = server.getAddress().getPort();
            String url = "http://127.0.0.1:" + port
                    + "/__internal/u-catalog/catalog/test_shard/v4/catalog?"
                    + "ab_testing=false&action=1004833&appType=1&cat=123&page=1";

            WbHttpClient client = new WbHttpClient(Map.of(
                    "WBToken", "test-token",
                    "x-supplier-id-external", "supplier-id"
            ));

            WbHttpClient.HttpResponse<String> response = client.get(url, Map.of(
                    "Referer", "https://www.wildberries.ru/promotions/rubli-za-otzyvy/test-category",
                    "X-Test-Header", "present"
            ));

            assertEquals(200, response.statusCode());
            assertEquals("ab_testing=false&action=1004833&appType=1&cat=123&page=1", capturedQuery.get());

            Headers headers = capturedHeaders.get();
            assertNotNull(headers);
            assertEquals("https://www.wildberries.ru/promotions/rubli-za-otzyvy/test-category",
                    headers.getFirst("Referer"));
            assertEquals("XMLHttpRequest", headers.getFirst("x-requested-with"));
            assertEquals("present", headers.getFirst("X-test-header"));
            assertTrue(headers.getFirst("Cookie").contains("WBToken=test-token"));
            assertTrue(headers.getFirst("Cookie").contains("x-supplier-id-external=supplier-id"));

            ParsingResult parsed = parseProducts(response.body());
            assertNotNull(parsed);
            assertEquals(2, parsed.data.products.size());
            assertEquals(2, parsed.totalProducts);
            assertEquals(1, parsed.totalPages);
            assertEquals("root_with_total", parsed.format);
            assertEquals("10000001", parsed.data.products.get(0).id);
            assertEquals("1500", parsed.data.products.get(0).feedbackPoints);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void cookieJarCombinesOneCookiePairPerLineIntoSingleSet() throws IOException {
        Path cookieFile = tempDir.resolve("cookies.txt");
        Files.writeString(cookieFile, """
                _wbauid=browser-id
                x_wbaas_token=session-token
                WBToken=auth-token
                """);

        RotatingCookieJar cookieJar = WbCookieFetcher.loadCookieJar(cookieFile);

        assertEquals(1, cookieJar.size());
        String cookieHeader = cookieJar.currentCookieHeader();
        assertTrue(cookieHeader.contains("_wbauid=browser-id"));
        assertTrue(cookieHeader.contains("x_wbaas_token=session-token"));
        assertTrue(cookieHeader.contains("WBToken=auth-token"));
    }

    @Test
    void wbClientReturnsNonSuccessStatusForWildberriesErrorResponses() throws IOException {
        HttpServer server = startServer(498, "{\"error\":\"token expired\"}", new AtomicReference<>(), new AtomicReference<>());
        try {
            int port = server.getAddress().getPort();
            WbHttpClient client = new WbHttpClient(Map.of("WBToken", "expired"));

            WbHttpClient.HttpResponse<String> response = client.get(
                    "http://127.0.0.1:" + port + "/__internal/u-catalog/catalog/test/v4/catalog",
                    Map.of("Referer", "https://www.wildberries.ru/promotions/rubli-za-otzyvy")
            );

            assertEquals(498, response.statusCode());
            assertTrue(response.body().contains("token expired"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void wbClientSwitchesCookieSetAfterHttp498() throws IOException {
        String body = Files.readString(Path.of("src", "test", "resources", "wb_catalog_example.json"));
        AtomicReference<String> firstCookie = new AtomicReference<>();
        AtomicReference<String> secondCookie = new AtomicReference<>();

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String cookie = exchange.getRequestHeaders().getFirst("Cookie");
            byte[] bytes;
            int status;
            if (cookie != null && cookie.contains("WBToken=second")) {
                secondCookie.set(cookie);
                status = 200;
                bytes = body.getBytes(StandardCharsets.UTF_8);
            } else {
                firstCookie.set(cookie);
                status = 498;
                bytes = "{\"error\":\"token expired\"}".getBytes(StandardCharsets.UTF_8);
            }
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();

        try {
            int port = server.getAddress().getPort();
            RotatingCookieJar cookieJar = new RotatingCookieJar(
                    List.of("WBToken=first", "WBToken=second"),
                    1,
                    1,
                    1.0
            );
            WbHttpClient client = new WbHttpClient(cookieJar, List.of());
            String url = "http://127.0.0.1:" + port + "/__internal/u-catalog/catalog/test/v4/catalog";

            WbHttpClient.HttpResponse<String> first = client.get(url, Map.of("Referer", "https://www.wildberries.ru/"));
            WbHttpClient.HttpResponse<String> second = client.get(url, Map.of("Referer", "https://www.wildberries.ru/"));

            assertEquals(498, first.statusCode());
            assertEquals(200, second.statusCode());
            assertTrue(firstCookie.get().contains("WBToken=first"));
            assertTrue(secondCookie.get().contains("WBToken=second"));
            assertEquals(1, cookieJar.currentIndex());
            assertNotNull(parseProducts(second.body()));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void liveWildberriesCatalogSmokeWhenEnabled() throws IOException {
        Assumptions.assumeTrue(Boolean.getBoolean("wb.live.tests"),
                "Live WB smoke is disabled by default. Run with -Dwb.live.tests=true when fresh cookies are available.");

        RotatingCookieJar cookieJar = WbCookieFetcher.loadCookieJar(Path.of("cookies.txt"));
        List<ProxyConfig> proxies = ProxyLoader.loadFromFile(Path.of("proxies.txt"));
        WbHttpClient client = new WbHttpClient(cookieJar, proxies);
        List<String[]> categories = MyDualBot.getURL();

        assertNotNull(categories);
        assertFalse(categories.isEmpty());

        int ok = 0;
        int checked = 0;
        StringBuilder diagnostics = new StringBuilder();
        for (String[] category : categories.stream().limit(1).toList()) {
            String url = buildWildberriesCatalogUrl(category[1], category[2], category[3]);
            checked++;
            try {
                WbHttpClient.HttpResponse<String> response = client.get(url, Map.of("Referer", category[0]));
                diagnostics.append("\n")
                        .append(category[0])
                        .append(" -> HTTP ")
                        .append(response.statusCode())
                        .append(", body=")
                        .append(shortBody(response.body()));

                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    ParsingResult parsed = parseProducts(response.body());
                    assertNotNull(parsed, "WB response should be parseable for " + category[0]);
                    assertNotNull(parsed.data.products, "WB products should be present for " + category[0]);
                    ok++;
                }
            } catch (RuntimeException e) {
                diagnostics.append("\n")
                        .append(category[0])
                        .append(" -> ")
                        .append(e.getClass().getSimpleName())
                        .append(": ")
                        .append(e.getMessage());
            }
        }

        if (ok == 0) {
            System.out.println("WB live catalog requests did not return parseable JSON. Checked: "
                    + checked + diagnostics);
        }
        Assumptions.assumeTrue(ok > 0, "WB live catalog requests did not return parseable JSON. "
                + "This usually means the current cookies/session are blocked or expired.");
    }

    private static HttpServer startServer(
            int status,
            String body,
            AtomicReference<Headers> capturedHeaders,
            AtomicReference<String> capturedQuery
    ) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> handle(exchange, status, body, capturedHeaders, capturedQuery));
        server.start();
        return server;
    }

    private static void handle(
            HttpExchange exchange,
            int status,
            String body,
            AtomicReference<Headers> capturedHeaders,
            AtomicReference<String> capturedQuery
    ) throws IOException {
        capturedHeaders.set(exchange.getRequestHeaders());
        capturedQuery.set(exchange.getRequestURI().getRawQuery());
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static String buildWildberriesCatalogUrl(String shardKey, String query, String action) {
        StringBuilder queryParams = new StringBuilder();
        queryParams.append("ab_testing=false&action=").append(action);
        queryParams.append("&appType=1");
        if (query != null && !query.isEmpty()) {
            queryParams.append("&").append(query);
        }
        queryParams.append("&curr=rub&dest=-1257786&hide_dtype=15&hide_vflags=4294967296&lang=ru");
        queryParams.append("&sort=popular&spp=30");

        return "https://www.wildberries.ru/__internal/u-catalog/catalog/"
                + shardKey + "/v4/catalog?" + queryParams;
    }

    private static String shortBody(String body) {
        if (body == null) {
            return "<null>";
        }
        String normalized = body.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 180 ? normalized : normalized.substring(0, 180);
    }

    private static ParsingResult parseProducts(String jsonBody) {
        ParsingResult result = new ParsingResult();
        Data data = null;
        int numberCells = 0;

        try {
            JsonElement jsonElement = GSON.fromJson(jsonBody, JsonElement.class);
            if (jsonElement != null && jsonElement.isJsonObject()) {
                JsonObject jsonObject = jsonElement.getAsJsonObject();
                if (jsonObject.has("products")) {
                    JsonArray productsArray = jsonObject.getAsJsonArray("products");
                    if (productsArray != null && !productsArray.isEmpty()) {
                        data = new Data();
                        data.products = GSON.fromJson(productsArray, new TypeToken<List<Product>>() {}.getType());
                        numberCells = jsonObject.has("total") ? jsonObject.get("total").getAsInt() : data.products.size();
                        data.total = numberCells;
                        result.format = jsonObject.has("total") ? "products_direct" : "products_no_total";
                    }
                }
            }
        } catch (Exception ignored) {
        }

        if (data == null || data.products == null || data.products.isEmpty()) {
            try {
                Root root = GSON.fromJson(jsonBody, Root.class);
                if (root != null && root.data != null && root.data.products != null && !root.data.products.isEmpty()) {
                    data = root.data;
                    numberCells = data.total > 0 ? data.total : data.products.size();
                    result.format = data.total > 0 ? "root_with_total" : "root_no_total";
                }
            } catch (Exception ignored) {
                return null;
            }
        }

        if (data == null || data.products == null || data.products.isEmpty()) {
            return null;
        }

        result.data = data;
        result.totalProducts = numberCells;
        result.totalPages = numberCells % 100 == 0 ? numberCells / 100 : numberCells / 100 + 1;
        return result;
    }

    private static class ParsingResult {
        Data data;
        int totalProducts;
        int totalPages;
        String format;
    }
}
