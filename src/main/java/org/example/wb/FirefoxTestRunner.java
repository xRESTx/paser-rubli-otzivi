package org.example.wb;

import org.openqa.selenium.WebDriver;
import org.openqa.selenium.firefox.FirefoxDriver;
import org.openqa.selenium.firefox.FirefoxOptions;
import org.openqa.selenium.firefox.FirefoxProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Простой тест для проверки запуска Firefox
 * Просто открывает браузер и заходит на страницу
 */
public class FirefoxTestRunner {
    private static final Logger log = LoggerFactory.getLogger(FirefoxTestRunner.class);
    
    public static void main(String[] args) {
        log.info("=== FIREFOX TEST RUNNER ===");
        log.info("This will simply open Firefox browser");
        log.info("If Firefox doesn't open, check the path configuration");
        log.info("");
        
        WebDriver driver = null;
        
        try {
            // Упрощенный подход - как в рабочем примере пользователя
            // Просто создаем FirefoxOptions и позволяем FirefoxDriver самому найти Firefox
            FirefoxOptions options = new FirefoxOptions();
            // НЕ используем headless режим - браузер должен быть виден
            // options.addArguments("--headless"); // НЕ добавляем!
            
            // Проверяем GeckoDriver - устанавливаем путь, если найден
            String geckoDriverPath = System.getProperty("webdriver.gecko.driver");
            if (geckoDriverPath == null || geckoDriverPath.isEmpty()) {
                // Проверяем стандартные места
                String[] commonPaths = {
                    "E:\\geckodriver-v0.36.0-win64\\geckodriver.exe",
                    "E:\\geckodriver\\geckodriver.exe",
                    "C:\\geckodriver\\geckodriver.exe"
                };
                
                for (String path : commonPaths) {
                    java.io.File driverFile = new java.io.File(path);
                    if (driverFile.exists() && driverFile.isFile()) {
                        System.setProperty("webdriver.gecko.driver", path);
                        log.info("Found GeckoDriver at: {}", path);
                        break;
                    }
                }
            }
            
            // Если GeckoDriver не найден, пробуем WebDriverManager
            if (System.getProperty("webdriver.gecko.driver") == null) {
                log.info("GeckoDriver not found. Trying WebDriverManager...");
                try {
                    io.github.bonigarcia.wdm.WebDriverManager.firefoxdriver().setup();
                    log.info("GeckoDriver downloaded/setup via WebDriverManager");
                } catch (Exception e) {
                    log.warn("WebDriverManager failed, but continuing anyway...");
                }
            }
            
            // Перед созданием драйвера, попробуем найти Firefox на всех дисках
            log.info("");
            log.info("=== SEARCHING FOR FIREFOX ===");
            String foundFirefox = findFirefoxOnAllDrives();
            if (foundFirefox != null) {
                log.info("✓ Firefox found at: {}", foundFirefox);
                options.setBinary(foundFirefox);
            } else {
                log.warn("Firefox not found automatically. FirefoxDriver will try to find it...");
                log.warn("If this fails, Firefox may not be installed or not in standard location.");
            }
            
            log.info("");
            log.info("=== CREATING FIREFOX DRIVER ===");
            log.info("This may take a few seconds...");
            log.info("");
            
            // Создаем драйвер - как в рабочем примере пользователя
            // FirefoxDriver сам найдет Firefox, если он установлен
            driver = new FirefoxDriver(options);
            
            log.info("SUCCESS! Firefox browser opened!");
            log.info("Browser window should be visible on your screen");
            log.info("");
            
            // Открываем тестовую страницу
            log.info("Navigating to: https://www.google.com");
            driver.get("https://www.google.com");
            
            log.info("Page loaded! Title: {}", driver.getTitle());
            log.info("Current URL: {}", driver.getCurrentUrl());
            log.info("");
            log.info("=== TEST SUCCESSFUL ===");
            log.info("Firefox is working correctly!");
            log.info("Press Enter to close the browser...");
            
            // Ждем, чтобы пользователь мог увидеть браузер
            System.in.read();
            
        } catch (Exception e) {
            log.error("");
            log.error("=== ERROR ===");
            log.error("Failed to start Firefox: {}", e.getMessage());
            log.error("Error class: {}", e.getClass().getName());
            log.error("");
            
            // Проверяем, может ли быть проблема с Firefox
            String foundFirefox = findFirefoxOnAllDrives();
            if (foundFirefox == null) {
                log.error("FIREFOX НЕ НАЙДЕН на вашем компьютере!");
                log.error("");
                log.error("РЕШЕНИЕ:");
                log.error("1. Установите Firefox с https://www.mozilla.org/firefox/");
                log.error("2. Или если Firefox уже установлен, найдите firefox.exe и укажите путь:");
                log.error("   -Dfirefox.binary.path=<путь-к-firefox.exe>");
                log.error("");
            } else {
                log.error("Firefox найден, но не запускается.");
                log.error("Найденный путь: {}", foundFirefox);
                log.error("");
                log.error("Возможные причины:");
                log.error("1. Firefox установлен, но поврежден - переустановите Firefox");
                log.error("2. Недостаточно прав - запустите от имени администратора");
                log.error("3. Конфликт версий GeckoDriver и Firefox");
                log.error("");
                log.error("Попробуйте указать путь явно:");
                log.error("  -Dfirefox.binary.path={}", foundFirefox);
                log.error("");
            }
            
            log.error("Дополнительная информация:");
            log.error("  GeckoDriver path: {}", System.getProperty("webdriver.gecko.driver"));
            log.error("  Project location: {}", System.getProperty("user.dir"));
            log.error("");
            e.printStackTrace();
        } finally {
            if (driver != null) {
                try {
                    log.info("Closing browser...");
                    driver.quit();
                    log.info("Browser closed.");
                } catch (Exception e) {
                    log.error("Error closing browser: {}", e.getMessage());
                }
            }
        }
    }
    
    /**
     * Ищет Firefox на всех доступных дисках
     */
    private static String findFirefoxOnAllDrives() {
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
    private static void findFirefoxInDirectory(java.io.File directory, java.util.List<String> paths) {
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

