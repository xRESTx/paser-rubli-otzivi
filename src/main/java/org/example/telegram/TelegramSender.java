package org.example.telegram;

import com.pengrad.telegrambot.TelegramBot;
import com.pengrad.telegrambot.model.request.ParseMode;
import com.pengrad.telegrambot.request.SendMessage;
import com.pengrad.telegrambot.request.SendPhoto;
import com.pengrad.telegrambot.response.SendResponse;

public class TelegramSender {
	private final TelegramBot bot;
	private final java.util.concurrent.ConcurrentHashMap<String, TokenBucket> chatBuckets = new java.util.concurrent.ConcurrentHashMap<>();
	private final int defaultRatePerSec = 1; // по умолчанию 1 сообщение/сек на чат
	private final int burst = 3; // кратковременный бёрст

	public TelegramSender(String token) {
		this.bot = new TelegramBot(token);
	}

	public void sendText(String chatId, Integer messageThreadId, String messageText) {
		throttle(chatId);
		boolean sent = false;
		while (!sent) {
			SendMessage req = new SendMessage(chatId, messageText).parseMode(ParseMode.HTML);
			if (messageThreadId != null && messageThreadId > 0) {
				req.messageThreadId(messageThreadId);
			}
			SendResponse resp = bot.execute(req);
			if (resp.isOk()) {
				sent = true;
			} else {
				int retryAfter = getRetryAfter(resp);
				if (retryAfter > 0) {
					try { Thread.sleep(retryAfter * 1000L); } catch (InterruptedException ignored) { break; }
				} else {
					break;
				}
			}
		}
	}

	public void sendPhoto(String chatId, Integer messageThreadId, String caption, byte[] imageBytes) {
		throttle(chatId);
		boolean sent = false;
		while (!sent) {
			SendPhoto req = new SendPhoto(chatId, imageBytes).fileName("photo.jpg").caption(caption).parseMode(ParseMode.HTML);
			if (messageThreadId != null && messageThreadId > 0) {
				req.messageThreadId(messageThreadId);
			}
			SendResponse resp = bot.execute(req);
			if (resp.isOk()) {
				sent = true;
			} else {
				int retryAfter = getRetryAfter(resp);
				if (retryAfter > 0) {
					try { Thread.sleep(retryAfter * 1000L); } catch (InterruptedException ignored) { break; }
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
			try { return Integer.parseInt(parts[parts.length - 1]); }
			catch (NumberFormatException ignored) { }
		}
		return 0;
	}

	private void throttle(String chatId) {
		TokenBucket bucket = chatBuckets.computeIfAbsent(chatId, k -> new TokenBucket(defaultRatePerSec, burst));
		bucket.consume();
	}

	private static class TokenBucket {
		private final double ratePerSec; // токенов в секунду
		private final double capacity;
		private double tokens;
		private long lastRefillNs;

		TokenBucket(double ratePerSec, double capacity) {
			this.ratePerSec = ratePerSec;
			this.capacity = capacity;
			this.tokens = capacity;
			this.lastRefillNs = System.nanoTime();
		}

		synchronized void consume() {
			refill();
			while (tokens < 1.0) {
				long sleepNs = (long) Math.max(1_000_000L, (1_000_000_000L / ratePerSec) / 2);
				try { Thread.sleep(sleepNs / 1_000_000L, (int)(sleepNs % 1_000_000L)); } catch (InterruptedException ignored) {}
				refill();
			}
			tokens -= 1.0;
		}

		private void refill() {
			long now = System.nanoTime();
			double add = (now - lastRefillNs) / 1_000_000_000.0 * ratePerSec;
			if (add > 0) {
				tokens = Math.min(capacity, tokens + add);
				lastRefillNs = now;
			}
		}
	}
}


