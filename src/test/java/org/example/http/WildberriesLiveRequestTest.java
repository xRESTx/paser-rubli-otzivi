package org.example.http;

import org.example.config.AppConfig;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Integration test that sends a real request to Wildberries to ensure headers/cookies are valid.
 * Run with: ./gradlew test -Dwb.integration=true
 */
class WildberriesLiveRequestTest {

    private static final String LIVE_URL = "https://www.wildberries.ru/__internal/u-recom/personal/ru/common/v8/search"
            + "?ab_testing=false&action=1004833&appType=1&curr=rub&dest=-1257786&hide_dtype=11&lang=ru"
            + "&page=1&query=0&resultset=catalog&spp=30&suppressSpellcheck=false";

    @Test
    void liveSearchRequestReturns200() throws IOException, InterruptedException {
        boolean runLive = Boolean.getBoolean("wb.integration");
        if (!runLive) {
            System.err.println("=== Wildberries live request test SKIPPED ===");
            System.err.println("Set -Dwb.integration=true to run live Wildberries request");
            return;
        }

        System.err.println("=== Wildberries live request test STARTING ===");
        AppConfig config = AppConfig.load();
        Map<String, String> cookies = config.getStaticCookies();
        if (cookies.isEmpty()) {
            System.err.println("ERROR: wb.staticCookies is empty in app.properties");
            System.err.println("Provide cookies in format: name=value;name2=value2");
            return;
        }

        System.err.println("Cookies count: " + cookies.size());
        System.err.println("Request URL: " + LIVE_URL);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(LIVE_URL))
                .GET()
                .timeout(Duration.ofSeconds(15))
                .header("Cookie", toCookieHeader(cookies))
                .headers(toHeaderArray(buildHeaders(config.getClicksHeader())))
                .build();

        System.err.println("Sending request...");
        HttpResponse<String> response = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)
                .build()
                .send(request, HttpResponse.BodyHandlers.ofString());

        System.err.println("=== Wildberries live request RESULT ===");
        System.err.println("Status: " + response.statusCode());
        System.err.println("Response headers:");
        response.headers().map().forEach((k, v) -> System.err.println("  " + k + ": " + v));
        
        String body = response.body();
        String bodySample = body.length() > 500 ? body.substring(0, 500) + "..." : body;
        System.err.println("Body sample (" + body.length() + " chars): " + bodySample);
        System.err.println("=======================================");

        if (response.statusCode() != 200) {
            System.err.println("FAILED: Expected status 200, got " + response.statusCode());
        }

        assertEquals(200, response.statusCode(),
                "Wildberries responded with status " + response.statusCode()
                        + " body sample: " + bodySample);
    }

    private static Map<String, String> buildHeaders(String clicksHeader) {
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                + "(KHTML, like Gecko) Chrome/138.0.0.0 YaBrowser/25.8.0.0 Safari/537.36");
        headers.put("Accept", "*/*");
        headers.put("Accept-Language", "ru,en;q=0.9");
        headers.put("Accept-Encoding", "identity");
        headers.put("Referer", "https://www.wildberries.ru/promotions/rubli-za-otzyvy/detyam");
        headers.put("Origin", "https://www.wildberries.ru");
        // Connection header is restricted by Java HttpClient, skip it
        headers.put("Sec-Fetch-Dest", "empty");
        headers.put("Sec-Fetch-Mode", "cors");
        headers.put("Sec-Fetch-Site", "same-origin");
        headers.put("Sec-CH-UA", "\"Not)A;Brand\";v=\"8\", \"Chromium\";v=\"138\", \"YaBrowser\";v=\"25.8\", \"Yowser\";v=\"2.5\"");
        headers.put("Sec-CH-UA-Mobile", "?0");
        headers.put("Sec-CH-UA-Platform", "\"Windows\"");
        headers.put("Priority", "u=1, i");
        headers.put("x-requested-with", "XMLHttpRequest");
        headers.put("x-spa-version", "13.14.1");
        headers.put("x-userid", "0");
        headers.put("deviceid", "site_2bc3dd7d2f1a4eb28539e17ff17c894a");
        if (clicksHeader != null && !clicksHeader.isBlank()) {
            headers.put("clicks", clicksHeader);
        }
        return headers;
    }

    private static String toCookieHeader(Map<String, String> cookies) {
        return cookies.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(Collectors.joining("; "));
    }

    private static String[] toHeaderArray(Map<String, String> headers) {
        return headers.entrySet().stream()
                .flatMap(e -> java.util.stream.Stream.of(e.getKey(), e.getValue()))
                .toArray(String[]::new);
    }
}


