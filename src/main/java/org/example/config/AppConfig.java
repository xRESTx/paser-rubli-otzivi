package org.example.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;

/**
 * Central place for runtime configuration. Values come from {@code app.properties}
 * located either on the classpath (src/main/resources) or in the working directory.
 */
public final class AppConfig {

    private final String botUsername;
    private final String botToken;
    private final int catalogThreads;
    private final int productThreads;
    private final int telegramThreads;
    private final int httpTimeoutSeconds;
    private final int httpMaxRetries;
    private final long httpBaseBackoffMillis;
    private final long httpRateLimitMillis;
    private final int maxPagesPerCategory;
    private final int catalogRefreshSeconds;
    private final String sqlitePath;
    private final int storageQueueCapacity;
    private final int storageBatchSize;
    private final String clicksHeader;
    private final Map<String, String> staticCookies;

    private AppConfig(Properties properties) {
        this.botUsername = require(properties, "bot.username");
        this.botToken = require(properties, "bot.token");
        this.catalogThreads = getInt(properties, "threads.catalog", 8);
        this.productThreads = getInt(properties, "threads.product", 24);
        this.telegramThreads = getInt(properties, "threads.telegram", 2);
        this.httpTimeoutSeconds = getInt(properties, "http.timeout.seconds", 15);
        this.httpMaxRetries = getInt(properties, "http.max.retries", 3);
        this.httpBaseBackoffMillis = getLong(properties, "http.base.backoff.millis", 500L);
        this.httpRateLimitMillis = getLong(properties, "http.rate.limit.millis", 200L);
        this.maxPagesPerCategory = getInt(properties, "wb.maxPagesPerCategory", 60);
        this.catalogRefreshSeconds = getInt(properties, "wb.catalogRefreshSeconds", 60);
        this.sqlitePath = properties.getProperty("sqlite.path", "data/wb-bot.db");
        this.storageQueueCapacity = getInt(properties, "sqlite.queue.capacity", 2_000);
        this.storageBatchSize = getInt(properties, "sqlite.batch.size", 100);
        this.clicksHeader = properties.getProperty("wb.clicks", "").trim();
        this.staticCookies = parseCookies(properties.getProperty("wb.staticCookies", ""));
    }

    public static AppConfig load() {
        Properties properties = new Properties();
        // First, try to load from working dir if user created override file
        Optional<Path> fsConfig = Optional.of(Path.of("app.properties"))
                .filter(Files::exists);
        if (fsConfig.isPresent()) {
            try (InputStream in = Files.newInputStream(fsConfig.get())) {
                properties.load(in);
                return new AppConfig(properties);
            } catch (IOException e) {
                throw new IllegalStateException("Failed to read app.properties from working directory", e);
            }
        }
        try (InputStream in = AppConfig.class.getClassLoader().getResourceAsStream("app.properties")) {
            if (in == null) {
                throw new IllegalStateException("app.properties not found on classpath");
            }
            properties.load(in);
            return new AppConfig(properties);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load app.properties from classpath", e);
        }
    }

    private static String require(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing required property: " + key);
        }
        return value.trim();
    }

    private static int getInt(Properties properties, String key, int defaultValue) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalStateException("Invalid integer for " + key + ": " + value, e);
        }
    }

    private static long getLong(Properties properties, String key, long defaultValue) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalStateException("Invalid long for " + key + ": " + value, e);
        }
    }

    public String getBotUsername() {
        return botUsername;
    }

    public String getBotToken() {
        return botToken;
    }

    public int getCatalogThreads() {
        return catalogThreads;
    }

    public int getProductThreads() {
        return productThreads;
    }

    public int getTelegramThreads() {
        return telegramThreads;
    }

    public int getHttpTimeoutSeconds() {
        return httpTimeoutSeconds;
    }

    public int getHttpMaxRetries() {
        return httpMaxRetries;
    }

    public long getHttpBaseBackoffMillis() {
        return httpBaseBackoffMillis;
    }

    public long getHttpRateLimitMillis() {
        return httpRateLimitMillis;
    }

    public int getMaxPagesPerCategory() {
        return maxPagesPerCategory;
    }

    public int getCatalogRefreshSeconds() {
        return catalogRefreshSeconds;
    }

    public String getSqlitePath() {
        return sqlitePath;
    }

    public int getStorageQueueCapacity() {
        return storageQueueCapacity;
    }

    public int getStorageBatchSize() {
        return storageBatchSize;
    }

    public String getClicksHeader() {
        return clicksHeader;
    }

    public Map<String, String> getStaticCookies() {
        return staticCookies;
    }

    private static Map<String, String> parseCookies(String raw) {
        Map<String, String> map = new java.util.HashMap<>();
        if (raw == null || raw.isBlank()) {
            return map;
        }
        String[] tokens = raw.split(";");
        for (String token : tokens) {
            String trimmed = token.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            int idx = trimmed.indexOf('=');
            if (idx > 0) {
                String name = trimmed.substring(0, idx).trim();
                String value = trimmed.substring(idx + 1).trim();
                if (!name.isEmpty() && !value.isEmpty()) {
                    map.put(name, value);
                }
            }
        }
        return map;
    }
}


