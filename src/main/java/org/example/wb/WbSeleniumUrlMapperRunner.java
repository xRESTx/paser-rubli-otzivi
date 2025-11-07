package org.example.wb;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * Запускает многопоточный маппинг URL категорий WB на JSON URL через Selenium
 * С циклической обработкой ошибок до полного успеха
 */
public class WbSeleniumUrlMapperRunner {
    private static final Logger log = LoggerFactory.getLogger(WbSeleniumUrlMapperRunner.class);
    
    private static final String ERROR_FILE = "wb_url_mapping_errors.txt";
    private static final String OUTPUT_FILE = "wb_url_mapping.txt";
    
    public static void main(String[] args) {
        // Параметры
        int threadCount = 10; // Количество потоков для парсинга
        
        // Если передан аргумент - используем его как количество потоков
        if (args.length > 0 && !args[0].isEmpty()) {
            try {
                threadCount = Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                log.warn("Invalid thread count argument: {}. Using default: {}", args[0], threadCount);
            }
        }
        
        log.info("=== Starting WB URL to JSON URL mapping with error retry loop ===");
        log.info("Error file: {}", ERROR_FILE);
        log.info("Output file: {} (will append successful results)", OUTPUT_FILE);
        log.info("Thread count: {}", threadCount);
        log.info("The process will continue until all URLs are successfully processed");
        
        int iteration = 0;
        int totalProcessed = 0;
        int totalSuccess = 0;
        
        // Цикл обработки ошибок
        while (true) {
            iteration++;
            log.info("");
            log.info("=== Iteration #{} ===", iteration);
            
            // Проверяем, существует ли файл ошибок и не пуст ли он
            Path errorPath = Paths.get(ERROR_FILE);
            if (!Files.exists(errorPath)) {
                log.info("Error file does not exist: {}. Starting fresh from Wildberries categories.", ERROR_FILE);
                // Если файла нет, загружаем категории из WB
                WbSeleniumUrlMapper mapper = new WbSeleniumUrlMapper(OUTPUT_FILE, threadCount);
                if (mapper.getCategoriesCount() == 0) {
                    log.error("No categories loaded from Wildberries. Exiting.");
                    break;
                }
                log.info("Loaded {} categories from Wildberries", mapper.getCategoriesCount());
                mapper.start();
                totalProcessed += mapper.getCategoriesCount();
                totalSuccess += mapper.getSuccessCount();
                
                // Проверяем, есть ли ошибки после первой итерации
                if (!Files.exists(errorPath) || isFileEmpty(errorPath)) {
                    log.info("No errors! All categories processed successfully.");
                    break;
                }
                continue; // Переходим к следующей итерации для обработки ошибок
            }
            
            // Проверяем, не пуст ли файл ошибок
            if (isFileEmpty(errorPath)) {
                log.info("Error file is empty. All URLs have been successfully processed!");
                break;
            }
            
            // Читаем количество строк в файле ошибок
            int errorCount = countLinesInFile(errorPath);
            log.info("Found {} URLs with errors in file: {}", errorCount, ERROR_FILE);
            
            // Создаем маппер для обработки ошибок
            WbSeleniumUrlMapper mapper = new WbSeleniumUrlMapper(ERROR_FILE, OUTPUT_FILE, threadCount);
            
            // Проверяем, что категории загружены
            if (mapper.getCategoriesCount() == 0) {
                log.warn("No categories loaded from error file. File might be empty or corrupted.");
                // Проверяем еще раз, может файл стал пустым
                if (isFileEmpty(errorPath)) {
                    log.info("Error file is now empty. All URLs processed!");
                    break;
                }
                log.error("Error file exists but no categories loaded. Exiting to avoid infinite loop.");
                break;
            }
            
            log.info("Loaded {} URLs from error file for processing", mapper.getCategoriesCount());
            
            // Добавляем обработчик завершения
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                log.info("Shutting down...");
                mapper.stop();
            }));
            
            // Запускаем обработку
            mapper.start();
            
            // Получаем статистику
            int processed = mapper.getCategoriesCount();
            int success = mapper.getSuccessCount();
            int errors = mapper.getErrorCount();
            
            totalProcessed += processed;
            totalSuccess += success;
            
            log.info("Iteration #{} completed: processed={}, success={}, errors={}", 
                    iteration, processed, success, errors);
            
            // Проверяем, есть ли еще ошибки
            if (!Files.exists(errorPath) || isFileEmpty(errorPath)) {
                log.info("No more errors! All URLs have been successfully processed.");
                break;
            }
            
            // Проверяем, не застряли ли мы (количество ошибок не уменьшилось)
            int newErrorCount = countLinesInFile(errorPath);
            if (newErrorCount >= errorCount) {
                log.warn("Warning: Error count did not decrease (was: {}, now: {}). " +
                        "Some URLs might be permanently failing.", errorCount, newErrorCount);
                // Продолжаем, но логируем предупреждение
            } else {
                log.info("Progress: Error count decreased from {} to {} ({} URLs fixed)", 
                        errorCount, newErrorCount, errorCount - newErrorCount);
            }
            
            // Небольшая пауза между итерациями
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Interrupted during pause between iterations");
                break;
            }
        }
        
        log.info("");
        log.info("=== Final Statistics ===");
        log.info("Total iterations: {}", iteration);
        log.info("Total URLs processed: {}", totalProcessed);
        log.info("Total successful: {}", totalSuccess);
        log.info("Results saved to: {}", OUTPUT_FILE);
        log.info("All processing completed!");
    }
    
    /**
     * Проверяет, пуст ли файл
     */
    private static boolean isFileEmpty(Path filePath) {
        try {
            if (!Files.exists(filePath)) {
                return true;
            }
            List<String> lines = Files.readAllLines(filePath);
            // Проверяем, есть ли непустые строки (игнорируем пустые и комментарии)
            for (String line : lines) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                    return false;
                }
            }
            return true;
        } catch (Exception e) {
            log.error("Error checking if file is empty: {}", filePath, e);
            return true; // В случае ошибки считаем файл пустым
        }
    }
    
    /**
     * Подсчитывает количество непустых строк в файле
     */
    private static int countLinesInFile(Path filePath) {
        try {
            if (!Files.exists(filePath)) {
                return 0;
            }
            List<String> lines = Files.readAllLines(filePath);
            int count = 0;
            for (String line : lines) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                    count++;
                }
            }
            return count;
        } catch (Exception e) {
            log.error("Error counting lines in file: {}", filePath, e);
            return 0;
        }
    }
}

