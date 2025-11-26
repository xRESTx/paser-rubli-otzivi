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
        return fetchCookiesAndHeaders(null);
    }
    
    public static WbSessionData fetchCookiesAndHeaders(Map<String, String> staticCookies) {
        try {
            WbSessionData seleniumData = fetchViaSelenium();
            Map<String, String> seleniumCookies = seleniumData.cookies();
            Map<String, String> mergedCookies = new HashMap<>(seleniumCookies);
            
            // Проверяем наличие критических cookies из Selenium
            boolean hasWbauid = mergedCookies.containsKey("_wbauid");
            boolean hasToken = mergedCookies.containsKey("x_wbaas_token");
            
            // Используем статические cookies только как fallback для критически важных
            if (staticCookies != null && !staticCookies.isEmpty()) {
                // Добавляем критически важные cookies из статических, если их нет в Selenium
                if (!hasWbauid && staticCookies.containsKey("_wbauid")) {
                    mergedCookies.put("_wbauid", staticCookies.get("_wbauid"));
                    log.info("Using static _wbauid cookie as fallback (Selenium didn't capture it)");
                    hasWbauid = true;
                }
                if (!hasToken && staticCookies.containsKey("x_wbaas_token")) {
                    mergedCookies.put("x_wbaas_token", staticCookies.get("x_wbaas_token"));
                    log.info("Using static x_wbaas_token cookie as fallback (Selenium didn't capture it)");
                    hasToken = true;
                }
                
                // Добавляем другие статические cookies, которых нет в Selenium
                for (Map.Entry<String, String> entry : staticCookies.entrySet()) {
                    String key = entry.getKey();
                    if (!key.equals("_wbauid") && !key.equals("x_wbaas_token") && !mergedCookies.containsKey(key)) {
                        mergedCookies.put(key, entry.getValue());
                    }
                }
            }
            
            // Проверяем наличие критических cookies после слияния
            if (!mergedCookies.containsKey("_wbauid")) {
                log.error("ERROR: _wbauid cookie missing! This will cause 498 errors. Please check Selenium setup or provide static cookie.");
            } else {
                log.info("_wbauid cookie present in merged cookies");
            }
            if (!mergedCookies.containsKey("x_wbaas_token")) {
                log.error("ERROR: x_wbaas_token cookie missing! This will cause 498 errors. Please check Selenium setup or provide static cookie.");
            } else {
                log.info("x_wbaas_token cookie present in merged cookies");
            }
            
            log.info("Final merged cookies: {} total ({} from Selenium, {} from static)", 
                    mergedCookies.size(), seleniumCookies.size(), 
                    staticCookies != null ? staticCookies.size() : 0);
            return new WbSessionData(mergedCookies, seleniumData.userAgent(), seleniumData.requestHeaders());
        } catch (Exception seleniumFailure) {
            log.warn("Selenium cookie fetch failed, falling back to HTTP client: {}", seleniumFailure.getMessage());
            if (seleniumFailure.getMessage() != null && seleniumFailure.getMessage().contains("marionette")) {
                log.error("Firefox/Selenium failed to start. This usually means:");
                log.error("  1. Firefox needs a display server (install Xvfb: sudo apt install -y xvfb)");
                log.error("  2. Missing Firefox dependencies");
                log.error("  3. Firefox and geckodriver version mismatch");
                log.error("Using static cookies from app.properties as fallback...");
            }
            
            // Если есть статические cookies, используем их напрямую (HTTP fallback часто не работает)
            if (staticCookies != null && !staticCookies.isEmpty()) {
                boolean hasCritical = staticCookies.containsKey("_wbauid") && staticCookies.containsKey("x_wbaas_token");
                if (hasCritical) {
                    log.info("Using static cookies directly ({} cookies, including critical cookies)", staticCookies.size());
                    log.info("Static cookies contain: {}", staticCookies.keySet());
                    // Используем статические cookies напрямую, без HTTP fallback
                    Map<String, String> defaultHeaders = new HashMap<>();
                    defaultHeaders.put("accept", "*/*");
                    defaultHeaders.put("accept-language", "en-US,en;q=0.5");
                    defaultHeaders.put("accept-encoding", "gzip, deflate");
                    defaultHeaders.put("sec-fetch-dest", "empty");
                    defaultHeaders.put("sec-fetch-mode", "cors");
                    defaultHeaders.put("sec-fetch-site", "same-origin");
                    defaultHeaders.put("x-requested-with", "XMLHttpRequest");
                    return new WbSessionData(new HashMap<>(staticCookies), DEFAULT_USER_AGENT, defaultHeaders);
                } else {
                    log.warn("Static cookies missing critical cookies (_wbauid or x_wbaas_token). Trying HTTP fallback...");
                }
            } else {
                log.warn("No static cookies configured. Trying HTTP fallback...");
            }
            
            try {
                WbSessionData httpData = fetchViaHttp();
                Map<String, String> mergedCookies = new HashMap<>(httpData.cookies());
                
                // Используем статические cookies как fallback
                if (staticCookies != null && !staticCookies.isEmpty()) {
                    log.info("Merging static cookies ({} cookies) with HTTP cookies ({} cookies)", 
                            staticCookies.size(), mergedCookies.size());
                    // Приоритет статическим для критических cookies
                    if (!mergedCookies.containsKey("_wbauid") && staticCookies.containsKey("_wbauid")) {
                        mergedCookies.put("_wbauid", staticCookies.get("_wbauid"));
                        log.info("Added _wbauid from static cookies");
                    }
                    if (!mergedCookies.containsKey("x_wbaas_token") && staticCookies.containsKey("x_wbaas_token")) {
                        mergedCookies.put("x_wbaas_token", staticCookies.get("x_wbaas_token"));
                        log.info("Added x_wbaas_token from static cookies");
                    }
                    // Добавляем остальные статические cookies
                    for (Map.Entry<String, String> entry : staticCookies.entrySet()) {
                        if (!mergedCookies.containsKey(entry.getKey())) {
                            mergedCookies.put(entry.getKey(), entry.getValue());
                        }
                    }
                    log.info("Final merged cookies after HTTP fallback: {} total", mergedCookies.size());
                } else {
                    log.warn("No static cookies configured in app.properties! Bot may not work correctly.");
                }
                
                return new WbSessionData(mergedCookies, httpData.userAgent(), httpData.requestHeaders());
            } catch (IOException httpFailure) {
                // Если и HTTP fallback не сработал, используем только статические cookies
                if (staticCookies != null && !staticCookies.isEmpty()) {
                    log.warn("Both Selenium and HTTP failed, using static cookies only ({} cookies)", staticCookies.size());
                    boolean hasCritical = staticCookies.containsKey("_wbauid") && staticCookies.containsKey("x_wbaas_token");
                    if (hasCritical) {
                        log.info("Static cookies contain critical cookies (_wbauid and x_wbaas_token), bot should work");
                    } else {
                        log.error("Static cookies missing critical cookies! Bot may not work correctly.");
                    }
                    return new WbSessionData(new HashMap<>(staticCookies), DEFAULT_USER_AGENT, new HashMap<>());
                }
                throw new IllegalStateException("Could not fetch cookies from Wildberries (Selenium + HTTP failed). Please provide static cookies in app.properties.", httpFailure);
            }
        }
    }
    
    private static WbSessionData fetchViaSelenium() throws Exception {
        WebDriver driver = null;
        Path botProfilePath = null;
        try {
            // Проверяем наличие geckodriver в /usr/local/bin/geckodriver
            String customGeckodriverPath = "/usr/local/bin/geckodriver";
            Path geckodriverPath = Path.of(customGeckodriverPath);
            
            String geckodriverToUse = null;
            if (Files.exists(geckodriverPath) && Files.isRegularFile(geckodriverPath)) {
                // Проверяем, что файл исполняемый
                if (Files.isExecutable(geckodriverPath)) {
                    log.info("Found custom geckodriver at: {}", customGeckodriverPath);
                    System.setProperty("webdriver.gecko.driver", customGeckodriverPath);
                    geckodriverToUse = customGeckodriverPath;
                } else {
                    log.warn("Geckodriver found at {} but not executable, trying to make it executable", customGeckodriverPath);
                    try {
                        geckodriverPath.toFile().setExecutable(true);
                        System.setProperty("webdriver.gecko.driver", customGeckodriverPath);
                        geckodriverToUse = customGeckodriverPath;
                        log.info("Made geckodriver executable, using: {}", customGeckodriverPath);
                    } catch (Exception e) {
                        log.warn("Failed to make geckodriver executable: {}", e.getMessage());
                    }
                }
            }
            
            if (geckodriverToUse == null) {
                log.info("Custom geckodriver not found at {}, using WebDriverManager to download", customGeckodriverPath);
                // Автоматически скачиваем и настраиваем FirefoxDriver
                WebDriverManager.firefoxdriver().setup();
                // WebDriverManager устанавливает свой путь, получаем его
                geckodriverToUse = System.getProperty("webdriver.gecko.driver");
                if (geckodriverToUse != null) {
                    log.info("WebDriverManager downloaded geckodriver to: {}", geckodriverToUse);
                }
            }
            
            log.info("FirefoxDriver will use geckodriver from: {}", geckodriverToUse != null ? geckodriverToUse : "system PATH");
            
            // Получаем путь к Firefox
            String firefoxBinary = resolveFirefoxBinary();
            
            // Проверяем версию geckodriver для диагностики
            if (geckodriverToUse != null) {
                try {
                    ProcessBuilder pb = new ProcessBuilder(geckodriverToUse, "--version");
                    Process process = pb.start();
                    try (java.io.BufferedReader reader = new java.io.BufferedReader(
                            new java.io.InputStreamReader(process.getInputStream()))) {
                        String version = reader.readLine();
                        if (version != null) {
                            log.info("Geckodriver version: {}", version.trim());
                        }
                    }
                    process.waitFor();
                } catch (Exception e) {
                    log.warn("Failed to check geckodriver version: {}", e.getMessage());
                }
            }
            
            // Проверяем версию Firefox для диагностики
            if (firefoxBinary != null) {
                try {
                    ProcessBuilder pb = new ProcessBuilder(firefoxBinary, "--version");
                    Process process = pb.start();
                    try (java.io.BufferedReader reader = new java.io.BufferedReader(
                            new java.io.InputStreamReader(process.getInputStream()))) {
                        String version = reader.readLine();
                        if (version != null) {
                            log.info("Firefox version: {}", version.trim());
                        }
                    }
                    process.waitFor();
                } catch (Exception e) {
                    log.warn("Failed to check Firefox version: {}", e.getMessage());
                }
                
            }
            
            FirefoxOptions options = new FirefoxOptions();
            if (firefoxBinary != null) {
                log.info("Using Firefox binary at {}", firefoxBinary);
                options.setBinary(firefoxBinary);
            } else {
                log.warn("Firefox binary not found. Using system default (if installed).");
            }
            
            // Проверяем, есть ли DISPLAY и можем ли мы его использовать
            String display = System.getenv("DISPLAY");
            
            // Проверяем, можем ли мы использовать реальный DISPLAY
            if (display != null && !display.isEmpty()) {
                // Проверяем, доступен ли DISPLAY (может быть недоступен для процесса)
                if (isDisplayAvailable(display)) {
                    log.info("DISPLAY detected and available: {}", display);
                } else {
                    log.warn("DISPLAY {} is set but not accessible, checking for Xvfb...", display);
                    display = null; // Сбрасываем, будем искать Xvfb
                }
            }
            
            // Если DISPLAY не доступен, ищем Xvfb (только на Linux/Unix)
            String osName = System.getProperty("os.name", "").toLowerCase();
            boolean isLinux = !osName.contains("win");
            
            if ((display == null || display.isEmpty()) && isLinux) {
                log.info("No DISPLAY detected, checking for Xvfb (Linux)...");
                String xvfbDisplay = findXvfbDisplay();
                if (xvfbDisplay != null) {
                    log.info("Found Xvfb display: {}, using it", xvfbDisplay);
                    System.setProperty("DISPLAY", xvfbDisplay);
                    display = xvfbDisplay;
                } else {
                    log.warn("No DISPLAY and no Xvfb found. Firefox may fail to start.");
                    log.warn("Consider installing Xvfb: sudo apt install -y xvfb");
                    log.warn("Or run with: xvfb-run -a java -jar ...");
                }
            } else if (display == null || display.isEmpty()) {
                log.warn("No DISPLAY detected. On Windows, Firefox should work without DISPLAY.");
            }
            
            if (display != null && !display.isEmpty()) {
                log.info("Firefox will run with display: {}", display);
            } else {
                log.warn("Firefox will try to run without DISPLAY (may fail)");
            }
            
            // Настраиваем профиль "botprofile"
            botProfilePath = getOrCreateBotProfile();
            if (botProfilePath != null) {
                log.info("Using Firefox profile: {}", botProfilePath);
                options.addArguments("-profile", botProfilePath.toString());
            } else {
                log.warn("Could not create/find botprofile, Firefox will use default profile");
            }
            
            // Базовые опции (упрощенные для стабильности)
            options.addArguments("--width=1920");
            options.addArguments("--height=1080");
            options.addArguments("--new-instance"); // Создает новый экземпляр Firefox с новым профилем
            options.addArguments("--no-remote");
            options.addArguments("--disable-dev-shm-usage"); // Избегаем проблем с /dev/shm
            
            // Минимальные опции для стабильности
            options.addArguments("--disable-extensions");
            options.addArguments("--disable-background-timer-throttling");
            options.addArguments("--disable-backgrounding-occluded-windows");
            options.addArguments("--disable-renderer-backgrounding");
            
            // Дополнительные опции для стабильности на сервере
            options.addArguments("--no-first-run");
            options.addArguments("--disable-default-apps");
            options.addArguments("--disable-popup-blocking");
            
            options.addPreference("general.useragent.override", DEFAULT_USER_AGENT);
            options.addPreference("dom.webdriver.enabled", false);
            options.addPreference("useAutomationExtension", false);
            
            // Упрощенные настройки для стабильности (убрали TRACE логирование)
            // options.addPreference("marionette.logging", "TRACE"); // Убрано для стабильности
            // options.addPreference("marionette.log.level", "Trace"); // Убрано для стабильности
            
            // Увеличиваем таймауты для медленных систем
            System.setProperty("webdriver.firefox.driver.timeout", "120");
            System.setProperty("webdriver.firefox.driver.readTimeout", "120");
            // Не устанавливаем logfile, чтобы избежать проблем с правами доступа
            
            log.info("Creating FirefoxDriver...");
            log.info("Firefox options: binary={}, display={}", firefoxBinary, display != null ? display : "none");
            
            String originalDisplay = display;
            Exception lastException = null;
            
            // Пытаемся запустить Firefox, если не получилось - пробуем с Xvfb
            for (int attempt = 0; attempt < 2; attempt++) {
                try {
                    driver = new FirefoxDriver(options);
                    log.info("FirefoxDriver created successfully");
                    break; // Успешно запустили
                } catch (Exception e) {
                    lastException = e;
                    String errorMsg = e.getMessage();
                    
                    // Если это первая попытка и мы на Linux, пробуем Xvfb
                    if (attempt == 0 && isLinux && (originalDisplay == null || originalDisplay.equals(":0") || !originalDisplay.startsWith(":"))) {
                        log.warn("Failed to start Firefox with DISPLAY={}, trying to find/start Xvfb (Linux)...", originalDisplay);
                        
                        // Пытаемся найти существующий Xvfb
                        String xvfbDisplay = findXvfbDisplay();
                        if (xvfbDisplay != null && !xvfbDisplay.equals(originalDisplay)) {
                            log.info("Found Xvfb display: {}, switching to it", xvfbDisplay);
                            System.setProperty("DISPLAY", xvfbDisplay);
                            display = xvfbDisplay;
                            continue; // Пробуем еще раз с Xvfb
                        }
                        
                        // Пытаемся запустить Xvfb автоматически (только на Linux)
                        log.info("Trying to start Xvfb automatically...");
                        String newXvfbDisplay = tryStartXvfb();
                        if (newXvfbDisplay != null) {
                            log.info("Successfully started Xvfb on display: {}", newXvfbDisplay);
                            System.setProperty("DISPLAY", newXvfbDisplay);
                            display = newXvfbDisplay;
                            continue; // Пробуем еще раз с новым Xvfb
                        }
                    }
                    
                    // Если это последняя попытка или Xvfb не помог, выводим ошибку
                    if (attempt == 1 || (attempt == 0 && (originalDisplay == null || originalDisplay.equals(":0") || !originalDisplay.startsWith(":")))) {
                        log.error("Failed to create FirefoxDriver: {}", errorMsg);
                        
                        if (errorMsg != null && errorMsg.contains("status 1")) {
                            log.error("========================================");
                            log.error("FIREFOX FAILED TO START - Process exited with status 1");
                            log.error("========================================");
                            log.error("This usually means:");
                            log.error("  1. Firefox cannot access DISPLAY (even if DISPLAY={} is set)", display);
                            log.error("  2. Missing Firefox dependencies: sudo apt install -y libgtk-3-0 libdbus-glib-1-2 libx11-xcb1 libxcomposite1 libxcursor1 libxdamage1 libxi6 libxtst6 libnss3 libxrandr2 libasound2 libpangocairo-1.0-0 libatk1.0-0 libcairo-gobject2 libgtk-3-0 libgdk-pixbuf2.0-0");
                            log.error("  3. Firefox/geckodriver version mismatch");
                            log.error("Solution: Install Xvfb and run with: xvfb-run -a java -jar ...");
                        } else if (errorMsg != null && errorMsg.contains("marionette port")) {
                            log.error("========================================");
                            log.error("FIREFOX FAILED TO START - DISPLAY ISSUE");
                            log.error("========================================");
                            log.error("Firefox cannot start without a display server.");
                            log.error("");
                            log.error("SOLUTION 1 (Recommended): Use Xvfb");
                            log.error("  1. Install Xvfb: sudo apt install -y xvfb");
                            log.error("  2. Run with: xvfb-run -a java -jar paser-rubli-otzivi.jar");
                            log.error("  3. Or update start.sh to use xvfb-run automatically");
                            log.error("");
                            log.error("SOLUTION 2: Use static cookies");
                            log.error("  Add cookies to app.properties: wb.staticCookies=_wbauid=...;x_wbaas_token=...");
                            log.error("  Bot will use static cookies instead of Selenium");
                            log.error("========================================");
                        } else if (errorMsg != null && (errorMsg.contains("Could not start a new session") || 
                                                          errorMsg.contains("invalid address of the remote server") ||
                                                          errorMsg.contains("browser start-up failure"))) {
                            log.error("========================================");
                            log.error("FIREFOX FAILED TO START - Browser startup failure");
                            log.error("========================================");
                            log.error("Geckodriver cannot start Firefox. Possible causes:");
                            log.error("  1. Firefox/geckodriver version mismatch");
                            log.error("     - Firefox version: Check logs above");
                            log.error("     - Geckodriver version: Check logs above");
                            log.error("     - Solution: Update geckodriver to match Firefox version");
                            log.error("  2. Missing Firefox dependencies");
                            log.error("     - Run: sudo apt install -y libgtk-3-0 libdbus-glib-1-2 libx11-xcb1 libxcomposite1 libxcursor1 libxdamage1 libxi6 libxtst6 libnss3 libxrandr2 libasound2 libpangocairo-1.0-0 libatk1.0-0 libcairo-gobject2 libgdk-pixbuf2.0-0");
                            log.error("  3. Firefox cannot access DISPLAY");
                            log.error("     - DISPLAY is set to: {}", display != null ? display : "not set");
                            log.error("     - Solution: Ensure Xvfb is running or use: xvfb-run -a java -jar ...");
                            log.error("  4. Geckodriver permissions issue");
                            log.error("     - Check: ls -la {}", geckodriverToUse != null ? geckodriverToUse : "/usr/local/bin/geckodriver");
                            log.error("     - Fix: sudo chmod +x {}", geckodriverToUse != null ? geckodriverToUse : "/usr/local/bin/geckodriver");
                            log.error("========================================");
                        } else {
                            log.warn("This might be due to Firefox/geckodriver version mismatch.");
                            log.warn("Make sure Firefox and geckodriver versions are compatible.");
                        }
                        
                        throw new RuntimeException("Could not start FirefoxDriver: " + errorMsg, e);
                    }
                }
            }
            
            if (driver == null) {
                throw new RuntimeException("Could not start FirefoxDriver after retries", lastException);
            }
            
            log.info("Opening Wildberries via Selenium (Firefox) to fetch cookies...");
            driver.get(WB_URL);
            
            // Ждем полной загрузки страницы и выполнения всех скриптов
            Thread.sleep(8000); // Увеличиваем время для полной загрузки страницы
            
            // Проверяем, не попали ли мы на страницу блокировки (только логируем, не ждем)
            try {
                String pageSource = driver.getPageSource();
                if (pageSource.contains("Подозрительная активность") || 
                    pageSource.contains("Что-то не так") ||
                    pageSource.contains("captcha-support")) {
                    log.warn("WARNING: Wildberries blocked the request - suspicious activity detected!");
                    log.warn("Will continue with available cookies. _wbauid should be provided via static cookies in app.properties.");
                }
            } catch (Exception e) {
                log.debug("Failed to check for blocking page: {}", e.getMessage());
            }
            
            // Получаем начальные cookies
            Set<Cookie> initialCookies = driver.manage().getCookies();
            log.debug("Initial cookies after page load: {}", initialCookies.size());
            
            // Выполняем JavaScript для инициализации всех cookies (особенно x_wbaas_token и _wbauid)
            try {
                JavascriptExecutor jsExecutor = (JavascriptExecutor) driver;
                // Ждем, пока страница полностью загрузится
                jsExecutor.executeScript("return document.readyState;");
                Thread.sleep(2000);
                
                // Прокручиваем страницу, чтобы активировать скрипты
                jsExecutor.executeScript("window.scrollTo(0, 500);");
                Thread.sleep(2000);
                jsExecutor.executeScript("window.scrollTo(0, 0);");
                Thread.sleep(2000);
                
                // Перезагружаем страницу для инициализации cookies
                jsExecutor.executeScript("window.location.reload();");
                Thread.sleep(5000);
            } catch (Exception e) {
                log.debug("Failed to interact with page: {}", e.getMessage());
            }
            
            // Дополнительно обращаемся к JSON-эндпоинту, чтобы получить x_wbaas_token и другие cookies
            try {
                driver.get("https://www.wildberries.ru/webapi/personalinfo/totals?locale=ru");
                Thread.sleep(3000);
            } catch (Exception e) {
                log.debug("Failed to fetch personalinfo endpoint: {}", e.getMessage());
            }
            
            // Также обращаемся к странице промо-акции для получения всех cookies
            try {
                log.info("Navigating to promotions page to initialize cookies...");
                driver.get("https://www.wildberries.ru/promotions/rubli-za-otzyvy");
                Thread.sleep(8000); // Увеличиваем время для полной загрузки страницы и выполнения скриптов
                
                // Проверяем на блокировку (только логируем)
                String pageSource = driver.getPageSource();
                if (pageSource.contains("Подозрительная активность") || pageSource.contains("Что-то не так")) {
                    log.warn("WARNING: Blocked on promotions page! Continuing anyway...");
                }
                
                // Выполняем JavaScript для инициализации cookies через взаимодействие со страницей
                try {
                    JavascriptExecutor jsExecutor = (JavascriptExecutor) driver;
                    // Ждем полной загрузки
                    jsExecutor.executeScript("return document.readyState;");
                    Thread.sleep(2000);
                    
                    // Прокручиваем страницу несколько раз, чтобы активировать скрипты и инициализировать cookies
                    jsExecutor.executeScript("window.scrollTo(0, document.body.scrollHeight / 3);");
                    Thread.sleep(3000);
                    jsExecutor.executeScript("window.scrollTo(0, document.body.scrollHeight / 2);");
                    Thread.sleep(3000);
                    jsExecutor.executeScript("window.scrollTo(0, 0);");
                    Thread.sleep(3000);
                    
                    // Пытаемся получить токены через JavaScript напрямую
                    try {
                        // Проверяем localStorage и sessionStorage для токенов
                        String xToken = (String) jsExecutor.executeScript(
                            "return localStorage.getItem('x_wbaas_token') || sessionStorage.getItem('x_wbaas_token') || null;");
                        if (xToken != null && !xToken.isEmpty()) {
                            log.info("Found x_wbaas_token in storage: {}", xToken.substring(0, Math.min(50, xToken.length())) + "...");
                        }
                        
                        // Пытаемся выполнить запрос к API, чтобы инициализировать cookies
                        jsExecutor.executeScript(
                            "var xhr = new XMLHttpRequest();" +
                            "xhr.open('GET', 'https://www.wildberries.ru/webapi/personalinfo/totals?locale=ru', false);" +
                            "xhr.send();");
                        Thread.sleep(2000);
                    } catch (Exception e) {
                        log.debug("Failed to initialize tokens via JavaScript: {}", e.getMessage());
                    }
                } catch (Exception e) {
                    log.debug("Failed to interact with page: {}", e.getMessage());
                }
            } catch (Exception e) {
                log.debug("Failed to fetch promotions page: {}", e.getMessage());
            }
            
            // Получаем только x_wbaas_token из Selenium, _wbauid берем из статических cookies
            // Обращаемся к API эндпоинту один раз для получения x_wbaas_token
            try {
                String apiUrl = "https://www.wildberries.ru/__internal/u-recom/personal/ru/common/v8/search?ab_testing=false&ab_testing=false&action=1004833&appType=1&curr=rub&dest=-1257786&hide_dtype=11&lang=ru&page=1&query=0&resultset=catalog&spp=30&suppressSpellcheck=false";
                log.info("Fetching API endpoint to get x_wbaas_token: {}", apiUrl);
                driver.get(apiUrl);
                Thread.sleep(5000); // Даем время для установки cookies
            } catch (Exception e) {
                log.warn("Failed to fetch search API endpoint: {}", e.getMessage());
            }
            
            // Получаем cookies через Selenium API - только x_wbaas_token
            Set<Cookie> seleniumCookies = driver.manage().getCookies();
            Map<String, String> cookies = new HashMap<>();
            log.info("Collecting cookies from Selenium API ({} cookies found)...", seleniumCookies.size());
            for (Cookie cookie : seleniumCookies) {
                cookies.put(cookie.getName(), cookie.getValue());
                // Логируем только x_wbaas_token
                if (cookie.getName().equals("x_wbaas_token")) {
                    log.info("Found x_wbaas_token from Selenium API: {}... (domain: {}, path: {})", 
                            cookie.getValue().length() > 30 ? cookie.getValue().substring(0, 30) + "..." : cookie.getValue(),
                            cookie.getDomain(),
                            cookie.getPath());
                }
            }
            
            // Проверяем наличие x_wbaas_token
            if (!cookies.containsKey("x_wbaas_token")) {
                log.warn("WARNING: x_wbaas_token cookie not found! This will likely cause 498 errors.");
                log.warn("Available cookies: {}", cookies.keySet());
            } else {
                log.info("x_wbaas_token cookie found successfully");
            }
            
            // _wbauid не получаем через Selenium - используем только из статических cookies
            log.info("_wbauid will be taken from static cookies in app.properties (not fetched via Selenium)");
            
            // Получаем User-Agent из браузера
            JavascriptExecutor jsExecutor = (JavascriptExecutor) driver;
            String userAgent = (String) jsExecutor.executeScript("return navigator.userAgent;");
            
            // Перехватываем заголовки реального запроса к API через JavaScript
            Map<String, String> requestHeaders = captureRequestHeaders(driver);
            
            log.info("Successfully fetched {} cookies from Wildberries: {}", cookies.size(), cookies.keySet());
            log.info("Captured {} request headers from browser: {}", requestHeaders.size(), requestHeaders.keySet());
            if (!requestHeaders.isEmpty()) {
                log.debug("Captured headers details: {}", requestHeaders);
            }
            // Логируем наличие критически важных заголовков
            if (requestHeaders.containsKey("clicks")) {
                log.info("clicks header captured successfully (length: {})", requestHeaders.get("clicks").length());
            } else {
                log.warn("WARNING: clicks header not captured! This may cause 498 errors.");
            }
            
            return new WbSessionData(cookies, userAgent, requestHeaders);
            
        } finally {
            if (driver != null) {
                try {
                    driver.quit();
                } catch (Exception e) {
                    log.warn("Failed to close WebDriver", e);
                }
            }
            // Профиль botprofile сохраняется между запусками, не удаляем его
        }
    }
    
    /**
     * Находит или создает профиль Firefox с именем "botprofile"
     */
    private static Path getOrCreateBotProfile() {
        try {
            String osName = System.getProperty("os.name", "").toLowerCase();
            
            // Список возможных путей к директории профилей Firefox (в порядке приоритета)
            java.util.List<Path> possibleProfileDirs = new java.util.ArrayList<>();
            
            if (osName.contains("win")) {
                // Windows: %APPDATA%\Mozilla\Firefox\Profiles
                String appData = System.getenv("APPDATA");
                if (appData != null) {
                    possibleProfileDirs.add(Path.of(appData, "Mozilla", "Firefox", "Profiles"));
                }
            } else {
                // Linux/Unix: проверяем несколько возможных мест
                String home = System.getProperty("user.home");
                if (home != null) {
                    // 1. Snap Firefox (приоритет) - /home/user/snap/firefox/common/.mozilla/firefox
                    possibleProfileDirs.add(Path.of(home, "snap", "firefox", "common", ".mozilla", "firefox"));
                    // 2. Стандартное место - ~/.mozilla/firefox
                    possibleProfileDirs.add(Path.of(home, ".mozilla", "firefox"));
                }
            }
            
            // Ищем существующий профиль "botprofile" во всех возможных директориях
            for (Path firefoxProfilesDir : possibleProfileDirs) {
                if (!Files.exists(firefoxProfilesDir)) {
                    log.debug("Firefox profiles directory not found: {}", firefoxProfilesDir);
                    continue;
                }
                
                // Ищем существующий профиль с именем, содержащим "botprofile"
                try (var stream = Files.list(firefoxProfilesDir)) {
                    Path existingProfile = stream
                        .filter(Files::isDirectory)
                        .filter(path -> {
                            String name = path.getFileName().toString();
                            return name.contains("botprofile") || name.endsWith(".botprofile");
                        })
                        .findFirst()
                        .orElse(null);
                    
                    if (existingProfile != null) {
                        log.info("Found existing botprofile: {}", existingProfile);
                        return existingProfile;
                    }
                } catch (Exception e) {
                    log.debug("Failed to list profiles in {}: {}", firefoxProfilesDir, e.getMessage());
                }
            }
            
            // Если профиль не найден, используем первый доступный каталог для создания нового
            Path firefoxProfilesDir = null;
            for (Path dir : possibleProfileDirs) {
                if (Files.exists(dir)) {
                    firefoxProfilesDir = dir;
                    break;
                }
            }
            
            // Если ни один каталог не существует, пытаемся создать стандартный
            if (firefoxProfilesDir == null) {
                String home = System.getProperty("user.home");
                if (home != null && !osName.contains("win")) {
                    firefoxProfilesDir = Path.of(home, ".mozilla", "firefox");
                } else if (osName.contains("win")) {
                    String appData = System.getenv("APPDATA");
                    if (appData != null) {
                        firefoxProfilesDir = Path.of(appData, "Mozilla", "Firefox", "Profiles");
                    }
                }
            }
            
            if (firefoxProfilesDir == null) {
                log.warn("Could not determine Firefox profiles directory");
                return null;
            }
            
            // Создаем директорию профилей, если её нет
            if (!Files.exists(firefoxProfilesDir)) {
                try {
                    Files.createDirectories(firefoxProfilesDir);
                    log.info("Created Firefox profiles directory: {}", firefoxProfilesDir);
                } catch (Exception e) {
                    log.error("Failed to create Firefox profiles directory: {}", e.getMessage());
                    return null;
                }
            }
            
            // Если профиль не найден, создаем новый
            // Firefox создает профили в формате: xxxxxxxx.botprofile
            String profileName = System.currentTimeMillis() + ".botprofile";
            Path newProfile = firefoxProfilesDir.resolve(profileName);
            
            // Создаем директорию профиля
            Files.createDirectories(newProfile);
            
            // Создаем базовые файлы профиля
            // prefs.js - базовые настройки
            Path prefsJs = newProfile.resolve("prefs.js");
            if (!Files.exists(prefsJs)) {
                Files.createFile(prefsJs);
                String prefsContent = "user_pref(\"app.update.auto\", false);\n" +
                                      "user_pref(\"app.update.enabled\", false);\n" +
                                      "user_pref(\"browser.sessionstore.resume_from_crash\", false);\n";
                Files.writeString(prefsJs, prefsContent);
            }
            
            // user.js - пользовательские настройки (опционально)
            Path userJs = newProfile.resolve("user.js");
            if (!Files.exists(userJs)) {
                Files.createFile(userJs);
            }
            
            log.info("Created new botprofile: {}", newProfile);
            return newProfile;
            
        } catch (Exception e) {
            log.error("Failed to get or create botprofile: {}", e.getMessage());
            return null;
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
        
        // Используем заголовки по умолчанию для HTTP fallback
        Map<String, String> defaultHeaders = new HashMap<>();
        defaultHeaders.put("accept", "*/*");
        defaultHeaders.put("accept-language", "en-US,en;q=0.5");
        defaultHeaders.put("accept-encoding", "gzip, deflate, br, zstd");
        defaultHeaders.put("sec-fetch-dest", "empty");
        defaultHeaders.put("sec-fetch-mode", "cors");
        defaultHeaders.put("sec-fetch-site", "same-origin");
        defaultHeaders.put("priority", "u=4");
        defaultHeaders.put("x-requested-with", "XMLHttpRequest");
        defaultHeaders.put("x-spa-version", "13.14.1");
        defaultHeaders.put("x-userid", "0");
        defaultHeaders.put("deviceid", "site_2bc3dd7d2f1a4eb28539e17ff17c894a");
        
        return new WbSessionData(cookies, DEFAULT_USER_AGENT, defaultHeaders);
    }
    
    private static String resolveFirefoxBinary() {
        // 1. System property (высший приоритет)
        String sysProp = System.getProperty("firefox.binary");
        if (sysProp != null && !sysProp.isBlank()) {
            Path path = Path.of(sysProp);
            if (Files.exists(path)) {
                log.debug("Found Firefox via system property: {}", path);
                return path.toString();
            } else {
                log.warn("Firefox path from system property does not exist: {}", sysProp);
            }
        }
        // 2. Environment variable
        String env = System.getenv("FIREFOX_BIN");
        if (env != null && !env.isBlank()) {
            Path path = Path.of(env);
            if (Files.exists(path)) {
                log.debug("Found Firefox via environment variable: {}", path);
                return path.toString();
            } else {
                log.warn("Firefox path from environment variable does not exist: {}", env);
            }
        }
        // 3. Проверяем, есть ли firefox в PATH (кроссплатформенный способ)
        try {
            String osName = System.getProperty("os.name", "").toLowerCase();
            String[] command;
            if (osName.contains("win")) {
                // Windows использует where вместо which
                command = new String[]{"where", "firefox.exe"};
            } else {
                // Linux/Unix использует which
                command = new String[]{"which", "firefox"};
            }
            
            Process process = new ProcessBuilder(command).start();
            int exitCode = process.waitFor();
            if (exitCode == 0) {
                try (java.io.BufferedReader reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(process.getInputStream()))) {
                    String path = reader.readLine();
                    if (path != null && !path.isBlank()) {
                        Path firefoxPath = Path.of(path.trim());
                        if (Files.exists(firefoxPath)) {
                            log.debug("Found Firefox in PATH: {}", firefoxPath);
                            return firefoxPath.toString();
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.trace("Failed to find Firefox in PATH: {}", e.getMessage());
        }
        // 4. Common install paths (Linux)
        String osName = System.getProperty("os.name", "").toLowerCase();
        String[] defaultPaths;
        if (osName.contains("win")) {
            // Windows paths
            defaultPaths = new String[]{
                    "C:\\Program Files\\Mozilla Firefox\\firefox.exe",
                    "C:\\Program Files (x86)\\Mozilla Firefox\\firefox.exe"
            };
        } else {
            // Linux/Unix paths
            defaultPaths = new String[]{
                    "/usr/bin/firefox",
                    "/usr/local/bin/firefox",
                    "/opt/firefox/firefox",
                    "/snap/bin/firefox"
            };
        }
        for (String pathStr : defaultPaths) {
            Path path = Path.of(pathStr);
            if (Files.exists(path)) {
                log.debug("Found Firefox at standard location: {}", path);
                return path.toString();
            } else {
                log.trace("Firefox not found at: {}", pathStr);
            }
        }
        // 5. Cached selenium downloads
        String cacheSubdir = osName.contains("win") ? "win64" : "linux64";
        Path cacheDir = Path.of(System.getProperty("user.home"),
                ".cache", "selenium", "firefox", cacheSubdir);
        if (Files.exists(cacheDir) && Files.isDirectory(cacheDir)) {
            try {
                Path latest = Files.list(cacheDir)
                        .filter(Files::isDirectory)
                        .max(Comparator.comparing(Path::getFileName))
                        .orElse(null);
                if (latest != null) {
                    String binaryName = osName.contains("win") ? "firefox.exe" : "firefox";
                    Path candidate = latest.resolve(binaryName);
                    if (Files.exists(candidate)) {
                        log.debug("Found Firefox in Selenium cache: {}", candidate);
                        return candidate.toString();
                    }
                }
            } catch (IOException e) {
                log.warn("Failed to inspect Firefox cache directory", e);
            }
        }
        log.warn("Firefox binary not found in any standard location");
        return null;
    }
    
    /**
     * Проверяет, доступен ли DISPLAY для использования (только на Linux/Unix)
     */
    private static boolean isDisplayAvailable(String display) {
        // DISPLAY доступен только на Linux/Unix системах
        String osName = System.getProperty("os.name", "").toLowerCase();
        if (osName.contains("win")) {
            return false;
        }
        
        try {
            // Проверяем доступность DISPLAY через xset (Linux/Unix команда)
            ProcessBuilder pb = new ProcessBuilder("xset", "q");
            pb.environment().put("DISPLAY", display);
            Process process = pb.start();
            // Читаем вывод, чтобы процесс не завис
            try (java.io.InputStream is = process.getInputStream();
                 java.io.InputStream es = process.getErrorStream()) {
                is.readAllBytes();
                es.readAllBytes();
            }
            
            // Ждем завершения процесса (без таймаута, так как xset обычно быстрый)
            int exitCode = process.waitFor();
            return exitCode == 0;
        } catch (Exception e) {
            log.debug("Display {} check failed (xset not available or display not accessible): {}", display, e.getMessage());
            // Если xset недоступен, предполагаем что DISPLAY может быть недоступен
            return false;
        }
    }
    
    /**
     * Пытается найти доступный Xvfb дисплей, проверяя /tmp/.X*-lock файлы
     * Работает только на Linux/Unix системах
     */
    private static String findXvfbDisplay() {
        // Xvfb доступен только на Linux/Unix
        String osName = System.getProperty("os.name", "").toLowerCase();
        if (osName.contains("win")) {
            return null;
        }
        
        try {
            Path tmpDir = Path.of("/tmp");
            if (!Files.exists(tmpDir)) {
                return null;
            }
            
            // Ищем lock файлы X серверов
            try (var stream = Files.list(tmpDir)) {
                return stream
                    .filter(path -> {
                        String name = path.getFileName().toString();
                        return name.startsWith(".X") && name.endsWith("-lock");
                    })
                    .map(path -> {
                        String name = path.getFileName().toString();
                        // Извлекаем номер дисплея из имени .X99-lock -> :99
                        String displayNum = name.substring(2, name.length() - 5);
                        try {
                            Integer.parseInt(displayNum);
                            return ":" + displayNum;
                        } catch (NumberFormatException e) {
                            return null;
                        }
                    })
                    .filter(display -> display != null)
                    .findFirst()
                    .orElse(null);
            }
        } catch (Exception e) {
            log.debug("Failed to find Xvfb display: {}", e.getMessage());
            return null;
        }
    }
    
    /**
     * Пытается автоматически запустить Xvfb на свободном дисплее
     * Работает только на Linux/Unix системах
     */
    private static String tryStartXvfb() {
        // Xvfb доступен только на Linux/Unix
        String osName = System.getProperty("os.name", "").toLowerCase();
        if (osName.contains("win")) {
            return null;
        }
        
        try {
            // Проверяем, установлен ли Xvfb
            ProcessBuilder checkPb = new ProcessBuilder("which", "Xvfb");
            Process checkProcess = checkPb.start();
            int checkExitCode = checkProcess.waitFor();
            if (checkExitCode != 0) {
                log.debug("Xvfb not found in PATH");
                return null;
            }
            
            // Сначала пробуем display :0, затем остальные (99-199)
            int[] displayNumbers = new int[102]; // 0 + 99-199
            displayNumbers[0] = 0; // Начинаем с :0
            for (int i = 1; i < displayNumbers.length; i++) {
                displayNumbers[i] = 98 + i; // 99, 100, 101, ..., 199
            }
            
            for (int displayNum : displayNumbers) {
                String display = ":" + displayNum;
                Path lockFile = Path.of("/tmp", ".X" + displayNum + "-lock");
                
                // Если lock файл существует, дисплей занят
                if (Files.exists(lockFile)) {
                    log.debug("Display {} is already in use (lock file exists)", display);
                    continue;
                }
                
                // Пытаемся запустить Xvfb на этом дисплее
                try {
                    log.debug("Attempting to start Xvfb on display {}", display);
                    ProcessBuilder pb = new ProcessBuilder("Xvfb", display, "-screen", "0", "1920x1080x24", "-ac", "+extension", "RANDR");
                    pb.redirectError(ProcessBuilder.Redirect.DISCARD);
                    pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
                    Process xvfbProcess = pb.start();
                    
                    // Ждем немного, чтобы Xvfb успел запуститься
                    Thread.sleep(2000);
                    
                    // Проверяем, запустился ли процесс
                    if (xvfbProcess.isAlive() && Files.exists(lockFile)) {
                        log.info("Successfully started Xvfb on display {}", display);
                        return display;
                    } else {
                        // Процесс не запустился или завершился
                        log.debug("Xvfb process on display {} did not start properly", display);
                        try {
                            xvfbProcess.destroy();
                        } catch (Exception ignored) {}
                    }
                } catch (Exception e) {
                    log.debug("Failed to start Xvfb on display {}: {}", display, e.getMessage());
                    continue;
                }
            }
            
            log.debug("Could not find free display for Xvfb");
            return null;
        } catch (Exception e) {
            log.debug("Failed to start Xvfb: {}", e.getMessage());
            return null;
        }
    }
    
    public record WbSessionData(Map<String, String> cookies, String userAgent, Map<String, String> requestHeaders) {
        public WbSessionData(Map<String, String> cookies, String userAgent) {
            this(cookies, userAgent, new HashMap<>());
        }
        
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
    
    /**
     * Перехватывает заголовки реального запроса к API через JavaScript.
     * Выполняет реальный запрос к API и перехватывает его заголовки, включая динамический заголовок 'clicks'.
     */
    private static Map<String, String> captureRequestHeaders(WebDriver driver) {
        Map<String, String> headers = new HashMap<>();
        try {
            JavascriptExecutor jsExecutor = (JavascriptExecutor) driver;
            
            // Перехватываем заголовки реального запроса через перехват fetch/XHR
            String script = 
                "var capturedHeaders = {};" +
                "var capturedClicks = null;" +
                "var originalFetch = window.fetch;" +
                "window.fetch = function(url, options) {" +
                "  if (options && options.headers) {" +
                "    for (var key in options.headers) {" +
                "      var lowerKey = key.toLowerCase();" +
                "      capturedHeaders[lowerKey] = options.headers[key];" +
                "      if (lowerKey === 'clicks') {" +
                "        capturedClicks = options.headers[key];" +
                "      }" +
                "    }" +
                "  }" +
                "  return originalFetch.apply(this, arguments);" +
                "};" +
                "var originalSetRequestHeader = XMLHttpRequest.prototype.setRequestHeader;" +
                "XMLHttpRequest.prototype.setRequestHeader = function(header, value) {" +
                "  var lowerKey = header.toLowerCase();" +
                "  capturedHeaders[lowerKey] = value;" +
                "  if (lowerKey === 'clicks') {" +
                "    capturedClicks = value;" +
                "  }" +
                "  return originalSetRequestHeader.apply(this, arguments);" +
                "};" +
                "try {" +
                "  var xhr = new XMLHttpRequest();" +
                "  xhr.open('GET', 'https://www.wildberries.ru/__internal/u-recom/personal/ru/common/v8/search?ab_testing=false&appType=1&curr=rub&dest=-1257786&lang=ru&page=1&query=0&resultset=catalog&spp=30', false);" +
                "  xhr.send();" +
                "} catch(e) {}" +
                "try {" +
                "  var clicksFromStorage = localStorage.getItem('clicks') || sessionStorage.getItem('clicks');" +
                "  if (clicksFromStorage) {" +
                "    capturedClicks = clicksFromStorage;" +
                "    capturedHeaders['clicks'] = clicksFromStorage;" +
                "  }" +
                "} catch(e) {}" +
                "return JSON.stringify({headers: capturedHeaders, clicks: capturedClicks});";
            
            String resultJson = (String) jsExecutor.executeScript(script);
            if (resultJson != null && !resultJson.isEmpty() && !resultJson.equals("{}")) {
                try {
                    com.google.gson.Gson gson = new com.google.gson.Gson();
                    @SuppressWarnings("unchecked")
                    Map<String, Object> resultMap = gson.fromJson(resultJson, Map.class);
                    
                    @SuppressWarnings("unchecked")
                    Map<String, Object> headersMap = (Map<String, Object>) resultMap.get("headers");
                    if (headersMap != null) {
                        for (Map.Entry<String, Object> entry : headersMap.entrySet()) {
                            headers.put(entry.getKey(), entry.getValue().toString());
                        }
                    }
                    
                    // Добавляем clicks отдельно, если он был захвачен
                    Object clicks = resultMap.get("clicks");
                    if (clicks != null && !clicks.toString().isEmpty()) {
                        headers.put("clicks", clicks.toString());
                    }
                } catch (Exception e) {
                    log.debug("Failed to parse headers JSON: {}", e.getMessage());
                }
            }
            
        } catch (Exception e) {
            log.warn("Failed to capture request headers from browser: {}", e.getMessage());
        }
        
        // Если не удалось перехватить заголовки, используем заголовки по умолчанию из браузера
        if (headers.isEmpty()) {
            headers.put("accept", "*/*");
            headers.put("accept-language", "en-US,en;q=0.5");
            headers.put("accept-encoding", "gzip, deflate, br, zstd");
            headers.put("sec-fetch-dest", "empty");
            headers.put("sec-fetch-mode", "cors");
            headers.put("sec-fetch-site", "same-origin");
            headers.put("priority", "u=4");
            headers.put("x-requested-with", "XMLHttpRequest");
            headers.put("x-spa-version", "13.14.1");
            headers.put("x-userid", "0");
            headers.put("deviceid", "site_2bc3dd7d2f1a4eb28539e17ff17c894a");
        }
        
        return headers;
    }
}

