package org.example;

import java.io.IOException;
import java.io.PrintStream;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpRequest;
import org.example.http.WbHttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import org.example.config.BotConfig;
import org.example.http.ProxyConfig;
import org.example.http.ProxyLoader;
import org.example.http.RotatingCookieJar;
import org.example.http.WbCookieFetcher;
import org.example.http.WbHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class WbRpsAutotune {
    private static final Logger log = LoggerFactory.getLogger(WbRpsAutotune.class);

    // ========== НАСТРОЙКИ ==========

    // Список категорий для запросов
    private static List<String[]> categories = new ArrayList<>();
    
    // Индекс текущей категории для ротации (thread-safe)
    private static final AtomicInteger categoryIndex = new AtomicInteger(0);
    
    // HTTP клиент с поддержкой прокси и ротации куки
    private static WbHttpClient wbHttpClient;

    // Авто-подбор
    private static final double START_RPS = 100.0;   // с какого RPS начинаем
    private static final double MULTIPLIER = 1.3;    // шаг увеличения
    private static final double MAX_RPS = 10000;    // страховка по максимуму RPS

    private static final double PHASE_DURATION_SEC = 20.0; // длительность фазы

    // Ограничитель конкурентности (сколько максимум потоков / запросов в полёте)
    private static final int MAX_CONCURRENCY_CAP = 10000;

    // Насколько запасаемся относительно rps * latency
    private static final double SAFETY_FACTOR = 1.5;

    // Если доля не-OK ответов больше порога — останавливаемся
    private static final double MAX_ERROR_RATIO = 0.10; // 10%

    // ========== ВСПОМОГАТЕЛЬНЫЕ КЛАССЫ ==========

    private static class Result {
        final String type;        // ok / antibot / http_XXX / exception / decode_error
        final double latencySec;  // в секундах

        Result(String type, double latencySec) {
            this.type = type;
            this.latencySec = latencySec;
        }
    }

    private static class Stats {
        int total = 0;
        int ok = 0;
        int antibot = 0;
        Map<String, Integer> errors = new HashMap<>();
        List<Double> latencies = new ArrayList<>();
    }

    // ========== HTTP-КЛИЕНТ И ЗАГОЛОВКИ ==========

    /**
     * Строит API URL для категории на основе данных категории.
     * Формат: https://www.wildberries.ru/__internal/u-catalog/catalog/{shardKey}/v4/catalog?{queryParams}
     */
    private static String buildCategoryApiUrl(String[] category) {
        String shardKey = category[1];
        String query = category[2];
        String action = category[3];
        
        StringBuilder queryParams = new StringBuilder();
        queryParams.append("ab_testing=false&action=").append(action);
        queryParams.append("&appType=1");
        if (query != null && !query.isEmpty()) {
            queryParams.append("&").append(query);
        }
        queryParams.append("&curr=rub&dest=-1257786&hide_dtype=15&hide_vflags=4294967296&lang=ru");
        queryParams.append("&sort=popular&spp=30");
        
        return "https://www.wildberries.ru/__internal/u-catalog/catalog/" + shardKey + "/v4/catalog?" + queryParams.toString();
    }

    /**
     * Получает следующую категорию для запроса (ротация по кругу).
     */
    private static String[] getNextCategory() {
        if (categories.isEmpty()) {
            throw new IllegalStateException("No categories loaded!");
        }
        int index = categoryIndex.getAndIncrement() % categories.size();
        return categories.get(index);
    }

    /**
     * Создает URL и заголовки для запроса категории (используется WbHttpClient.get()).
     * Возвращает пару: [URL, headers Map]
     */
    private static Object[] buildRequestData() {
        String[] category = getNextCategory();
        String apiUrl = buildCategoryApiUrl(category);
        String categoryUrl = category[0]; // URL категории для Referer
        
        // Убеждаемся, что categoryUrl полный URL
        if (!categoryUrl.startsWith("http")) {
            categoryUrl = "https://www.wildberries.ru" + categoryUrl;
        }
        
        Map<String, String> headers = new HashMap<>();
        headers.put("Referer", categoryUrl);
        
        return new Object[]{apiUrl, headers};
    }

    // ========== ЛОГИКА КЛАССИФИКАЦИИ ОТВЕТА ==========

    private static String classifyResponse(WbHttpClient.HttpResponse<String> resp) {
        String body = resp.body();
        String start = body.substring(0, Math.min(1000, body.length()));

        if (start.contains("__wbaas/challenges/antibot") || resp.uri().toString().contains("challenges/antibot")) {
            return "antibot";
        }

        int code = resp.statusCode();
        if (code >= 200 && code < 300) {
            return "ok";
        }
        return "http_" + code;
    }

    private static Result makeRequest() {
        double t0 = System.nanoTime() / 1_000_000_000.0;
        String url = null;
        try {
            Object[] requestData = buildRequestData();
            url = (String) requestData[0];
            @SuppressWarnings("unchecked")
            Map<String, String> headers = (Map<String, String>) requestData[1];
            WbHttpClient.HttpResponse<String> resp = wbHttpClient.get(url, headers);
            double t1 = System.nanoTime() / 1_000_000_000.0;
            String type = classifyResponse(resp);
            return new Result(type, t1 - t0);
        } catch (RuntimeException e) {
            double t1 = System.nanoTime() / 1_000_000_000.0;
            if (url != null) {
                log.error("Request failed for URL: {} - {}", url, e.getMessage());
            } else {
                log.error("Request failed (URL not built): {}", e.getMessage());
            }
            return new Result("exception", t1 - t0);
        }
    }

    // ========== WARMUP: измерение базовой latency ==========

    private static double warmupLatency(int numRequests) {
        List<Double> latencies = new ArrayList<>();
        log.info("Делаем {} прогревочных запросов для оценки latency...", numRequests);

        for (int i = 0; i < numRequests; i++) {
            Result r = makeRequest();
            String latencyStr = String.format("%.3f", r.latencySec);
            if ("exception".equals(r.type)) {
                log.warn("  [{}/{}] result={}, latency={} сек (см. ошибку выше для URL)", i + 1, numRequests, r.type, latencyStr);
            } else {
                log.info("  [{}/{}] result={}, latency={} сек", i + 1, numRequests, r.type, latencyStr);
            }
            latencies.add(r.latencySec);
        }

        if (latencies.isEmpty()) {
            log.warn("Не удалось измерить latency, используем fallback 0.3 сек");
            return 0.3;
        }

        double sum = 0.0;
        for (double l : latencies) {
            sum += l;
        }
        double avg = sum / latencies.size();
        log.info("Средняя прогревочная latency ≈ {} сек", String.format("%.3f", avg));
        return avg;
    }

    // ========== ОДНА ФАЗА ТЕСТА ДЛЯ ЗАДАННОГО RPS ==========

    private static class PhaseResult {
        final Stats stats;
        final double elapsedSec;
        final int concurrency;

        PhaseResult(Stats stats, double elapsedSec, int concurrency) {
            this.stats = stats;
            this.elapsedSec = elapsedSec;
            this.concurrency = concurrency;
        }
    }

    private static PhaseResult runPhase(double currentRps,
                                        double durationSec,
                                        double baseLatency) {

        int concurrency = (int) Math.ceil(currentRps * baseLatency * SAFETY_FACTOR);
        if (concurrency < 1) concurrency = 1;
        if (concurrency > MAX_CONCURRENCY_CAP) concurrency = MAX_CONCURRENCY_CAP;

        log.info("  -> Расчётная конкурентность (потоков / запросов в полёте): {}", concurrency);

        ExecutorService executor = Executors.newFixedThreadPool(concurrency);

        Stats stats = new Stats();
        double startTime = System.nanoTime() / 1_000_000_000.0;
        double endTime = startTime + durationSec;

        double intervalSec = 1.0 / currentRps;
        double nextFireTime = startTime;

        List<Future<Result>> inFlight = new ArrayList<>();

        while (true) {
            double now = System.nanoTime() / 1_000_000_000.0;
            if (now >= endTime && inFlight.isEmpty()) {
                break;
            }

            // запуск новых запросов, если не вышло время и есть свободные слоты
            while (now < endTime && inFlight.size() < concurrency && now >= nextFireTime) {
                double jitter = (Math.random() * 0.4 - 0.2) * intervalSec; // -0.2..+0.2
                double fireTime = Math.max(nextFireTime + jitter, now);
                if (fireTime > endTime) {
                    break;
                }
                double now2 = System.nanoTime() / 1_000_000_000.0;
                if (fireTime > now2) {
                    long sleepMs = (long) ((fireTime - now2) * 1000.0);
                    if (sleepMs > 0) {
                        try {
                            Thread.sleep(sleepMs);
                        } catch (InterruptedException ignored) {}
                    }
                }

                Future<Result> fut = executor.submit(() -> makeRequest());
                inFlight.add(fut);
                nextFireTime += intervalSec;
                now = System.nanoTime() / 1_000_000_000.0;
            }

            // снимаем готовые futures
            if (!inFlight.isEmpty()) {
                Iterator<Future<Result>> it = inFlight.iterator();
                while (it.hasNext()) {
                    Future<Result> f = it.next();
                    if (f.isDone()) {
                        Result r;
                        try {
                            r = f.get();
                        } catch (InterruptedException | ExecutionException e) {
                            r = new Result("exception", 0.0);
                        }
                        stats.total++;
                        stats.latencies.add(r.latencySec);
                        switch (r.type) {
                            case "ok" -> stats.ok++;
                            case "antibot" -> stats.antibot++;
                            default -> stats.errors.merge(r.type, 1, Integer::sum);
                        }
                        it.remove();
                    }
                }
            } else {
                // нечего ждать и нечего запускать
                if (now < endTime) {
                    try {
                        Thread.sleep(10);
                    } catch (InterruptedException ignored) {}
                } else {
                    break;
                }
            }
        }

        // дождаться остатки
        for (Future<Result> f : inFlight) {
            try {
                Result r = f.get();
                stats.total++;
                stats.latencies.add(r.latencySec);
                switch (r.type) {
                    case "ok" -> stats.ok++;
                    case "antibot" -> stats.antibot++;
                    default -> stats.errors.merge(r.type, 1, Integer::sum);
                }
            } catch (InterruptedException | ExecutionException e) {
                stats.total++;
                stats.errors.merge("exception", 1, Integer::sum);
            }
        }

        executor.shutdown();
        double elapsedSec = (System.nanoTime() / 1_000_000_000.0) - startTime;
        return new PhaseResult(stats, elapsedSec, concurrency);
    }

    // ========== MAIN: АВТО-ТЮНЕР ==========

    public static void main(String[] args) {
        System.setOut(new PrintStream(System.out, true, StandardCharsets.UTF_8));

        try {
            // Загружаем конфигурацию
            BotConfig cfg = BotConfig.load();
            Path cookiesFile = cfg.getPath("wb.cookies.file", "cookies.txt");
            String proxyFileRaw = cfg.getOptional("wb.proxies.file");
            Path proxyFile = (proxyFileRaw == null || proxyFileRaw.isBlank()) ? null : Path.of(proxyFileRaw);

            // Загружаем куки с ротацией
            log.info("Загружаем куки из файла: {}", cookiesFile);
            RotatingCookieJar cookieJar = WbCookieFetcher.loadCookieJar(cookiesFile);
            log.info("Загружено наборов куки: {}", cookieJar.size());

            // Загружаем прокси
            List<ProxyConfig> proxies = ProxyLoader.loadFromFile(
                    proxyFile,
                    cfg.getOptional("wb.proxy.username"),
                    cfg.getOptional("wb.proxy.password")
            );
            if (proxies.isEmpty()) {
                log.warn("⚠️  ПРОКСИ НЕ ЗАГРУЖЕНЫ! Запросы будут выполняться БЕЗ прокси.");
            } else {
                log.info("Загружено прокси: {}", proxies.size());
            }

            // Создаем WbHttpClient с поддержкой прокси и ротации куки
            wbHttpClient = new WbHttpClient(cookieJar, proxies);

            // Инициализируем Cookies для MyDualBot.getURL() (нужно для загрузки категорий)
            // Используем первый набор куки для инициализации
            Map<String, String> firstCookies = WbCookieFetcher.parseCookieHeaderToMap(cookieJar.firstCookieHeader());
            Set<HttpCookie> httpCookies = new HashSet<>();
            for (Map.Entry<String, String> entry : firstCookies.entrySet()) {
                try {
                    HttpCookie cookie = new HttpCookie(entry.getKey(), entry.getValue());
                    cookie.setDomain(".wildberries.ru");
                    cookie.setPath("/");
                    httpCookies.add(cookie);
                } catch (IllegalArgumentException e) {
                    // Игнорируем некорректные cookies
                }
            }
            // Устанавливаем Cookies в MyDualBot через reflection
            try {
                java.lang.reflect.Field cookiesField = MyDualBot.class.getDeclaredField("Cookies");
                cookiesField.setAccessible(true);
                cookiesField.set(null, httpCookies);
            } catch (Exception e) {
                log.warn("Предупреждение: не удалось установить Cookies в MyDualBot: {}", e.getMessage());
            }

            // Загружаем категории
            log.info("Загружаем категории...");
            categories = MyDualBot.getURL();
            log.info("Загружено категорий: {}", categories.size());
            
            if (categories.isEmpty()) {
                log.error("ОШИБКА: Не удалось загрузить категории!");
                return;
            }

            // 1) прогрев
            double baseLatency = warmupLatency(5);

            double currentRps = START_RPS;
            Double bestSafeRps = null;

            log.info("Стартуем автоподбор RPS с {}, шаг ×{}, максимум {}", START_RPS, MULTIPLIER, MAX_RPS);
            log.info("Каждая фаза длится {} сек", PHASE_DURATION_SEC);
            log.info("Используем {} категорий для запросов", categories.size());

            while (currentRps <= MAX_RPS) {
                log.info("======================================================================");
                log.info("ТЕСТ RPS = {}", String.format("%.3f", currentRps));
                log.info("======================================================================");

                PhaseResult pr = runPhase(currentRps, PHASE_DURATION_SEC, baseLatency);
                Stats stats = pr.stats;

                double elapsed = pr.elapsedSec;
                int total = stats.total;
                int ok = stats.ok;
                int antibot = stats.antibot;
                Map<String, Integer> errors = stats.errors;
                List<Double> latencies = stats.latencies;

                double avgLat = 0.0;
                if (!latencies.isEmpty()) {
                    double sum = 0.0;
                    for (double l : latencies) sum += l;
                    avgLat = sum / latencies.size();
                }

                double errorRatio = (total > 0) ? (double) (total - ok) / total : 0.0;
                double actualRps = (elapsed > 0) ? total / elapsed : 0.0;

                log.info("Фактическое время фазы:     {} сек", String.format("%.2f", elapsed));
                log.info("Фактический RPS:            {}", String.format("%.2f", actualRps));
                log.info("Всего запросов:             {}", total);
                log.info("Успешных (ok):              {}", ok);
                log.info("Антибот (antibot):          {}", antibot);
                log.info("Прочие ошибки:              {}", errors.isEmpty() ? "{}" : errors.toString());
                log.info("Средняя latency:            {} сек", String.format("%.3f", avgLat));
                log.info("Доля не-ok ответов:         {}%", String.format("%.2f", errorRatio * 100.0));
                log.info("Использованная конкурентность: {}", pr.concurrency);

                if (antibot > 0) {
                    log.warn("\n⚠️  Обнаружен антибот на этом уровне RPS.");
                    log.warn("   Останавливаем увеличение. Этот RPS уже слишком агрессивный.\n");
                    break;
                }

                if (errorRatio > MAX_ERROR_RATIO) {
                    log.warn("\n⚠️  Слишком много ошибок (> {}%).", String.format("%.0f", MAX_ERROR_RATIO * 100.0));
                    log.warn("   Останавливаем увеличение, чтобы не убивать сайт.\n");
                    break;
                }

                bestSafeRps = currentRps;
                currentRps *= MULTIPLIER;
                log.info("\n✅ Уровень выглядит стабильным, повышаем RPS...\n");
            }

            log.info("======================================================================");
            log.info("АВТОПОДБОР ЗАВЕРШЁН");
            if (bestSafeRps != null) {
                log.info("Рекомендуемый безопасный RPS (по результатам теста): ~{}", String.format("%.3f", bestSafeRps));
            } else {
                log.warn("Не удалось найти безопасный уровень RPS (даже стартовый оказался проблемным).");
            }
        } catch (IOException e) {
            log.error("ОШИБКА при загрузке категорий или куки: {}", e.getMessage(), e);
        } catch (Exception e) {
            log.error("НЕОЖИДАННАЯ ОШИБКА: {}", e.getMessage(), e);
        }
    }
}
