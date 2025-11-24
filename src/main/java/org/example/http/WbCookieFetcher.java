package org.example.http;

import io.github.bonigarcia.wdm.WebDriverManager;
import org.openqa.selenium.Cookie;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.firefox.FirefoxDriver;
import org.openqa.selenium.firefox.FirefoxOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Получает cookies и заголовки с Wildberries через Selenium один раз при старте.
 */
public final class WbCookieFetcher {
    
    private static final Logger log = LoggerFactory.getLogger(WbCookieFetcher.class);
    
    private static final String DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:120.0) Gecko/20100101 Firefox/120.0";
    private static final String WB_URL = "https://www.wildberries.ru/";
    
    public static WbSessionData fetchCookiesAndHeaders() {
        try {
            return fetchViaSelenium();
        } catch (Exception seleniumFailure) {
            log.warn("Selenium cookie fetch failed, falling back to HTTP client: {}", seleniumFailure.getMessage());
            try {
                return fetchViaHttp();
            } catch (IOException httpFailure) {
                throw new IllegalStateException("Could not fetch cookies from Wildberries (Selenium + HTTP failed)", httpFailure);
            }
        }
    }
    
    private static WbSessionData fetchViaSelenium() throws Exception {
        WebDriver driver = null;
        try {
            // Автоматически скачиваем и настраиваем FirefoxDriver
            WebDriverManager.firefoxdriver().setup();
            
            FirefoxOptions options = new FirefoxOptions();
            String firefoxBinary = resolveFirefoxBinary();
            if (firefoxBinary != null) {
                log.info("Using Firefox binary at {}", firefoxBinary);
                options.setBinary(firefoxBinary);
            } else {
                log.warn("Firefox binary not found. Using system default (if installed).");
            }
            options.addArguments("-headless");
            options.addArguments("--width=1920");
            options.addArguments("--height=1080");
            options.addPreference("general.useragent.override", DEFAULT_USER_AGENT);
            
            driver = new FirefoxDriver(options);
            log.info("Opening Wildberries via Selenium (Firefox) to fetch cookies...");
            driver.get(WB_URL);
            Thread.sleep(2000); // ждем, пока заголовки и cookies загрузятся

            // Дополнительно обращаемся к JSON-эндпоинту, чтобы получить x_wbaas_token и другие cookies
            try {
                driver.get("https://www.wildberries.ru/webapi/personalinfo/totals?locale=ru");
                Thread.sleep(1500);
            } catch (Exception ignored) {
            }
            
            Set<Cookie> seleniumCookies = driver.manage().getCookies();
            Map<String, String> cookies = new HashMap<>();
            for (Cookie cookie : seleniumCookies) {
                cookies.put(cookie.getName(), cookie.getValue());
            }
            
            // Получаем User-Agent из браузера
            JavascriptExecutor jsExecutor = (JavascriptExecutor) driver;
            String userAgent = (String) jsExecutor.executeScript("return navigator.userAgent;");
            
            log.info("Successfully fetched {} cookies from Wildberries", cookies.size());
            
            return new WbSessionData(cookies, userAgent);
            
        } finally {
            if (driver != null) {
                try {
                    driver.quit();
                } catch (Exception e) {
                    log.warn("Failed to close WebDriver", e);
                }
            }
        }
    }
    
    private static WbSessionData fetchViaHttp() throws IOException {
        log.info("Fetching cookies via HttpClient fallback...");
        CookieManager cookieManager = new CookieManager();
        cookieManager.setCookiePolicy(CookiePolicy.ACCEPT_ALL);
        
        HttpClient client = HttpClient.newBuilder()
                .cookieHandler(cookieManager)
                .build();
        
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(WB_URL))
                .GET()
                .header("User-Agent", DEFAULT_USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
                .header("Accept-Language", "ru-RU,ru;q=0.8,en-US;q=0.5,en;q=0.3")
                .build();
        
        try {
            client.send(request, HttpResponse.BodyHandlers.discarding());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while fetching cookies via HTTP", e);
        }
        
        Map<String, String> cookies = new HashMap<>();
        List<java.net.HttpCookie> cookieList = cookieManager.getCookieStore().get(URI.create(WB_URL));
        for (java.net.HttpCookie cookie : cookieList) {
            cookies.put(cookie.getName(), cookie.getValue());
        }
        
        log.info("HTTP fallback fetched {} cookies from Wildberries", cookies.size());
        return new WbSessionData(cookies, DEFAULT_USER_AGENT);
    }
    
    private static String resolveFirefoxBinary() {
        // 1. System property
        String sysProp = System.getProperty("firefox.binary");
        if (sysProp != null && Files.exists(Path.of(sysProp))) {
            return sysProp;
        }
        // 2. Environment variable
        String env = System.getenv("FIREFOX_BIN");
        if (env != null && Files.exists(Path.of(env))) {
            return env;
        }
        // 3. Common install paths
        String[] defaultPaths = {
                "C:\\\\Program Files\\\\Mozilla Firefox\\\\firefox.exe",
                "C:\\\\Program Files (x86)\\\\Mozilla Firefox\\\\firefox.exe"
        };
        for (String path : defaultPaths) {
            if (Files.exists(Path.of(path))) {
                return path;
            }
        }
        // 4. Cached selenium downloads (e.g., user-provided path)
        Path cacheDir = Path.of(System.getProperty("user.home"),
                ".cache", "selenium", "firefox", "win64");
        if (Files.exists(cacheDir) && Files.isDirectory(cacheDir)) {
            try {
                Path latest = Files.list(cacheDir)
                        .filter(Files::isDirectory)
                        .max(Comparator.comparing(Path::getFileName))
                        .orElse(null);
                if (latest != null) {
                    Path candidate = latest.resolve("firefox.exe");
                    if (Files.exists(candidate)) {
                        return candidate.toString();
                    }
                }
            } catch (IOException e) {
                log.warn("Failed to inspect Firefox cache directory", e);
            }
        }
        return null;
    }
    
    public record WbSessionData(Map<String, String> cookies, String userAgent) {
        public String toCookieHeader() {
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, String> entry : cookies.entrySet()) {
                if (sb.length() > 0) {
                    sb.append("; ");
                }
                sb.append(entry.getKey()).append("=").append(entry.getValue());
            }
            return sb.toString();
        }
    }
}

