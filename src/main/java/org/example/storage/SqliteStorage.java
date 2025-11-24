package org.example.storage;

import org.example.service.ChannelType;
import org.example.storage.commands.InsertSentRecordCommand;
import org.example.storage.commands.PersistSnapshotCommand;
import org.example.storage.commands.StorageCommand;
import org.example.storage.records.ProductSnapshot;
import org.example.storage.records.SentRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class SqliteStorage implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SqliteStorage.class);

    private final BlockingQueue<StorageCommand> queue;
    private final int batchSize;
    private final String jdbcUrl;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread worker;

    public SqliteStorage(String dbPath, int queueCapacity, int batchSize) {
        this.queue = new LinkedBlockingQueue<>(queueCapacity);
        this.batchSize = batchSize;
        this.jdbcUrl = "jdbc:sqlite:" + dbPath;
    }

    public void start() {
        if (!running.compareAndSet(false, true)) {
            log.warn("Storage already running, ignoring start()");
            return;
        }
        log.info("Starting SqliteStorage, database path: {}", jdbcUrl);
        ensureDirectory();
        this.worker = new Thread(this::runLoop, "sqlite-writer");
        this.worker.setDaemon(true);
        this.worker.start();
        log.info("SqliteStorage worker thread started");
    }

    public void stop() {
        running.set(false);
        if (worker != null) {
            worker.interrupt();
            try {
                worker.join(Duration.ofSeconds(5).toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public void close() {
        stop();
    }

    public void enqueueSnapshot(ProductSnapshot snapshot) {
        enqueue(new PersistSnapshotCommand(snapshot));
    }

    public void enqueueSentRecord(SentRecord record) {
        enqueue(new InsertSentRecordCommand(record));
    }
    
    /**
     * Загружает последние отправленные записи из БД для предзагрузки кэша.
     * Загружает записи за последние указанные часы вместе с последней ценой из таблицы products.
     * Возвращает список записей с информацией: article, channelType, percent, lastPrice.
     */
    public List<CacheWarmupRecord> loadRecentSentRecordsForCache(int hoursBack) {
        String jdbcUrl = this.jdbcUrl;
        List<CacheWarmupRecord> records = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection(jdbcUrl)) {
            // Убеждаемся, что таблицы созданы
            configure(connection);
            long cutoffTime = System.currentTimeMillis() - (hoursBack * 3600_000L);
            // Загружаем отправленные записи вместе с последней ценой из products
            // Без DISTINCT, чтобы загрузить все записи для каждого канала и percent
            String sql = """
                    SELECT sp.nm_id, sp.channel, sp.percent, COALESCE(p.last_price, 0) as last_price
                    FROM sent_posts sp
                    LEFT JOIN products p ON sp.nm_id = p.nm_id
                    WHERE sp.sent_at >= ?
                    ORDER BY sp.sent_at DESC
                    """;
            try (var statement = connection.prepareStatement(sql)) {
                statement.setLong(1, cutoffTime);
                try (var rs = statement.executeQuery()) {
                    while (rs.next()) {
                        String article = String.valueOf(rs.getLong("nm_id"));
                        ChannelType channelType = ChannelType.valueOf(rs.getString("channel"));
                        // Округляем percent до 4 знаков для совпадения с сохраненным значением
                        double percent = Math.round(rs.getDouble("percent") * 10000.0) / 10000.0;
                        long lastPrice = rs.getLong("last_price");
                        records.add(new CacheWarmupRecord(article, channelType, percent, lastPrice));
                    }
                }
            }
            log.info("Loaded {} recent sent records from database for cache warmup (last {} hours)", records.size(), hoursBack);
        } catch (SQLException e) {
            log.warn("Failed to load recent sent records from database", e);
        }
        return records;
    }
    
    /**
     * Запись для предзагрузки кэша: содержит article, channelType, percent и lastPrice.
     */
    public record CacheWarmupRecord(String article, ChannelType channelType, double percent, long lastPrice) {
    }
    
    /**
     * Запись о товаре из БД для команды clear.
     */
    public record ProductRecord(long nmId, String name, long lastPrice, long lastFeedback, double lastPercent) {
    }
    
    /**
     * Получает все товары из БД для проверки акций.
     */
    public List<ProductRecord> getAllProducts() {
        List<ProductRecord> products = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection(jdbcUrl)) {
            String sql = """
                    SELECT nm_id, name, last_price, last_feedback, 
                           CAST(last_feedback AS REAL) / CAST(last_price AS REAL) as last_percent
                    FROM products
                    ORDER BY last_seen_at DESC
                    """;
            try (var statement = connection.prepareStatement(sql);
                 var rs = statement.executeQuery()) {
                while (rs.next()) {
                    long nmId = rs.getLong("nm_id");
                    String name = rs.getString("name");
                    long lastPrice = rs.getLong("last_price");
                    long lastFeedback = rs.getLong("last_feedback");
                    double lastPercent = rs.getDouble("last_percent");
                    products.add(new ProductRecord(nmId, name, lastPrice, lastFeedback, lastPercent));
                }
            }
        } catch (SQLException e) {
            log.error("Failed to load products from database", e);
        }
        return products;
    }
    
    /**
     * Удаляет товар из БД (включая связанные записи).
     */
    public void deleteProduct(long nmId) {
        try (Connection connection = DriverManager.getConnection(jdbcUrl)) {
            connection.setAutoCommit(false);
            try {
                // Удаляем из sent_posts
                try (var stmt = connection.prepareStatement("DELETE FROM sent_posts WHERE nm_id = ?")) {
                    stmt.setLong(1, nmId);
                    stmt.executeUpdate();
                }
                // Удаляем из price_history
                try (var stmt = connection.prepareStatement("DELETE FROM price_history WHERE nm_id = ?")) {
                    stmt.setLong(1, nmId);
                    stmt.executeUpdate();
                }
                // Удаляем из products
                try (var stmt = connection.prepareStatement("DELETE FROM products WHERE nm_id = ?")) {
                    stmt.setLong(1, nmId);
                    stmt.executeUpdate();
                }
                connection.commit();
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException e) {
            log.error("Failed to delete product {} from database", nmId, e);
        }
    }

    private void enqueue(StorageCommand command) {
        if (!running.get()) {
            log.warn("Storage not running, dropping command: {}", command.getClass().getSimpleName());
            return;
        }
        try {
            boolean offered = queue.offer(command, 2, TimeUnit.SECONDS);
            if (!offered) {
                log.warn("Failed to enqueue storage command, queue full: {}", command.getClass().getSimpleName());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void runLoop() {
        log.info("SqliteStorage runLoop started");
        try (Connection connection = DriverManager.getConnection(jdbcUrl)) {
            configure(connection);
            log.info("SqliteStorage database connection established and tables configured");
            List<StorageCommand> batch = new ArrayList<>(batchSize);
            while (running.get() || !queue.isEmpty()) {
                try {
                    StorageCommand cmd = queue.poll(500, TimeUnit.MILLISECONDS);
                    if (cmd != null) {
                        batch.add(cmd);
                    }
                    // Для sent_posts записываем сразу (batchSize=1), для остального - по batchSize
                    boolean shouldFlush = batch.size() >= batchSize 
                            || (!running.get() && !batch.isEmpty())
                            || (batch.size() > 0 && batch.stream().anyMatch(c -> c instanceof InsertSentRecordCommand));
                    if (shouldFlush) {
                        flush(connection, batch);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    // Нормальное завершение - обрабатываем оставшиеся команды и выходим
                    if (!batch.isEmpty()) {
                        try {
                            flush(connection, batch);
                        } catch (InterruptedException ignored) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    break;
                }
            }
            if (!batch.isEmpty()) {
                try {
                    flush(connection, batch);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        } catch (SQLException e) {
            log.error("SQLite writer stopped unexpectedly", e);
        }
    }

    private int flush(Connection connection, List<StorageCommand> batch) throws InterruptedException {
        if (batch.isEmpty()) {
            return 0;
        }
        int count = batch.size();
        try {
            connection.setAutoCommit(false);
            for (StorageCommand command : batch) {
                command.execute(connection);
            }
            connection.commit();
            return count;
        } catch (SQLException e) {
            log.error("Failed to flush {} storage commands", batch.size(), e);
            try {
                connection.rollback();
            } catch (SQLException ex) {
                log.error("Rollback failed", ex);
            }
            return 0;
        } finally {
            batch.clear();
            try {
                connection.setAutoCommit(true);
            } catch (SQLException ignored) {
            }
        }
    }

    private void configure(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA synchronous=NORMAL");
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS products(
                        nm_id INTEGER PRIMARY KEY,
                        name TEXT NOT NULL,
                        supplier_id INTEGER,
                        supplier_name TEXT,
                        category_url TEXT,
                        first_seen_at INTEGER NOT NULL,
                        last_seen_at INTEGER NOT NULL,
                        last_price INTEGER NOT NULL,
                        last_feedback INTEGER NOT NULL,
                        last_stock INTEGER NOT NULL
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS price_history(
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        nm_id INTEGER NOT NULL,
                        observed_at INTEGER NOT NULL,
                        price INTEGER NOT NULL,
                        feedback INTEGER NOT NULL,
                        stock INTEGER NOT NULL,
                        percent REAL NOT NULL,
                        channel_mask INTEGER NOT NULL,
                        FOREIGN KEY(nm_id) REFERENCES products(nm_id)
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS sent_posts(
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        nm_id INTEGER NOT NULL,
                        channel TEXT NOT NULL,
                        chat_id TEXT NOT NULL,
                        sent_at INTEGER NOT NULL,
                        percent REAL NOT NULL,
                        UNIQUE(nm_id, channel, percent)
                    )
                    """);
            statement.execute("CREATE INDEX IF NOT EXISTS idx_price_history_nm_time ON price_history(nm_id, observed_at DESC)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_price_history_percent ON price_history(percent DESC)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_sent_posts_channel_time ON sent_posts(channel, sent_at DESC)");
        }
    }

    private void ensureDirectory() {
        if (!jdbcUrl.startsWith("jdbc:sqlite:")) {
            return;
        }
        String path = jdbcUrl.substring("jdbc:sqlite:".length());
        Path file = Path.of(path).toAbsolutePath();
        try {
            Files.createDirectories(file.getParent());
        } catch (IOException e) {
            throw new IllegalStateException("Failed to create directories for " + file, e);
        }
    }
}


