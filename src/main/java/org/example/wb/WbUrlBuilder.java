package org.example.wb;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

public class WbUrlBuilder {
	private static final String HOST = "https://www.wildberries.ru";
	private static final String DEST = "-5551776";
	private static final String SORT = "priceup";
	private static final String PRICE_U = "0;800000";
	private static final String SPP = "30";

	public String ensurePrefix(String url) {
		if (url == null || url.isEmpty()) return HOST;
		if (url.startsWith(HOST) || url.startsWith("https://vmeste.wildberries.ru/")) return url;
		return HOST + url;
	}

	public String buildCatalogPageUrl(String shard, String query, int page) {
		// сохраняем точные параметры, подтвержденные опытным путем
		return "https://catalog.wb.ru/catalog/" + shard +
				"/v2/catalog?ab_testing=false&appType=1&" + (query == null ? "" : query) +
				"&curr=rub&dest=" + DEST +
				"&ffeedbackpoints=1&page=" + page +
				"&sort=" + SORT +
				"&priceU=" + PRICE_U +
				"&spp=" + SPP;
	}

	/**
	 * Строит URL для нового endpoint поиска Wildberries
	 * @param categoryId ID категории (извлекается из query: cat=XXX или subject=XXX)
	 * @param categoryName название категории (из URL или name)
	 * @param page номер страницы
	 * @return URL для запроса
	 */
	public String buildSearchPageUrl(String categoryId, String categoryName, int page) {
		try {
			// Формируем query в формате: menu_redirect_subject_v2_{id} {name}
			String queryValue = "menu_redirect_subject_v2_" + categoryId + " " + categoryName;
			String encodedQuery = URLEncoder.encode(queryValue, StandardCharsets.UTF_8);
			
			return HOST + "/__internal/u-search/exactmatch/ru/common/v18/search" +
					"?ab_testid=new_benefit_sort" +
					"&ab_testing=false" +
					"&appType=1" +
					"&curr=rub" +
					"&dest=-1257786" +
					"&ffeedbackpoints=1" +
					"&hide_dtype=11" +
					"&lang=ru" +
					"&page=" + page +
					"&query=" + encodedQuery +
					"&resultset=catalog" +
					"&sort=popular" +
					"&spp=" + SPP +
					"&suppressSpellcheck=false";
		} catch (Exception e) {
			throw new RuntimeException("Error building search URL", e);
		}
	}

	/**
	 * Извлекает ID категории из query параметра
	 * @param query строка вида "cat=10318" или "subject=2791"
	 * @return ID категории или null
	 */
	public String extractCategoryId(String query) {
		if (query == null || query.isEmpty()) return null;
		
		// Пробуем извлечь cat=XXX
		if (query.contains("cat=")) {
			String[] parts = query.split("cat=");
			if (parts.length > 1) {
				String id = parts[1].split("&")[0].trim();
				return id;
			}
		}
		
		// Пробуем извлечь subject=XXX
		if (query.contains("subject=")) {
			String[] parts = query.split("subject=");
			if (parts.length > 1) {
				String id = parts[1].split("&")[0].trim();
				return id;
			}
		}
		
		return null;
	}

	/**
	 * Извлекает название категории из URL
	 * @param categoryUrl полный URL категории
	 * @return название категории или пустая строка
	 */
	public String extractCategoryName(String categoryUrl) {
		if (categoryUrl == null || categoryUrl.isEmpty()) return "";
		
		try {
			// Извлекаем последнюю часть URL после последнего /
			// Например: https://www.wildberries.ru/catalog/aksessuary/bizhuteriya/kole
			// -> kole
			String[] parts = categoryUrl.split("/");
			if (parts.length > 0) {
				String lastPart = parts[parts.length - 1];
				// Убираем параметры запроса, если есть
				if (lastPart.contains("?")) {
					lastPart = lastPart.split("\\?")[0];
				}
				return lastPart;
			}
		} catch (Exception e) {
			// Игнорируем ошибки
		}
		
		return "";
	}

	public String buildCardUrl(String article) {
		return "https://card.wb.ru/cards/v2/detail?appType=1&curr=rub&dest=-5923914&spp=30&ab_testing=false&nm=" + article;
	}
}


