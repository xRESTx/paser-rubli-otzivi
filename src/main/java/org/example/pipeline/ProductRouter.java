package org.example.pipeline;

import com.google.gson.Gson;
import org.example.ProductInfo;
import org.example.jsonmodel.Product;
import org.example.jsonmodel.Root;
import org.example.jsonmodel.Size;
import org.jsoup.Connection;
import org.jsoup.Jsoup;

import java.io.BufferedWriter;
import java.io.IOException;
import java.net.HttpCookie;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.DecimalFormat;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.BlockingQueue;

import static java.nio.file.StandardOpenOption.APPEND;
import static java.nio.file.StandardOpenOption.CREATE;

public class ProductRouter {
	private final BlockingQueue<String> queue100;
	private final BlockingQueue<String> queue90;
	private final BlockingQueue<String> queue80;
	private final BlockingQueue<String> queueBig;
	private final BlockingQueue<String> queueMyChat;
	private final BlockingQueue<String> queueFood;
	private final BlockingQueue<String> queueDetyam;

	private final com.github.benmanes.caffeine.cache.Cache<String, Double> sentArticles100;
	private final com.github.benmanes.caffeine.cache.Cache<String, Double> sentArticles90;
	private final com.github.benmanes.caffeine.cache.Cache<String, Double> sentArticles80;
	private final com.github.benmanes.caffeine.cache.Cache<String, Double> sentArticlesBig;
	private final com.github.benmanes.caffeine.cache.Cache<String, Double> sentArticlesCommunity;
	private final com.github.benmanes.caffeine.cache.Cache<String, Double> sentArticlesFood;
	private final com.github.benmanes.caffeine.cache.Cache<String, Double> sentArticlesDetyam;
	private final com.github.benmanes.caffeine.cache.Cache<String, Double> test;

	private final Set<String> pidory;
	private final Set<String> urlsFood;
	private final Set<String> urlsDetyam;
	// Map для хранения категория -> JSON URL для Food и Detyam
	private final java.util.Map<String, String> categoryUrlMapFood;
	private final java.util.Map<String, String> categoryUrlMapDetyam;
	
	/**
	 * Получает JSON URL для категории Food
	 * @param category URL категории
	 * @return JSON URL или null если не найден
	 */
	public String getFoodJsonUrl(String category) {
		return categoryUrlMapFood.get(category);
	}
	
	/**
	 * Получает JSON URL для категории Detyam
	 * @param category URL категории
	 * @return JSON URL или null если не найден
	 */
	public String getDetyamJsonUrl(String category) {
		return categoryUrlMapDetyam.get(category);
	}
	
	/**
	 * Проверяет, есть ли JSON URL для категории Food
	 * @param category URL категории
	 * @return true если есть JSON URL для этой категории
	 */
	public boolean hasFoodJsonUrl(String category) {
		return categoryUrlMapFood.containsKey(category) && categoryUrlMapFood.get(category) != null;
	}
	
	/**
	 * Проверяет, есть ли JSON URL для категории Detyam
	 * @param category URL категории
	 * @return true если есть JSON URL для этой категории
	 */
	public boolean hasDetyamJsonUrl(String category) {
		return categoryUrlMapDetyam.containsKey(category) && categoryUrlMapDetyam.get(category) != null;
	}
	
	/**
	 * Проверяет, является ли категория Food
	 * @param category URL категории
	 * @return true если категория есть в Food
	 */
	public boolean isFoodCategory(String category) {
		return urlsFood.contains(category);
	}
	
	/**
	 * Проверяет, является ли категория Detyam
	 * @param category URL категории
	 * @return true если категория есть в Detyam
	 */
	public boolean isDetyamCategory(String category) {
		return urlsDetyam.contains(category);
	}
	
	/**
	 * Изменяет параметр page в URL
	 * @param url исходный URL
	 * @param page номер страницы
	 * @return URL с измененным параметром page
	 */
	public String changePageInUrl(String url, int page) {
		if (url == null || url.isEmpty()) {
			return url;
		}
		
		try {
			// Если URL содержит параметр page, заменяем его
			if (url.contains("page=")) {
				// Заменяем page=XXX на page=новый_номер
				url = url.replaceAll("page=\\d+", "page=" + page);
			} else {
				// Если параметра page нет, добавляем его
				if (url.contains("?")) {
					url += "&page=" + page;
				} else {
					url += "?page=" + page;
				}
			}
		} catch (Exception e) {
			// В случае ошибки возвращаем исходный URL
			org.slf4j.LoggerFactory.getLogger(ProductRouter.class)
				.debug("Error changing page in URL: {}", e.getMessage());
		}
		
		return url;
	}
	
	private final java.util.function.Supplier<Set<HttpCookie>> cookiesSupplier;

	public ProductRouter(
			BlockingQueue<String> queue100,
			BlockingQueue<String> queue90,
			BlockingQueue<String> queue80,
			BlockingQueue<String> queueBig,
			BlockingQueue<String> queueMyChat,
			BlockingQueue<String> queueFood,
			BlockingQueue<String> queueDetyam,
			com.github.benmanes.caffeine.cache.Cache<String, Double> sentArticles100,
			com.github.benmanes.caffeine.cache.Cache<String, Double> sentArticles90,
			com.github.benmanes.caffeine.cache.Cache<String, Double> sentArticles80,
			com.github.benmanes.caffeine.cache.Cache<String, Double> sentArticlesBig,
			com.github.benmanes.caffeine.cache.Cache<String, Double> sentArticlesCommunity,
			com.github.benmanes.caffeine.cache.Cache<String, Double> sentArticlesFood,
			com.github.benmanes.caffeine.cache.Cache<String, Double> sentArticlesDetyam,
			com.github.benmanes.caffeine.cache.Cache<String, Double> test,
			Set<String> pidory,
			Set<String> urlsFood,
			Set<String> urlsDetyam,
			java.util.function.Supplier<Set<HttpCookie>> cookiesSupplier
	) {
		this(queue100, queue90, queue80, queueBig, queueMyChat, queueFood, queueDetyam,
				sentArticles100, sentArticles90, sentArticles80, sentArticlesBig,
				sentArticlesCommunity, sentArticlesFood, sentArticlesDetyam, test,
				pidory, urlsFood, urlsDetyam, null, null, cookiesSupplier);
	}
	
	public ProductRouter(
			BlockingQueue<String> queue100,
			BlockingQueue<String> queue90,
			BlockingQueue<String> queue80,
			BlockingQueue<String> queueBig,
			BlockingQueue<String> queueMyChat,
			BlockingQueue<String> queueFood,
			BlockingQueue<String> queueDetyam,
			com.github.benmanes.caffeine.cache.Cache<String, Double> sentArticles100,
			com.github.benmanes.caffeine.cache.Cache<String, Double> sentArticles90,
			com.github.benmanes.caffeine.cache.Cache<String, Double> sentArticles80,
			com.github.benmanes.caffeine.cache.Cache<String, Double> sentArticlesBig,
			com.github.benmanes.caffeine.cache.Cache<String, Double> sentArticlesCommunity,
			com.github.benmanes.caffeine.cache.Cache<String, Double> sentArticlesFood,
			com.github.benmanes.caffeine.cache.Cache<String, Double> sentArticlesDetyam,
			com.github.benmanes.caffeine.cache.Cache<String, Double> test,
			Set<String> pidory,
			Set<String> urlsFood,
			Set<String> urlsDetyam,
			java.util.Map<String, String> categoryUrlMapFood,
			java.util.Map<String, String> categoryUrlMapDetyam,
			java.util.function.Supplier<Set<HttpCookie>> cookiesSupplier
	) {
		this.queue100 = queue100;
		this.queue90 = queue90;
		this.queue80 = queue80;
		this.queueBig = queueBig;
		this.queueMyChat = queueMyChat;
		this.queueFood = queueFood;
		this.queueDetyam = queueDetyam;
		this.sentArticles100 = sentArticles100;
		this.sentArticles90 = sentArticles90;
		this.sentArticles80 = sentArticles80;
		this.sentArticlesBig = sentArticlesBig;
		this.sentArticlesCommunity = sentArticlesCommunity;
		this.sentArticlesFood = sentArticlesFood;
		this.sentArticlesDetyam = sentArticlesDetyam;
		this.test = test;
		this.pidory = pidory;
		this.urlsFood = urlsFood;
		this.urlsDetyam = urlsDetyam;
		this.categoryUrlMapFood = categoryUrlMapFood != null ? categoryUrlMapFood : new java.util.concurrent.ConcurrentHashMap<>();
		this.categoryUrlMapDetyam = categoryUrlMapDetyam != null ? categoryUrlMapDetyam : new java.util.concurrent.ConcurrentHashMap<>();
		this.cookiesSupplier = cookiesSupplier;
	}

	public void routeProduct(String itemName, String itemCost, String itemFeedBackCost, String article, String totalQuery, String category, String supplier) throws IOException, InterruptedException {
		// Проверка supplier как в старом коде
		if (supplier != null && pidory.contains(supplier)) {
			return;
		}
		if (Objects.equals(itemCost, "0")) {
			itemCost = String.valueOf(hasFeedbackPoints(article));
		}
		double percent = Double.parseDouble(itemFeedBackCost) / Double.parseDouble(itemCost);
		ProductInfo productInfo = new ProductInfo();
		Double old100 = sentArticles100.getIfPresent(article);
		Double old90 = sentArticles90.getIfPresent(article);
		Double old80 = sentArticles80.getIfPresent(article);
		Double oldBig = sentArticlesBig.getIfPresent(article);
		Double oldCommunity = sentArticlesCommunity.getIfPresent(article);
		Double oldFood = sentArticlesFood.getIfPresent(article);
		Double oldDetyam = sentArticlesDetyam.getIfPresent(article);
		Double tests = test.getIfPresent(article);
		boolean absent = old100 == null && old90 == null && old80 == null && oldBig == null;
		boolean changed = (old100 != null && Math.abs(old100 - percent) > 0.1) ||
				(old90 != null && Math.abs(old90 - percent) > 0.1) ||
				(old80 != null && Math.abs(old80 - percent) > 0.1) ||
				(oldBig != null && Math.abs(oldBig - percent) > 0.1);
//		if (tests == null || Math.abs(tests - percent) > 0.01) {
//			try (BufferedWriter writer = Files.newBufferedWriter(Path.of("test.txt"), CREATE, APPEND)) {
//				DateTimeFormatter dtf = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss");
//				LocalDateTime now = LocalDateTime.now();
//				writer.write(article + " " + dtf.format(now) + "\t");
//				test.put(article, percent);
//			}
//		}
//		if (absent || changed) {
//			String message;
//			if (((percent > 0.49 && Integer.parseInt(itemFeedBackCost) >= 1000 && Integer.parseInt(itemFeedBackCost) < 2500)
//					|| (percent > 0.59 && Integer.parseInt(itemFeedBackCost) >= 699 && Integer.parseInt(itemFeedBackCost) < 1000 && percent < 0.9)
//					|| (percent >= 0.4 && Integer.parseInt(itemFeedBackCost) >= 2500))) {
//				message = createMessage(itemName, itemCost, itemFeedBackCost, article, percent, totalQuery);
//				queueBig.add(message);
//				productInfo.setquantity(String.valueOf(Integer.parseInt(totalQuery)));
//				productInfo.settime(System.currentTimeMillis());
//			}
//			if (percent >= 1) {
//				message = createMessage(itemName, itemCost, itemFeedBackCost, article, percent, totalQuery);
//				queue100.add(message);
//				productInfo.setquantity(String.valueOf(Integer.parseInt(totalQuery)));
//				productInfo.settime(System.currentTimeMillis());
//			} else if (percent >= 0.9) {
//				message = createMessage(itemName, itemCost, itemFeedBackCost, article, percent, totalQuery);
//				queue90.add(message);
//				productInfo.setquantity(String.valueOf(Integer.parseInt(totalQuery)));
//				productInfo.settime(System.currentTimeMillis());
//			} else if (percent >= 0.8) {
//				message = createMessage(itemName, itemCost, itemFeedBackCost, article, percent, totalQuery);
//				queue80.add(message);
//				productInfo.setquantity(String.valueOf(Integer.parseInt(totalQuery)));
//				productInfo.settime(System.currentTimeMillis());
//			}
//		}
		// Отправляем в community только если процент больше определенного значения
		// или если процент изменился более чем на 1
		if ((oldCommunity == null || Math.abs(oldCommunity - percent) > 1) && percent > 0.01) {
			queueMyChat.add(createMessage(itemName, itemCost, itemFeedBackCost, article, percent, totalQuery));
			sentArticlesCommunity.put(article, percent);
		}
		
		// Проверяем категории Food и Detyam
		// Если товар проходит по критериям стоимости и категория есть в Food/Detyam, отправляем в соответствующие очереди
//		if ((oldFood == null || Math.abs(oldFood - percent) > 0.1) && urlsFood.contains(category)) {
//			if (percent >= 0.45) {
//				String message = createMessage(itemName, itemCost, itemFeedBackCost, article, percent, totalQuery);
//				queueFood.add(message);
//				sentArticlesFood.put(article, percent);
//			}
//		}
//		if ((oldDetyam == null || Math.abs(oldDetyam - percent) > 0.1) && urlsDetyam.contains(category)) {
//			if (percent >= 0.5) {
//				String message = createMessage(itemName, itemCost, itemFeedBackCost, article, percent, totalQuery);
//				queueDetyam.add(message);
//				sentArticlesDetyam.put(article, percent);
//			}
//		}
	}

	public List<String> repeatCheck(String article) {
		List<String> sent = new ArrayList<>();
		try {
			String jsonUrl = "https://card.wb.ru/cards/v2/detail?appType=1&curr=rub&dest=-5923914&spp=30&ab_testing=false&nm=" + article;
			Connection connection = Jsoup.connect(jsonUrl)
					.userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:132.0) Gecko/20100101 Firefox/132.0")
					.method(Connection.Method.GET)
					.ignoreContentType(true);
			for (HttpCookie cookie : cookiesSupplier.get()) {
				connection.cookie(cookie.getName(), cookie.getValue());
			}
			Connection.Response response = connection.execute();
			String json = response.body();
			Root root = new Gson().fromJson(json, Root.class);
			for (Product product : root.data.products) {
				if (product.feedbackPoints != null && product.totalQuantity != null && !product.totalQuantity.equals("0")) {
					String feedBackSum = product.feedbackPoints;
					String itemName = product.name != null ? product.name : " ";
					String totalQuery = product.totalQuantity;
					if (product.sizes != null) {
						for (Size size : product.sizes) {
							if (size.price != null && size.price.product != 0) {
								int priceRub = size.price.product / 100;
								sent.add(itemName);
								sent.add(String.valueOf(priceRub));
								sent.add(feedBackSum);
								sent.add(totalQuery);
								break;
							}
						}
					}
				}
			}
			return sent;
		} catch (Exception ignored) {
		}
		return sent;
	}

	public double hasFeedbackPoints(String url1) throws IOException {
		String card = "https://card.wb.ru/cards/v2/detail?appType=1&curr=rub&dest=-5923914&spp=30&ab_testing=false&nm=" + url1;
		Connection connection = Jsoup.connect(card)
				.userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:132.0) Gecko/20100101 Firefox/132.0")
				.method(Connection.Method.GET)
				.ignoreContentType(true);
		for (HttpCookie cookie : cookiesSupplier.get()) {
			connection.cookie(cookie.getName(), cookie.getValue());
		}
		Connection.Response response = connection.execute();
		String json = response.body();
		Root root = new Gson().fromJson(json, Root.class);
		for (Product product : root.data.products) {
			if (product.feedbackPoints != null && !product.feedbackPoints.equals("0")) {
				double total = 1;
				if (product.sizes != null) {
					for (Size size : product.sizes) {
						if (size.price != null && size.price.product != 0) {
							total = (double) size.price.product / 100;
							break;
						}
					}
				}
				return Double.parseDouble(product.feedbackPoints) / total;
			}
		}
		return 0;
	}

	public String createMessage(String itemName, String itemCost, String itemFeedBackCost, String article, Double percent, String totalQuery) {
		String href = "https://www.wildberries.ru/catalog/" + article + "/detail.aspx";
		DecimalFormat df = new DecimalFormat("#.##");
		itemName = itemName.replace(":", "");
		return article + "~~" + itemName + "\n\uD83D\uDCB8Стоимость " + itemCost + "\u20BD\n" +
				"\uD83C\uDFB0Кешбэк " + itemFeedBackCost + "\u20BD\n " +
				"\uD83D\uDCAFПроцент выгоды " + df.format(percent * 100) + "%\n" +
				"\uD83C\uDFB2Количество " + totalQuery + "\n" + href + "~~" + percent;
	}
}


