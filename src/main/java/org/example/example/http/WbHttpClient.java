package org.example.example.http;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.StringJoiner;
import java.util.zip.GZIPInputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

/**
 * HTTP клиент для работы с Wildberries API без Selenium.
 * Использует прямые HTTP-запросы с куки и заголовками.
 */
public class WbHttpClient {
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:145.0) Gecko/20100101 Firefox/145.0";
    
    private final HttpClient client;
    private final String cookies;
    
    public WbHttpClient(Map<String, String> cookiesMap) {
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .version(HttpClient.Version.HTTP_1_1) // Используем HTTP/1.1 вместо HTTP/2, чтобы избежать "too many concurrent streams"
                .build();
        
        this.cookies = buildCookieHeader(cookiesMap);
    }
    
    /**
     * Выполняет GET запрос.
     */
    public HttpResponse<String> get(String url, Map<String, String> additionalHeaders) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(20))
                    .GET()
                    .header("Accept", "application/json, text/plain, */*")
                    .header("Accept-Language", "ru-RU,ru;q=0.9,en;q=0.8")
                    .header("Accept-Encoding", "gzip, deflate") // Только gzip и deflate (Java HttpClient автоматически распаковывает)
                    .header("Origin", "https://www.wildberries.ru")
                    .header("Referer", "https://www.wildberries.ru/promotions/rubli-za-otzyvy")
                    .header("User-Agent", USER_AGENT)
                    .header("Sec-Fetch-Site", "same-origin")
                    .header("Sec-Fetch-Mode", "cors")
                    .header("Sec-Fetch-Dest", "empty")
                    .header("x-requested-with", "XMLHttpRequest")
                    .header("x-spa-version", "13.14.2")
                    .header("deviceid", "site_8eaf7acc4d8646988bbfd2d0579895e2");
            
            // Добавляем дополнительные заголовки
            if (additionalHeaders != null) {
                for (Map.Entry<String, String> entry : additionalHeaders.entrySet()) {
                    builder.header(entry.getKey(), entry.getValue());
                }
            }
            
            // Добавляем Cookie заголовок, если есть куки
            if (cookies != null && !cookies.trim().isEmpty()) {
                builder.header("Cookie", cookies);
            }
            
            HttpRequest request = builder.build();
            
            // Получаем ответ как байты, чтобы вручную распаковать если нужно
            HttpResponse<byte[]> byteResponse = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            
            // Получаем Content-Encoding
            String contentEncoding = byteResponse.headers().firstValue("Content-Encoding").orElse("");
            
            // Распаковываем данные если они сжаты
            byte[] bodyBytes = byteResponse.body();
            if ("gzip".equalsIgnoreCase(contentEncoding)) {
                bodyBytes = decompressGzip(bodyBytes);
            } else if ("deflate".equalsIgnoreCase(contentEncoding)) {
                bodyBytes = decompressDeflate(bodyBytes);
            }
            
            // Конвертируем в строку
            String bodyString = new String(bodyBytes, java.nio.charset.StandardCharsets.UTF_8);
            
            // Создаем HttpResponse<String> с распакованными данными
            final String finalBody = bodyString;
            return new HttpResponse<String>() {
                @Override
                public int statusCode() {
                    return byteResponse.statusCode();
                }
                
                @Override
                public HttpRequest request() {
                    return byteResponse.request();
                }
                
                @Override
                public java.util.Optional<HttpResponse<String>> previousResponse() {
                    return java.util.Optional.empty();
                }
                
                @Override
                public java.net.http.HttpHeaders headers() {
                    return byteResponse.headers();
                }
                
                @Override
                public String body() {
                    return finalBody;
                }
                
                @Override
                public java.util.Optional<javax.net.ssl.SSLSession> sslSession() {
                    return byteResponse.sslSession();
                }
                
                @Override
                public URI uri() {
                    return byteResponse.uri();
                }
                
                @Override
                public HttpClient.Version version() {
                    return byteResponse.version();
                }
            };
            
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Request interrupted", e);
        } catch (java.net.http.HttpTimeoutException e) {
            throw new RuntimeException("Request timeout", e);
        } catch (IOException e) {
            throw new RuntimeException("IO error: " + e.getClass().getSimpleName() + " - " + e.getMessage(), e);
        } catch (Exception e) {
            throw new RuntimeException("Failed to execute request: " + e.getClass().getSimpleName() + " - " + e.getMessage(), e);
        }
    }
    
    /**
     * Обновляет куки.
     */
    public void updateCookies(Map<String, String> newCookies) {
        // Для обновления нужно создать новый экземпляр
        // Или можно сделать cookies не final и обновлять
    }
    
    /**
     * Строит строку Cookie заголовка из Map куки.
     */
    private String buildCookieHeader(Map<String, String> cookiesMap) {
        if (cookiesMap == null || cookiesMap.isEmpty()) {
            return null;
        }
        
        StringJoiner joiner = new StringJoiner("; ");
        for (Map.Entry<String, String> entry : cookiesMap.entrySet()) {
            joiner.add(entry.getKey() + "=" + entry.getValue());
        }
        return joiner.toString();
    }
    
    /**
     * Распаковывает gzip данные.
     */
    private byte[] decompressGzip(byte[] compressed) {
        try {
            ByteArrayInputStream bis = new ByteArrayInputStream(compressed);
            GZIPInputStream gis = new GZIPInputStream(bis);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int len;
            while ((len = gis.read(buffer)) != -1) {
                bos.write(buffer, 0, len);
            }
            gis.close();
            return bos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Failed to decompress gzip", e);
        }
    }
    
    /**
     * Распаковывает deflate данные.
     */
    private byte[] decompressDeflate(byte[] compressed) {
        try {
            Inflater inflater = new Inflater();
            inflater.setInput(compressed);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            while (!inflater.finished()) {
                int count = inflater.inflate(buffer);
                bos.write(buffer, 0, count);
            }
            inflater.end();
            return bos.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("Failed to decompress deflate", e);
        }
    }
}

