package org.example.storage;

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
            return;
        }
        ensureDirectory();
        this.worker = new Thread(this::runLoop, "sqlite-writer");
        this.worker.setDaemon(true);
        this.worker.start();
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

    private void enqueue(StorageCommand command) {
        if (!running.get()) {
            return;
        }
        try {
            queue.offer(command, 2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void runLoop() {
        try (Connection connection = DriverManager.getConnection(jdbcUrl)) {
            configure(connection);
            List<StorageCommand> batch = new ArrayList<>(batchSize);
            while (running.get() || !queue.isEmpty()) {
                try {
                    StorageCommand cmd = queue.poll(500, TimeUnit.MILLISECONDS);
                    if (cmd != null) {
                        batch.add(cmd);
                    }
                    if (batch.size() >= batchSize || (!running.get() && !batch.isEmpty())) {
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

    private void flush(Connection connection, List<StorageCommand> batch) throws InterruptedException {
        try {
            connection.setAutoCommit(false);
            for (StorageCommand command : batch) {
                command.execute(connection);
            }
            connection.commit();
        } catch (SQLException e) {
            log.error("Failed to flush {} storage commands", batch.size(), e);
            try {
                connection.rollback();
            } catch (SQLException ex) {
                log.error("Rollback failed", ex);
            }
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


