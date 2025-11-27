package org.example.example.http;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

/**
 * Класс для работы с куки Wildberries без Selenium.
 * Загружает куки из файла cookies.txt
 */
public class WbCookieFetcher {
    private static final Logger log = LoggerFactory.getLogger(WbCookieFetcher.class);
    
    /**
     * Путь к файлу с куки
     */
    private static final String COOKIES_FILE = "cookies.txt";
    
    /**
     * User-Agent из запроса пользователя.
     */
    private static final String DEFAULT_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:145.0) Gecko/20100101 Firefox/145.0";
    
    /**
     * Device ID из запроса пользователя.
     */
    private static final String DEFAULT_DEVICE_ID = "site_8eaf7acc4d8646988bbfd2d0579895e2";
    
    /**
     * Получает куки и заголовки для работы с Wildberries.
     * 
     * @param existingCookies существующие куки (опционально, для слияния)
     * @return данные сессии с куки и заголовками
     */
    public static WbSessionData fetchCookiesAndHeaders(Map<String, String> existingCookies) {
        log.info("Loading cookies from file: {}", COOKIES_FILE);
        
        // Загружаем куки из файла
        String cookiesString = loadCookiesFromFile();
        
        if (cookiesString == null || cookiesString.trim().isEmpty()) {
            log.warn("Cookies file is empty or not found. Using empty cookies.");
            cookiesString = "";
        }
        
        // Парсим куки из файла
        Map<String, String> cookies = parseCookies(cookiesString);
        
        // Объединяем с существующими куки, если они есть
        if (existingCookies != null && !existingCookies.isEmpty()) {
            cookies.putAll(existingCookies);
        }
        
        // Строим заголовки запроса
        Map<String, String> headers = buildRequestHeaders();
        
        log.info("Successfully loaded {} cookies and {} headers", cookies.size(), headers.size());
        
        return new WbSessionData(cookies, DEFAULT_USER_AGENT, headers);
    }
    
    /**
     * Загружает куки из файла cookies.txt
     * Поддерживает два формата:
     * 1. Одна строка: name1=value1; name2=value2; name3=value3
     * 2. Много строк: name1=value1\nname2=value2\nname3=value3
     */
    private static String loadCookiesFromFile() {
        Path path = Paths.get(COOKIES_FILE);
        
        if (!Files.exists(path)) {
            log.warn("Cookies file not found: {}. Please create it with your cookies.", COOKIES_FILE);
            return null;
        }
        
        try {
            // Читаем весь файл
            String content = Files.readString(path).trim();
            
            if (content.isEmpty()) {
                log.warn("Cookies file is empty: {}", COOKIES_FILE);
                return null;
            }
            
            // Если файл содержит только одну строку с точкой с запятой, это формат "name=value; name2=value2"
            if (content.contains(";") && !content.contains("\n")) {
                return content;
            }
            
            // Если файл содержит несколько строк, объединяем их через точку с запятой
            if (content.contains("\n")) {
                String[] lines = content.split("\n");
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < lines.length; i++) {
                    String line = lines[i].trim();
                    if (!line.isEmpty() && line.contains("=")) {
                        if (sb.length() > 0) {
                            sb.append("; ");
                        }
                        sb.append(line);
                    }
                }
                return sb.toString();
            }
            
            // Одна строка без точки с запятой
            return content;
            
        } catch (IOException e) {
            log.error("Error reading cookies file: {}", COOKIES_FILE, e);
            return null;
        }
    }
    
    /**
     * Парсит строку куки в Map.
     */
    private static Map<String, String> parseCookies(String cookieString) {
        Map<String, String> cookies = new HashMap<>();
        
        if (cookieString == null || cookieString.trim().isEmpty()) {
            return cookies;
        }
        
        String[] pairs = cookieString.split(";");
        for (String pair : pairs) {
            String[] keyValue = pair.trim().split("=", 2);
            if (keyValue.length == 2) {
                cookies.put(keyValue[0].trim(), keyValue[1].trim());
            }
        }
        
        return cookies;
    }
    
    /**
     * Строит заголовки для запросов к Wildberries API.
     */
    private static Map<String, String> buildRequestHeaders() {
        Map<String, String> headers = new HashMap<>();
        
        headers.put("User-Agent", DEFAULT_USER_AGENT);
        headers.put("Accept", "*/*");
        headers.put("Accept-Language", "en-US,en;q=0.5");
        // Не указываем Accept-Encoding, чтобы получить несжатый ответ
        headers.put("Referer", "https://www.wildberries.ru/promotions/rubli-za-otzyvy/muzhchinam/odezhda/tolstovki");
        headers.put("deviceid", DEFAULT_DEVICE_ID);
        headers.put("x-requested-with", "XMLHttpRequest");
        headers.put("x-spa-version", "13.14.2");
        headers.put("Connection", "keep-alive");
        headers.put("Sec-Fetch-Dest", "empty");
        headers.put("Sec-Fetch-Mode", "cors");
        headers.put("Sec-Fetch-Site", "same-origin");
        headers.put("Priority", "u=4");
        
        return headers;
    }
    
    /**
     * Данные сессии с куки и заголовками.
     */
    public record WbSessionData(
            Map<String, String> cookies,
            String userAgent,
            Map<String, String> requestHeaders
    ) {}
}

