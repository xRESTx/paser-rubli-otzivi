package org.example.http;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

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
    // Используем volatile long для rate limiting с синхронизацией
    private volatile long lastRequestAt = 0L;
    private final Object rateLimitLock = new Object();
    // Семафор для ограничения количества одновременных HTTP/2 streams
    // HTTP/2 серверы обычно ограничивают concurrent streams до ~100 на соединение
    // Лимит 100 предотвращает ошибки "too many concurrent streams"
    private final java.util.concurrent.Semaphore concurrentRequestsSemaphore;
    private volatile Map<String, String> defaultCookies;
    private volatile String defaultUserAgent;
    private volatile Map<String, String> defaultHeaders;

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
        this(timeout, maxRetries, baseBackoffMillis, minIntervalMillis, defaultCookies, defaultUserAgent, null);
    }
    
    public WbHttpClient(Duration timeout,
                        int maxRetries,
                        long baseBackoffMillis,
                        long minIntervalMillis,
                        Map<String, String> defaultCookies,
                        String defaultUserAgent,
                        Map<String, String> defaultHeaders) {
        // Используем HTTP/2 как в оригинальных запросах Wildberries
        // Создаем ограниченный ExecutorService для HTTP клиента
        // FixedThreadPool с достаточным количеством потоков, но с ограничением
        // Это предотвращает создание тысяч потоков при 256 потоках категорий
        // 512 потоков достаточно для обработки запросов без перегрузки системы
        AtomicLong threadCounter = new AtomicLong(0);
        java.util.concurrent.ExecutorService executor = Executors.newFixedThreadPool(512, r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            t.setName("http-client-" + threadCounter.incrementAndGet());
            return t;
        });
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .version(HttpClient.Version.HTTP_2)
                .executor(executor)
                .build();
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        this.maxRetries = Math.max(1, maxRetries);
        this.baseBackoffMillis = Math.max(100L, baseBackoffMillis);
        this.minIntervalMillis = Math.max(0L, minIntervalMillis);
        // Ограничиваем количество одновременных запросов до 100
        // Это соответствует типичному лимиту HTTP/2 concurrent streams на сервере
        // Предотвращает ошибки "too many concurrent streams"
        this.concurrentRequestsSemaphore = new java.util.concurrent.Semaphore(100, true);
        this.defaultCookies = defaultCookies;
        this.defaultUserAgent = defaultUserAgent;
        this.defaultHeaders = defaultHeaders;
    }

    public HttpResponse<String> get(String url, Map<String, String> headers) throws IOException, InterruptedException {
        Objects.requireNonNull(url, "url");

        int attempt = 0;
        IOException last = null;
        while (attempt < maxRetries) {
            attempt++;
            // Получаем разрешение семафора для ограничения количества одновременных HTTP/2 streams
            concurrentRequestsSemaphore.acquire();
            try {
                // Временно отключен rate limiting для диагностики производительности
                // rateLimit();
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
                    // Логируем наличие критически важных cookies
                    if (defaultCookies.containsKey("x_wbaas_token")) {
                        // Не логируем значение токена, только факт наличия
                    } else {
                        java.util.logging.Logger.getLogger(WbHttpClient.class.getName())
                            .warning("x_wbaas_token cookie missing in request!");
                    }
                }
                if (defaultUserAgent != null) {
                    builder.header("User-Agent", defaultUserAgent);
                }
                
                // Сначала добавляем заголовки из CategoryTask (если есть)
                // Они будут базовыми заголовками
                if (headers != null) {
                    headers.forEach(builder::header);
                }
                
                // Затем добавляем/перезаписываем заголовками из Selenium (если есть)
                // Это дает приоритет заголовкам из браузера
                // Важно: заголовки из Selenium могут быть в нижнем регистре,
                // поэтому нужно найти соответствующий заголовок из CategoryTask и использовать его регистр
                if (defaultHeaders != null && !defaultHeaders.isEmpty()) {
                    for (Map.Entry<String, String> entry : defaultHeaders.entrySet()) {
                        // Не перезаписываем Cookie, User-Agent, Accept-Encoding, Connection и Referer
                        // Referer должен быть из CategoryTask (содержит categoryUrl)
                        String key = entry.getKey();
                        String normalizedKey = key.toLowerCase();
                        if (!normalizedKey.equals("cookie") && !normalizedKey.equals("user-agent") 
                                && !normalizedKey.equals("accept-encoding") && !normalizedKey.equals("connection")
                                && !normalizedKey.equals("referer") && !normalizedKey.equals("origin")) {
                            // Для некоторых заголовков (например, clicks) используем оригинальное имя из Selenium
                            // Для остальных - находим оригинальное имя из CategoryTask (с правильным регистром)
                            String headerName;
                            if (normalizedKey.equals("clicks")) {
                                // clicks всегда в нижнем регистре
                                headerName = "clicks";
                            } else {
                                headerName = findHeaderName(normalizedKey, headers);
                                if (headerName == null) {
                                    headerName = key; // Используем имя из Selenium, если не найдено в CategoryTask
                                }
                            }
                            builder.header(headerName, entry.getValue());
                        }
                    }
                }
                
                HttpRequest request = builder.build();
                // HttpClient автоматически распаковывает gzip/deflate, если в запросе есть Accept-Encoding: gzip
                // Используем ofString() напрямую - это проще и быстрее
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
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
                // Улучшенное логирование ошибок для диагностики блокировок Wildberries
                java.util.logging.Logger logger = java.util.logging.Logger.getLogger(WbHttpClient.class.getName());
                logger.warning(String.format("HTTP request failed (attempt %d/%d) for URL: %s - Error: %s (type: %s)", 
                        attempt, maxRetries, url, ex.getMessage(), ex.getClass().getSimpleName()));
                if (ex.getCause() != null) {
                    logger.warning(String.format("  Caused by: %s", ex.getCause().getMessage()));
                }
                
                // RST_STREAM и другие HTTP/2 ошибки - это временные проблемы сети
                if (!shouldRetry(ex, attempt)) {
                    throw ex;
                }
                // Для HTTP/2 ошибок увеличиваем задержку перед повтором
                if (isHttp2Error(ex)) {
                    logger.info(String.format("HTTP/2 error detected, waiting %dms before retry", baseBackoffMillis * 2 * attempt));
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
            } finally {
                // Освобождаем семафор после завершения запроса (успешного или неуспешного)
                concurrentRequestsSemaphore.release();
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
        // Используем быстрый синхронизированный блок для rate limiting
        // При большом количестве потоков lock-free подход создает гонки и большие задержки
        // Короткая синхронизация более эффективна для этой задачи
        long wait;
        synchronized (rateLimitLock) {
            long now = System.currentTimeMillis();
            long last = lastRequestAt;
            wait = (last + minIntervalMillis) - now;
            
            if (wait <= 0) {
                // Не нужно ждать, обновляем lastRequestAt и продолжаем
                lastRequestAt = now;
                return;
            }
            
            // Нужно ждать, обновляем lastRequestAt на время следующего разрешенного запроса
            lastRequestAt = last + minIntervalMillis;
        }
        
        // Ждем необходимое время вне синхронизированного блока
        // Это позволяет другим потокам быстро обновить lastRequestAt
        if (wait > 0) {
            // Логируем только если ждем долго (больше 100мс)
            if (wait > 100) {
                java.util.logging.Logger.getLogger(WbHttpClient.class.getName())
                    .info(String.format("Rate limiting: sleeping %dms (minInterval: %dms)", 
                            wait, minIntervalMillis));
            }
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
    
    /**
     * Обновляет cookies и User-Agent для последующих запросов.
     * Используется при получении HTTP 498 ошибок (истекшие cookies).
     */
    public synchronized void updateCookies(Map<String, String> newCookies, String newUserAgent) {
        this.defaultCookies = newCookies;
        this.defaultUserAgent = newUserAgent;
    }
    
    /**
     * Находит имя заголовка с правильным регистром из переданной Map.
     * Используется для нормализации заголовков из Selenium (которые могут быть в нижнем регистре).
     */
    private String findHeaderName(String normalizedKey, Map<String, String> headers) {
        if (headers == null || normalizedKey == null) {
            return null;
        }
        for (String key : headers.keySet()) {
            if (key.toLowerCase().equals(normalizedKey)) {
                return key;
            }
        }
        return null;
    }
    
    /**
     * Обновляет cookies, User-Agent и заголовки для последующих запросов.
     */
    public synchronized void updateCookiesAndHeaders(Map<String, String> newCookies, String newUserAgent, Map<String, String> newHeaders) {
        this.defaultCookies = newCookies;
        this.defaultUserAgent = newUserAgent;
        this.defaultHeaders = newHeaders;
    }
}


