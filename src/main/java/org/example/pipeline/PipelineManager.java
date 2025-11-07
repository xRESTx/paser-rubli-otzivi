package org.example.pipeline;

import com.google.gson.Gson;
import org.example.jsonmodel.Size;
import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.HttpCookie;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

public class PipelineManager {
    private static final Logger log = LoggerFactory.getLogger(PipelineManager.class);
    private final AtomicLong pagesProcessed = new AtomicLong(0);
    private final AtomicLong productsFound = new AtomicLong(0);
    private final List<String[]> urls;
    private volatile Set<HttpCookie> cookies;
	private volatile boolean stopped = false;
    private final ProductRouter router;
    private final org.example.wb.WbUrlBuilder urlBuilder;
    private final ExecutorService executorService1;
    private final ExecutorService executorService2;
    private final int[] numberPagesProcessed = new int[2]; // Для двух парсеров
    private int delta = 0; // Для балансировки нагрузки

    public PipelineManager(List<String[]> urls, Set<HttpCookie> cookies, ProductRouter router) {
		this.urls = urls;
        this.cookies = cookies != null ? cookies : new HashSet<>();
        this.router = router;
        this.urlBuilder = new org.example.wb.WbUrlBuilder();
		// Два пула потоков - по 500 потоков каждый (увеличено для большей нагрузки на сеть)
		this.executorService1 = Executors.newFixedThreadPool(500, r -> {
			Thread t = new Thread(r, "wb-parser-1-" + System.nanoTime());
			t.setDaemon(true);
			return t;
		});
		this.executorService2 = Executors.newFixedThreadPool(500, r -> {
			Thread t = new Thread(r, "wb-parser-2-" + System.nanoTime());
			t.setDaemon(true);
			return t;
		});
	}

	public void stop() {
		stopped = true;
		executorService1.shutdown();
		executorService2.shutdown();
		try {
			if (!executorService1.awaitTermination(10, TimeUnit.SECONDS)) {
				executorService1.shutdownNow();
			}
			if (!executorService2.awaitTermination(10, TimeUnit.SECONDS)) {
				executorService2.shutdownNow();
			}
		} catch (InterruptedException e) {
			executorService1.shutdownNow();
			executorService2.shutdownNow();
			Thread.currentThread().interrupt();
		}
	}

    public void updateCookies(Set<HttpCookie> newCookies) {
        if (newCookies != null && !newCookies.isEmpty()) {
            this.cookies = new HashSet<>(newCookies);
            log.info("Cookies updated in PipelineManager: {} cookies", cookies.size());
        } else {
            log.warn("Attempted to update cookies with empty set");
        }
    }

	public void run(Supplier<Boolean> isRunning) {
		log.info("Pipeline started: {} categories, two parsers with 500 threads each, initial cookies: {}", 
				urls.size(), cookies.size());
		
		// Запускаем два парсера параллельно как в старом коде
		java.util.concurrent.ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
		
		// Парсер 1: запускается сразу, обрабатывает первую половину
		scheduler.scheduleWithFixedDelay(() -> {
			if (!isRunning.get() || stopped) return;
			try {
				runParser(true, true); // version=true, reverse=true
			} catch (Throwable t) {
				log.error("parser-v1", t);
			}
		}, 0, 10, TimeUnit.MILLISECONDS); // Минимальная задержка для максимальной нагрузки
		
		// Парсер 2: запускается через 2 секунды, обрабатывает вторую половину
		scheduler.scheduleWithFixedDelay(() -> {
			if (!isRunning.get() || stopped) return;
			try {
				runParser(false, true); // version=false, reverse=true
			} catch (Throwable t) {
				log.error("parser-v2", t);
			}
		}, 2_000, 10, TimeUnit.MILLISECONDS); // Минимальная задержка для максимальной нагрузки
		
		// Ждем пока работает
		while (isRunning.get() && !stopped) {
			try {
				Thread.sleep(1000);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				break;
			}
		}
		
		scheduler.shutdown();
		log.info("Pipeline stopped");
	}
	
	private void runParser(boolean version, boolean reverse) {
		long startTime = System.currentTimeMillis();
		java.util.concurrent.atomic.AtomicInteger errors = new java.util.concurrent.atomic.AtomicInteger(0);
		java.util.concurrent.atomic.AtomicInteger pages = new java.util.concurrent.atomic.AtomicInteger(0);
		
		// Балансировка нагрузки как в старом коде
		if ((numberPagesProcessed[0] - numberPagesProcessed[1]) > 50) {
			delta++;
		} else if ((numberPagesProcessed[1] - numberPagesProcessed[0]) > 50) {
			delta--;
		}
		
		int halfSize = urls.size() / 2;
		List<String[]> halfUrls;
		
		if (version) {
			halfUrls = new ArrayList<>(urls.subList(0, halfSize - delta));
		} else {
			halfUrls = new ArrayList<>(urls.subList(halfSize - delta, urls.size()));
		}
		
		if (reverse) {
			Collections.reverse(halfUrls);
		}
		
		ExecutorService executor = version ? executorService1 : executorService2;
		CountDownLatch latch = new CountDownLatch(halfUrls.size());
		
		// Запускаем задачи для своей половины категорий
		for (String[] url : halfUrls) {
			if (stopped) break;
			executor.submit(() -> {
				try {
					fetchCategorySync(url, errors, pages);
				} catch (Exception e) {
					errors.incrementAndGet();
					log.debug("Error processing category", e);
				} finally {
					latch.countDown();
				}
			});
		}
		
		// Ждем завершения всех задач
		try {
			latch.await(5, TimeUnit.MINUTES);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		
		// Сохраняем количество обработанных страниц
		if (version) {
			numberPagesProcessed[0] = pages.get();
		} else {
			numberPagesProcessed[1] = pages.get();
		}
		
		long time = System.currentTimeMillis() - startTime;
		// Логируем статистику по страницам за проход
		log.info("Parser {}: обработано страниц: {}, ошибок: {}, время: {}ms, категорий: {}", 
				version ? "v1" : "v2", pages.get(), errors.get(), time, halfUrls.size());
	}

	private void fetchCategorySync(String[] url, java.util.concurrent.atomic.AtomicInteger errors, 
	                                java.util.concurrent.atomic.AtomicInteger pages) {
		String categoryLabel = url[0];
		String query = url[2];
		// Название категории из JSON (4-й элемент массива, если есть)
		String categoryName = url.length > 3 ? url[3] : "";
        int page = 0, increment = 0;
        boolean checkPage = true;
        
        // Проверяем, является ли категория Food или Detyam для использования JSON URL из Map
        boolean isFood = router.isFoodCategory(categoryLabel);
        boolean isDetyam = router.isDetyamCategory(categoryLabel);
        String baseJsonUrl = null;
        
        if (isFood) {
            baseJsonUrl = router.getFoodJsonUrl(categoryLabel);
        } else if (isDetyam) {
            baseJsonUrl = router.getDetyamJsonUrl(categoryLabel);
        }
        
        // Если это Food или Detyam и есть JSON URL, используем его
        // Иначе используем стандартный способ построения URL
        String categoryId = null;
        if (baseJsonUrl == null || baseJsonUrl.isEmpty()) {
            // Извлекаем ID категории для нового endpoint
            categoryId = urlBuilder.extractCategoryId(query);
            // Если название категории не было в массиве, пытаемся извлечь из URL
            if (categoryName == null || categoryName.isEmpty()) {
                categoryName = urlBuilder.extractCategoryName(categoryLabel);
            }
            
            // Всегда используем новый endpoint, если есть ID категории
            if (categoryId == null || categoryId.isEmpty()) {
                errors.incrementAndGet();
                log.warn("No category ID found for category: {}, query: {}", categoryLabel, query);
                return;
            }
        }
        
            try {
                do {
                    if (stopped) break;
                    
                    String currentPage;
                    // Если есть baseJsonUrl (Food или Detyam), используем его и изменяем page
                    if (baseJsonUrl != null && !baseJsonUrl.isEmpty()) {
                        currentPage = router.changePageInUrl(baseJsonUrl, increment + 1);
                    } else {
                        // Всегда используем новый endpoint
                        currentPage = urlBuilder.buildSearchPageUrl(categoryId, categoryName, increment + 1);
                    }
                    
                    String jsonBody = sendPageSync(currentPage);
                
                if (jsonBody == null || jsonBody.isEmpty()) {
                    errors.incrementAndGet();
                    log.error("EMPTY RESPONSE: Category={}, Page={}, URL={}", categoryLabel, increment + 1, currentPage);
                    break;
                }
                
                Gson gson = new Gson();
                int numberCells = 0;
                java.util.List<?> productsList = null;
                
                // Парсим новый формат: /__internal/u-search/exactmatch
                try {
                    org.example.jsonmodel.SearchRoot searchRoot = gson.fromJson(jsonBody, org.example.jsonmodel.SearchRoot.class);
                    if (searchRoot == null) {
                        errors.incrementAndGet();
                        log.error("NULL ROOT: Category={}, Page={}, URL={}", categoryLabel, increment + 1, currentPage);
                        break;
                    }
                    // Используем методы getTotal() и getProducts() для поддержки обоих форматов
                    numberCells = searchRoot.getTotal();
                    productsList = searchRoot.getProducts();
                    
                    // Логируем информацию о формате JSON для диагностики
                    if (increment == 0) {
                        boolean hasDirectProducts = searchRoot.products != null;
                        boolean hasDataProducts = searchRoot.data != null && searchRoot.data.products != null;
                        boolean hasDirectTotal = searchRoot.total > 0;
                        boolean hasDataTotal = searchRoot.data != null && searchRoot.data.total > 0;
                        log.debug("JSON Format detected: Category={}, DirectProducts={}, DataProducts={}, DirectTotal={}, DataTotal={}, FinalTotal={}, FinalProducts={}", 
                                categoryLabel, hasDirectProducts, hasDataProducts, hasDirectTotal, hasDataTotal, 
                                numberCells, productsList != null ? productsList.size() : 0);
                    }
                } catch (com.google.gson.JsonSyntaxException e) {
                    errors.incrementAndGet();
                    log.error("JSON SYNTAX ERROR: Category={}, Page={}, URL={}, Error={}, JSON length={}", 
                            categoryLabel, increment + 1, currentPage, e.getMessage(), 
                            jsonBody != null ? jsonBody.length() : 0);
                    if (jsonBody != null && jsonBody.length() < 1000) {
                        log.error("JSON Body (first 1000 chars): {}", jsonBody.substring(0, Math.min(1000, jsonBody.length())));
                    }
                    break;
                } catch (Exception e) {
                    errors.incrementAndGet();
                    log.error("PARSE ERROR: Category={}, Page={}, URL={}, Error={}, StackTrace={}", 
                            categoryLabel, increment + 1, currentPage, e.getMessage(), 
                            java.util.Arrays.toString(e.getStackTrace()).substring(0, Math.min(500, java.util.Arrays.toString(e.getStackTrace()).length())));
                    break;
                }
            
            // Логируем категории с 0 товарами
            if (numberCells == 0) {
                if (increment == 0) {
                    log.warn("EMPTY CATEGORY: Category={}, URL={}, Total=0", categoryLabel, currentPage);
                } else {
                    log.warn("EMPTY PAGE: Category={}, Page={}, URL={}, Total=0", categoryLabel, increment + 1, currentPage);
                }
                break;
            }
            
            // Логируем если productsList null или пустой, но total > 0
            if ((productsList == null || productsList.isEmpty()) && numberCells > 0) {
                log.warn("NO PRODUCTS IN RESPONSE: Category={}, Page={}, Total={}, URL={}", 
                        categoryLabel, increment + 1, numberCells, currentPage);
            }
            
            // Обрабатываем АБСОЛЮТНО все страницы
            // Если товаров больше 100, значит есть следующая страница - продолжаем цикл
            // Если товаров <= 100, значит это последняя страница - заканчиваем после обработки
            // Определяем количество страниц (только при первой итерации)
            int totalPages = 0;
            if (checkPage) {
                // Всегда вычисляем количество страниц на основе общего количества товаров
                if (numberCells % 100 == 0) {
                    totalPages = numberCells / 100;
                } else {
                    totalPages = numberCells / 100 + 1;
                }
                page = totalPages;
                log.debug("Category {} has {} products, will process {} pages", categoryLabel, numberCells, totalPages);
                checkPage = false;
            } else {
                totalPages = page;
            }
            
            // Убрали логирование прогресса для уменьшения нагрузки на логирование
            
            // Обрабатываем продукты (новый формат: SearchProduct)
            java.util.HashSet<String> seen = new java.util.HashSet<>();
            int productsProcessed = 0;
            int productsSkipped = 0;
            if (productsList != null) {
                for (Object productObj : productsList) {
                    try {
                        org.example.jsonmodel.SearchProduct product = (org.example.jsonmodel.SearchProduct) productObj;
                        
                        if (product == null) {
                            productsSkipped++;
                            log.warn("NULL PRODUCT: Category={}, Page={}", categoryLabel, increment + 1);
                            continue;
                        }
                        
                        String article = product.id > 0 ? String.valueOf(product.id) : "0";
                        String itemName = product.name != null && !product.name.isEmpty() ? product.name : " ";
                        String feedBackSum = String.valueOf(product.feedbackPoints);
                        String totalQuery = String.valueOf(product.totalQuantity);
                        String supplier = product.supplier != null && !product.supplier.isEmpty() ? product.supplier : " ";
                        int priceRub = 0;
                        
                        if (product.sizes != null && !product.sizes.isEmpty()) {
                            for (Size size : product.sizes) {
                                if (size.price != null && size.price.product != 0) {
                                    priceRub = size.price.product / 100;
                                    break;
                                }
                            }
                        }
                        
                        if (!seen.add(article)) {
                            productsSkipped++;
                            continue;
                        }
                        
                        productsFound.incrementAndGet();
                        productsProcessed++;
                        router.routeProduct(itemName, String.valueOf(priceRub), feedBackSum, article, totalQuery, categoryLabel, supplier);
                    } catch (ClassCastException e) {
                        productsSkipped++;
                        log.error("PRODUCT CAST ERROR: Category={}, Page={}, ProductClass={}, Error={}", 
                                categoryLabel, increment + 1, productObj != null ? productObj.getClass().getName() : "null", e.getMessage());
                    } catch (Exception e) {
                        productsSkipped++;
                        log.error("PRODUCT PROCESSING ERROR: Category={}, Page={}, Error={}, StackTrace={}", 
                                categoryLabel, increment + 1, e.getMessage(),
                                java.util.Arrays.toString(e.getStackTrace()).substring(0, Math.min(300, java.util.Arrays.toString(e.getStackTrace()).length())));
                    }
                }
                
                // Логируем статистику обработки товаров
                if (increment == 0 || productsProcessed > 0 || productsSkipped > 0) {
                    log.debug("Products processed: Category={}, Page={}, Total={}, Processed={}, Skipped={}", 
                            categoryLabel, increment + 1, productsList.size(), productsProcessed, productsSkipped);
                }
            }
            
            pagesProcessed.incrementAndGet();
            pages.incrementAndGet();
            
            increment++;
        } while (increment < page && !stopped);
        } catch (Exception e) {
            errors.incrementAndGet();
            log.error("EXCEPTION in fetchCategorySync: Category={}, Error={}, StackTrace={}", 
                    url[0], e.getMessage(),
                    java.util.Arrays.toString(e.getStackTrace()).substring(0, Math.min(500, java.util.Arrays.toString(e.getStackTrace()).length())));
        }
	}

    private String sendPageSync(String url) {
        try {
            // Используем Jsoup как в старом коде
            Connection connectionPage = Jsoup.connect(url)
                    .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:132.0) Gecko/20100101 Firefox/132.0")
                    .method(Connection.Method.GET)
                    .ignoreContentType(true)
                    .timeout(10_000);
            
            // Добавляем cookies как в старом коде
            int cookieCount = 0;
            for (HttpCookie cookie : cookies) {
                connectionPage.cookie(cookie.getName(), cookie.getValue());
                cookieCount++;
            }
            
            if (cookieCount == 0) {
                log.debug("Sending request without cookies to: {}", url);
            }
            
            Connection.Response responsePage = connectionPage.execute();
            String body = responsePage.body();
            
            // Логируем если ответ пустой или очень короткий (может быть ошибка)
            if (body == null || body.isEmpty()) {
                log.warn("Empty response body from URL: {}, Status: {}, Cookies used: {}", 
                        url, responsePage.statusCode(), cookieCount);
            } else if (body.length() < 100 && !body.contains("{")) {
                log.warn("Suspiciously short response from URL: {}, Length: {}, Cookies used: {}, Body preview: {}", 
                        url, body.length(), cookieCount, body.substring(0, Math.min(100, body.length())));
            }
            
            return body;
        } catch (Exception e) {
            log.error("Error in sendPageSync for URL: {}, Error: {}", url, e.getMessage());
            return "";
        }
    }

}


