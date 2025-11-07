package org.example.pipeline;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

public class AdaptiveRateController {
	// EWMA для доли блокировок/ошибок
	private volatile double errorRate = 0.0; // [0..1]
	private final double alpha = 0.2; // сглаживание
	private final long baseDelayMs; // базовая пауза между запросами
	private final long maxDelayMs; // верхняя граница
	private final double k; // усиление при росте ошибок
	private final AtomicLong lastRecalc = new AtomicLong(0);

	public AdaptiveRateController(long baseDelayMs, long maxDelayMs, double amplification) {
		this.baseDelayMs = baseDelayMs;
		this.maxDelayMs = maxDelayMs;
		this.k = amplification;
	}

	public void onSuccess() {
		update(false);
	}

	public void onBlocked() {
		update(true);
	}

	private void update(boolean blocked) {
		double target = blocked ? 1.0 : 0.0;
		errorRate = alpha * target + (1 - alpha) * errorRate;
		lastRecalc.set(System.currentTimeMillis());
	}

	public long currentDelayMs() {
		// Чем выше errorRate, тем больше задержка
		double factor = 1.0 + k * errorRate; // 1..(1+k)
		long delay = (long) Math.min(maxDelayMs, Math.max(0, baseDelayMs * factor));
		// небольшой джиттер, чтобы не попадать в синхронные окна
		long jitter = ThreadLocalRandom.current().nextLong(0, Math.max(1, delay / 10 + 1));
		return delay + jitter;
	}

	public int currentParallelism(int min, int base, int max) {
		// При росте errorRate снижаем параллелизм; при 0 — стремимся к max
		double inv = 1.0 - Math.max(0.0, Math.min(1.0, errorRate));
		int target = (int) Math.round(min + (max - min) * inv);
		// слегка тянем к базе, чтобы не прыгал
		target = (target + base) / 2;
		if (target < min) target = min;
		if (target > max) target = max;
		return target;
	}
}


