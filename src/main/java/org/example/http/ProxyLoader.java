package org.example.http;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public final class ProxyLoader {
    private static final Logger log = LoggerFactory.getLogger(ProxyLoader.class);

    private ProxyLoader() {}

    public static List<ProxyConfig> loadFromFile(Path proxyFile) {
        return loadFromFile(proxyFile, null, null);
    }

    public static List<ProxyConfig> loadFromFile(Path proxyFile, String defaultUsername, String defaultPassword) {
        if (proxyFile == null) return List.of();
        if (!Files.exists(proxyFile)) {
            log.warn("Proxy file not found: {}", proxyFile.toAbsolutePath());
            return List.of();
        }

        List<ProxyConfig> result = new ArrayList<>();
        try {
            List<String> lines = Files.readAllLines(proxyFile);
            for (String raw : lines) {
                if (raw == null) continue;
                String line = raw.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;

                // host:port:user:pass
                String[] parts = line.split(":", 4);
                if (parts.length < 2) continue;
                String host = parts[0].trim();
                int port;
                try {
                    port = Integer.parseInt(parts[1].trim());
                } catch (NumberFormatException e) {
                    continue;
                }

                String user = parts.length >= 3 ? parts[2].trim() : defaultUsername(defaultUsername);
                String pass = parts.length == 4 ? parts[3] : defaultPassword(defaultPassword);
                result.add(new ProxyConfig(host, port, user, pass));
            }
        } catch (IOException e) {
            log.warn("Failed to read proxy file: {}", proxyFile.toAbsolutePath(), e);
            return List.of();
        }

        if (Boolean.parseBoolean(System.getProperty("wb.proxyHealthCheck", "true"))) {
            List<ProxyConfig> live = healthCheck(result);
            if (live.isEmpty()) {
                log.warn("Proxy health check found no reachable proxies in {}. Using unfiltered proxy list because WB proxy mode is required.",
                        proxyFile.toAbsolutePath());
                return result;
            }
            return live;
        }
        if (result.isEmpty() && Boolean.parseBoolean(System.getProperty("wb.proxyRequired", "true"))) {
            throw new IllegalStateException("No WB proxies loaded from " + proxyFile.toAbsolutePath());
        }
        return result;
    }

    private static List<ProxyConfig> healthCheck(List<ProxyConfig> proxies) {
        if (proxies.isEmpty()) {
            return proxies;
        }

        int parallelism = Math.max(1, Math.min(32, proxies.size()));
        int timeoutMillis = Integer.getInteger("wb.proxyHealthTimeoutMs", 2_000);
        ExecutorService executor = Executors.newFixedThreadPool(parallelism);
        try {
            List<Callable<ProxyConfig>> tasks = proxies.stream()
                    .<Callable<ProxyConfig>>map(proxy -> () -> reachable(proxy, timeoutMillis) ? proxy : null)
                    .toList();
            long budgetMillis = (long) Math.ceil((double) proxies.size() / parallelism) * timeoutMillis + 1_000L;
            List<Future<ProxyConfig>> futures = executor.invokeAll(tasks, budgetMillis, TimeUnit.MILLISECONDS);
            List<ProxyConfig> live = new ArrayList<>();
            for (Future<ProxyConfig> future : futures) {
                if (future.isCancelled()) {
                    continue;
                }
                try {
                    ProxyConfig proxy = future.get();
                    if (proxy != null) {
                        live.add(proxy);
                    }
                } catch (Exception ignored) {
                    // Unreachable proxies are excluded from the pool.
                }
            }
            log.info("Proxy health check: {} of {} proxies are reachable", live.size(), proxies.size());
            return live;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Proxy health check was interrupted. Using unfiltered proxy list.");
            return proxies;
        } finally {
            executor.shutdownNow();
        }
    }

    private static boolean reachable(ProxyConfig proxy, int timeoutMillis) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(proxy.host(), proxy.port()), timeoutMillis);
            return true;
        } catch (IOException ignored) {
            return false;
        }
    }

    private static String defaultUsername(String configuredValue) {
        String value = configuredValue == null || configuredValue.isBlank()
                ? System.getProperty("wb.proxyUsername")
                : configuredValue;
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String defaultPassword(String configuredValue) {
        String value = configuredValue == null || configuredValue.isBlank()
                ? System.getProperty("wb.proxyPassword")
                : configuredValue;
        return value == null ? null : value;
    }
}


