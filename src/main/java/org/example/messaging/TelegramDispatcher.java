package org.example.messaging;

import com.pengrad.telegrambot.TelegramBot;
import com.pengrad.telegrambot.model.request.ParseMode;
import com.pengrad.telegrambot.request.SendMessage;
import com.pengrad.telegrambot.response.SendResponse;
import org.example.service.ChannelType;
import org.example.storage.SqliteStorage;
import org.example.storage.records.SentRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Dedicated dispatcher that sends messages to Telegram using a bounded queue.
 */
public final class TelegramDispatcher {

    private static final Logger log = LoggerFactory.getLogger(TelegramDispatcher.class);

    private final TelegramBot telegramBot;
    private final BlockingQueue<OutgoingMessage> queue;
    private final SqliteStorage storage;
    private final int workerCount;
    private ExecutorService executor;
    private volatile boolean running;

    public TelegramDispatcher(TelegramBot telegramBot,
                              BlockingQueue<OutgoingMessage> queue,
                              SqliteStorage storage,
                              int workerCount) {
        this.telegramBot = telegramBot;
        this.queue = queue;
        this.storage = storage;
        this.workerCount = workerCount;
    }

    public void start() {
        if (running) {
            return;
        }
        running = true;
        executor = Executors.newFixedThreadPool(workerCount, r -> {
            Thread t = new Thread(r, "tg-dispatcher");
            t.setDaemon(true);
            return t;
        });
        for (int i = 0; i < workerCount; i++) {
            executor.submit(this::runLoop);
        }
    }

    public BlockingQueue<OutgoingMessage> getQueue() {
        return queue;
    }

    public void stop() {
        running = false;
        if (executor != null) {
            executor.shutdown();
            try {
                executor.awaitTermination(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void runLoop() {
        while (running || !queue.isEmpty()) {
            try {
                OutgoingMessage message = queue.poll(500, TimeUnit.MILLISECONDS);
                if (message == null) {
                    continue;
                }
                send(message);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("Failed to dispatch telegram message", e);
            }
        }
    }

    private void send(OutgoingMessage message) throws InterruptedException {
        boolean sent = false;
        while (!sent) {
            SendMessage main = new SendMessage(message.getChatId(), message.getPayload())
                    .parseMode(ParseMode.HTML);
            if (message.getThreadId() != null) {
                main.messageThreadId(message.getThreadId());
            }
            SendResponse response = telegramBot.execute(main);
            if (response.isOk()) {
                sent = true;
                if (message.getSnapshot() != null) {
                    storage.enqueueSnapshot(message.getSnapshot());
                }
                storage.enqueueSentRecord(new SentRecord(
                        message.getArticle(),
                        message.getChannelType(),
                        message.getChatId(),
                        Instant.now().toEpochMilli(),
                        message.getPercent()
                ));
                if (message.getSecondaryChatId() != null) {
                    SendMessage second = new SendMessage(message.getSecondaryChatId(), message.getPayload())
                            .parseMode(ParseMode.HTML);
                    telegramBot.execute(second);
                }
            } else {
                int retryAfter = parseRetryAfter(response);
                if (retryAfter <= 0) {
                    log.warn("Telegram send failed without retryAfter: {}", response.description());
                    return;
                }
                TimeUnit.SECONDS.sleep(retryAfter);
            }
        }
    }

    private int parseRetryAfter(SendResponse response) {
        String description = response.description();
        if (description == null) {
            return 0;
        }
        String needle = "retry after";
        int idx = description.indexOf(needle);
        if (idx < 0) {
            return 0;
        }
        try {
            String tail = description.substring(idx + needle.length()).trim();
            return Integer.parseInt(tail);
        } catch (NumberFormatException ex) {
            return 0;
        }
    }
}


