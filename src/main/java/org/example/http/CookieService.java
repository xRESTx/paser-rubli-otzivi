package org.example.http;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

public class CookieService {
	private static final Logger log = LoggerFactory.getLogger(CookieService.class);
	private final AtomicReference<Set<HttpCookie>> cookiesRef = new AtomicReference<>(Collections.emptySet());

	public CookieService() {
	}

	public void initAndFetch() {
		refresh();
	}

	public void refresh() {
		try {
			// Точно как в старом коде - без лишних заголовков
			CookieManager cookieManager = new CookieManager();
			cookieManager.setCookiePolicy(CookiePolicy.ACCEPT_ALL);
			
			HttpClient client = HttpClient.newBuilder()
					.cookieHandler(cookieManager)
					.build();
			
			String urlWb = "https://www.wildberries.ru/";
			HttpRequest request = HttpRequest.newBuilder()
					.uri(URI.create(urlWb))
					.GET()
					.build();
			
			HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
			
			// Извлечение cookies как в старом коде
			Set<HttpCookie> cookies = new HashSet<>(cookieManager.getCookieStore().getCookies());
			
			cookiesRef.set(cookies);
			
			log.info("Cookies refreshed: {} cookies loaded, HTTP status: {}", cookies.size(), response.statusCode());
			if (cookies.isEmpty()) {
				log.warn("No cookies received from Wildberries. Will continue without cookies - this may cause 429 errors.");
			}
		} catch (Exception e) {
			log.error("Error refreshing cookies", e);
		}
	}

	public Set<HttpCookie> getCookiesSet() {
		return cookiesRef.get();
	}

	public String getCookieHeader() {
		StringBuilder sb = new StringBuilder();
		for (HttpCookie c : cookiesRef.get()) {
			if (sb.length() > 0) sb.append("; ");
			sb.append(c.getName()).append("=").append(c.getValue());
		}
		return sb.toString();
	}
}


