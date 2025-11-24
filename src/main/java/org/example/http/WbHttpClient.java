package org.example.http;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Wrapper above {@link HttpClient} that provides retry, rate limiting and sane defaults
 * for Wildberries catalog requests.
 */
public final class WbHttpClient {

    private final HttpClient httpClient;
    private final Duration timeout;
    private final int maxRetries;
    private final long baseBackoffMillis;
    private final long minIntervalMillis;
    private final Object rateLimiterLock = new Object();
    private volatile long lastRequestAt = 0L;
    private final Map<String, String> defaultCookies;
    private final String defaultUserAgent;

    public WbHttpClient(Duration timeout,
                        int maxRetries,
                        long baseBackoffMillis,
                        long minIntervalMillis) {
        this(timeout, maxRetries, baseBackoffMillis, minIntervalMillis, null, null);
    }
    
    public WbHttpClient(Duration timeout,
                        int maxRetries,
                        long baseBackoffMillis,
                        long minIntervalMillis,
                        Map<String, String> defaultCookies,
                        String defaultUserAgent) {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .version(HttpClient.Version.HTTP_2)
                .build();
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        this.maxRetries = Math.max(1, maxRetries);
        this.baseBackoffMillis = Math.max(100L, baseBackoffMillis);
        this.minIntervalMillis = Math.max(0L, minIntervalMillis);
        this.defaultCookies = defaultCookies;
        this.defaultUserAgent = defaultUserAgent;
    }

    public HttpResponse<String> get(String url, Map<String, String> headers) throws IOException, InterruptedException {
        Objects.requireNonNull(url, "url");

        int attempt = 0;
        IOException last = null;
        while (attempt < maxRetries) {
            attempt++;
            try {
                rateLimit();
                HttpRequest.Builder builder = HttpRequest.newBuilder()
                        .GET()
                        .timeout(timeout)
                        .uri(URI.create(url));
                
                // Добавляем cookies и User-Agent по умолчанию, если они есть
                if (defaultCookies != null && !defaultCookies.isEmpty()) {
                    StringBuilder cookieHeader = new StringBuilder();
                    for (Map.Entry<String, String> entry : defaultCookies.entrySet()) {
                        if (cookieHeader.length() > 0) {
                            cookieHeader.append("; ");
                        }
                        cookieHeader.append(entry.getKey()).append("=").append(entry.getValue());
                    }
                    builder.header("Cookie", cookieHeader.toString());
                }
                if (defaultUserAgent != null) {
                    builder.header("User-Agent", defaultUserAgent);
                }
                
                if (headers != null) {
                    headers.forEach(builder::header);
                }
                HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
                int status = response.statusCode();
                if (status >= 200 && status < 300) {
                    return response;
                }
                if (!shouldRetry(status, attempt)) {
                    return response;
                }
                sleepBackoff(status, attempt);
            } catch (IOException ex) {
                last = ex;
                // RST_STREAM и другие HTTP/2 ошибки - это временные проблемы сети
                if (!shouldRetry(ex, attempt)) {
                    throw ex;
                }
                // Для HTTP/2 ошибок увеличиваем задержку перед повтором
                if (isHttp2Error(ex)) {
                    if (Thread.currentThread().isInterrupted()) {
                        Thread.currentThread().interrupt();
                        throw new InterruptedException("Thread interrupted during HTTP/2 error backoff");
                    }
                    try {
                        Thread.sleep(baseBackoffMillis * 2 * attempt);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        // Пробрасываем InterruptedException дальше - это нормальное завершение потока
                        throw ie;
                    }
                } else {
                    sleepBackoff(0, attempt);
                }
            }
        }
        if (last != null) {
            throw last;
        }
        throw new IOException("Failed to GET " + url);
    }

    private void rateLimit() throws InterruptedException {
        if (minIntervalMillis <= 0) {
            return;
        }
        synchronized (rateLimiterLock) {
            long now = System.currentTimeMillis();
            long wait = (lastRequestAt + minIntervalMillis) - now;
            if (wait > 0) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException("Thread interrupted before rate limit sleep");
                }
                try {
                    Thread.sleep(wait);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
            lastRequestAt = System.currentTimeMillis();
        }
    }

    private boolean shouldRetry(int statusCode, int attempt) {
        if (attempt >= maxRetries) {
            return false;
        }
        if (statusCode == 429) {
            return true;
        }
        return statusCode >= 500;
    }

    private boolean shouldRetry(IOException exception, int attempt) {
        return attempt < maxRetries;
    }

    private void sleepBackoff(int statusCode, int attempt) throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("Thread interrupted before backoff sleep");
        }
        long multiplier = statusCode == 429 ? 4L : 1L;
        long base = (long) Math.pow(2, attempt - 1) * baseBackoffMillis * multiplier;
        long jitter = ThreadLocalRandom.current().nextLong(100);
        try {
            Thread.sleep(base + jitter);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        }
    }
    
    private boolean isHttp2Error(IOException ex) {
        if (ex == null) {
            return false;
        }
        String message = ex.getMessage();
        if (message != null) {
            String lower = message.toLowerCase();
            return lower.contains("rst_stream")
                    || lower.contains("stream not processed")
                    || lower.contains("goaway")
                    || lower.contains("too many concurrent streams");
        }
        return false;
    }
}


