package org.example.pipeline;

import com.google.gson.Gson;
import org.example.jsonmodel.Product;
import org.example.jsonmodel.Root;
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
    private static final java.util.concurrent.atomic.AtomicInteger errorLogCount = new java.util.concurrent.atomic.AtomicInteger(0);

    public PipelineManager(List<String[]> urls, Set<HttpCookie> cookies, ProductRouter router) {
		this.urls = urls;
        this.cookies = cookies != null ? cookies : new HashSet<>();
        this.router = router;
        this.urlBuilder = new org.example.wb.WbUrlBuilder();
		// Два пула потоков - по 330 потоков каждый (как в старом коде)
		this.executorService1 = Executors.newFixedThreadPool(330, r -> {
			Thread t = new Thread(r, "wb-parser-1-" + System.nanoTime());
			t.setDaemon(true);
			return t;
		});
		this.executorService2 = Executors.newFixedThreadPool(330, r -> {
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
		log.info("Pipeline started: {} categories, two parsers with 330 threads each, initial cookies: {}", 
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
		}, 0, 50, TimeUnit.MILLISECONDS); // Уменьшили задержку для ускорения
		
		// Парсер 2: запускается через 5 секунд, обрабатывает вторую половину
		scheduler.scheduleWithFixedDelay(() -> {
			if (!isRunning.get() || stopped) return;
			try {
				runParser(false, true); // version=false, reverse=true
			} catch (Throwable t) {
				log.error("parser-v2", t);
			}
		}, 5_000, 50, TimeUnit.MILLISECONDS); // Уменьшили задержку для ускорения
		
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
		log.info("Parser {}: {} pages, {} errors, {}ms, categories: {}", 
				version ? "v1" : "v2", pages.get(), errors.get(), time, halfUrls.size());
	}

	private void fetchCategorySync(String[] url, java.util.concurrent.atomic.AtomicInteger errors, 
	                                java.util.concurrent.atomic.AtomicInteger pages) {
		String shard = url[1];
		String query = url[2];
        String categoryLabel = url[0];
        int page = 0, increment = 0;
        boolean checkPage = true;
        
        // Извлекаем ID категории и название для нового endpoint
        String categoryId = urlBuilder.extractCategoryId(query);
        String categoryName = urlBuilder.extractCategoryName(categoryLabel);
        boolean useNewEndpoint = categoryId != null && !categoryName.isEmpty();
        
            try {
                do {
                    if (stopped) break;
                    
                    // Пробуем использовать новый endpoint, если доступен
                    String currentPage;
                    if (useNewEndpoint) {
                        currentPage = urlBuilder.buildSearchPageUrl(categoryId, categoryName, increment + 1);
                    } else {
                        currentPage = urlBuilder.buildCatalogPageUrl(shard, query, increment + 1);
                    }
                    
                    String jsonBody = sendPageSync(currentPage);
                
                if (jsonBody == null || jsonBody.isEmpty()) {
                    errors.incrementAndGet();
                    if (increment == 0 && errorLogCount.getAndIncrement() < 5) {
                        log.info("Empty response for category: {}, URL: {}, cookies: {}", 
                                categoryLabel, currentPage, cookies.size());
                    }
                    break;
                }
                
                Gson gson = new Gson();
                int numberCells = 0;
                int productsOnPage = 0;
                java.util.List<?> productsList = null;
                
                // Парсим в зависимости от формата
                if (useNewEndpoint) {
                    // Новый формат: /__internal/u-search/exactmatch
                    try {
                        org.example.jsonmodel.SearchRoot searchRoot = gson.fromJson(jsonBody, org.example.jsonmodel.SearchRoot.class);
                        if (searchRoot == null) {
                            errors.incrementAndGet();
                            if (increment == 0 && errorLogCount.getAndIncrement() < 5) {
                                log.info("Null searchRoot for category: {}, URL: {}", categoryLabel, currentPage);
                            }
                            break;
                        }
                        numberCells = searchRoot.total;
                        productsList = searchRoot.products;
                        productsOnPage = productsList != null ? productsList.size() : 0;
                    } catch (Exception e) {
                        errors.incrementAndGet();
                        if (increment == 0 && errorLogCount.getAndIncrement() < 5) {
                            log.info("JSON parse error (new format) for category: {}, URL: {}, error: {}", 
                                    categoryLabel, currentPage, e.getMessage());
                        }
                        break;
                    }
                } else {
                    // Старый формат: /catalog/.../v2/catalog
                    try {
                        Root root = gson.fromJson(jsonBody, Root.class);
                        if (root == null || root.data == null) {
                            errors.incrementAndGet();
                            if (increment == 0 && errorLogCount.getAndIncrement() < 5) {
                                log.info("Null root or data for category: {}, URL: {}", categoryLabel, currentPage);
                            }
                            break;
                        }
                        numberCells = root.data.total;
                        productsList = root.data.products;
                        productsOnPage = productsList != null ? productsList.size() : 0;
                    } catch (Exception e) {
                        errors.incrementAndGet();
                        if (increment == 0 && errorLogCount.getAndIncrement() < 5) {
                            log.info("JSON parse error (old format) for category: {}, URL: {}, error: {}", 
                                    categoryLabel, currentPage, e.getMessage());
                        }
                        break;
                    }
                }
            
            // Выводим URL первого JSON для каждой категории (для проверки парсинга)
            if (increment == 0) {
                System.out.println("=== CATEGORY: " + categoryLabel + " ===");
                System.out.println("Endpoint: " + (useNewEndpoint ? "NEW (search)" : "OLD (catalog)"));
                System.out.println("JSON URL: " + currentPage);
                System.out.println("Total products in category: " + numberCells);
                System.out.println("Products on this page: " + productsOnPage);
                System.out.println("=== END CATEGORY ===");
            }
            
            if (numberCells == 0) {
                break;
            }
            
            // Определяем количество страниц (только при первой итерации)
            int totalPages = 0;
            if (checkPage) {
                if (numberCells % 100 == 0) {
                    totalPages = numberCells / 100;
                } else {
                    totalPages = numberCells / 100 + 1;
                }
                page = totalPages;
                checkPage = false;
            } else {
                totalPages = page;
            }
            
            // Логируем прогресс по страницам (для категорий с несколькими страницами)
            if (totalPages > 1 && (increment == 0 || increment == totalPages - 1 || (increment + 1) % 10 == 0)) {
                log.info("Parsing category: {}, page: {}/{}, products on page: {}, total products: {}", 
                        categoryLabel, increment + 1, totalPages, productsOnPage, numberCells);
            } else if (totalPages == 1 && pages.get() % 100 == 0) {
                // Для одностраничных категорий логируем реже
                log.info("Parsed category: {}, products: {}", categoryLabel, numberCells);
            }
            
            // Обрабатываем продукты
            java.util.HashSet<String> seen = new java.util.HashSet<>();
            if (productsList != null) {
                for (Object productObj : productsList) {
                    try {
                        String article;
                        String itemName;
                        String feedBackSum;
                        String totalQuery;
                        String supplier;
                        int priceRub = 0;
                        
                        if (useNewEndpoint) {
                            // Новый формат: SearchProduct
                            org.example.jsonmodel.SearchProduct product = (org.example.jsonmodel.SearchProduct) productObj;
                            article = product.id > 0 ? String.valueOf(product.id) : "0";
                            itemName = product.name != null && !product.name.isEmpty() ? product.name : " ";
                            feedBackSum = String.valueOf(product.feedbackPoints);
                            totalQuery = String.valueOf(product.totalQuantity);
                            supplier = product.supplier != null && !product.supplier.isEmpty() ? product.supplier : " ";
                            
                            if (product.sizes != null && !product.sizes.isEmpty()) {
                                for (Size size : product.sizes) {
                                    if (size.price != null && size.price.product != 0) {
                                        priceRub = size.price.product / 100;
                                        break;
                                    }
                                }
                            }
                        } else {
                            // Старый формат: Product
                            Product product = (Product) productObj;
                            article = product.id != null ? product.id : "0";
                            itemName = product.name != null ? product.name : " ";
                            feedBackSum = product.feedbackPoints != null ? product.feedbackPoints : "0";
                            totalQuery = product.totalQuantity != null ? product.totalQuantity : "0";
                            supplier = product.supplier != null ? product.supplier : " ";
                            
                            if (product.sizes != null) {
                                for (Size size : product.sizes) {
                                    if (size.price != null && size.price.product != 0) {
                                        priceRub = size.price.product / 100;
                                        break;
                                    }
                                }
                            }
                        }
                        
                        if (!seen.add(article)) continue;
                        
                        productsFound.incrementAndGet();
                        router.routeProduct(itemName, String.valueOf(priceRub), feedBackSum, article, totalQuery, categoryLabel, supplier);
                    } catch (Exception e) {
                        // Игнорируем ошибки обработки отдельных продуктов
                        log.debug("Error processing product", e);
                    }
                }
            }
            
            pagesProcessed.incrementAndGet();
            pages.incrementAndGet();
            
            increment++;
        } while (increment < page && !stopped);
        } catch (Exception e) {
            errors.incrementAndGet();
            log.debug("Exception in fetchCategorySync for category: {}, error: {}", url[0], e.getMessage());
        }
	}

    private String sendPageSync(String url) {
        // Логируем предупреждение о пустых cookies только один раз
        if (cookies.isEmpty()) {
            int count = errorLogCount.getAndIncrement();
            if (count == 0) {
                log.warn("No cookies available! Cookies count: {}. This will be logged only once.", cookies.size());
            }
        }
        try {
            // Используем Jsoup как в старом коде
            Connection connectionPage = Jsoup.connect(url)
                    .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:132.0) Gecko/20100101 Firefox/132.0")
                    .method(Connection.Method.GET)
                    .ignoreContentType(true)
                    .timeout(10_000);
            
            // Добавляем cookies как в старом коде
            for (HttpCookie cookie : cookies) {
                connectionPage.cookie(cookie.getName(), cookie.getValue());
            }
            
            Connection.Response responsePage = connectionPage.execute();
            return responsePage.body();
        } catch (Exception e) {
            if (errorLogCount.get() < 5) {
                log.debug("Error fetching URL: {}, error: {}", url, e.getMessage());
            }
            return "";
        }
    }

}


