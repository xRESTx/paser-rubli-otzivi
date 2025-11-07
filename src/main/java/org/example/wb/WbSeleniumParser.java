package org.example.wb;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.example.jsonmodel.SearchProduct;
import org.example.jsonmodel.SearchRoot;
import io.github.bonigarcia.wdm.WebDriverManager;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.firefox.FirefoxDriver;
import org.openqa.selenium.firefox.FirefoxOptions;
import org.openqa.selenium.firefox.FirefoxProfile;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Парсер для извлечения данных о товарах через Selenium
 * Загружает страницу через браузер Firefox и ищет JSON данные в HTML
 */
public class WbSeleniumParser {
    private static final Logger log = LoggerFactory.getLogger(WbSeleniumParser.class);
    private static final Gson gson = new Gson();
    private static volatile boolean driverLogLogged = false; // Статический флаг для логирования только один раз
    private static final java.util.concurrent.atomic.AtomicInteger detailedLogCount = new java.util.concurrent.atomic.AtomicInteger(0); // Счетчик для детального логирования
    private WebDriver driver;
    private boolean driverInitialized = false;
    
    /**
     * Ищет исполняемый файл Firefox в стандартных местах установки
     * @return путь к firefox.exe или null, если не найден
     */
    private String findFirefoxBinary() {
        // Сначала проверяем системное свойство
        String firefoxPath = System.getProperty("firefox.binary.path");
        if (firefoxPath != null && !firefoxPath.isEmpty()) {
            java.io.File firefoxFile = new java.io.File(firefoxPath);
            if (firefoxFile.exists() && firefoxFile.isFile()) {
                log.info("Using Firefox from system property: {}", firefoxPath);
                return firefoxPath;
            } else {
                log.warn("Firefox path from system property does not exist: {}", firefoxPath);
            }
        }
        
        // Проверяем стандартные пути установки Firefox на Windows
        java.util.List<String> firefoxPaths = new java.util.ArrayList<>();
        String os = System.getProperty("os.name").toLowerCase();
        
        if (os.contains("win")) {
            // Windows стандартные пути
            firefoxPaths.add("C:\\Program Files\\Mozilla Firefox\\firefox.exe");
            firefoxPaths.add("C:\\Program Files (x86)\\Mozilla Firefox\\firefox.exe");
            firefoxPaths.add("D:\\Program Files\\Mozilla Firefox\\firefox.exe");
            firefoxPaths.add("D:\\Program Files (x86)\\Mozilla Firefox\\firefox.exe");
            firefoxPaths.add("E:\\Program Files\\Mozilla Firefox\\firefox.exe");
            firefoxPaths.add("E:\\Program Files (x86)\\Mozilla Firefox\\firefox.exe");
            // Дополнительные возможные пути
            firefoxPaths.add("C:\\Program Files\\Firefox\\firefox.exe");
            firefoxPaths.add("D:\\Program Files\\Firefox\\firefox.exe");
            firefoxPaths.add("E:\\Program Files\\Firefox\\firefox.exe");
            firefoxPaths.add("C:\\Firefox\\firefox.exe");
            firefoxPaths.add("D:\\Firefox\\firefox.exe");
            firefoxPaths.add("E:\\Firefox\\firefox.exe");
            
            // Проверяем LOCALAPPDATA
            String localAppData = System.getenv("LOCALAPPDATA");
            if (localAppData != null && !localAppData.isEmpty()) {
                firefoxPaths.add(localAppData + "\\Mozilla Firefox\\firefox.exe");
                firefoxPaths.add(localAppData + "\\Programs\\Mozilla Firefox\\firefox.exe");
            }
            
            // Проверяем APPDATA
            String appData = System.getenv("APPDATA");
            if (appData != null && !appData.isEmpty()) {
                firefoxPaths.add(appData + "\\..\\Local\\Mozilla Firefox\\firefox.exe");
                firefoxPaths.add(appData + "\\..\\Local\\Programs\\Mozilla Firefox\\firefox.exe");
            }
            
            // Проверяем ProgramData
            String programData = System.getenv("ProgramData");
            if (programData != null && !programData.isEmpty()) {
                firefoxPaths.add(programData + "\\Mozilla Firefox\\firefox.exe");
            }
            
            // Проверяем пользовательскую директорию
            String userHome = System.getProperty("user.home");
            if (userHome != null && !userHome.isEmpty()) {
                firefoxPaths.add(userHome + "\\AppData\\Local\\Mozilla Firefox\\firefox.exe");
                firefoxPaths.add(userHome + "\\AppData\\Local\\Programs\\Mozilla Firefox\\firefox.exe");
                firefoxPaths.add(userHome + "\\Desktop\\Mozilla Firefox\\firefox.exe");
                firefoxPaths.add(userHome + "\\Downloads\\Mozilla Firefox\\firefox.exe");
            }
        } else if (os.contains("mac")) {
            // macOS стандартные пути
            firefoxPaths.add("/Applications/Firefox.app/Contents/MacOS/firefox");
            firefoxPaths.add(System.getProperty("user.home") + "/Applications/Firefox.app/Contents/MacOS/firefox");
        } else {
            // Linux стандартные пути
            firefoxPaths.add("/usr/bin/firefox");
            firefoxPaths.add("/usr/bin/firefox-esr");
            firefoxPaths.add("/snap/bin/firefox");
        }
        
        // Проверяем каждый путь
        for (String path : firefoxPaths) {
            java.io.File firefoxFile = new java.io.File(path);
            if (firefoxFile.exists() && firefoxFile.isFile()) {
                log.info("Found Firefox at: {}", path);
                return path;
            }
        }
        
        // Если не нашли, пробуем найти через команду where/which
        try {
            String command = os.contains("win") ? "where firefox" : (os.contains("mac") ? "which firefox" : "which firefox");
            Process process = Runtime.getRuntime().exec(command);
            java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(process.getInputStream()));
            String line = reader.readLine();
            if (line != null && !line.isEmpty()) {
                line = line.trim();
                java.io.File firefoxFile = new java.io.File(line);
                if (firefoxFile.exists() && firefoxFile.isFile()) {
                    log.info("Found Firefox via command: {}", line);
                    return line;
                }
            }
            process.waitFor();
        } catch (Exception e) {
            log.debug("Could not find Firefox via command: {}", e.getMessage());
        }
        
        // Windows: пробуем найти через реестр (если доступен)
        if (os.contains("win")) {
            try {
                // Пробуем найти через reg query
                Process process = Runtime.getRuntime().exec(
                    "reg query \"HKEY_LOCAL_MACHINE\\SOFTWARE\\Mozilla\\Mozilla Firefox\" /v CurrentVersion"
                );
                java.io.BufferedReader reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(process.getInputStream()));
                String line;
                String version = null;
                while ((line = reader.readLine()) != null) {
                    if (line.contains("CurrentVersion") && line.contains("REG_SZ")) {
                        String[] parts = line.split("REG_SZ");
                        if (parts.length > 1) {
                            version = parts[1].trim();
                            break;
                        }
                    }
                }
                process.waitFor();
                
                if (version != null && !version.isEmpty()) {
                    // Теперь получаем путь установки для этой версии
                    Process process2 = Runtime.getRuntime().exec(
                        "reg query \"HKEY_LOCAL_MACHINE\\SOFTWARE\\Mozilla\\Mozilla Firefox\\" + version + "\\Main\" /v PathToExe"
                    );
                    java.io.BufferedReader reader2 = new java.io.BufferedReader(
                            new java.io.InputStreamReader(process2.getInputStream()));
                    while ((line = reader2.readLine()) != null) {
                        if (line.contains("PathToExe") && line.contains("REG_SZ")) {
                            String[] parts = line.split("REG_SZ");
                            if (parts.length > 1) {
                                String path = parts[1].trim();
                                java.io.File firefoxFile = new java.io.File(path);
                                if (firefoxFile.exists() && firefoxFile.isFile()) {
                                    log.info("Found Firefox via registry: {}", path);
                                    return path;
                                }
                            }
                        }
                    }
                    process2.waitFor();
                }
            } catch (Exception e) {
                log.debug("Could not find Firefox via registry: {}", e.getMessage());
            }
        }
        
        log.warn("Firefox binary not found in standard locations");
        log.warn("Please set system property: -Dfirefox.binary.path=<path-to-firefox.exe>");
        return null;
    }
    
    /**
     * Инициализирует WebDriver (Firefox)
     * Использует системный GeckoDriver из PATH или пытается скачать через WebDriverManager
     */
    private synchronized void initDriver() {
        if (driverInitialized && driver != null) {
            log.debug("Driver already initialized, reusing existing instance");
            return;
        }
        
        log.info("=== INITIALIZING FIREFOX DRIVER ===");
        log.info("Thread: {}", Thread.currentThread().getName());
        
        try {
            boolean driverFound = false;
            
            // Шаг 1: Проверяем системное свойство
            String geckoDriverPath = System.getProperty("webdriver.gecko.driver");
            if (geckoDriverPath != null && !geckoDriverPath.isEmpty()) {
                java.io.File driverFile = new java.io.File(geckoDriverPath);
                if (driverFile.exists() && driverFile.isFile()) {
                    if (!driverLogLogged) {
                        synchronized (WbSeleniumParser.class) {
                            if (!driverLogLogged) {
                                log.info("Using GeckoDriver from system property: {}", geckoDriverPath);
                                driverLogLogged = true;
                            }
                        }
                    }
                    driverFound = true;
                } else {
                    if (!driverLogLogged) {
                        synchronized (WbSeleniumParser.class) {
                            if (!driverLogLogged) {
                                log.warn("GeckoDriver path from system property does not exist: {}", geckoDriverPath);
                            }
                        }
                    }
                }
            }
            
            // Шаг 2: Ищем в PATH (только если не найден через system property)
            if (!driverFound) {
                try {
                    String os = System.getProperty("os.name").toLowerCase();
                    String command = os.contains("win") ? "where geckodriver" : "which geckodriver";
                    
                    Process process = Runtime.getRuntime().exec(command);
                    java.io.BufferedReader reader = new java.io.BufferedReader(
                            new java.io.InputStreamReader(process.getInputStream()));
                    String line = reader.readLine();
                    if (line != null && !line.isEmpty()) {
                        line = line.trim();
                        java.io.File driverFile = new java.io.File(line);
                        if (driverFile.exists() && driverFile.isFile()) {
                            System.setProperty("webdriver.gecko.driver", line);
                            if (!driverLogLogged) {
                                synchronized (WbSeleniumParser.class) {
                                    if (!driverLogLogged) {
                                        log.info("Found GeckoDriver in PATH: {}", line);
                                        driverLogLogged = true;
                                    }
                                }
                            }
                            driverFound = true;
                        }
                    }
                    process.waitFor();
                } catch (Exception e) {
                    log.debug("GeckoDriver not found in PATH: {}", e.getMessage());
                }
            }
            
            // Шаг 2.5: Проверяем стандартные места установки GeckoDriver
            if (!driverFound) {
                java.util.List<String> commonPaths = new java.util.ArrayList<>();
                
                // Windows: проверяем стандартные места
                if (System.getProperty("os.name").toLowerCase().contains("win")) {
                    // Пользователь указал этот путь
                    commonPaths.add("E:\\geckodriver-v0.36.0-win64\\geckodriver.exe");
                    commonPaths.add("E:\\geckodriver\\geckodriver.exe");
                    commonPaths.add("C:\\geckodriver\\geckodriver.exe");
                    commonPaths.add(System.getProperty("user.home") + "\\geckodriver\\geckodriver.exe");
                    commonPaths.add("D:\\geckodriver\\geckodriver.exe");
                } else {
                    // Linux/Mac
                    commonPaths.add("/usr/local/bin/geckodriver");
                    commonPaths.add("/usr/bin/geckodriver");
                    commonPaths.add(System.getProperty("user.home") + "/geckodriver/geckodriver");
                }
                
                for (String path : commonPaths) {
                    java.io.File driverFile = new java.io.File(path);
                    if (driverFile.exists() && driverFile.isFile()) {
                        System.setProperty("webdriver.gecko.driver", path);
                        if (!driverLogLogged) {
                            synchronized (WbSeleniumParser.class) {
                                if (!driverLogLogged) {
                                    log.info("Found GeckoDriver in common location: {}", path);
                                    driverLogLogged = true;
                                }
                            }
                        }
                        driverFound = true;
                        break;
                    }
                }
            }
            
            // Шаг 3: Пробуем WebDriverManager только если драйвер не найден и не отключен
            if (!driverFound) {
                // Проверяем, не отключен ли WebDriverManager через системное свойство
                String skipWdm = System.getProperty("selenium.skip.webdrivermanager", "false");
                if (!"true".equalsIgnoreCase(skipWdm)) {
                    log.info("GeckoDriver not found in system. Attempting to download via WebDriverManager...");
                    log.info("(To skip WebDriverManager, set system property: -Dselenium.skip.webdrivermanager=true)");
                    try {
                        // Отключаем логирование WebDriverManager для уменьшения шума
                        java.util.logging.Logger wdmLogger = java.util.logging.Logger.getLogger("io.github.bonigarcia.wdm");
                        wdmLogger.setLevel(java.util.logging.Level.SEVERE);
                        
                        // Пробуем скачать через WebDriverManager (без clearDriverCache, чтобы не удалять кеш)
                        WebDriverManager.firefoxdriver().setup();
                        driverFound = true;
                        log.info("GeckoDriver downloaded successfully via WebDriverManager");
                    } catch (Exception e) {
                        log.error("WebDriverManager failed to download GeckoDriver. Error: {}", e.getMessage());
                        log.error("This is often due to network restrictions or API changes in WebDriverManager.");
                        log.error("Please install GeckoDriver manually:");
                        log.error("1. Download from: https://github.com/mozilla/geckodriver/releases");
                        log.error("2. Extract geckodriver.exe to a folder");
                        log.error("3. Add the folder to your system PATH, OR");
                        log.error("4. Set system property: -Dwebdriver.gecko.driver=<path-to-geckodriver.exe>");
                        log.error("5. To skip WebDriverManager in future runs: -Dselenium.skip.webdrivermanager=true");
                        // Не бросаем исключение здесь - возможно драйвер уже установлен
                    }
                } else {
                    log.info("WebDriverManager is disabled via system property. Skipping download attempt.");
                }
            }
            
            // Шаг 4: Создаем FirefoxOptions - упрощенный подход, как в рабочем примере
            FirefoxOptions options = new FirefoxOptions();
            
            // Опционально: если указан путь через системное свойство или переменную окружения, используем его
            String firefoxBinaryPath = System.getProperty("firefox.binary.path");
            if (firefoxBinaryPath == null || firefoxBinaryPath.isEmpty()) {
                firefoxBinaryPath = System.getenv("FIREFOX_BINARY_PATH");
            }
            
            // Если путь не указан явно, ищем Firefox на всех дисках
            if (firefoxBinaryPath == null || firefoxBinaryPath.isEmpty()) {
                firefoxBinaryPath = findFirefoxOnAllDrives();
            }
            
            // Если путь указан или найден, устанавливаем его
            if (firefoxBinaryPath != null && !firefoxBinaryPath.isEmpty()) {
                java.io.File firefoxFile = new java.io.File(firefoxBinaryPath);
                if (firefoxFile.exists() && firefoxFile.isFile()) {
                    options.setBinary(firefoxBinaryPath);
                    log.info("Using Firefox from: {}", firefoxBinaryPath);
                } else {
                    log.warn("Firefox path does not exist: {}, will try auto-detection", firefoxBinaryPath);
                }
            } else {
                // Если путь не указан, FirefoxDriver сам найдет Firefox автоматически
                log.info("Firefox path not specified. FirefoxDriver will auto-detect Firefox.");
            }
            
            // Headless режим отключен для отображения интерфейса браузера
            // Если нужно включить headless, раскомментируйте следующую строку:
            // options.addArguments("-headless");
            
            // Настраиваем Firefox профиль
            FirefoxProfile profile = new FirefoxProfile();
            profile.setPreference("general.useragent.override", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:132.0) Gecko/20100101 Firefox/132.0");
            // Отключаем изображения для ускорения загрузки
            profile.setPreference("permissions.default.image", 2);
            // Отключаем CSS для ускорения
            profile.setPreference("permissions.default.stylesheet", 2);
            options.setProfile(profile);
            
            // Минимальное логирование для производительности
            if (!driverLogLogged) {
                synchronized (WbSeleniumParser.class) {
                    if (!driverLogLogged) {
                        log.info("=== CREATING FIREFOX DRIVER (GUI MODE) ===");
                        log.info("GeckoDriver path: {}", System.getProperty("webdriver.gecko.driver"));
                        log.info("Firefox: {}", firefoxBinaryPath != null ? firefoxBinaryPath : "auto-detected");
                        driverLogLogged = true;
                    }
                }
            }
            
            try {
                // Создаем драйвер без лишних проверок для скорости
                driver = new FirefoxDriver(options);
                driverInitialized = true;
                
            } catch (Exception e) {
                log.error("=== FAILED to create FirefoxDriver! ===");
                log.error("Error message: {}", e.getMessage());
                log.error("Error class: {}", e.getClass().getName());
                log.error("Full stack trace:", e);
                throw new RuntimeException("Cannot create FirefoxDriver. Check if Firefox is installed and GeckoDriver is accessible.", e);
            }
            
            if (!driverLogLogged) {
                synchronized (WbSeleniumParser.class) {
                    if (!driverLogLogged) {
                        log.info("Selenium WebDriver initialized successfully");
                        driverLogLogged = true;
                    }
                }
            }
            
        } catch (org.openqa.selenium.WebDriverException e) {
            log.error("=== WebDriverException during FirefoxDriver creation ===");
            log.error("Error message: {}", e.getMessage());
            log.error("Error class: {}", e.getClass().getName());
            String errorMsg = e.getMessage();
            if (errorMsg != null && (errorMsg.contains("geckodriver") || errorMsg.contains("firefox"))) {
                log.error("GeckoDriver not found or not accessible. Please install GeckoDriver manually:");
                log.error("1. Download from: https://github.com/mozilla/geckodriver/releases");
                log.error("2. Extract geckodriver.exe to a folder");
                log.error("3. Add the folder to your system PATH, OR");
                log.error("4. Set system property: -Dwebdriver.gecko.driver=<path-to-geckodriver.exe>");
            }
            log.error("Full stack trace:", e);
            throw new RuntimeException("Selenium initialization failed. " +
                    "Install GeckoDriver manually. See logs above for instructions.", e);
        } catch (Exception e) {
            log.error("=== Exception during FirefoxDriver creation ===");
            log.error("Error message: {}", e.getMessage());
            log.error("Error class: {}", e.getClass().getName());
            log.error("Full stack trace:", e);
            throw new RuntimeException("Selenium initialization failed. " +
                    "Install GeckoDriver manually or check your internet connection.", e);
        }
    }
    
    /**
     * Загружает страницу и извлекает JSON данные о товарах
     * @param url URL страницы для парсинга
     * @return SearchRoot с данными о товарах или null при ошибке
     */
    public SearchRoot parsePage(String url) {
        if (driver == null) {
            initDriver();
        }
        
        try {
            log.debug("Loading page: {}", url);
            driver.get(url);
            
            // Если это API endpoint, пробуем получить JSON напрямую из response body
            if (url.contains("/__internal/u-search/")) {
                String jsonBody = getJsonFromResponseBody();
                if (jsonBody != null && !jsonBody.isEmpty()) {
                    try {
                        SearchRoot root = gson.fromJson(jsonBody, SearchRoot.class);
                        if (root != null && root.products != null) {
                            log.debug("Successfully parsed JSON from API response, total: {}", root.total);
                            return root;
                        } else {
                            log.debug("Parsed JSON but root is null or has no products");
                        }
                    } catch (Exception e) {
                        log.debug("Failed to parse JSON from response body: {}", e.getMessage());
                    }
                } else {
                    log.debug("No JSON body found in response for API endpoint");
                }
            }
            
            // Ждем загрузки страницы
            try {
                WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(10));
                wait.until(ExpectedConditions.presenceOfElementLocated(By.tagName("body")));
            } catch (Exception e) {
                log.debug("Timeout waiting for page load: {}", e.getMessage());
                return null;
            }
            
            // Даем время на выполнение JavaScript
            Thread.sleep(1000);
            
            // Пробуем найти JSON данные разными способами
            String jsonData = extractJsonFromPage();
            
            if (jsonData == null || jsonData.isEmpty()) {
                log.debug("No JSON data found on page: {}", url);
                return null;
            }
            
            // Парсим JSON
            try {
                SearchRoot root = gson.fromJson(jsonData, SearchRoot.class);
                if (root != null && root.products != null) {
                    log.debug("Successfully parsed JSON from page, total: {}", root.total);
                    return root;
                } else {
                    log.debug("Parsed JSON but root is null or has no products");
                    return null;
                }
            } catch (Exception e) {
                log.debug("Failed to parse JSON data: {}", e.getMessage());
                return null;
            }
            
        } catch (org.openqa.selenium.TimeoutException e) {
            log.debug("Timeout loading page: {}", url);
            return null;
        } catch (org.openqa.selenium.WebDriverException e) {
            log.debug("WebDriver error loading page {}: {}", url, e.getMessage());
            return null;
        } catch (Exception e) {
            log.debug("Error parsing page {}: {}", url, e.getMessage());
            return null;
        }
    }
    
    /**
     * Пытается получить JSON напрямую из response body через JavaScript
     * Работает для API endpoints, которые возвращают чистый JSON
     */
    private String getJsonFromResponseBody() {
        try {
            JavascriptExecutor js = (JavascriptExecutor) driver;
            // Пробуем получить response body через перехват network или из page source
            String pageSource = driver.getPageSource();
            
            // Если page source начинается с { или [, это JSON
            String trimmed = pageSource.trim();
            if ((trimmed.startsWith("{") && trimmed.endsWith("}")) || 
                (trimmed.startsWith("[") && trimmed.endsWith("]"))) {
                // Это чистый JSON ответ
                return trimmed;
            }
            
            // Пробуем найти JSON в pre тегах (иногда API ответы оборачиваются в pre)
            List<WebElement> preTags = driver.findElements(By.tagName("pre"));
            for (WebElement pre : preTags) {
                String text = pre.getText();
                if (text != null && (text.trim().startsWith("{") || text.trim().startsWith("["))) {
                    return text.trim();
                }
            }
            
        } catch (Exception e) {
            log.debug("Error getting JSON from response body", e);
        }
        
        return null;
    }
    
    /**
     * Извлекает JSON данные из загруженной страницы
     * Пробует несколько методов поиска JSON
     */
    private String extractJsonFromPage() {
        try {
            // Метод 1: Ищем в script тегах с id или data-атрибутами
            List<WebElement> scriptTags = driver.findElements(By.tagName("script"));
            for (WebElement script : scriptTags) {
                String scriptContent = script.getAttribute("innerHTML");
                if (scriptContent != null && scriptContent.contains("\"products\"")) {
                    // Пробуем извлечь JSON из script тега
                    String json = extractJsonFromScript(scriptContent);
                    if (json != null && !json.isEmpty()) {
                        return json;
                    }
                }
            }
            
            // Метод 2: Выполняем JavaScript для поиска данных в window объектах и network requests
            JavascriptExecutor js = (JavascriptExecutor) driver;
            
            // Пробуем найти данные в window объектах
            String jsResult = (String) js.executeScript(
                "try { " +
                "  if (window.__NEXT_DATA__) return JSON.stringify(window.__NEXT_DATA__); " +
                "  if (window.__INITIAL_STATE__) return JSON.stringify(window.__INITIAL_STATE__); " +
                "  if (window.__APP_DATA__) return JSON.stringify(window.__APP_DATA__); " +
                "  if (window.__WB_DATA__) return JSON.stringify(window.__WB_DATA__); " +
                "  return null; " +
                "} catch(e) { return null; }"
            );
            
            if (jsResult != null && !jsResult.isEmpty()) {
                // Пробуем найти данные о товарах в window объекте
                String productsJson = extractProductsFromWindowData(jsResult);
                if (productsJson != null && !productsJson.isEmpty()) {
                    return productsJson;
                }
            }
            
            // Метод 2.5: Пробуем найти JSON в ответах fetch/XMLHttpRequest через перехват network
            // Это более сложный метод, но может быть эффективнее
            try {
                String networkJson = (String) js.executeScript(
                    "try { " +
                    "  var scripts = document.getElementsByTagName('script'); " +
                    "  for (var i = 0; i < scripts.length; i++) { " +
                    "    var content = scripts[i].innerHTML || scripts[i].textContent || ''; " +
                    "    if (content.includes('products') && content.includes('total')) { " +
                    "      var match = content.match(/\\{[\\s\\S]*?\"products\"[\\s\\S]*?\"total\"[\\s\\S]*?\\}/); " +
                    "      if (match) return match[0]; " +
                    "    } " +
                    "  } " +
                    "  return null; " +
                    "} catch(e) { return null; }"
                );
                
                if (networkJson != null && !networkJson.isEmpty()) {
                    try {
                        JsonObject obj = JsonParser.parseString(networkJson).getAsJsonObject();
                        if (obj.has("products")) {
                            return networkJson;
                        }
                    } catch (Exception e) {
                        // Не валидный JSON
                    }
                }
            } catch (Exception e) {
                log.debug("Error extracting JSON from JavaScript execution", e);
            }
            
            // Метод 3: Ищем JSON в plain text на странице
            String pageSource = driver.getPageSource();
            String jsonFromSource = extractJsonFromPageSource(pageSource);
            if (jsonFromSource != null && !jsonFromSource.isEmpty()) {
                return jsonFromSource;
            }
            
            // Метод 4: Пробуем найти JSON в response body через network interception
            // (более сложный метод, требует настройки proxy)
            
        } catch (Exception e) {
            log.debug("Error extracting JSON from page", e);
        }
        
        return null;
    }
    
    /**
     * Извлекает JSON из содержимого script тега
     */
    private String extractJsonFromScript(String scriptContent) {
        try {
            // Ищем JSON объект с полем "products"
            Pattern pattern = Pattern.compile("\\{[^{}]*\"products\"[^{}]*\\[[\\s\\S]*?\\][^{}]*\\}", Pattern.MULTILINE);
            Matcher matcher = pattern.matcher(scriptContent);
            
            if (matcher.find()) {
                String jsonCandidate = matcher.group(0);
                // Пробуем распарсить, чтобы убедиться что это валидный JSON
                try {
                    JsonObject obj = JsonParser.parseString(jsonCandidate).getAsJsonObject();
                    if (obj.has("products")) {
                        return jsonCandidate;
                    }
                } catch (Exception e) {
                    // Не валидный JSON, пробуем дальше
                }
            }
            
            // Ищем более широкий паттерн
            pattern = Pattern.compile("\\{[\\s\\S]*?\"products\"[\\s\\S]*?\\}", Pattern.MULTILINE);
            matcher = pattern.matcher(scriptContent);
            
            if (matcher.find()) {
                String jsonCandidate = matcher.group(0);
                try {
                    JsonObject obj = JsonParser.parseString(jsonCandidate).getAsJsonObject();
                    if (obj.has("products") || obj.has("total")) {
                        return jsonCandidate;
                    }
                } catch (Exception e) {
                    // Не валидный JSON
                }
            }
            
        } catch (Exception e) {
            log.debug("Error extracting JSON from script", e);
        }
        
        return null;
    }
    
    /**
     * Извлекает данные о товарах из window объекта
     */
    private String extractProductsFromWindowData(String windowData) {
        try {
            JsonObject root = JsonParser.parseString(windowData).getAsJsonObject();
            
            // Ищем вложенные объекты с данными о товарах
            JsonElement products = findJsonElement(root, "products");
            if (products != null && products.isJsonArray()) {
                // Создаем объект в формате SearchRoot
                JsonObject result = new JsonObject();
                result.add("products", products);
                
                JsonElement total = findJsonElement(root, "total");
                if (total != null) {
                    result.add("total", total);
                } else {
                    result.addProperty("total", products.getAsJsonArray().size());
                }
                
                return gson.toJson(result);
            }
            
        } catch (Exception e) {
            log.debug("Error extracting products from window data", e);
        }
        
        return null;
    }
    
    /**
     * Рекурсивно ищет элемент JSON по ключу
     */
    private JsonElement findJsonElement(JsonElement element, String key) {
        if (element == null || element.isJsonNull()) {
            return null;
        }
        
        if (element.isJsonObject()) {
            JsonObject obj = element.getAsJsonObject();
            if (obj.has(key)) {
                return obj.get(key);
            }
            // Рекурсивно ищем во вложенных объектах
            for (String k : obj.keySet()) {
                JsonElement found = findJsonElement(obj.get(k), key);
                if (found != null) {
                    return found;
                }
            }
        } else if (element.isJsonArray()) {
            for (JsonElement item : element.getAsJsonArray()) {
                JsonElement found = findJsonElement(item, key);
                if (found != null) {
                    return found;
                }
            }
        }
        
        return null;
    }
    
    /**
     * Извлекает JSON из исходного кода страницы
     */
    private String extractJsonFromPageSource(String pageSource) {
        try {
            // Ищем JSON в script тегах с разными паттернами
            // Паттерн 1: Обычные script теги
            Pattern pattern = Pattern.compile("<script[^>]*>([\\s\\S]*?\"products\"[\\s\\S]*?)</script>", Pattern.CASE_INSENSITIVE);
            Matcher matcher = pattern.matcher(pageSource);
            
            while (matcher.find()) {
                String scriptContent = matcher.group(1);
                String json = extractJsonFromScript(scriptContent);
                if (json != null && !json.isEmpty()) {
                    return json;
                }
            }
            
            // Паттерн 2: Ищем JSON объекты напрямую в тексте страницы
            pattern = Pattern.compile("(\\{[\\s\\S]{100,}?\"products\"[\\s\\S]{100,}?\"total\"[\\s\\S]{0,500}?\\})", Pattern.MULTILINE);
            matcher = pattern.matcher(pageSource);
            
            while (matcher.find()) {
                String jsonCandidate = matcher.group(1);
                try {
                    JsonObject obj = JsonParser.parseString(jsonCandidate).getAsJsonObject();
                    if (obj.has("products") && (obj.has("total") || obj.has("metadata"))) {
                        return jsonCandidate;
                    }
                } catch (Exception e) {
                    // Не валидный JSON, пробуем дальше
                }
            }
            
        } catch (Exception e) {
            log.debug("Error extracting JSON from page source", e);
        }
        
        return null;
    }
    
    /**
     * Парсит страницу и возвращает список товаров
     * @param url URL страницы
     * @return список товаров или пустой список при ошибке
     */
    public List<SearchProduct> parseProducts(String url) {
        SearchRoot root = parsePage(url);
        if (root != null && root.products != null) {
            return root.products;
        }
        return new ArrayList<>();
    }
    
    /**
     * Закрывает WebDriver
     */
    public void close() {
        if (driver != null) {
            try {
                driver.quit();
                driver = null;
                driverInitialized = false;
                log.info("Selenium WebDriver closed");
            } catch (Exception e) {
                log.error("Error closing WebDriver", e);
            }
        }
    }
    
    /**
     * Проверяет, инициализирован ли драйвер
     */
    public boolean isInitialized() {
        return driverInitialized && driver != null;
    }
    
    /**
     * Ищет JSON URL на странице категории, который начинается с указанного префикса
     * @param categoryUrl URL страницы категории
     * @param urlPrefix префикс JSON URL для поиска
     * @return найденный JSON URL или null
     */
    public String findJsonUrlOnPage(String categoryUrl, String urlPrefix) {
        // Если переданный URL уже является JSON endpoint, возвращаем его напрямую
        if (categoryUrl != null && (categoryUrl.contains("__internal/catalog/catalog") ||
                                     categoryUrl.contains("__internal/search/exactmatch") ||
                                     categoryUrl.contains("__internal/u-search/exactmatch"))) {
            log.debug("URL is already a JSON endpoint, returning as-is: {}", categoryUrl);
            return categoryUrl;
        }
        
        if (driver == null) {
            initDriver();
        }
        
        if (driver == null) {
            log.warn("WebDriver is null, cannot search for JSON URL on: {}", categoryUrl);
            return null;
        }
        
        // Ограничиваем детальное логирование только первыми ошибками
        final boolean shouldLogDetails = detailedLogCount.getAndIncrement() < 10; // Первые 10 ошибок для анализа
        
        // Пробуем до 2 раз
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                if (attempt > 1) {
                    log.debug("Retry attempt {} for URL: {}", attempt, categoryUrl);
                    Thread.sleep(2000); // Пауза перед повтором
                }
                
                // Навигация
                driver.get(categoryUrl);
                
                // Ждем загрузки страницы (увеличено для надежности)
                try {
                    WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(10));
                    wait.until(ExpectedConditions.presenceOfElementLocated(By.tagName("body")));
                    
                    // Сразу после загрузки body устанавливаем перехватчики fetch/XMLHttpRequest
                    // Это нужно сделать ДО того, как JavaScript начнет делать запросы
                    try {
                        JavascriptExecutor jsPre = (JavascriptExecutor) driver;
                        jsPre.executeScript(
                            "try { " +
                            "  window._interceptedUrls = []; " +
                            "  var patterns = ['__internal/catalog/catalog', '__internal/search/exactmatch', '__internal/u-search/exactmatch']; " +
                            "  if (window.fetch) { " +
                            "    var originalFetch = window.fetch; " +
                            "    window.fetch = function(...args) { " +
                            "      var url = args[0]; " +
                            "      if (typeof url === 'string') { " +
                            "        for (var p = 0; p < patterns.length; p++) { " +
                            "          if (url.indexOf(patterns[p]) !== -1) { " +
                            "            window._interceptedUrls.push(url); " +
                            "            break; " +
                            "          } " +
                            "        } " +
                            "      } " +
                            "      return originalFetch.apply(this, args); " +
                            "    }; " +
                            "  } " +
                            "  if (window.XMLHttpRequest) { " +
                            "    var originalOpen = XMLHttpRequest.prototype.open; " +
                            "    XMLHttpRequest.prototype.open = function(method, url, ...rest) { " +
                            "      if (typeof url === 'string') { " +
                            "        for (var p = 0; p < patterns.length; p++) { " +
                            "          if (url.indexOf(patterns[p]) !== -1) { " +
                            "            window._interceptedUrls.push(url); " +
                            "            break; " +
                            "          } " +
                            "        } " +
                            "      } " +
                            "      return originalOpen.apply(this, [method, url, ...rest]); " +
                            "    }; " +
                            "  } " +
                            "} catch(e) { }"
                        );
                    } catch (Exception e) {
                        // Игнорируем ошибки установки перехватчиков
                    }
                    
                    // Дополнительная проверка: ждем, пока страница полностью загрузится
                    wait.until(ExpectedConditions.jsReturnsValue("return document.readyState === 'complete'"));
                } catch (Exception e) {
                    if (shouldLogDetails) {
                        log.warn("Timeout waiting for page load (attempt {}): {}", attempt, e.getMessage());
                    }
                    if (attempt == 2) {
                        return null; // Последняя попытка - возвращаем null
                    }
                    continue; // Пробуем еще раз
                }
                
                // Проверяем, что страница действительно загрузилась
                String currentUrl = driver.getCurrentUrl();
                if (currentUrl == null || currentUrl.isEmpty() || currentUrl.equals("about:blank")) {
                    if (shouldLogDetails) {
                        log.warn("Page not loaded properly, current URL: {}", currentUrl);
                    }
                    if (attempt == 2) {
                        return null;
                    }
                    continue;
                }
                
                // Ждем загрузки динамического контента - ищем элементы товаров или другие признаки загрузки
                try {
                    WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(15));
                    // Пробуем дождаться появления элементов товаров или других признаков загрузки
                    // Если не найдем, продолжаем (не критично)
                    try {
                        wait.until(ExpectedConditions.presenceOfElementLocated(
                            By.cssSelector("[data-product-id], .product-card, .catalog-page, [class*='product'], [class*='catalog']")));
                    } catch (Exception e) {
                        // Игнорируем, если элементы не найдены - продолжаем поиск
                    }
                } catch (Exception e) {
                    // Игнорируем ошибки ожидания
                }
                
                // Увеличиваем задержку для выполнения JavaScript (особенно для динамических страниц)
                Thread.sleep(5000); // Увеличено до 5 секунд для надежности
                
                // Дополнительно ждем, пока все сетевые запросы завершатся
                try {
                    JavascriptExecutor js = (JavascriptExecutor) driver;
                    // Ждем, пока все fetch/XMLHttpRequest завершатся
                    js.executeScript(
                        "return new Promise((resolve) => { " +
                        "  if (document.readyState === 'complete') { " +
                        "    setTimeout(resolve, 2000); " + // Дополнительные 2 секунды после readyState
                        "  } else { " +
                        "    window.addEventListener('load', () => setTimeout(resolve, 2000)); " +
                        "  } " +
                        "});"
                    );
                } catch (Exception e) {
                    // Игнорируем ошибки JavaScript
                }
                
                JavascriptExecutor js = (JavascriptExecutor) driver;
                
                // Ищем более гибко - поддерживаем несколько форматов URL:
                // 1. __internal/catalog/catalog/... (формат каталога)
                // 2. __internal/search/exactmatch/... (формат поиска)
                // 3. __internal/u-search/exactmatch/... (старый формат для обратной совместимости)
                String[] searchPatterns = {
                    "__internal/catalog/catalog",
                    "__internal/search/exactmatch",
                    "__internal/u-search/exactmatch"
                };
                
                // Метод 0: Проверяем перехваченные URL из window._interceptedUrls
                try {
                    String interceptedUrl = (String) js.executeScript(
                        "try { " +
                        "  if (window._interceptedUrls && window._interceptedUrls.length > 0) { " +
                        "    // Возвращаем последний перехваченный URL (самый свежий) " +
                        "    return window._interceptedUrls[window._interceptedUrls.length - 1]; " +
                        "  } " +
                        "  return null; " +
                        "} catch(e) { return null; }"
                    );
                    
                    if (interceptedUrl != null && !interceptedUrl.isEmpty()) {
                        // Проверяем, что URL содержит один из паттернов
                        boolean matches = false;
                        for (String pattern : searchPatterns) {
                            if (interceptedUrl.contains(pattern)) {
                                matches = true;
                                break;
                            }
                        }
                        if (matches) {
                            log.debug("Found JSON URL via pre-interception: {}", interceptedUrl);
                            return interceptedUrl;
                        }
                    }
                } catch (Exception e) {
                    log.debug("Error checking pre-intercepted URLs: {}", e.getMessage());
                }
                
                // Метод 1: Ищем в network requests через Performance API (работает в Firefox и Chrome)
                try {
                    String networkUrls = (String) js.executeScript(
                    "try { " +
                    "  var entries = performance.getEntriesByType('resource'); " +
                    "  var patterns = ['__internal/catalog/catalog', '__internal/search/exactmatch', '__internal/u-search/exactmatch']; " +
                    "  for (var i = entries.length - 1; i >= 0; i--) { " + // Идем с конца (последние запросы)
                    "    var url = entries[i].name; " +
                    "    for (var j = 0; j < patterns.length; j++) { " +
                    "      if (url.indexOf(patterns[j]) !== -1) { " +
                    "        return url; " +
                    "      } " +
                    "    } " +
                    "  } " +
                    "  return null; " +
                    "} catch(e) { return null; }"
                );
                
                if (networkUrls != null && !networkUrls.isEmpty()) {
                    // Проверяем, что URL содержит один из паттернов
                    boolean matches = false;
                    for (String pattern : searchPatterns) {
                        if (networkUrls.contains(pattern)) {
                            matches = true;
                            break;
                        }
                    }
                    if (matches) {
                        log.debug("Found JSON URL in network requests: {}", networkUrls);
                        return networkUrls;
                    }
                }
                } catch (Exception e) {
                    log.debug("Error searching in network requests: {}", e.getMessage());
                }
                
                // Метод 2: Ищем в исходном коде страницы (page source)
                String pageSource = driver.getPageSource();
                String foundUrl = findJsonUrlInTextFlexible(pageSource, searchPatterns, urlPrefix);
                if (foundUrl != null) {
                    log.debug("Found JSON URL in page source: {}", foundUrl);
                    return foundUrl;
                }
                
                // Метод 3: Ищем в JavaScript коде через выполнение скрипта (более гибкий поиск)
                try {
                    String jsResult = (String) js.executeScript(
                        "try { " +
                    "  var scripts = document.getElementsByTagName('script'); " +
                    "  var patterns = ['__internal/catalog/catalog', '__internal/search/exactmatch', '__internal/u-search/exactmatch']; " +
                    "  for (var i = 0; i < scripts.length; i++) { " +
                    "    var content = scripts[i].innerHTML || scripts[i].textContent || ''; " +
                    "    // Ищем URL с любым из паттернов " +
                    "    for (var p = 0; p < patterns.length; p++) { " +
                    "      var regex = new RegExp('https?:\\\\/\\\\/[^\"'\\\\s]*' + patterns[p].replace(/\\//g, '\\\\/') + '[^\"'\\\\s<>]*'); " +
                    "      var match = content.match(regex); " +
                    "      if (match) { " +
                    "        var url = match[0]; " +
                    "        // Убираем завершающие символы " +
                    "        url = url.replace(/[\\\"'<>\\s]+$/, ''); " +
                    "        return url; " +
                    "      } " +
                    "    } " +
                    "  } " +
                    "  return null; " +
                    "} catch(e) { return null; }"
                );
                
                if (jsResult != null && !jsResult.isEmpty()) {
                    // Проверяем, что URL содержит один из паттернов
                    boolean matches = false;
                    for (String pattern : searchPatterns) {
                        if (jsResult.contains(pattern)) {
                            matches = true;
                            break;
                        }
                    }
                    if (matches) {
                        // Проверяем префикс, но если не совпадает - все равно возвращаем (URL содержит паттерн)
                        if (urlPrefix != null && !urlPrefix.isEmpty() && !jsResult.startsWith(urlPrefix)) {
                            log.debug("Found JSON URL in JavaScript (prefix mismatch, but pattern matches): {}", jsResult);
                            // Все равно возвращаем, так как URL содержит нужный паттерн
                        }
                        log.debug("Found JSON URL in JavaScript code: {}", jsResult);
                        return jsResult;
                    }
                }
                } catch (Exception e) {
                    log.debug("Error searching in JavaScript: {}", e.getMessage());
                }
                
                // Метод 4: Ищем в window объектах
                try {
                    String windowData = (String) js.executeScript(
                        "try { " +
                    "  var data = window.__NEXT_DATA__ || window.__INITIAL_STATE__ || window.__APP_DATA__ || window.__WB_DATA__ || {}; " +
                    "  return JSON.stringify(data); " +
                    "} catch(e) { return null; }"
                );
                
                if (windowData != null && !windowData.isEmpty()) {
                    foundUrl = findJsonUrlInTextFlexible(windowData, searchPatterns, urlPrefix);
                    if (foundUrl != null) {
                        log.debug("Found JSON URL in window data: {}", foundUrl);
                        return foundUrl;
                    }
                }
                } catch (Exception e) {
                    log.debug("Error searching in window objects: {}", e.getMessage());
                }
                
                // Метод 5: Ищем в атрибутах элементов (data-*, href, src)
                try {
                    String attrUrl = (String) js.executeScript(
                        "try { " +
                    "  var allElements = document.querySelectorAll('*'); " +
                    "  var patterns = ['__internal/catalog/catalog', '__internal/search/exactmatch', '__internal/u-search/exactmatch']; " +
                    "  for (var i = 0; i < allElements.length; i++) { " +
                    "    var el = allElements[i]; " +
                    "    var attrs = ['href', 'src', 'data-url', 'data-src', 'data-href']; " +
                    "    for (var j = 0; j < attrs.length; j++) { " +
                    "      var attr = el.getAttribute(attrs[j]); " +
                    "      if (attr) { " +
                    "        for (var p = 0; p < patterns.length; p++) { " +
                    "          if (attr.indexOf(patterns[p]) !== -1) { " +
                    "            return attr; " +
                    "          } " +
                    "        } " +
                    "      } " +
                    "    } " +
                    "  } " +
                    "  return null; " +
                    "} catch(e) { return null; }"
                );
                
                if (attrUrl != null && !attrUrl.isEmpty()) {
                    // Проверяем, что URL содержит один из паттернов
                    boolean matches = false;
                    for (String pattern : searchPatterns) {
                        if (attrUrl.contains(pattern)) {
                            matches = true;
                            break;
                        }
                    }
                    if (matches) {
                        // Проверяем префикс, но если не совпадает - все равно возвращаем
                        if (urlPrefix != null && !urlPrefix.isEmpty() && !attrUrl.startsWith(urlPrefix)) {
                            log.debug("Found JSON URL in attributes (prefix mismatch, but pattern matches): {}", attrUrl);
                            // Все равно возвращаем, так как URL содержит нужный паттерн
                        }
                        log.debug("Found JSON URL in element attributes: {}", attrUrl);
                        return attrUrl;
                    }
                }
                } catch (Exception e) {
                    log.debug("Error searching in element attributes: {}", e.getMessage());
                }
                
                // Метод 6: Проверяем перехваченные URL (если они были перехвачены ранее)
                try {
                    // Проверяем, есть ли перехваченные URL
                    String interceptedUrl = (String) js.executeScript(
                        "try { " +
                        "  if (window._interceptedUrls && window._interceptedUrls.length > 0) { " +
                        "    // Возвращаем последний перехваченный URL " +
                        "    return window._interceptedUrls[window._interceptedUrls.length - 1]; " +
                        "  } " +
                        "  return null; " +
                        "} catch(e) { return null; }"
                    );
                    
                    if (interceptedUrl != null && !interceptedUrl.isEmpty()) {
                        // Проверяем, что URL содержит один из паттернов
                        boolean matches = false;
                        for (String pattern : searchPatterns) {
                            if (interceptedUrl.contains(pattern)) {
                                matches = true;
                                break;
                            }
                        }
                        if (matches) {
                            log.debug("Found JSON URL via fetch/XHR interception: {}", interceptedUrl);
                            return interceptedUrl;
                        }
                    }
                } catch (Exception e) {
                    log.debug("Error checking intercepted URLs: {}", e.getMessage());
                }
                
                // Метод 7: Ищем в localStorage/sessionStorage
                try {
                    String storageData = (String) js.executeScript(
                        "try { " +
                    "  var data = ''; " +
                    "  for (var i = 0; i < localStorage.length; i++) { " +
                    "    var key = localStorage.key(i); " +
                    "    data += localStorage.getItem(key) + ' '; " +
                    "  } " +
                    "  for (var i = 0; i < sessionStorage.length; i++) { " +
                    "    var key = sessionStorage.key(i); " +
                    "    data += sessionStorage.getItem(key) + ' '; " +
                    "  } " +
                    "  return data; " +
                    "} catch(e) { return null; }"
                );
                
                if (storageData != null && !storageData.isEmpty()) {
                    foundUrl = findJsonUrlInTextFlexible(storageData, searchPatterns, urlPrefix);
                    if (foundUrl != null) {
                        log.debug("Found JSON URL in storage: {}", foundUrl);
                        return foundUrl;
                    }
                }
                } catch (Exception e) {
                    log.debug("Error searching in storage: {}", e.getMessage());
                }
                
                // Если дошли сюда, значит JSON URL не найден в этой попытке
                // Если это не последняя попытка, продолжаем цикл
                if (attempt < 2) {
                    continue;
                }
                
                // Логируем детали для отладки только первых ошибок (только на последней попытке)
                if (shouldLogDetails) {
                    log.warn("JSON URL not found on page (attempt {}): {}", attempt, categoryUrl);
                    log.warn("Page title: {}", driver.getTitle());
                    log.warn("Page URL: {}", driver.getCurrentUrl());
                    
                    // Проверяем, есть ли вообще запросы в browser logs (Firefox поддерживает BROWSER logs)
                    try {
                        org.openqa.selenium.logging.LogEntries logEntries = driver.manage().logs().get(org.openqa.selenium.logging.LogType.BROWSER);
                        int logCount = 0;
                        for (org.openqa.selenium.logging.LogEntry entry : logEntries) {
                            logCount++;
                            if (logCount <= 3) {
                                log.warn("Browser log entry #{}: {}", logCount, entry.getMessage().substring(0, Math.min(200, entry.getMessage().length())));
                            }
                        }
                        log.warn("Total browser log entries: {}", logCount);
                    } catch (Exception e) {
                        log.warn("Could not read browser logs: {}", e.getMessage());
                    }
                    
                    // Проверяем, есть ли URL в page source (хотя бы частично)
                    try {
                        String pageSourceCheck = driver.getPageSource();
                        boolean containsPattern = pageSourceCheck.contains("__internal/catalog/catalog") ||
                                                  pageSourceCheck.contains("__internal/search/exactmatch") ||
                                                  pageSourceCheck.contains("__internal/u-search/exactmatch") ||
                                                  pageSourceCheck.contains("__internal");
                        if (containsPattern) {
                            log.warn("Page source contains search pattern keywords, but URL not found");
                        } else {
                            log.warn("Page source does not contain search pattern keywords");
                        }
                    } catch (Exception e) {
                        log.warn("Could not check page source: {}", e.getMessage());
                    }
                }
                
            } catch (Exception e) {
                if (shouldLogDetails) {
                    log.warn("Error searching for JSON URL on page {} (attempt {}): {}", categoryUrl, attempt, e.getMessage());
                }
                if (attempt == 2) {
                    // Последняя попытка - возвращаем null
                    return null;
                }
                // Продолжаем цикл для следующей попытки
                continue;
            }
        } // конец цикла for
        
        // Если дошли сюда, значит все попытки исчерпаны
        return null;
    }
    
    /**
     * Гибкий поиск JSON URL в тексте - ищет по паттернам, а не по точному префиксу
     * Поддерживает несколько форматов URL
     */
    private String findJsonUrlInTextFlexible(String text, String[] searchPatterns, String urlPrefix) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        
        try {
            // Собираем все найденные URL для всех паттернов
            java.util.List<String> foundUrls = new java.util.ArrayList<>();
            
            // Ищем URL для каждого паттерна
            for (String searchPattern : searchPatterns) {
                // Ищем URL, который содержит паттерн
                java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                    "https?:\\/\\/[^\"'\\s<>]*" + Pattern.quote(searchPattern) + "[^\"'\\s<>]*"
                );
                java.util.regex.Matcher matcher = pattern.matcher(text);
                
                while (matcher.find()) {
                    String url = matcher.group(0);
                    // Убираем возможные завершающие символы
                    url = url.replaceAll("[\\\"'<>\\s]+$", "");
                    if (url != null && !url.isEmpty()) {
                        foundUrls.add(url);
                    }
                }
            }
            
            // Если нашли URL, возвращаем первый подходящий
            if (!foundUrls.isEmpty()) {
                // Если указан префикс, ищем URL с этим префиксом
                if (urlPrefix != null && !urlPrefix.isEmpty()) {
                    for (String url : foundUrls) {
                        if (url.startsWith(urlPrefix)) {
                            return url;
                        }
                    }
                }
                // Если префикс не указан или не найден, возвращаем первый найденный
                return foundUrls.get(0);
            }
        } catch (Exception e) {
            log.debug("Error finding URL in text: {}", e.getMessage());
        }
        
        return null;
    }
    
    /**
     * Ищет JSON URL в тексте по префиксу
     */
    private String findJsonUrlInText(String text, String prefix) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        
        try {
            // Экранируем специальные символы для regex
            String escapedPrefix = prefix.replace("?", "\\?").replace(".", "\\.").replace("/", "\\/");
            
            // Ищем URL, который начинается с префикса
            java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                escapedPrefix + "[^\"'\\s<>]*"
            );
            java.util.regex.Matcher matcher = pattern.matcher(text);
            
            if (matcher.find()) {
                String url = matcher.group(0);
                // Убираем возможные завершающие символы
                url = url.replaceAll("[\\\"'<>\\s]+$", "");
                return url;
            }
        } catch (Exception e) {
            log.debug("Error finding URL in text: {}", e.getMessage());
        }
        
        return null;
    }
    
    /**
     * Ищет Firefox на всех доступных дисках
     */
    private String findFirefoxOnAllDrives() {
        // Получаем все доступные диски
        java.io.File[] roots = java.io.File.listRoots();
        java.util.List<String> firefoxPaths = new java.util.ArrayList<>();
        
        // ВАЖНО: Проверяем кеш Selenium/WebDriverManager (где Firefox может быть скачан автоматически)
        String userHome = System.getProperty("user.home");
        if (userHome != null && !userHome.isEmpty()) {
            // Проверяем кеш Selenium для Firefox
            java.io.File seleniumCache = new java.io.File(userHome, ".cache\\selenium\\firefox");
            if (seleniumCache.exists() && seleniumCache.isDirectory()) {
                // Ищем firefox.exe в подпапках кеша
                findFirefoxInDirectory(seleniumCache, firefoxPaths);
            }
        }
        
        // Стандартные пути на каждом диске
        for (java.io.File root : roots) {
            String drive = root.getAbsolutePath().substring(0, 1); // C, D, E, etc.
            firefoxPaths.add(drive + ":\\Program Files\\Mozilla Firefox\\firefox.exe");
            firefoxPaths.add(drive + ":\\Program Files (x86)\\Mozilla Firefox\\firefox.exe");
            firefoxPaths.add(drive + ":\\Program Files\\Firefox\\firefox.exe");
        }
        
        // Также проверяем пользовательские пути
        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData != null && !localAppData.isEmpty()) {
            firefoxPaths.add(localAppData + "\\Mozilla Firefox\\firefox.exe");
        }
        
        if (userHome != null && !userHome.isEmpty()) {
            firefoxPaths.add(userHome + "\\AppData\\Local\\Mozilla Firefox\\firefox.exe");
        }
        
        // Проверяем каждый путь
        for (String path : firefoxPaths) {
            java.io.File firefoxFile = new java.io.File(path);
            if (firefoxFile.exists() && firefoxFile.isFile()) {
                return path;
            }
        }
        
        // Пробуем найти через команду where
        try {
            Process process = Runtime.getRuntime().exec("where firefox");
            java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(process.getInputStream()));
            String line = reader.readLine();
            if (line != null && !line.isEmpty()) {
                line = line.trim();
                java.io.File firefoxFile = new java.io.File(line);
                if (firefoxFile.exists() && firefoxFile.isFile()) {
                    return line;
                }
            }
            process.waitFor();
        } catch (Exception e) {
            // Игнорируем ошибки
        }
        
        return null;
    }
    
    /**
     * Рекурсивно ищет firefox.exe в директории (для поиска в кеше Selenium)
     */
    private void findFirefoxInDirectory(java.io.File directory, java.util.List<String> paths) {
        try {
            java.io.File[] files = directory.listFiles();
            if (files == null) return;
            
            for (java.io.File file : files) {
                if (file.isDirectory()) {
                    // Рекурсивно ищем в подпапках
                    findFirefoxInDirectory(file, paths);
                } else if (file.getName().equals("firefox.exe")) {
                    // Нашли firefox.exe
                    paths.add(file.getAbsolutePath());
                }
            }
        } catch (Exception e) {
            // Игнорируем ошибки доступа
        }
    }
}

