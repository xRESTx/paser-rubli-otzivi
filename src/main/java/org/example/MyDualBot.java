package org.example;

import com.google.gson.*;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import com.pengrad.telegrambot.TelegramBot;
import com.pengrad.telegrambot.model.request.ParseMode;
import com.pengrad.telegrambot.request.SendMessage;
import com.pengrad.telegrambot.request.SendPhoto;
import com.pengrad.telegrambot.response.SendResponse;

import org.example.jsonmodel.Product;
import org.example.jsonmodel.Root;
import org.example.jsonmodel.SearchResponse;
import org.example.jsonmodel.Size;
import org.example.jsonmodel.UrlFetcher;
import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

import java.io.*;
import java.net.*;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.DecimalFormat;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.io.BufferedWriter;
import java.io.IOException;
import java.util.concurrent.*;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import static java.nio.file.StandardOpenOption.*;

public class MyDualBot extends TelegramLongPollingBot {
    private static final String FILE_PATH = "sent_articles";
    private static final String FILE_PATH_COMMUNITY = "sent_articles_community.txt";

    private static final Logger log = LoggerFactory.getLogger(MyDualBot.class);

    private ScheduledExecutorService SCHEDULER;

    private static final Cache<String, Double> sentArticles100 =
            Caffeine.newBuilder().maximumSize(Long.MAX_VALUE).build();
    private static final Cache<String, Double> sentArticles90 =
            Caffeine.newBuilder().maximumSize(Long.MAX_VALUE).build();
    private static final Cache<String, Double> sentArticles80 =
            Caffeine.newBuilder().maximumSize(Long.MAX_VALUE).build();
    private static final Cache<String, Double> sentArticlesBig =
            Caffeine.newBuilder().maximumSize(Long.MAX_VALUE).build();
    private static final Cache<String, Double> sentArticlesCommunity =
            Caffeine.newBuilder().maximumSize(Long.MAX_VALUE).build();
    private static final Cache<String, Double> sentArticlesFood =
            Caffeine.newBuilder().maximumSize(Long.MAX_VALUE).build();
    private static final Cache<String, Double> sentArticlesDetyam =
            Caffeine.newBuilder().maximumSize(Long.MAX_VALUE).build();
    private static final Cache<String, Double> test =
            Caffeine.newBuilder().maximumSize(Long.MAX_VALUE).build();
    // Кэш для отправленных в free chat товаров (для предотвращения дублирования)
    private static final Cache<String, Long> sentArticlesFree =
            Caffeine.newBuilder()
                    .maximumSize(Long.MAX_VALUE)
                    .expireAfterWrite(24, TimeUnit.HOURS) // Автоматически удаляем через 24 часа
                    .build();

    private static final BlockingQueue<String> queue100 = new LinkedBlockingQueue<>();
    private static final BlockingQueue<String> queue90 = new LinkedBlockingQueue<>();
    private static final BlockingQueue<String> queue80 = new LinkedBlockingQueue<>();
    private static final BlockingQueue<String> queueBig = new LinkedBlockingQueue<>();
    private static final BlockingQueue<String> queueMyChat = new LinkedBlockingQueue<>();
    private static final BlockingQueue<String> queueFood = new LinkedBlockingQueue<>();
    private static final BlockingQueue<String> queueDetyam = new LinkedBlockingQueue<>();
//    private static final BlockingQueue<ProductInfo> queueStrippingLazar = new LinkedBlockingQueue<>();
    private static final BlockingQueue<String> queueFree = new LinkedBlockingQueue<>();


    static List<String[]> urls = new ArrayList<>();
    // Новый формат: список SearchUrlInfo из SearchUrlGenerator
    static List<SearchUrlGenerator.SearchUrlInfo> searchUrls = new ArrayList<>();
    private static Set<String> urlsFood = ConcurrentHashMap.newKeySet();
    private static Set<String> urlsDetyam = ConcurrentHashMap.newKeySet();
    
    // Общий ExecutorService для непрерывного мониторинга категорий
    private static volatile ExecutorService sharedParserExecutor = null;
    private static final Object executorLock = new Object();

    private final TelegramBot pengradBot;

    private static volatile boolean running = false;
    private static volatile boolean isFree = true;
    private static Set<HttpCookie> Cookies;
    public static Set<String> pidory = ConcurrentHashMap.newKeySet();
    private final Set<Long> waitingForMessage = new HashSet<>();
    private final List<String> admin = new ArrayList<>(Arrays.asList("1027094894", "1039378955","5392268853"));
    private final List<String> worker = new ArrayList<>(Arrays.asList("466086607","1039378955"));

    static Map<String, ProductInfo> mapOnSent = new HashMap<>();
    static Map<String, Long> mapOnSentFree = new HashMap<>();

    static int[] numberPagesProcessed = new int[2];
    
    // Глобальные счетчики статистики для обоих парсеров
    private static final AtomicInteger[] globalProductsFound = new AtomicInteger[] {
        new AtomicInteger(0), new AtomicInteger(0)
    };
    private static final AtomicInteger[] globalProductsProcessed = new AtomicInteger[] {
        new AtomicInteger(0), new AtomicInteger(0)
    };
    private static final AtomicInteger[] globalProductsSkipped = new AtomicInteger[] {
        new AtomicInteger(0), new AtomicInteger(0)
    };
    private static final AtomicInteger[] globalPagesProcessed = new AtomicInteger[] {
        new AtomicInteger(0), new AtomicInteger(0)
    };
    private static final AtomicInteger[] globalCategoriesCompleted = new AtomicInteger[] {
        new AtomicInteger(0), new AtomicInteger(0)
    };
    private static final AtomicInteger[] globalCategoriesErrors = new AtomicInteger[] {
        new AtomicInteger(0), new AtomicInteger(0)
    };
    private static volatile long globalStartTime = System.currentTimeMillis();
    
    // Время начала парсинга для каждого парсера (для отслеживания времени обработки пакетов)
    private static final long[] parserStartTime = new long[] {
        System.currentTimeMillis(), System.currentTimeMillis()
    };
    
    // Время начала текущего цикла для каждого парсера
    private static final long[] cycleStartTime = new long[] {
        System.currentTimeMillis(), System.currentTimeMillis()
    };
    
    // Индексы текущей категории для каждой версии (для циклической обработки)
    private static final AtomicInteger[] currentCategoryIndex = new AtomicInteger[] {
        new AtomicInteger(0), new AtomicInteger(0)
    };
    
    // Счетчик циклов для каждого парсера (для отслеживания начала нового цикла)
    private static final AtomicInteger[] cycleCounter = new AtomicInteger[] {
        new AtomicInteger(0), new AtomicInteger(0)
    };
    
    // Флаг для отслеживания, был ли залогирован завершение цикла (чтобы не логировать несколько раз)
    private static final boolean[] cycleLogged = new boolean[] {
        false, false
    };
    
    // Флаг для отслеживания, запущен ли цикл (чтобы не отправлять категории повторно)
    private static final boolean[] cycleStarted = new boolean[] {
        false, false
    };
    
    // Future для отслеживания завершения цикла
    private static final Future<?>[] cycleTrackingFutures = new Future<?>[] {
        null, null
    };

    private static int delta = 0;
    public MyDualBot(String pengradBotToken) {
        this.pengradBot = new TelegramBot(pengradBotToken);
    }

    @Override
    public String getBotUsername() {
        return "shovel_seller_bot";//
    }

    @Override
    public String getBotToken() {
        return "7564492259:AAHJFWRqVvJQuuUIVd5584h8ePoFxsg7YVc";
//        return System.getenv("botToken");
    }

    @Override
    public void onUpdateReceived(Update update) {
        if (update.hasMessage() && update.getMessage().getText() != null) {
            String messageText = update.getMessage().getText();
            long chatId = update.getMessage().getChatId();
            String regex = "^(?:\\D*\\d\\D*){3,11}$";
            Pattern pattern = Pattern.compile(regex);
            if(worker.contains(String.valueOf(chatId))){
                if(pattern.matcher(messageText).matches()){
                    addMessage(chatId, messageText);
                }
            }

            if(admin.contains(String.valueOf(chatId))){
                if (waitingForMessage.contains(chatId)) {
                    try (BufferedWriter reader = new BufferedWriter(new FileWriter("pidory.txt", true))) {
                        pidory.add(messageText);
                        reader.write("\n" + messageText);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                    sendPengradMessage(String.valueOf(chatId),  "The text is written to a file!");
                    waitingForMessage.remove(chatId); // Убираем из режима ожидания
                    return;
                }
                switch (messageText) {
                    case "/run" -> startTask(chatId);
                    case "/stop" -> stopTask(chatId);
                    case "/clear" -> {
                    try {
                        clearTask(chatId);
                    } catch (IOException e) {
//                        e.printStackTrace();
                    }
                    }
                    case "/stopFree" -> {
                        isFree = false;
                        queueFree.clear();
                        queueFree.add("00");
                        sendPengradMessage(String.valueOf(chatId), "Free chat stopped");
                    }
                    case "/runFree" -> {
                        queueFree.clear();
                        isFree = true;
                        sendPengradMessage(String.valueOf(chatId), "Free chat started");
                    }
                    case "/pidory" -> {
                        waitingForMessage.add(chatId);
                        sendPengradMessage(String.valueOf(chatId),  "Send pidora");
                    }
                }
            }
        }
    }
    private void addMessage(long chatId, String messageText){
        List<String> sent = repeatCheck(messageText);
        if(sent.isEmpty()) {
            sendPengradMessage(String.valueOf(chatId),  "Кешбэка нет");
            return;
        }
        try {
            readTxtFile(sent.get(0), sent.get(1), sent.get(2), messageText, sent.get(3),"");
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }
        sendPengradMessage(String.valueOf(chatId),  "Сообщение отправлено ");
    }
    private void clearTask(long chatId) throws IOException {
        sendPengradMessage(String.valueOf(chatId),  "Start cleaning");
        
        if(running){
            stopTask(chatId);
        }
        
        // Очищаем все очереди
        queue100.clear();
        queue90.clear();
        queue80.clear();
        queueBig.clear();
        queueMyChat.clear();
        queueFood.clear();
        queueDetyam.clear();
        queueFree.clear();
        
        // Очищаем mapOnSent
        mapOnSent.clear();
        
        // Очищаем кэш отправленных в free chat товаров
        sentArticlesFree.invalidateAll();
        
        // Очищаем кэши и записываем в файлы
        hasPoint(sentArticles100, FILE_PATH + "100.txt");
        hasPoint(sentArticles90, FILE_PATH + "90.txt");
        hasPoint(sentArticles80, FILE_PATH + "80.txt");
        hasPoint(sentArticlesBig, FILE_PATH + "Big.txt");
        hasPoint(sentArticlesCommunity, FILE_PATH_COMMUNITY);
        hasPoint(sentArticlesFood, FILE_PATH + "Food_products.txt");
        hasPoint(sentArticlesDetyam, FILE_PATH + "detyam_products.txt");
        
        // Сбрасываем счетчики
        totalProductsQueued.set(0);
        totalProductsSent.set(0);
        totalProductsFiltered.set(0);

        sendPengradMessage(String.valueOf(chatId),  "Cleaning is complete");
        startTask(chatId);
    }

    private final List<Future<?>> tasks = new ArrayList<>();

    private void startTask(long chatId) {
        if (running) {
            log.warn("Task start requested but already running");
            sendPengradMessage(String.valueOf(chatId), "Task is already running.");
            return;
        }
        sendPengradMessage(String.valueOf(chatId), "Start tasks.");

        // Закрой предыдущий пул потоков, если он существует
        if (SCHEDULER != null) {
            SCHEDULER.shutdown();
            try {
                if (!SCHEDULER.awaitTermination(60, TimeUnit.SECONDS)) {
                    SCHEDULER.shutdownNow();
                    if (!SCHEDULER.awaitTermination(60, TimeUnit.SECONDS)) {
                    }
                }
            } catch (InterruptedException e) {
                SCHEDULER.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        // Создай новый пул потоков
        SCHEDULER = Executors.newScheduledThreadPool(
                20,
                new ThreadFactory() {
                    private final AtomicInteger idx = new AtomicInteger();
                    @Override
                    public Thread newThread(Runnable r) {
                        Thread t = new Thread(r, "wb-sched-" + idx.incrementAndGet());
                        t.setDaemon(true);
                        t.setUncaughtExceptionHandler(
                                (th, ex) -> log.error("Thread {} died", th.getName(), ex));
                        return t;
                    }
                });
        pidory = readPidora("pidory.txt");
        readSentArticlesToCache(FILE_PATH + "100.txt", sentArticles100);
        readSentArticlesToCache(FILE_PATH + "90.txt", sentArticles90);
        readSentArticlesToCache(FILE_PATH + "80.txt", sentArticles80);
        readSentArticlesToCache(FILE_PATH + "Big.txt", sentArticlesBig);
        readSentArticlesToCache(FILE_PATH + "Food_products.txt", sentArticlesFood);
        readSentArticlesToCache(FILE_PATH + "detyam_products.txt", sentArticlesDetyam);
        readSentArticlesToCache(FILE_PATH_COMMUNITY, sentArticlesCommunity);

        readSentArticlesToCache("test.txt", test);
        running = true;
        isFree = true;

        
        // Сбрасываем глобальные счетчики при старте
        globalStartTime = System.currentTimeMillis();
        for (int i = 0; i < 2; i++) {
            globalProductsFound[i].set(0);
            globalProductsProcessed[i].set(0);
            globalProductsSkipped[i].set(0);
            globalPagesProcessed[i].set(0);
            globalCategoriesCompleted[i].set(0);
            globalCategoriesErrors[i].set(0);
            parserStartTime[i] = System.currentTimeMillis();
            cycleStartTime[i] = System.currentTimeMillis();
            cycleCounter[i].set(0);
            cycleLogged[i] = false;
            cycleStarted[i] = false;
            cycleTrackingFutures[i] = null;
        }
        log.info("=================================================================================");
        log.info("[PARSER] PARSER STARTED! Start time: {}", new java.util.Date(globalStartTime));
        log.info("=================================================================================");
        
        // Запускаем глобальное логирование статистики
        startGlobalStatsLogger();

        // 100
        tasks.add(SCHEDULER.submit(() ->
        {
            try {
                runSender("100.txt", queue100, sentArticles100,"-1002340997107", 2,"-1002402655346");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();

            } catch (Throwable t) {

            }
        }));

        // 90
        tasks.add(SCHEDULER.submit(() ->
        {
            try {
                runSender("90.txt", queue90, sentArticles90, "-1002340997107", 4,"-1002446322077");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();

            } catch (Throwable t) {

            }
        }));

        tasks.add(SCHEDULER.submit(() ->
        {
            try {
                runSender("80.txt", queue80, sentArticles80, "-1002340997107", 6,"-1002305962649");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Throwable t) {

            }
        }));


        // Big
        tasks.add(SCHEDULER.submit(() ->
        {
            try {
                runSender("Big.txt", queueBig, sentArticlesBig, "-1002340997107", 13,"-1002290311759");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Throwable t) {

            }
        }));

        // Food
        tasks.add(SCHEDULER.submit(() ->
        {
            try {
                runSender("Food_products.txt", queueFood, sentArticlesFood, "-1002340997107", 89330,"-1002474423617");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Throwable t) {

            }
        }));
        //detyam
        tasks.add(SCHEDULER.submit(() ->
        {
            try {
                runSender("detyam_products.txt", queueDetyam, sentArticlesDetyam, "-1002340997107", 255209,null);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Throwable t) {

            }
        }));

        // Community
        tasks.add(SCHEDULER.submit(() ->
        {
            try {
                runSender("_community.txt", queueMyChat, sentArticlesCommunity, "-1002397733938", 8,null);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Throwable t) {

            }
        }));

//        // StrippingLazarSent
//        tasks.add(SCHEDULER.scheduleWithFixedDelay(
//                () -> {
//                    try {
//                        MyDualBot.sentStrippingLazarSent();
//                    } catch (InterruptedException e) {
//                        Thread.currentThread().interrupt();
//                        log.warn("Consumer StrippingLazarSent was interrupted, exiting", e);
//                    } catch (Throwable t) {
//                        log.error("Error in consumer StrippingLazarSent", t);
//                    }
//                },
//                0, 500, TimeUnit.SECONDS));

        // Free
        tasks.add(SCHEDULER.scheduleWithFixedDelay(
                () -> {
                    try {
                        MyDualBot.sentFree();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } catch (Throwable t) {

                    }
                },
                0, 5000, TimeUnit.SECONDS));

        tasks.add(SCHEDULER.scheduleWithFixedDelay(
                () -> {
                    try {
                        long now = System.currentTimeMillis();
                        mapOnSent.entrySet().removeIf(e -> {
                            long age = now - e.getValue().gettime();
                            if (age > TimeUnit.MINUTES.toMillis(2) + 20_000) { // 2 мин 20 сек
                                String article = e.getKey();
                                // Проверяем, не был ли товар уже отправлен в free chat
                                if (sentArticlesFree.getIfPresent(article) == null) {
                                    queueFree.add(article);
                                    totalProductsQueued.incrementAndGet();
                                }
                                return true;
                            }
                            return false;
                        });
                    } catch (Throwable t) {
                    }
                },
                0, 10, TimeUnit.SECONDS));

//        tasks.add(SCHEDULER.scheduleWithFixedDelay(
//                () -> {
//                    try {
//                        long now = System.currentTimeMillis();
//                        mapOnSentFree.entrySet().removeIf(e -> {
//                            long age = now - e.getValue();
//                            if (age > TimeUnit.MINUTES.toMillis(8)) {
//                                queueFree.add(e.getKey());
//                                return true;
//                            }
//                            return false;
//                        });
//                    } catch (Throwable t) {
//                        log.error("Error in Free cleaner", t);
//                    }
//                },
//                0, 30, TimeUnit.SECONDS));

        // --- два парсера с «переключением» направления ---
        tasks.add(SCHEDULER.scheduleWithFixedDelay(() -> {
            try { mainOld(true,  true); } catch (Throwable t) {  }
        }, 0, 100, TimeUnit.MILLISECONDS));

        tasks.add(SCHEDULER.scheduleWithFixedDelay(() -> {
            try { mainOld(false, true); } catch (Throwable t) { }
        }, 5_000, 100, TimeUnit.MILLISECONDS));
    }

    private void stopTask(long chatId) {
        if (!running) {
            sendPengradMessage(String.valueOf(chatId), "Task is not running.");
            return;
        }
        running = false;
        sendPengradMessage(String.valueOf(chatId), "Wait pls.");

        // «пустышки» в очередях, чтобы потоки-таскеры вышли из блокирующего take()
        queue100.add("0~~0~~0");
        queue90.add("0~~0~~0");
        queue80.add("0~~0~~0");
        queueBig.add("0~~0~~0");
        queueMyChat.add("0~~0~~0");
        queueFood.add("0~~0~~0");
        queueDetyam.add("0~~0~~0");
//        ProductInfo productInfo = new ProductInfo();
//        productInfo.settime(0L);
//        queueStrippingLazar.add(productInfo); // time=0 → выход
        queueFree.add("00");

        tasks.forEach(f -> f.cancel(false));

        SCHEDULER.shutdown();

        try {
            if (!SCHEDULER.awaitTermination(60, TimeUnit.SECONDS)) { // Увеличил время ожидания до 60 секунд
                SCHEDULER.shutdownNow();
                if (!SCHEDULER.awaitTermination(60, TimeUnit.SECONDS)) { // Добавил еще одну проверку
                }
            }
        } catch (InterruptedException e) {
            SCHEDULER.shutdownNow();
            Thread.currentThread().interrupt();
        }

        tasks.clear();
        
        // Закрываем общий ExecutorService для парсера
        synchronized (executorLock) {
            if (sharedParserExecutor != null && !sharedParserExecutor.isShutdown()) {
                sharedParserExecutor.shutdown();
                try {
                    if (!sharedParserExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                        sharedParserExecutor.shutdownNow();
                        if (!sharedParserExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                        }
                    }
                } catch (InterruptedException e) {
                    sharedParserExecutor.shutdownNow();
                    Thread.currentThread().interrupt();
                }
                sharedParserExecutor = null;
            }
        }
        sendPengradMessage(String.valueOf(chatId), "Task stopped.");
    }

    private void sendPengradMessage(String chatId, String messageText) {
        boolean sent = false;
        while (!sent) {
            SendMessage request = new SendMessage(chatId, messageText).parseMode(ParseMode.Markdown);
            SendResponse response = pengradBot.execute(request);

            if (response.isOk()) {
                sent = true;
            } else {
                int retryAfter = getRetryAfter(response);
                if (retryAfter > 0) {
                    try {
                        Thread.sleep(retryAfter * 1000L);
                    } catch (InterruptedException e) {
//                        e.printStackTrace();
                        break;
                    }
                } else {
                    break;
                }
            }
        }
    }

    private int getRetryAfter(SendResponse response) {
        String description = response.description();
        if (description != null && description.contains("retry after")) {
            String[] parts = description.split(" ");
            try {
                return Integer.parseInt(parts[parts.length - 1]);
            } catch (NumberFormatException e) {
//                e.printStackTrace();
            }
        }
        return 0;
    }

    public static void mainOld(boolean version, boolean reverse) {
        // Используем новый формат URL из SearchUrlGenerator, если доступен
        if (!searchUrls.isEmpty()) {
            mainOldNewFormat(version, reverse);
        } else if (!urls.isEmpty()) {
            // Fallback на старый формат
            mainOldLegacyFormat(version, reverse);
        } else {
        }
    }
    
    /**
     * Парсинг с использованием нового формата URL из SearchUrlGenerator
     * Использует общий ExecutorService для непрерывного мониторинга без блокировки
     */
    private static void mainOldNewFormat(boolean version, boolean reverse) {
        // Инициализируем общий ExecutorService, если его еще нет
        int versionIndex = version ? 0 : 1;
        synchronized (executorLock) {
            if (sharedParserExecutor == null || sharedParserExecutor.isShutdown()) {
                sharedParserExecutor = Executors.newFixedThreadPool(330, new ThreadFactory() {
                    private final AtomicInteger threadNumber = new AtomicInteger(1);
                    @Override
                    public Thread newThread(Runnable r) {
                        Thread t = new Thread(r, "parser-thread-" + threadNumber.getAndIncrement());
                        t.setDaemon(true);
                        return t;
                    }
                });
            }
        }
        
        ExecutorService executorService = sharedParserExecutor;
        AtomicInteger i = new AtomicInteger();
        AtomicInteger it = new AtomicInteger();
        AtomicInteger totalProductsFound = new AtomicInteger(0);      // Всего найдено товаров
        AtomicInteger totalProductsProcessed = new AtomicInteger(0);  // Обработано товаров
        AtomicInteger totalProductsSkipped = new AtomicInteger(0);   // Пропущено товаров (pidory)
        AtomicInteger totalPagesProcessed = new AtomicInteger(0);    // Обработано страниц
        int halfSize = searchUrls.size() / 2;
        List<SearchUrlGenerator.SearchUrlInfo> halfUrls;
        
        // Delta регулирует границу между двумя половинами списка для балансировки нагрузки
        if((numberPagesProcessed[0] - numberPagesProcessed[1]) > 50){
            delta++;
        } else if((numberPagesProcessed[1] - numberPagesProcessed[0]) > 50){
            delta--;
        }
        
        // Ограничиваем delta, чтобы не выходить за границы списка
        // Delta может сдвигать границу максимум на 10% от половины или 50 категорий
        int maxDelta = Math.min(50, halfSize / 10);
        delta = Math.max(-maxDelta, Math.min(maxDelta, delta));
        
        // Вычисляем границы для текущей версии
        int listStart, listEnd;
        if(version) {
            // Первая половина: от начала до (halfSize - delta)
            listStart = 0;
            listEnd = Math.max(1, Math.min(halfSize - delta, searchUrls.size()));
        } else {
            // Вторая половина: от (halfSize - delta) до конца
            listStart = Math.max(0, Math.min(halfSize - delta, searchUrls.size() - 1));
            listEnd = searchUrls.size();
        }
        
        // Создаем halfUrls из исходного списка
        halfUrls = new ArrayList<>(searchUrls.subList(listStart, listEnd));
        if(reverse){
            Collections.reverse(halfUrls);
        }
        
        // Обрабатываем ВСЕ категории сразу, без пакетов
        AtomicInteger currentIndex = currentCategoryIndex[versionIndex];
        int absoluteIndex = currentIndex.get();
        
        // Проверяем, нужно ли начать новый цикл
        boolean needNewCycle = false;
        if (halfUrls.isEmpty()) {
            // Список пуст - ничего не делаем
            return;
        } else if (absoluteIndex < listStart || absoluteIndex >= listEnd) {
            // Индекс вне границ - начинаем новый цикл
            needNewCycle = true;
            absoluteIndex = listStart;
            currentIndex.set(absoluteIndex);
        } else if (!cycleStarted[versionIndex]) {
            // Цикл еще не запущен - начинаем первый цикл
            needNewCycle = true;
        }
        
        // Если цикл уже запущен, проверяем, можно ли начать новый
        // Новый цикл можно начать, если прошло достаточно времени с начала предыдущего (минимум 30 секунд)
        if (cycleStarted[versionIndex] && !needNewCycle) {
            long timeSinceCycleStart = System.currentTimeMillis() - cycleStartTime[versionIndex];
            final long MIN_CYCLE_INTERVAL = 10 * 1000; // Минимум 10 секунд между циклами (для быстрого прохода)
            
            if (timeSinceCycleStart < MIN_CYCLE_INTERVAL) {
                // Слишком рано для нового цикла, ждем
                return;
            } else {
                // Прошло достаточно времени, можно начать новый цикл
                needNewCycle = true;
            }
        }
        
        // Если нужен новый цикл, проверяем завершение предыдущего
        if (needNewCycle) {
            int previousCycle = cycleCounter[versionIndex].get();
            
            // Если предыдущий цикл был завершен, логируем его завершение
            if (previousCycle > 0 && !cycleLogged[versionIndex]) {
                // Ждем завершения предыдущего цикла, если он еще не завершен
                if (cycleTrackingFutures[versionIndex] != null && !cycleTrackingFutures[versionIndex].isDone()) {
                    try {
                        cycleTrackingFutures[versionIndex].get(5, TimeUnit.SECONDS);
                    } catch (Exception e) {
                        // Игнорируем таймауты и ошибки
                    }
                }
            }
            
            // Увеличиваем счетчик циклов ПЕРЕД началом обработки
            cycleCounter[versionIndex].incrementAndGet();
            cycleStartTime[versionIndex] = System.currentTimeMillis();
            parserStartTime[versionIndex] = System.currentTimeMillis();
            cycleLogged[versionIndex] = false;
            cycleStarted[versionIndex] = true; // Помечаем, что цикл запущен
        }
        
        // Отправляем ВСЕ категории сразу в ExecutorService
        List<Future<?>> allFutures = new ArrayList<>();
        
        for (SearchUrlGenerator.SearchUrlInfo urlInfo : halfUrls) {
            Future<?> future = executorService.submit(() -> {
                try {
                    int increment = 0;
                    int totalProductsInCategory = 0; // Счетчик товаров для текущей категории
                    int expectedPages = 0; // Ожидаемое количество страниц (вычисляется на первой странице)
                    final int MAX_PAGES_PER_CATEGORY = 200; // Максимальное количество страниц на категорию (защита от бесконечного цикла)
                    long categoryStartTime = System.currentTimeMillis();
                    final long MAX_TIME_PER_CATEGORY = 2 * 60 * 1000; // Максимум 2 минуты на категорию (для быстрого прохода)
                    boolean categoryHasError = false; // Флаг для отслеживания ошибок в категории
                    int httpErrorCount = 0; // Счетчик HTTP ошибок

                    do {
                        // Защита от бесконечного цикла: проверяем максимальное количество страниц
                        if (increment >= MAX_PAGES_PER_CATEGORY) {
                            break;
                        }
                        
                        // Защита от зависания: проверяем время выполнения
                        if (System.currentTimeMillis() - categoryStartTime > MAX_TIME_PER_CATEGORY) {
                            // Не логируем предупреждение для каждой категории, чтобы не засорять логи
                            break;
                        }
                        // Формируем URL для текущей страницы
                        String currentPage = urlInfo.apiUrl;
                        int pageNumber = increment + 1; // Номер страницы (1-based)
                        
                        // Формируем URL с правильным номером страницы
                        if (currentPage.contains("page=")) {
                            // Если page уже есть, заменяем его
                            currentPage = currentPage.replaceAll("page=\\d+", "page=" + pageNumber);
                        } else {
                            // Если page нет, добавляем его
                            currentPage += (currentPage.contains("?") ? "&" : "?") + "page=" + pageNumber;
                        }
                        
                        // Используем те же заголовки, что и в SearchUrlGenerator
                        Connection connectionPage = Jsoup.connect(currentPage)
                                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 YaBrowser/25.8.0.0 Safari/537.36")
                                .method(Connection.Method.GET)
                                .ignoreContentType(true)
                                .timeout(15_000)
                                .followRedirects(true)
                                .header("Accept", "*/*")
                                .header("Accept-Language", "ru,en;q=0.9")
                                .header("Accept-Encoding", "gzip, deflate, br, zstd")
                                .header("Referer", "https://www.wildberries.ru/")
                                .header("Origin", "https://www.wildberries.ru")
                                .header("Connection", "keep-alive")
                                .header("Sec-Fetch-Dest", "empty")
                                .header("Sec-Fetch-Mode", "cors")
                                .header("Sec-Fetch-Site", "same-origin")
                                .header("Sec-Ch-Ua", "\"Not)A;Brand\";v=\"8\", \"Chromium\";v=\"138\", \"YaBrowser\";v=\"25.8\", \"Yowser\";v=\"2.5\"")
                                .header("Sec-Ch-Ua-Mobile", "?0")
                                .header("Sec-Ch-Ua-Platform", "\"Windows\"")
                                .header("Priority", "u=1, i")
                                .header("X-Requested-With", "XMLHttpRequest")
                                .header("X-Spa-Version", "13.12.0");
                        
                        // Извлекаем deviceid, authorization из cookies (как в SearchUrlGenerator)
                        String deviceId = null;
                        String authorizationToken = null;
                        
                        if (Cookies != null && !Cookies.isEmpty()) {
                            for (HttpCookie cookie : Cookies) {
                                String cookieName = cookie.getName();
                                String cookieValue = cookie.getValue();
                                
                                connectionPage.cookie(cookieName, cookieValue);
                                
                                // Ищем deviceid в cookies
                                if ("device_id_guru".equals(cookieName) && cookieValue != null && !cookieValue.isEmpty()) {
                                    deviceId = "site_" + cookieValue;
                                }
                                
                                // Ищем authorization токен
                                if (cookieName.toLowerCase().contains("token") || cookieName.toLowerCase().contains("auth")) {
                                    if (cookieValue != null && cookieValue.startsWith("eyJ")) {
                                        authorizationToken = cookieValue;
                                    }
                                }
                            }
                        }
                        
                        // Добавляем специальные заголовки из cookies
                        if (deviceId != null) {
                            connectionPage.header("Deviceid", deviceId);
                        }
                        
                        if (authorizationToken != null) {
                            connectionPage.header("Authorization", "Bearer " + authorizationToken);
                        }
                        
                        // Генерируем x-queryid (как в SearchUrlGenerator)
                        String queryId = "qid" + System.currentTimeMillis() + (int)(Math.random() * 1000000);
                        connectionPage.header("X-Queryid", queryId);
                        
                        Connection.Response responsePage = null;
                        String jsons = null;
                        try {
                            responsePage = connectionPage.execute();
                            
                            // Обрабатываем ответ с учетом различных типов сжатия (gzip, deflate, br/brotli)
                            byte[] responseBytes = responsePage.bodyAsBytes();
                            
                            if (responseBytes == null || responseBytes.length == 0) {
                                jsons = "";
                            } else {
                                String contentEncoding = responsePage.header("Content-Encoding");
                                boolean isGzip = false;
                                boolean isBrotli = false;
                                
                                if (contentEncoding != null) {
                                    String enc = contentEncoding.toLowerCase();
                                    if (enc.contains("gzip")) {
                                        isGzip = true;
                                    } else if (enc.contains("br") || enc.contains("brotli")) {
                                        isBrotli = true;
                                    } else if (enc.contains("deflate")) {
                                        isGzip = true;
                                    }
                                }
                                
                                // Проверяем magic numbers, если Content-Encoding не указан
                                if (!isGzip && !isBrotli && responseBytes.length >= 2) {
                                    if (responseBytes[0] == 0x1F && responseBytes[1] == (byte)0x8B) {
                                        isGzip = true;
                                    } else {
                                        // Проверяем, похоже ли на бинарные данные
                                        boolean looksLikeBinary = true;
                                        for (int j = 0; j < Math.min(10, responseBytes.length); j++) {
                                            byte b = responseBytes[j];
                                            if ((b >= 32 && b <= 126) || b == 9 || b == 10 || b == 13) {
                                                if (j > 0 || b == '{' || b == '[') {
                                                    looksLikeBinary = false;
                                                    break;
                                                }
                                            }
                                        }
                                        if (looksLikeBinary && responseBytes.length > 10) {
                                            isGzip = true;
                                        }
                                    }
                                }
                                
                                if (isBrotli) {
                                    // Обработка Brotli сжатия (как в SearchUrlGenerator)
                                    try {
                                        Class<?> loaderClass = Class.forName("com.aayushatharva.brotli4j.Brotli4jLoader");
                                        java.lang.reflect.Method ensureMethod = loaderClass.getMethod("ensureAvailability");
                                        ensureMethod.invoke(null);
                                        
                                        Class<?> streamClass = Class.forName("com.aayushatharva.brotli4j.decoder.BrotliInputStream");
                                        java.io.InputStream brotliIn = (java.io.InputStream) streamClass
                                            .getConstructor(java.io.InputStream.class)
                                            .newInstance(new java.io.ByteArrayInputStream(responseBytes));
                                        
                                        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                                        byte[] buffer = new byte[8192];
                                        int len;
                                        while ((len = brotliIn.read(buffer)) != -1) {
                                            baos.write(buffer, 0, len);
                                        }
                                        jsons = baos.toString("UTF-8");
                                        brotliIn.close();
                                        baos.close();
                                    } catch (Exception e) {
                                        // Если не получилось распаковать Brotli, пробуем как обычный текст
                                        jsons = new String(responseBytes, "UTF-8");
                                    }
                                } else if (isGzip) {
                                    // Обработка GZIP сжатия
                                    try {
                                        java.util.zip.GZIPInputStream gzipIn = new java.util.zip.GZIPInputStream(
                                            new java.io.ByteArrayInputStream(responseBytes));
                                        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                                        byte[] buffer = new byte[8192];
                                        int len;
                                        while ((len = gzipIn.read(buffer)) != -1) {
                                            baos.write(buffer, 0, len);
                                        }
                                        jsons = baos.toString("UTF-8");
                                        gzipIn.close();
                                        baos.close();
                                    } catch (Exception e) {
                                        // Если не получилось распаковать, пробуем как обычный текст
                                        jsons = new String(responseBytes, "UTF-8");
                                    }
                                } else {
                                    // Обычный текст без сжатия
                                    jsons = new String(responseBytes, "UTF-8");
                                }
                            }
                            
                            // Проверяем, что JSON не обрезан (должен начинаться с { и заканчиваться })
                            if (jsons != null && jsons.length() > 0) {
                                String trimmed = jsons.trim();
                                if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) {
                                    i.getAndIncrement();
                                    continue;
                                }
                            }
                            
                        } catch (org.jsoup.HttpStatusException httpEx) {
                            httpErrorCount++;
                            categoryHasError = true;
                            
                            // Если это первая страница и получили ошибку, категория недоступна - считаем это ошибкой
                            if (increment == 0) {
                                // Первая страница вернула ошибку - категория недоступна
                                break; // Прекращаем парсинг категории
                            } else {
                                break; // Прекращаем парсинг категории при ошибке на последующих страницах
                            }
                        } catch (Exception ex) {
                            i.getAndIncrement();
                            continue;
                        }

                        if (jsons == null || jsons.isEmpty()) {
                            i.getAndIncrement();
                            continue;
                        }

                        Gson gson = new Gson();
                        Root root = null;
                        SearchResponse searchResponse = null;
                        
                        try {
                            // Проверяем валидность JSON перед парсингом
                            if (jsons == null || jsons.trim().isEmpty()) {
                                i.getAndIncrement();
                                continue;
                            }
                            
                            // Пробуем сначала структуру Root (data.products)
                            try {
                                root = gson.fromJson(jsons, Root.class);
                            } catch (Exception e) {
                                // Если не получилось, пробуем SearchResponse (products в корне)
                                try {
                                    searchResponse = gson.fromJson(jsons, SearchResponse.class);
                                } catch (Exception e2) {
                                    // Если оба варианта не сработали, логируем ошибку
                                    i.getAndIncrement();
                                    continue;
                                }
                            }
                            
                            // Если root не валиден, пробуем SearchResponse
                            if (root == null || root.data == null) {
                                if (searchResponse == null) {
                                    try {
                                        searchResponse = gson.fromJson(jsons, SearchResponse.class);
                                    } catch (Exception e) {
                                        i.getAndIncrement();
                                        continue;
                                    }
                                }
                            }
                        } catch (Exception ex) {
                            i.getAndIncrement();
                            continue;
                        }
                        
                        List<Product> products = null;
                        int totalFromApi = 0; // Общее количество товаров в категории из API
                        
                        if (root != null && root.data != null) {
                            products = root.data.products;
                            totalFromApi = root.data.total;
                        } else if (searchResponse != null) {
                            products = searchResponse.products;
                            totalFromApi = searchResponse.total;
                            
                            // Если total = 0, но есть products, возможно total не пришел в ответе
                            // Пробуем извлечь total из JSON напрямую (fallback)
                            if (totalFromApi == 0 && products != null && !products.isEmpty() && increment == 0) {
                                try {
                                    com.google.gson.JsonObject jsonObject = gson.fromJson(jsons, com.google.gson.JsonObject.class);
                                    if (jsonObject != null && jsonObject.has("total")) {
                                        com.google.gson.JsonElement totalElement = jsonObject.get("total");
                                        if (totalElement != null && !totalElement.isJsonNull() && totalElement.isJsonPrimitive()) {
                                            totalFromApi = totalElement.getAsInt();
                                        }
                                    }
                                } catch (Exception e) {
                                    // Игнорируем ошибки парсинга
                                }
                            }
                        } else {
                            i.getAndIncrement();
                            continue;
                        }

                        if (products == null) {
                            products = new ArrayList<>();
                        }
                        
                        // Считаем количество товаров по фактически распарсенным данным
                        int productsOnPage = products.size();
                        
                        // ВАЖНО: Если узнали общее количество товаров из API, используем его для определения количества страниц
                        // Это нужно делать на КАЖДОЙ странице, так как totalFromApi может прийти не только на первой странице
                        if (totalFromApi > 0 && expectedPages == 0) {
                            // Вычисляем ожидаемое количество страниц (по 100 товаров на страницу)
                            expectedPages = (totalFromApi + 99) / 100; // Округление вверх
                        }
                        
                        // Если на странице нет товаров, прекращаем обработку этой категории
                        // Но только если это не первая страница (increment == 0 означает первую страницу)
                        if (productsOnPage == 0 && increment > 0) {
                            // Если это не первая страница и она пустая, значит мы дошли до конца
                            break;
                        }
                        
                        // Если первая страница пустая, тоже прекращаем (категория пустая)
                        if (productsOnPage == 0 && increment == 0) {
                            break;
                        }

                        List<String> newItem = new ArrayList<>();
                        int processedCount = 0;
                        int skippedCount = 0;
                        
                        for (Product product : products) {
                            try {
                                String article = product.getIdAsString();
                                if (article == null || article.isEmpty() || article.equals("0")) {
                                    // Пропускаем товары с некорректным ID
                                    skippedCount++;
                                    continue;
                                }
                                if (!newItem.contains(article)) {
                                    newItem.add(article);
                                    String itemName = product.name != null ? product.name : " ";
                                    String feedBackSum = product.getFeedbackPointsAsString();
                                    String totalQuery = product.totalQuantity != null ? product.totalQuantity : "0";
                                    String supplier = product.supplier != null ? product.supplier : " ";
                                    if(pidory.contains(supplier)){
                                        skippedCount++;
                                        continue;
                                    }
                                    int total = 0;
                                    if (product.sizes != null) {
                                        for (Size size : product.sizes) {
                                            if (size.price != null && size.price.product != 0) {
                                                total = size.price.product / 100;
                                                break;
                                            }
                                        }
                                    }
                                    readTxtFile(itemName, String.valueOf(total), feedBackSum, article, totalQuery, 
                                            urlInfo.catalogUrl != null ? urlInfo.catalogUrl : String.valueOf(urlInfo.categoryId));
                                    processedCount++;
                                }
                            } catch (Exception e) {
                                skippedCount++;
                                // Не прерываем обработку других товаров
                            }
                        }
                        
                        // Обновляем счетчики (локальные и глобальные)
                        // Используем фактическое количество товаров на странице (productsOnPage)
                        totalProductsInCategory += productsOnPage;
                        totalProductsFound.addAndGet(productsOnPage);
                        totalProductsProcessed.addAndGet(processedCount);
                        totalProductsSkipped.addAndGet(skippedCount);
                        totalPagesProcessed.incrementAndGet();
                        
                        // Обновляем глобальные счетчики
                        globalProductsFound[versionIndex].addAndGet(productsOnPage);
                        globalProductsProcessed[versionIndex].addAndGet(processedCount);
                        globalProductsSkipped[versionIndex].addAndGet(skippedCount);
                        globalPagesProcessed[versionIndex].incrementAndGet();
                        
                        // Определяем, нужно ли продолжать парсинг
                        // ВАЖНО: Сначала проверяем по expectedPages (если известно), чтобы обработать ВСЕ страницы
                        boolean shouldContinue = true;
                        
                        if (expectedPages > 0) {
                            // increment - это номер текущей страницы (0 = первая страница, 1 = вторая и т.д.)
                            // После обработки текущей страницы, следующая будет increment + 1
                            // Если следующая страница (increment + 1) превышает ожидаемое количество, останавливаемся
                            if (increment + 1 >= expectedPages) {
                                // Дошли до последней ожидаемой страницы - останавливаемся
                                shouldContinue = false;
                            } else if (productsOnPage == 0 && increment > 0) {
                                // Если на промежуточной странице нет товаров, но мы еще не дошли до expectedPages - это ошибка
                                // Останавливаемся, чтобы не зависнуть
                                shouldContinue = false;
                            }
                        } else {
                            // Не знаем ожидаемое количество страниц
                            // Если на странице меньше 100 товаров, значит это последняя страница
                            if (productsOnPage < 100) {
                                shouldContinue = false;
                            }
                            // Если на странице ровно 100 товаров, продолжаем (но с защитой от бесконечного цикла выше)
                        }
                        
                        if (!shouldContinue) {
                            break;
                        }
                        
                        // Если на странице ровно 100 товаров или мы еще не дошли до последней ожидаемой страницы, продолжаем
                        increment++;
                    } while (true); // Продолжаем до тех пор, пока не получим страницу с менее чем 100 товарами
                    
                    // Увеличиваем счетчик обработанных категорий только один раз после завершения парсинга всех страниц
                    it.getAndIncrement();
                    globalCategoriesCompleted[versionIndex].incrementAndGet();
                    
                    // Если были HTTP ошибки, учитываем их как ошибки категории
                    // Считаем ошибкой, если:
                    // 1. Ошибка была на первой странице (категория недоступна) И товаров не получено
                    // 2. Или было много HTTP ошибок (больше 1)
                    if (categoryHasError && httpErrorCount > 0) {
                        if ((increment == 0 && totalProductsInCategory == 0) || httpErrorCount > 1) {
                            // Категория недоступна (ошибка на первой странице без товаров) или много ошибок
                            globalCategoriesErrors[versionIndex].incrementAndGet();
                        }
                    }
                } catch (Exception e) {
                    i.getAndIncrement();
                    globalCategoriesErrors[versionIndex].incrementAndGet();
                }
            });
            allFutures.add(future);
        }
        // Асинхронно отслеживаем завершение задач (но не блокируем запуск нового цикла)
        cycleTrackingFutures[versionIndex] = executorService.submit(() -> {
            try {
                // Ждем завершения всех задач цикла с таймаутом
                int completedTasks = 0;
                int timeoutTasks = 0;
                int errorTasks = 0;
                long startWaitTime = System.currentTimeMillis();
                final long MAX_CYCLE_WAIT_TIME = 5 * 60 * 1000; // Максимум 5 минут ждем завершения цикла
                final long cycleEndTime = startWaitTime + MAX_CYCLE_WAIT_TIME;
                
                for (Future<?> future : allFutures) {
                    // Проверяем, не истек ли общий таймаут цикла
                    if (System.currentTimeMillis() > cycleEndTime) {
                        for (Future<?> remainingFuture : allFutures) {
                            if (!remainingFuture.isDone()) {
                                remainingFuture.cancel(true);
                                timeoutTasks++;
                            }
                        }
                        break;
                    }
                    
                    try {
                        // Короткий таймаут на каждую задачу (3 минуты, так как категория имеет лимит 2 минуты)
                        future.get(3, TimeUnit.MINUTES);
                        completedTasks++;
                    } catch (java.util.concurrent.TimeoutException e) {
                        timeoutTasks++;
                        future.cancel(true); // Отменяем зависшую задачу
                    } catch (Exception e) {
                        errorTasks++;
                    }
                }
            } catch (Exception e) {
            }
        });
        
        // НЕ сбрасываем флаг сразу - он будет сброшен через MIN_CYCLE_INTERVAL (30 секунд) 
        // Это позволяет новому циклу начаться через 30 секунд после начала предыдущего

        if(version){
            numberPagesProcessed[0] = numberPagesProcessed[0] + halfUrls.size();
        } else {
            numberPagesProcessed[1] = numberPagesProcessed[1] + halfUrls.size();
        }
    }
    
    /**
     * Запускает глобальное логирование статистики для всех парсеров
     * Логирует статистику раз в 5 минут
     */
    private static void startGlobalStatsLogger() {
        // Используем синхронизацию, чтобы запустить только один поток логирования
        synchronized (MyDualBot.class) {
            if (globalStatsLoggerRunning) {
                return; // Уже запущен
            }
            globalStatsLoggerRunning = true;
        }
        
        new Thread(() -> {
            try {
                while (running) {
                    Thread.sleep(60000); // 5 минут
                    
                    int v1Found = globalProductsFound[0].get();
                    int v1Processed = globalProductsProcessed[0].get();
                    int v1Skipped = globalProductsSkipped[0].get();
                    int v1Pages = globalPagesProcessed[0].get();
                    int v1Completed = globalCategoriesCompleted[0].get();
                    int v1Errors = globalCategoriesErrors[0].get();
                    
                    int v2Found = globalProductsFound[1].get();
                    int v2Processed = globalProductsProcessed[1].get();
                    int v2Skipped = globalProductsSkipped[1].get();
                    int v2Pages = globalPagesProcessed[1].get();
                    int v2Completed = globalCategoriesCompleted[1].get();
                    int v2Errors = globalCategoriesErrors[1].get();

                    System.out.println("Parser v1: "+v1Completed+" categories completed, "+v1Errors+" errors | Products: "+v1Found+" found, "+v1Processed+" processed, "+v1Skipped+" skipped | Pages: "+v1Pages);
                    System.out.println("Parser v1: "+v2Completed+" categories completed, "+v2Errors+" errors | Products: "+v2Found+" found, "+v2Processed+" processed, "+v2Skipped+" skipped | Pages: "+v2Pages);

                    // Сбрасываем счетчики после вывода статистики
                    globalStartTime = System.currentTimeMillis();
                    for (int i = 0; i < 2; i++) {
                        globalProductsFound[i].set(0);
                        globalProductsProcessed[i].set(0);
                        globalProductsSkipped[i].set(0);
                        globalPagesProcessed[i].set(0);
                        globalCategoriesCompleted[i].set(0);
                        globalCategoriesErrors[i].set(0);
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                synchronized (MyDualBot.class) {
                    globalStatsLoggerRunning = false;
                }
            }
        }, "global-stats-logger").start();
    }
    
    private static volatile boolean globalStatsLoggerRunning = false;
    
    /**
     * Парсинг со старым форматом URL (fallback)
     */
    private static void mainOldLegacyFormat(boolean version, boolean reverse) {
        ExecutorService executorService = Executors.newFixedThreadPool(330);
        long startTime = System.currentTimeMillis();
        AtomicInteger i= new AtomicInteger();
        AtomicInteger it= new AtomicInteger();
        int halfSize = urls.size() / 2;
        List<String[]> halfUrls;
        if((numberPagesProcessed[0] - numberPagesProcessed[1])>50){
            delta++;
        }else if((numberPagesProcessed[1] - numberPagesProcessed[0])>50){
            delta--;
        }
        if(version) {
            halfUrls = new ArrayList<>(urls.subList(0, halfSize - delta));
        } else {
            halfUrls = new ArrayList<>(urls.subList(halfSize-delta, urls.size()));
        }
        if(reverse){
            Collections.reverse(halfUrls);
        }

        for (String[] url : halfUrls) {
            executorService.submit(() -> {
                String currentPage;
                try {
                    int page=0, increment = 0;

                    boolean checkPage = true;

                    do{
                        currentPage = "https://catalog.wb.ru/catalog/" + url[1] + "/v2/catalog?ab_testing=false&appType=1&" + url[2] + "&curr=rub&dest=-5551776&ffeedbackpoints=1&page=" + (increment+1) + "&sort=priceup&priceU=0;800000&spp=30";
                        Connection connectionPage = Jsoup.connect(currentPage)
                                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:132.0) Gecko/20100101 Firefox/132.0")
                                .method(Connection.Method.GET)
                                .ignoreContentType(true)
                                .timeout(10_000);

                        for (HttpCookie cookie : Cookies) {
                            connectionPage.cookie(cookie.getName(), cookie.getValue());
                        }
                        Connection.Response responsePage = null;
                        String jsons = null;
                        try {
                            responsePage = connectionPage.execute();
                            jsons = responsePage.body();
                            
                        } catch (org.jsoup.HttpStatusException httpEx) {
                            // Не логируем 404 и 429 ошибки - это нормально (404 для несуществующих категорий, 429 для rate limiting)
                            int statusCode = httpEx.getStatusCode();
                            i.getAndIncrement();
                            continue;
                        } catch (Exception ex) {
                            i.getAndIncrement();
                            continue;
                        }

                        if (jsons == null || jsons.isEmpty()) {
                            i.getAndIncrement();
                            continue;
                        }

                        Gson gson = new Gson();
                        Root root = null;
                        try {
                            root = gson.fromJson(jsons, Root.class);
                        } catch (Exception ex) {
                            i.getAndIncrement();
                            continue;
                        }
                        
                        if (root == null || root.data == null) {
                            i.getAndIncrement();
                            continue;
                        }
                        
                        int numberCells = root.data.total;

                        if(numberCells == 0){
                            urls.remove(url);
                            continue;
                        }

                        List<String> newItem = new ArrayList<>();
                        for (Product product : root.data.products) {
                            try {
                                String article = product.getIdAsString();
                                if (article == null || article.isEmpty() || article.equals("0")) {
                                    // Пропускаем товары с некорректным ID
                                    continue;
                                }
                                if (!newItem.contains(article)) {
                                    newItem.add(article);
                                    String itemName = product.name != null ? product.name : " ";
                                    String feedBackSum = product.getFeedbackPointsAsString();
                                    String totalQuery = product.totalQuantity != null ? product.totalQuantity : "0";
                                    String supplier = product.supplier != null ? product.supplier : " ";
                                    if(pidory.contains(supplier)){
                                        continue;
                                    }
                                    int total = 0;
                                    if (product.sizes != null) {
                                        for (Size size : product.sizes) {
                                            if (size.price != null && size.price.product != 0) {
                                                total = size.price.product / 100;
                                                break;
                                            }
                                        }
                                    }
                                    readTxtFile(itemName, String.valueOf(total), feedBackSum, article, totalQuery, url[0]);
                                }
                            } catch (Exception e) {
                            }
                        }
                        int totalPage;

                        if (checkPage) {
                            if (numberCells % 100 == 0) {
                                totalPage = numberCells / 100;
                            } else {
                                totalPage = numberCells / 100;
                                totalPage++;
                            }
                            page = totalPage;
                            checkPage = false;
                        }
                        increment++;
                        it.getAndIncrement();
                    }while (increment<page);
                } catch (Exception e) {
                    i.getAndIncrement();
                }
            });
        }
        executorService.shutdown();
        while (!executorService.isTerminated()) {
        }
        if(version){
            numberPagesProcessed[0] = Integer.parseInt(String.valueOf(it));
        }else {
            numberPagesProcessed[1] = Integer.parseInt(String.valueOf(it));
        }
        System.out.println((System.currentTimeMillis() - startTime) + " " + i + " " + it + " " + halfUrls.size());
    }

    private static Set<String> readPidora(String FILE_PATH) {
        Set<String> sentArticles = ConcurrentHashMap.newKeySet();
        File file = new File(FILE_PATH);
        try {
            if (!file.exists()) {
                file.createNewFile();
            }
            try (BufferedReader reader = new BufferedReader(new FileReader(FILE_PATH))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sentArticles.add(line);
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return sentArticles;
    }

    private static void readSentArticlesToCache(String filePath, Cache<String, Double> cache) {
        File file = new File(filePath);
        try {
            if (!file.exists()) {
                file.createNewFile();
            }
            try (BufferedReader reader = new BufferedReader(new FileReader(filePath))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String[] parts = line.split(" ");
                    if (parts.length == 2) {
                        try {
                            double value = Double.parseDouble(parts[1]);
                            cache.put(parts[0], value);
                        } catch (NumberFormatException e) {
                            System.err.println("Invalid number format in file: " + filePath + " -> " + line);
                        }
                    }
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static void main(String[] args) {
        // Установка CookieManager
        CookieManager cookieManager = new CookieManager();

        cookieManager.setCookiePolicy(CookiePolicy.ACCEPT_ALL);

        // Создание HttpClient с поддержкой CookieManager
        HttpClient client = HttpClient.newBuilder()
                .cookieHandler(cookieManager)
                .build();

        String urlWb = "https://www.wildberries.ru/";

        try {
            // Отправка запроса к сайту Wildberries
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(urlWb))
                    .GET()
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            // Проверка успешности запроса
            System.out.println(response.statusCode());

            // Извлечение cookies
            Cookies = new HashSet<>(cookieManager.getCookieStore().getCookies());
            Cookies.forEach(System.out::println);
            
            // Генерируем URL через SearchUrlGenerator (новый формат)
            SearchUrlGenerator generator = new SearchUrlGenerator();
            try {
                generator.generateUrlsFromCatalogApi(Cookies);
                searchUrls = generator.getGeneratedUrls();
            } catch (IOException e) {
                urls = getURL(); // Fallback на старый формат
            }
            
            urlsFood = readPidora("Food.txt");
            urlsDetyam = readPidora("detyam.txt");

        } catch (Exception e) {
            e.printStackTrace();
        }

        // Регистрация бота Telegram
        try {
            TelegramBotsApi botsApi = new TelegramBotsApi(DefaultBotSession.class);
            botsApi.registerBot(new MyDualBot("7564492259:AAHJFWRqVvJQuuUIVd5584h8ePoFxsg7YVc"));
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }

    }

    public void sendMessage(String chatId, Integer messageThreadId, String messageText) throws IOException {
        boolean sent = false;
        while (!sent) {
            SendMessage sendMessage = new SendMessage(chatId, messageText).messageThreadId(messageThreadId).parseMode(ParseMode.HTML);
            SendResponse response = pengradBot.execute(sendMessage);
            if (response.isOk()) {
                sent = true;
            } else {
                int retryAfter = getRetryAfter(response);
                if (retryAfter > 0) {
                    try {
                        Thread.sleep(retryAfter * 1000L);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                        break;
                    }
                } else {
                    break;
                }
            }
        }
    }

    public void sendPhoto(String chatId, Integer messageThreadId, String messageText, byte[] imageBytes) throws IOException {
        boolean sent = false;
        while (!sent) {
            try {

                SendPhoto sendPhoto = new SendPhoto(chatId, imageBytes);
                sendPhoto.fileName("photo.jpg");
                sendPhoto.caption(messageText);
                sendPhoto.parseMode(ParseMode.HTML);
                if (messageThreadId != null) {
                    sendPhoto.messageThreadId(messageThreadId);
                }

                // Выполняем запрос с использованием библиотеки com.pengrad.telegrambot
                SendResponse response = pengradBot.execute(sendPhoto);
                if (response.isOk()) {
                    sent = true;
                } else {
                    int retryAfter = getRetryAfter(response);
                    if (retryAfter > 0) {
                        Thread.sleep(retryAfter * 1000L);
                    } else {
                        throw new RuntimeException("Failed to send message: " + response.description());
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Thread was interrupted", e);
            } catch (Exception e) {
                throw new RuntimeException("Failed to send message", e);
            }
        }
    }

    public static void readTxtFile(String itemName, String itemCost, String itemFeedBackCost, String article, String totalQuery, String category) throws IOException, InterruptedException {
        if(Objects.equals(itemCost,"0")){
            itemCost = String.valueOf(hasFeedbackPoints(article));
        }
        double percent = Double.parseDouble(itemFeedBackCost) / Double.parseDouble(itemCost);
        ProductInfo productInfo = new ProductInfo();
        Double old100 = sentArticles100.getIfPresent(article);
        Double old90  = sentArticles90.getIfPresent(article);
        Double old80  = sentArticles80.getIfPresent(article);
        Double oldBig = sentArticlesBig.getIfPresent(article);
        Double oldCommunity =  sentArticlesCommunity.getIfPresent(article);
        Double oldFood = sentArticlesFood.getIfPresent(article);
        Double oldDetyam = sentArticlesDetyam.getIfPresent(article);
        Double tests = test.getIfPresent(article);
        boolean absent = old100 == null && old90 == null && old80 == null && oldBig == null;
        boolean changed =
                (old100 != null && Math.abs(old100 - percent) > 0.1) ||
                        (old90  != null && Math.abs(old90  - percent) > 0.1) ||
                        (old80  != null && Math.abs(old80  - percent) > 0.1) ||
                        (oldBig != null && Math.abs(oldBig - percent) > 0.1);
        if(tests == null || Math.abs(tests - percent) > 0.01){
            try (BufferedWriter writer = Files.newBufferedWriter(Path.of("test.txt"), CREATE, APPEND)) {
                DateTimeFormatter dtf = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss");
                LocalDateTime now = LocalDateTime.now();
                writer.write(article + " " + dtf.format(now) + "\t");
                test.put(article, percent);
            }
        }
        if(absent || changed){
            String message;
            if (((percent > 0.49 && Integer.parseInt(itemFeedBackCost) >= 1000 && Integer.parseInt(itemFeedBackCost) < 2500)
                    || (percent > 0.59 && Integer.parseInt(itemFeedBackCost) >= 699 && Integer.parseInt(itemFeedBackCost) < 1000 && percent < 0.9)
                    || (percent >= 0.4 && Integer.parseInt(itemFeedBackCost) >= 2500))) {

                message = createMessage(itemName, itemCost, itemFeedBackCost, article, percent, totalQuery);
                queueBig.add(message);
                productInfo.setquantity(String.valueOf(Integer.parseInt(totalQuery)));
                productInfo.settime(System.currentTimeMillis());
                mapOnSent.put(article, productInfo);
            }
            if (percent >= 1) {
                message = createMessage(itemName, itemCost, itemFeedBackCost, article,percent,totalQuery);
                queue100.add(message);
                productInfo.setquantity(String.valueOf(Integer.parseInt(totalQuery)));
                productInfo.settime(System.currentTimeMillis());
                mapOnSent.put(article, productInfo);
            } else if (percent >= 0.9) {
                message = createMessage(itemName, itemCost, itemFeedBackCost, article,percent,totalQuery);
                queue90.add(message);
                productInfo.setquantity(String.valueOf(Integer.parseInt(totalQuery)));
                productInfo.settime(System.currentTimeMillis());
                mapOnSent.put(article, productInfo);
            } else if (percent >= 0.8) {
                message = createMessage(itemName, itemCost, itemFeedBackCost, article,percent,totalQuery);
                queue80.add(message);
                productInfo.setquantity(String.valueOf(Integer.parseInt(totalQuery)));
                productInfo.settime(System.currentTimeMillis());
                mapOnSent.put(article, productInfo);
            }
        }
        if (oldCommunity == null || Math.abs(oldCommunity - percent) > 0.1) {
            String message;

            if (percent >= 1 || (Double.parseDouble(itemFeedBackCost) - Double.parseDouble(itemCost) >= 199 && percent > 1)) {
                message = createMessage(itemName, itemCost, itemFeedBackCost, article,percent,totalQuery);
                productInfo.setquantity(String.valueOf(Integer.parseInt(totalQuery)));
                productInfo.settime(System.currentTimeMillis());
                queueMyChat.add(message);
                // Обновляем кэш sentArticlesCommunity
                sentArticlesCommunity.put(article, percent);
            }
        }
        // Проверяем категорию Food
        if((oldFood==null || Math.abs(oldFood - percent) > 0.1) && category != null && urlsFood.contains(category)){
            if (percent >= 0.45) {
                String message = createMessage(itemName, itemCost, itemFeedBackCost, article, percent, totalQuery);
                queueFood.add(message);
                sentArticlesFood.put(article, percent);
            }
        }
        // Проверяем категорию Detyam
        if((oldDetyam==null || Math.abs(oldDetyam - percent) > 0.1) && category != null && urlsDetyam.contains(category)){
            if (percent >= 0.5) {
                String message = createMessage(itemName, itemCost, itemFeedBackCost, article, percent, totalQuery);
                queueDetyam.add(message);
                sentArticlesDetyam.put(article, percent);
            }
        }
    }

    private static void runSender(String fileName,
                                  BlockingQueue<String> queue,
                                  Cache<String, Double> cache,
                                  String chatId,
                                  Integer threadId,
                                  String secondChatId) throws InterruptedException {

        MyDualBot tgBot = new MyDualBot("7564492259:AAHJFWRqVvJQuuUIVd5584h8ePoFxsg7YVc");
        Path path = Path.of(FILE_PATH + fileName);

        try (BufferedWriter writer = Files.newBufferedWriter(path, CREATE, APPEND)) {
        while (running || !queue.isEmpty()) {
            String data = queue.take();
            if ("0~~0~~0".equals(data)) return;
            String[] parts = data.split("~~", 3);
            String article = parts[0];
            String productInfo = parts[1];
            double percent = Double.parseDouble(parts[2]);

            Double old = cache.getIfPresent(article);
            if (old == null) {
                cache.put(article, percent);

                // основной канал
                    tgBot.sendMessage(chatId, threadId, productInfo);
                // второй канал (если указан)
                if (secondChatId != null) {
                        tgBot.sendMessage(secondChatId, 0, productInfo);
                }

                    writer.write(article + " " + percent);
                    writer.newLine();
                    writer.flush();
            }
        }
        } catch (IOException e) {
            e.printStackTrace();
    }
    }
    public static int checkLinkStatus(String imageUrl) {
        try {
            URL url = new URL(imageUrl);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            connection.connect();
            return connection.getResponseCode();
        } catch (Exception e) {
            return -1;
        }
    }

    public static byte[] downloadImageToBuffer(String imageUrl) throws IOException {
        URL url = new URL(imageUrl);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(5000);

        try (InputStream in = connection.getInputStream();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[1024];
            int bytesRead;
            while ((bytesRead = in.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
            }
            return out.toByteArray();
        } finally {
            connection.disconnect();
        }
    }

    // Счетчики для статистики отправки товаров
    private static final AtomicInteger totalProductsQueued = new AtomicInteger(0);
    private static final AtomicInteger totalProductsSent = new AtomicInteger(0);
    private static final AtomicInteger totalProductsFiltered = new AtomicInteger(0);
    
    private static void sentFree() throws InterruptedException {
        String chatId = "-1002346226214";
        MyDualBot tgBot = new MyDualBot("7564492259:AAHJFWRqVvJQuuUIVd5584h8ePoFxsg7YVc");
        try{
            while (running && isFree) {
                String article = queueFree.take(); // Извлечение данных из очереди
                if(Objects.equals(article, "00")){
                    return;
                }
                // Проверяем, не был ли товар уже отправлен в free chat
                if (sentArticlesFree.getIfPresent(article) != null) {
                    totalProductsFiltered.incrementAndGet();
                    continue;
                }
                
                List<String> sent = repeatCheck(article);
                if(sent.isEmpty()){
                    totalProductsFiltered.incrementAndGet();
                    continue;
                }
                double percent = Double.parseDouble(sent.get(2)) / Integer.parseInt(sent.get(1));
                if (percent >= 0.8
                        || ((percent > 0.49 && Integer.parseInt(sent.get(2)) >= 1000 && Integer.parseInt(sent.get(2)) < 2500)
                        || (percent > 0.59 && Integer.parseInt(sent.get(2)) >= 699 && Integer.parseInt(sent.get(2)) < 1000)
                        || (percent >= 0.4 && Integer.parseInt(sent.get(2)) >= 2500))){
                    // Дополнительная проверка перед обработкой (на случай гонки условий)
                    // Это критически важно для предотвращения дублирования
                    if (sentArticlesFree.getIfPresent(article) != null) {
                        totalProductsFiltered.incrementAndGet();
                        continue;
                    }
                    
                    String data = createMessage(sent.get(0), sent.get(1), sent.get(2), article, percent, sent.get(3));
                    String[] parts = data.split("~~", 3);
                    String productInfo = parts[1];
                    productInfo += "\n\n <a href=\"https://t.me/WB_Jackpot/3793\">\uD83D\uDCB0Товар найден группой WB_Jackpot. Присоединяйтесь!\uD83D\uDCB0</a>";
                    byte[] imageBytes = new byte[0];
                    for (int i = 1; i <= 31; i++) {
                        String url = (i < 10) ? "https://basket-0" + i + ".wbbasket.ru/vol" + article.substring(0, article.length() - 5) + "/part" + article.substring(0, article.length() - 3) + "/" + article + "/images/c516x688/1.webp" : "https://basket-" + i + ".wbbasket.ru/vol" + article.substring(0, article.length() - 5) + "/part" + article.substring(0, article.length() - 3) + "/" + article + "/images/c516x688/1.webp";

                        int statusCode = checkLinkStatus(url);
                        if (statusCode == 200) {
                            try {
                                imageBytes = downloadImageToBuffer(url);
                                break;
                            } catch (IOException e) {
                            }
                        }
                    }
                    
                    // Финальная проверка перед отправкой (double-check на случай параллельной обработки)
                    if (sentArticlesFree.getIfPresent(article) != null) {
                        totalProductsFiltered.incrementAndGet();
                        continue;
                    }
                    
                    try {
                        if (imageBytes == null || imageBytes.length == 0) {
                            tgBot.sendMessage(chatId, 0, productInfo);
                        }
                        else {
                            tgBot.sendPhoto(chatId, 0, productInfo, imageBytes);
                        }
                        // Отмечаем товар как отправленный в free chat СРАЗУ после успешной отправки
                        // Это предотвращает дублирование при параллельной обработке
                        sentArticlesFree.put(article, System.currentTimeMillis());
                        totalProductsSent.incrementAndGet();
                    } catch (Exception e) {
                    }
                } else {
                    totalProductsFiltered.incrementAndGet();
                }
            }
        } catch (Exception e) {

            e.printStackTrace();
        }
    }

    public static double hasFeedbackPoints(String url1) throws IOException, InterruptedException {
        String card = "https://card.wb.ru/cards/v2/detail?appType=1&curr=rub&dest=-5923914&spp=30&ab_testing=false&nm="+ url1;
        Connection connection = Jsoup.connect(card)
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:132.0) Gecko/20100101 Firefox/132.0")
                .method(Connection.Method.GET)
                .ignoreContentType(true);

        for (HttpCookie cookie : Cookies) {
            connection.cookie(cookie.getName(), cookie.getValue());
        }
        Connection.Response response = connection.execute();

        String json = response.body();

        Gson gson = new Gson();
        Root root = gson.fromJson(json, Root.class);

        for (Product product : root.data.products) {

            String feedbackPointsStr = product.getFeedbackPointsAsString();
            if (feedbackPointsStr != null && !feedbackPointsStr.equals("0")) {
                double total = 1;
                if (product.sizes != null) {
                    for (Size size : product.sizes) {
                        if (size.price != null && size.price.product != 0) {
                            total = (double) size.price.product / 100;
                            break;
                        }
                    }
                }
                return Double.parseDouble(feedbackPointsStr) / total;
            }
        }
        return 0;
    }

    static String createMessage(String itemName, String itemCost, String itemFeedBackCost, String article, Double percent, String totalQuery){
        String href = "https://www.wildberries.ru/catalog/" + article + "/detail.aspx";
        DecimalFormat df = new DecimalFormat("#.##");
        itemName = itemName.replace(":","");
        return article+ "~~"  + itemName + "\n\uD83D\uDCB8Стоимость " + itemCost + "\u20BD\n" +
                "\uD83C\uDFB0Кешбэк " + itemFeedBackCost + "\u20BD\n " +
                "\uD83D\uDCAFПроцент выгоды " + df.format(percent * 100) + "%\n" +
                "\uD83C\uDFB2Количество " + totalQuery + "\n"+ href + "~~" + percent;
    }

    public static List<String[]> getURL() throws IOException {
        Connection connection = Jsoup.connect("https://static-basket-01.wbbasket.ru/vol0/data/main-menu-ru-ru-v3.json")
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:132.0) Gecko/20100101 Firefox/132.0")
                .method(Connection.Method.GET)
                .ignoreContentType(true);

        // если есть cookies
        for (HttpCookie cookie : Cookies) {
            connection.cookie(cookie.getName(), cookie.getValue());
        }

        String json = connection.execute().body();

        Gson gson = new Gson();
        UrlFetcher.RootItem[] items = gson.fromJson(json, UrlFetcher.RootItem[].class);

        List<String[]> urls = new ArrayList<>();
        for (UrlFetcher.RootItem item : items) {
            processChildGson(item, urls);
        }

        return urls;
    }

    private static void processChildGson(UrlFetcher.RootItem item, List<String[]> urls) {
        addUrlIfValid(item.url, item.shard, item.query, urls);

        if (item.children != null) {
            for (UrlFetcher.Child child : item.children) {
                processChildGson(child, urls);
            }
        }
    }

    private static void processChildGson(UrlFetcher.Child child, List<String[]> urls) {
        addUrlIfValid(child.url, child.shard, child.query, urls);
        if (child.children != null) {
            for (UrlFetcher.Child nested : child.children) {
                processChildGson(nested, urls);
            }
        }
    }

    private static void addUrlIfValid(String url, String shard, String query, List<String[]> urls) {
        if (url == null || url.isEmpty()) return;
        if (url.startsWith("https://vmeste.wildberries.ru")
                || url.startsWith("https://travel.wildberries.ru")
                || url.startsWith("https://digital.wildberries.ru")) return;
        if (shard == null || shard.isEmpty()) return;

        if (query == null) query = "";

        url += "?sort=popular&page=1&ffeedbackpoints=1";
        url = ensureUrlStartsWithPrefix(url);

        String finalUrl = url;
        boolean exists = urls.stream().anyMatch(u -> u[0].equals(finalUrl));
        if (!exists) {
            urls.add(new String[]{url, shard, query});
        }
    }

    public static String ensureUrlStartsWithPrefix(String url) {
        String prefixDigital = "https://www.wildberries.ru";
        String prefixVmeste = "https://vmeste.wildberries.ru/";

        if (url.startsWith(prefixDigital) || url.startsWith(prefixVmeste)) {
            return url;
        }
        return prefixDigital + url;
    }
    
    /**
     * Генерирует URL для API поиска для всех категорий из JSON каталога и проверяет их валидность
     * @return список информации о сгенерированных URL
     */
    public static List<SearchUrlGenerator.SearchUrlInfo> generateSearchUrlsForAllCategories() {
        try {
            SearchUrlGenerator generator = new SearchUrlGenerator();
            Set<HttpCookie> cookies = Cookies != null ? Cookies : new HashSet<>();
            
            // Генерируем URL из API каталога
            generator.generateUrlsFromCatalogApi(cookies);

            return generator.getGeneratedUrls();
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }
    
    private static List<String> repeatCheck(String article){
        List<String> sent = new ArrayList<>();
        try {
            String jsonUrl = "https://card.wb.ru/cards/v2/detail?appType=1&curr=rub&dest=-5923914&spp=30&ab_testing=false&nm="+ article;
            Connection connection = Jsoup.connect(jsonUrl)
                    .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:132.0) Gecko/20100101 Firefox/132.0")
                    .method(Connection.Method.GET)
                    .ignoreContentType(true);

            for (HttpCookie cookie : Cookies) {
                connection.cookie(cookie.getName(), cookie.getValue());
            }
            Connection.Response response = connection.execute();

            String json = response.body();

            Gson gson = new Gson();
            Root root = gson.fromJson(json, Root.class);

            for (Product product : root.data.products) {
                if (product.getFeedbackPointsAsString() != null && product.totalQuantity != null && !product.totalQuantity.equals("0")) {
                    String feedBackSum = product.getFeedbackPointsAsString();
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
        }catch (Exception e){
//            e.printStackTrace();
        }
        return sent;
    }

    private static void hasPoint(Cache<String, Double> cache,
                                 String fileName) throws IOException {
        
        ExecutorService pool = Executors.newFixedThreadPool(100);

        Path path = Path.of(fileName);
        int initialSize = cache.asMap().size();
        
        if (Files.exists(path)) {
            long lineCount = Files.lines(path).count();
            Files.lines(path)
                    .map(l -> l.split(" "))
                    .filter(p -> p.length == 2)          // article double epoch
                    .forEach(p -> cache.put(p[0], Double.parseDouble(p[1])));
        }

        Set<String> toRemove = ConcurrentHashMap.newKeySet();

        for (String article : cache.asMap().keySet()) {
            pool.submit(() -> {
                try {
                    double actual = hasFeedbackPoints(article);
                    Double stored = cache.getIfPresent(article);
                    if (stored == null || Math.abs(actual - stored) > 0.1) {
                        toRemove.add(article);
                    }
                } catch (IOException e) {
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                }
            });
        }

        pool.shutdown();
        try {
            if (!pool.awaitTermination(5, TimeUnit.MINUTES)) {
                pool.shutdownNow();
            }
        } catch (InterruptedException e) {
           pool.shutdownNow();
            Thread.currentThread().interrupt();
        }

        toRemove.forEach(cache::invalidate);
        try (BufferedWriter w = Files.newBufferedWriter(path, CREATE, TRUNCATE_EXISTING)) {
            for (Map.Entry<String, Double> e : cache.asMap().entrySet()) {
                w.write(e.getKey() + " " + e.getValue());
                w.newLine();
            }
        }
    }
}