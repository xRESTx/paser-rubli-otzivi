package org.example.http;

import org.jsoup.Connection;
import org.jsoup.Jsoup;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.net.Authenticator;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.Authenticator.RequestorType;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.StringJoiner;
import java.util.function.Supplier;
import java.util.zip.GZIPInputStream;

/**
 * HTTP клиент для работы с Wildberries API.
 * Использует Jsoup для HTTP-запросов с куки и заголовками (как в рабочем коде WbParser).
 */
public class WbHttpClient {
    private static final String USER_AGENT = System.getProperty(
            "wb.userAgent",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:151.0) Gecko/20100101 Firefox/151.0");
    private static final String SPA_VERSION = System.getProperty("wb.spaVersion", "14.13.6");
    private static final String DEVICE_ID = System.getProperty("wb.deviceId", "site_e51163b702ac40d3b293a9ccc7c333b8");
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(WbHttpClient.class);
    
    private final Supplier<String> cookieHeaderSupplier;
    private final ThreadLocal<RotatingCookieJar.CookieSelection> requestCookieSelection = new ThreadLocal<>();
    private final RotatingCookieJar cookieJar;
    private final ProxyPool proxyPool;
    private final int maxRetries;
    
    /**
     * ThreadLocal для хранения текущего прокси и его учетных данных
     */
    private static final ThreadLocal<ProxyConfig> threadProxyConfig = new ThreadLocal<>();
    
    /**
     * Глобальный Authenticator для аутентификации прокси
     * Использует ThreadLocal для получения учетных данных текущего прокси
     */
    private static final Authenticator PROXY_AUTHENTICATOR = new Authenticator() {
        @Override
        protected PasswordAuthentication getPasswordAuthentication() {
            ProxyConfig proxy = threadProxyConfig.get();
            RequestorType type = getRequestorType();
            String requestingHost = getRequestingHost();
            int requestingPort = getRequestingPort();
            
            
            // Проверяем, что это запрос для прокси (PROXY type) или сервера через прокси
            if (proxy != null && proxy.hasAuth()) {
                // Проверяем, что запрашиваемый хост и порт совпадают с нашим прокси
                boolean matches = false;
                if (requestingHost != null && requestingPort > 0) {
                    matches = requestingHost.equals(proxy.host()) && requestingPort == proxy.port();
                }
                
                // Для прокси принимаем как PROXY, так и SERVER type (для CONNECT)
                if (matches && (type == RequestorType.PROXY || type == RequestorType.SERVER)) {
                    return new PasswordAuthentication(
                            proxy.username(), 
                            proxy.password().toCharArray());
                } else if (proxy != null && proxy.hasAuth()) {
                    log.warn("Authenticator: proxy mismatch - requesting {}:{}, threadProxy {}:{}, type={}", 
                            requestingHost, requestingPort, proxy.host(), proxy.port(), type);
                }
            }
            return null;
        }
    };
    
    static {
        // Устанавливаем глобальный Authenticator один раз при загрузке класса
        Authenticator.setDefault(PROXY_AUTHENTICATOR);
    }
    
    public WbHttpClient(Map<String, String> cookiesMap) {
        this.cookieHeaderSupplier = () -> {
            requestCookieSelection.remove();
            return buildCookieHeader(cookiesMap);
        };
        this.cookieJar = null;
        this.proxyPool = null;
        this.maxRetries = 1;
    }

    public WbHttpClient(RotatingCookieJar cookieJar, List<ProxyConfig> proxies) {
        this.cookieHeaderSupplier = cookieJar == null
                ? () -> {
                    requestCookieSelection.remove();
                    return "";
                }
                : () -> {
                    RotatingCookieJar.CookieSelection selection = cookieJar.nextCookie();
                    requestCookieSelection.set(selection);
                    return selection.header();
                };
        this.cookieJar = cookieJar;
        if (proxies == null || proxies.isEmpty()) {
            this.proxyPool = null;
        } else {
            // Создаем пул прокси для управления работоспособностью
            this.proxyPool = new ProxyPool(proxies);
        }
        this.maxRetries = proxies != null && !proxies.isEmpty()
                ? Integer.getInteger("wb.http.maxRetries", 2)
                : 1; // Retry с переключением прокси только если есть прокси
    }
    
    /**
     * Выполняет GET запрос с retry логикой и переключением прокси.
     */
    public HttpResponse<String> get(String url, Map<String, String> additionalHeaders) {
        Exception lastException = null;
        
        for (int attempt = 0; attempt < maxRetries; attempt++) {
            ProxyConfig currentProxy = null;
            
            // Если есть пул прокси, используем его для получения рабочего прокси
            if (proxyPool != null && proxyPool.size() > 0) {
                currentProxy = proxyPool.getNextProxy();
                if (currentProxy == null) {
                    // Если нет рабочих прокси, принудительно сбрасываем все и пробуем еще раз
                    log.warn("No working proxies available (total: {}, working: {}), force resetting all proxies...", 
                            proxyPool.size(), proxyPool.getWorkingCount());
                    proxyPool.resetAllProxies(true);
                    currentProxy = proxyPool.getNextProxy();
                    if (currentProxy == null) {
                        log.error("No working proxies available even after reset (total: {}, working: {})", 
                                proxyPool.size(), proxyPool.getWorkingCount());
                        throw new RuntimeException("No working proxies available");
                    }
                }
            }
            
            try {
                // Устанавливаем прокси в ThreadLocal для Authenticator
                if (currentProxy != null) {
                    threadProxyConfig.set(currentProxy);
                    
                    // Устанавливаем системные свойства для прокси (как в рабочем коде WbParser)
                    // Используем имена БЕЗ точек: httpProxyHost, а не http.proxyHost
                    synchronized (WbHttpClient.class) {
                        System.setProperty("httpProxyHost", currentProxy.host());
                        System.setProperty("httpProxyPort", String.valueOf(currentProxy.port()));
                        System.setProperty("httpsProxyHost", currentProxy.host());
                        System.setProperty("httpsProxyPort", String.valueOf(currentProxy.port()));
                        
                    }
                }
                
                HttpResponse<String> response = executeRequest(url, additionalHeaders, currentProxy);
                int statusCode = response.statusCode();
                recordStatusCode(statusCode);
                
                // Очищаем ThreadLocal после успешного запроса
                if (currentProxy != null) {
                    threadProxyConfig.remove();
                    clearSystemProxyProperties();
                }
                
                // Обработка специфичных ошибок
                if (statusCode == 429) {
                    if (currentProxy != null) {
                        log.warn("Rate limit (429) for URL: {} via proxy {}:{}",
                                url, currentProxy.host(), currentProxy.port());
                    }
                    return response;
                }
                
                if (statusCode == 498 && currentProxy != null && attempt < maxRetries - 1) {
                    // Токен устарел - пробуем с другим прокси/куки
                    log.warn("Token expired (498) for URL: {} (attempt {}/{}) via proxy {}:{} cookie {}",
                            url, attempt + 1, maxRetries,
                            currentProxy.host(), currentProxy.port(),
                            cookieLabel());
                    proxyPool.releaseThreadProxy();
                    continue;
                }
                
                // Успешный ответ или не критичная ошибка
                if (statusCode >= 200 && statusCode < 300) {
                    return response;
                }
                
                // Для других ошибок пробуем retry
                if (attempt < maxRetries - 1) {
                    log.warn("HTTP error {} for URL: {} (attempt {}/{}) via proxy {}:{} cookie {}",
                            statusCode, url, attempt + 1, maxRetries,
                            currentProxy != null ? currentProxy.host() : "none",
                            currentProxy != null ? currentProxy.port() : 0,
                            cookieLabel());
                    if (currentProxy != null) {
                        proxyPool.releaseThreadProxy();
                    }
                    continue;
                }
                
                log.error("HTTP error {} for URL: {} (final attempt) via proxy {}:{} cookie {}",
                        statusCode, url,
                        currentProxy != null ? currentProxy.host() : "none",
                        currentProxy != null ? currentProxy.port() : 0,
                        cookieLabel());
                return response; // Возвращаем ответ даже с ошибкой на последней попытке
                
            } catch (IOException e) {
                lastException = e;
                if (currentProxy != null) {
                    threadProxyConfig.remove();
                    clearSystemProxyProperties();
                }
                // Сетевые ошибки - помечаем прокси как bad и пробуем следующий
                if (currentProxy != null && attempt < maxRetries - 1) {
                    proxyPool.markProxyAsBad(currentProxy);
                    proxyPool.releaseThreadProxy();
                    continue;
                }
            } catch (Exception e) {
                lastException = e;
                if (currentProxy != null) {
                    threadProxyConfig.remove();
                    clearSystemProxyProperties();
                }
                log.warn("Exception for URL: {} (attempt {}/{}) via proxy {}:{} cookie {} - {}",
                        url, attempt + 1, maxRetries,
                        currentProxy != null ? currentProxy.host() : "none",
                        currentProxy != null ? currentProxy.port() : 0,
                        cookieLabel(),
                        e.getMessage());
                if (currentProxy != null && attempt < maxRetries - 1) {
                    proxyPool.releaseThreadProxy();
                    continue;
                }
            }
        }
        
        // Очищаем ThreadLocal перед выходом
        threadProxyConfig.remove();
        clearSystemProxyProperties();
        
        // Все попытки исчерпаны
        if (lastException != null) {
            throw new RuntimeException("Failed to execute request after " + maxRetries + " attempts for URL: " + url + 
                    " - " + lastException.getClass().getSimpleName() + ": " + lastException.getMessage(), lastException);
        }
        throw new RuntimeException("Failed to execute request after " + maxRetries + " attempts for URL: " + url);
    }
    
    /**
     * Выполняет один HTTP запрос используя Jsoup
     */
    private HttpResponse<String> executeRequest(String url, Map<String, String> additionalHeaders, ProxyConfig proxy) 
            throws IOException {
        String referer = additionalHeaders != null ? additionalHeaders.get("Referer") : null;
        if (referer == null || referer.isEmpty()) {
            referer = "https://www.wildberries.ru/promotions/rubli-za-otzyvy";
        }
        if (proxy != null && proxy.secure()) {
            return executeSecureProxyRequest(url, referer, proxy);
        }

        Connection connection = Jsoup.connect(url)
                .userAgent(USER_AGENT)
                .header("Accept", "*/*")
                .header("Accept-Language", "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7")
                .header("Sec-Fetch-Site", "same-origin")
                .header("Sec-Fetch-Mode", "cors")
                .header("Sec-Fetch-Dest", "empty")
                .header("x-requested-with", "XMLHttpRequest")
                .header("x-spa-version", SPA_VERSION)
                .header("Priority", "u=4")
                .header("TE", "trailers")
                .method(Connection.Method.GET)
                .ignoreContentType(true)
                .ignoreHttpErrors(true)
                .timeout(20_000) // 20 секунд
                .followRedirects(true)
                .maxBodySize(0);

        if (DEVICE_ID != null && !DEVICE_ID.isBlank()) {
            connection.header("deviceid", DEVICE_ID);
        }
        
        connection.header("Referer", referer);
        
        // Добавляем дополнительные заголовки (кроме Referer, который уже обработан выше)
            if (additionalHeaders != null) {
                for (Map.Entry<String, String> entry : additionalHeaders.entrySet()) {
                String key = entry.getKey();
                // Referer уже обработан выше, пропускаем его
                if (!"Referer".equalsIgnoreCase(key)) {
                    connection.header(key, entry.getValue());
                }
                }
            }
            
        // Добавляем куки из cookieHeaderSupplier
        String cookieHeader = cookieHeaderSupplier.get();
        if (cookieHeader != null && !cookieHeader.trim().isEmpty()) {
            // Парсим cookie header и добавляем каждый cookie
            String[] cookies = cookieHeader.split(";");
            for (String cookie : cookies) {
                String trimmed = cookie.trim();
                if (!trimmed.isEmpty()) {
                    int eqIndex = trimmed.indexOf('=');
                    if (eqIndex > 0) {
                        String name = trimmed.substring(0, eqIndex).trim();
                        String value = eqIndex < trimmed.length() - 1 ? trimmed.substring(eqIndex + 1).trim() : "";
                        connection.cookie(name, value);
                    }
                }
            }
        } else {
            log.error("NO COOKIES AVAILABLE for request to: {} - This will likely fail!", url);
            }
            
        // Добавляем дополнительные заголовки для прокси (как в рабочем коде)
        if (proxy != null) {
            connection.proxy(proxy.host(), proxy.port());
            if (proxy.hasAuth()) {
                connection.header("Proxy-Authorization", basicProxyAuthorization(proxy));
            }
        }
        
        Connection.Response response = connection.execute();
            
        // Создаем обертку HttpResponse<String> для совместимости
        final java.net.URI requestUri = java.net.URI.create(url);
            return new HttpResponse<String>() {
            @Override
            public int statusCode() {
                return response.statusCode();
            }
            
                @Override
            public String body() {
                return response.body();
                }
                
                @Override
            public java.net.http.HttpRequest request() {
                // Возвращаем null, так как Jsoup не предоставляет HttpRequest
                return null;
                }
                
                @Override
                public java.util.Optional<HttpResponse<String>> previousResponse() {
                    return java.util.Optional.empty();
                }
                
                @Override
                public java.net.http.HttpHeaders headers() {
                // Конвертируем Jsoup headers (Map<String, String>) в HttpHeaders (Map<String, List<String>>)
                Map<String, String> jsoupHeaders = response.headers();
                java.util.Map<String, java.util.List<String>> headersMap = new java.util.HashMap<>();
                for (Map.Entry<String, String> entry : jsoupHeaders.entrySet()) {
                    headersMap.put(entry.getKey(), java.util.Collections.singletonList(entry.getValue()));
                }
                return java.net.http.HttpHeaders.of(headersMap, (name, value) -> true);
                }
                
                @Override
            public java.net.URI uri() {
                return requestUri;
                }
        };
    }

    private HttpResponse<String> executeSecureProxyRequest(String url, String referer, ProxyConfig proxy) throws IOException {
        URI uri = URI.create(url);
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            return executePlainResponse(url, 599, "Secure proxy mode supports only HTTPS target URLs");
        }

        String targetHost = uri.getHost();
        int targetPort = uri.getPort() > 0 ? uri.getPort() : 443;
        String targetPath = targetPath(uri);
        int timeoutMillis = 8_000;
        SSLSocketFactory sslSocketFactory = (SSLSocketFactory) SSLSocketFactory.getDefault();

        Socket tcpSocket = new Socket();
        tcpSocket.connect(new InetSocketAddress(proxy.host(), proxy.port()), timeoutMillis);
        tcpSocket.setSoTimeout(timeoutMillis);

        try (SSLSocket proxySocket = (SSLSocket) sslSocketFactory.createSocket(tcpSocket, proxy.host(), proxy.port(), true)) {
            configureSni(proxySocket, proxy.host());
            proxySocket.setSoTimeout(timeoutMillis);
            proxySocket.startHandshake();

            writeSecureProxyConnect(proxySocket, proxy, targetHost, targetPort);
            RawHeaders connectHeaders = readHeaders(proxySocket.getInputStream());
            if (connectHeaders.statusCode() != 200) {
                return executePlainResponse(url, connectHeaders.statusCode(), "");
            }

            try (SSLSocket targetSocket = (SSLSocket) sslSocketFactory.createSocket(proxySocket, targetHost, targetPort, false)) {
                configureSni(targetSocket, targetHost);
                targetSocket.setSoTimeout(timeoutMillis);
                targetSocket.startHandshake();
                writeTargetGet(targetSocket, targetHost, targetPath, referer);

                InputStream input = targetSocket.getInputStream();
                RawHeaders responseHeaders = readHeaders(input);
                byte[] bodyBytes = readBody(input, responseHeaders);
                String body = decodeBody(bodyBytes, responseHeaders);
                return executePlainResponse(url, responseHeaders.statusCode(), body);
            }
        }
    }

    private static void writeSecureProxyConnect(SSLSocket socket,
                                                ProxyConfig proxy,
                                                String targetHost,
                                                int targetPort) throws IOException {
        OutputStreamWriter writer = new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.ISO_8859_1);
        writer.write("CONNECT " + targetHost + ":" + targetPort + " HTTP/1.1\r\n");
        writer.write("Host: " + targetHost + ":" + targetPort + "\r\n");
        writer.write("User-Agent: " + USER_AGENT + "\r\n");
        if (proxy.hasAuth()) {
            writer.write("Proxy-Authorization: " + basicProxyAuthorization(proxy) + "\r\n");
        }
        writer.write("Proxy-Connection: Keep-Alive\r\n");
        writer.write("\r\n");
        writer.flush();
    }

    private void writeTargetGet(SSLSocket socket,
                                String targetHost,
                                String targetPath,
                                String referer) throws IOException {
        OutputStreamWriter writer = new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.ISO_8859_1);
        writer.write("GET " + targetPath + " HTTP/1.1\r\n");
        writer.write("Host: " + targetHost + "\r\n");
        writer.write("User-Agent: " + USER_AGENT + "\r\n");
        writer.write("Accept: */*\r\n");
        writer.write("Accept-Encoding: identity\r\n");
        writer.write("Accept-Language: ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7\r\n");
        writer.write("Referer: " + referer + "\r\n");
        writer.write("Sec-Fetch-Dest: empty\r\n");
        writer.write("Sec-Fetch-Mode: cors\r\n");
        writer.write("Sec-Fetch-Site: same-origin\r\n");
        writer.write("x-requested-with: XMLHttpRequest\r\n");
        writer.write("x-spa-version: " + SPA_VERSION + "\r\n");
        writer.write("Priority: u=4\r\n");
        writer.write("TE: trailers\r\n");
        if (DEVICE_ID != null && !DEVICE_ID.isBlank()) {
            writer.write("deviceid: " + DEVICE_ID + "\r\n");
        }
        String cookieHeader = cookieHeaderSupplier.get();
        if (cookieHeader != null && !cookieHeader.isBlank()) {
            writer.write("Cookie: " + cookieHeader + "\r\n");
        }
        writer.write("Connection: close\r\n");
        writer.write("\r\n");
        writer.flush();
    }

    private static void configureSni(SSLSocket socket, String host) {
        if (host == null || host.isBlank() || isIpAddress(host)) {
            return;
        }
        SSLParameters parameters = socket.getSSLParameters();
        parameters.setServerNames(List.of(new SNIHostName(host)));
        socket.setSSLParameters(parameters);
    }

    private static boolean isIpAddress(String host) {
        return host.chars().allMatch(ch -> Character.isDigit(ch) || ch == '.')
                || host.contains(":");
    }

    private static String targetPath(URI uri) {
        String path = uri.getRawPath();
        if (path == null || path.isBlank()) {
            path = "/";
        }
        String query = uri.getRawQuery();
        return query == null || query.isBlank() ? path : path + "?" + query;
    }

    private static RawHeaders readHeaders(InputStream input) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int previous3 = -1;
        int previous2 = -1;
        int previous1 = -1;
        int current;
        while ((current = input.read()) != -1) {
            buffer.write(current);
            if (previous3 == '\r' && previous2 == '\n' && previous1 == '\r' && current == '\n') {
                String headerText = new String(buffer.toByteArray(), StandardCharsets.ISO_8859_1);
                return RawHeaders.parse(headerText);
            }
            previous3 = previous2;
            previous2 = previous1;
            previous1 = current;
            if (buffer.size() > 64 * 1024) {
                throw new IOException("HTTP headers are too large");
            }
        }
        throw new EOFException("Connection closed before HTTP headers were received");
    }

    private static byte[] readBody(InputStream input, RawHeaders headers) throws IOException {
        if ("chunked".equalsIgnoreCase(headers.firstHeader("transfer-encoding"))) {
            return readChunkedBody(input);
        }
        String contentLength = headers.firstHeader("content-length");
        if (contentLength != null && !contentLength.isBlank()) {
            int length = Integer.parseInt(contentLength.trim());
            return input.readNBytes(length);
        }
        return input.readAllBytes();
    }

    private static byte[] readChunkedBody(InputStream input) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        while (true) {
            String chunkHeader = readAsciiLine(input);
            int separator = chunkHeader.indexOf(';');
            String sizeValue = separator >= 0 ? chunkHeader.substring(0, separator) : chunkHeader;
            int size = Integer.parseInt(sizeValue.trim(), 16);
            if (size == 0) {
                while (!readAsciiLine(input).isEmpty()) {
                    // discard trailers
                }
                return body.toByteArray();
            }
            body.write(input.readNBytes(size));
            readExpectedCrlf(input);
        }
    }

    private static String readAsciiLine(InputStream input) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int previous = -1;
        int current;
        while ((current = input.read()) != -1) {
            if (previous == '\r' && current == '\n') {
                byte[] bytes = line.toByteArray();
                return new String(bytes, 0, Math.max(0, bytes.length - 1), StandardCharsets.ISO_8859_1);
            }
            line.write(current);
            previous = current;
            if (line.size() > 8 * 1024) {
                throw new IOException("HTTP line is too large");
            }
        }
        throw new EOFException("Connection closed while reading HTTP line");
    }

    private static void readExpectedCrlf(InputStream input) throws IOException {
        int cr = input.read();
        int lf = input.read();
        if (cr != '\r' || lf != '\n') {
            throw new IOException("Invalid chunk delimiter");
        }
    }

    private static String decodeBody(byte[] bodyBytes, RawHeaders headers) throws IOException {
        String encoding = headers.firstHeader("content-encoding");
        if (encoding != null && "gzip".equalsIgnoreCase(encoding.trim())) {
            try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(bodyBytes))) {
                return new String(gzip.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
        return new String(bodyBytes, StandardCharsets.UTF_8);
    }
    
    /**
     * Очищает системные свойства прокси
     */
    private static void clearSystemProxyProperties() {
        synchronized (WbHttpClient.class) {
            System.clearProperty("httpProxyHost");
            System.clearProperty("httpProxyPort");
            System.clearProperty("httpsProxyHost");
            System.clearProperty("httpsProxyPort");
        }
    }
    
    /**
     * Строит Cookie заголовок из Map
     */
    private String buildCookieHeader(Map<String, String> cookiesMap) {
        if (cookiesMap == null || cookiesMap.isEmpty()) {
            return "";
        }
        StringJoiner joiner = new StringJoiner("; ");
        for (Map.Entry<String, String> entry : cookiesMap.entrySet()) {
            joiner.add(entry.getKey() + "=" + entry.getValue());
        }
        return joiner.toString();
    }
    
    /**
     * Обертка для HttpResponse, совместимая с Java HttpClient API
     */
    private void recordStatusCode(int statusCode) {
        if (cookieJar != null) {
            cookieJar.recordStatusCode(statusCode);
        }
    }

    private String cookieLabel() {
        RotatingCookieJar.CookieSelection selection = requestCookieSelection.get();
        if (selection == null || selection.total() <= 0) {
            return "n/a";
        }
        return (selection.index() + 1) + "/" + selection.total();
    }

    private static String basicProxyAuthorization(ProxyConfig proxy) {
        String username = proxy.username() == null ? "" : proxy.username();
        String password = proxy.password() == null ? "" : proxy.password();
        String token = Base64.getEncoder().encodeToString(
                (username + ":" + password).getBytes(StandardCharsets.ISO_8859_1));
        return "Basic " + token;
    }

    private static HttpResponse<String> executePlainResponse(String url, int statusCode, String body) {
        final java.net.URI requestUri = java.net.URI.create(url);
        return new HttpResponse<>() {
            @Override
            public int statusCode() {
                return statusCode;
            }

            @Override
            public String body() {
                return body;
            }

            @Override
            public java.net.http.HttpRequest request() {
                return null;
            }

            @Override
            public java.util.Optional<HttpResponse<String>> previousResponse() {
                return java.util.Optional.empty();
            }

            @Override
            public java.net.http.HttpHeaders headers() {
                return java.net.http.HttpHeaders.of(java.util.Map.of(), (name, value) -> true);
            }

            @Override
            public java.net.URI uri() {
                return requestUri;
            }
        };
    }

    private record RawHeaders(int statusCode, Map<String, List<String>> headers) {
        private static RawHeaders parse(String headerText) throws IOException {
            String[] lines = headerText.split("\\r?\\n");
            if (lines.length == 0 || lines[0].isBlank()) {
                throw new IOException("Empty HTTP response headers");
            }
            String[] statusParts = lines[0].split(" ", 3);
            if (statusParts.length < 2) {
                throw new IOException("Invalid HTTP status line: " + lines[0]);
            }

            int statusCode;
            try {
                statusCode = Integer.parseInt(statusParts[1]);
            } catch (NumberFormatException e) {
                throw new IOException("Invalid HTTP status code: " + lines[0], e);
            }

            Map<String, List<String>> headers = new LinkedHashMap<>();
            for (int i = 1; i < lines.length; i++) {
                String line = lines[i];
                int separator = line.indexOf(':');
                if (separator <= 0) {
                    continue;
                }
                String name = line.substring(0, separator).trim().toLowerCase(Locale.ROOT);
                String value = line.substring(separator + 1).trim();
                headers.computeIfAbsent(name, ignored -> new ArrayList<>()).add(value);
            }
            return new RawHeaders(statusCode, headers);
        }

        private String firstHeader(String name) {
            if (name == null) {
                return null;
            }
            List<String> values = headers.get(name.toLowerCase(Locale.ROOT));
            return values == null || values.isEmpty() ? null : values.get(0);
        }
    }

    public interface HttpResponse<T> {
        int statusCode();
        T body();
        java.net.http.HttpRequest request();
        java.util.Optional<HttpResponse<T>> previousResponse();
        java.net.http.HttpHeaders headers();
        java.net.URI uri(); // Добавляем метод uri() для совместимости
        }
    }
