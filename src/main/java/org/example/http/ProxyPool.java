package org.example.http;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Пул прокси с отслеживанием работоспособности и ThreadLocal распределением
 */
public final class ProxyPool {
    private static final Logger log = LoggerFactory.getLogger(ProxyPool.class);
    
    private final List<ProxyInfo> proxies;
    private final Random random = new Random();
    private final AtomicInteger currentIndex = new AtomicInteger(0);
    
    // ThreadLocal для хранения прокси для каждого потока
    private static final ThreadLocal<ProxyInfo> threadLocalProxy = new ThreadLocal<>();
    
    // Время последнего сброса всех прокси
    private volatile long lastResetTime = 0;
    private static final long RESET_COOLDOWN_MS = 10_000; // 10 секунд задержка после сброса
    
    public ProxyPool(List<ProxyConfig> configs) {
        this.proxies = configs.stream()
                .map(ProxyInfo::new)
                .toList();
    }
    
    /**
     * Получить следующий прокси для текущего потока
     */
    public ProxyConfig getNextProxy() {
        // Проверяем, есть ли уже прокси для этого потока
        ProxyInfo threadProxy = threadLocalProxy.get();
        if (threadProxy != null && threadProxy.isWorking()) {
            return threadProxy.config;
        }
        
        // Получаем новый случайный рабочий прокси
        ProxyInfo newProxy = getRandomWorkingProxy();
        if (newProxy != null) {
            threadLocalProxy.set(newProxy);
            return newProxy.config;
        }
        
        // Если нет рабочих прокси, принудительно сбрасываем все и пробуем снова
        resetAllProxies(true);
        newProxy = getRandomWorkingProxy();
        if (newProxy != null) {
            threadLocalProxy.set(newProxy);
            return newProxy.config;
        }
        
        return null;
    }
    
    /**
     * Освободить прокси для текущего потока (вызывать при ошибках)
     */
    public void releaseThreadProxy() {
        threadLocalProxy.remove();
    }
    
    /**
     * Пометить прокси как нерабочий
     */
    public void markProxyAsBad(ProxyConfig proxy) {
        if (proxy == null) return;
        
        for (ProxyInfo info : proxies) {
            if (info.config.equals(proxy)) {
                info.incrementFailureCount();
                return;
            }
        }
    }
    
    /**
     * Сбросить статус прокси (пометить как рабочий)
     */
    public void resetProxy(ProxyConfig proxy) {
        if (proxy == null) return;
        
        for (ProxyInfo info : proxies) {
            if (info.config.equals(proxy)) {
                info.resetFailureCount();
                return;
            }
        }
    }
    
    /**
     * Сбросить все прокси (пометить все как рабочие)
     * @param force если true, игнорирует cooldown (используется когда нет рабочих прокси)
     */
    public void resetAllProxies(boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now - lastResetTime < RESET_COOLDOWN_MS) {
            return; // Слишком рано для сброса
        }
        
        lastResetTime = now;
        for (ProxyInfo info : proxies) {
            info.resetFailureCount();
        }
    }
    
    /**
     * Сбросить все прокси (пометить все как рабочие) с проверкой cooldown
     */
    public void resetAllProxies() {
        resetAllProxies(false);
    }
    
    /**
     * Получить случайный рабочий прокси
     */
    private ProxyInfo getRandomWorkingProxy() {
        if (proxies.isEmpty()) {
            return null;
        }
        
        List<ProxyInfo> working = proxies.stream()
                .filter(ProxyInfo::isWorking)
                .toList();
        
        if (working.isEmpty()) {
            return null;
        }
        
        ProxyInfo selected = working.get(random.nextInt(working.size()));
        selected.updateLastUsed();
        return selected;
    }
    
    /**
     * Получить количество прокси в пуле
     */
    public int size() {
        return proxies.size();
    }
    
    /**
     * Получить количество рабочих прокси
     */
    public int getWorkingCount() {
        return (int) proxies.stream()
                .filter(ProxyInfo::isWorking)
                .count();
    }
    
    /**
     * Внутренний класс для отслеживания состояния прокси
     */
    private static class ProxyInfo {
        final ProxyConfig config;
        final AtomicInteger failureCount = new AtomicInteger(0);
        final AtomicLong lastUsed = new AtomicLong(0);
        
        private static final int MAX_FAILURES = 3; // Максимум ошибок перед пометкой как нерабочий
        private static final long RECOVERY_TIME_MS = 60_000; // 1 минута до восстановления
        
        ProxyInfo(ProxyConfig config) {
            this.config = config;
        }
        
        boolean isWorking() {
            // Проверяем и восстанавливаем прокси, если прошло достаточно времени
            checkAndRecover();
            return failureCount.get() < MAX_FAILURES;
        }
        
        void checkAndRecover() {
            if (failureCount.get() >= MAX_FAILURES) {
                long timeSinceLastFailure = System.currentTimeMillis() - lastUsed.get();
                if (timeSinceLastFailure > RECOVERY_TIME_MS) {
                    // Автоматическое восстановление после таймаута
                    resetFailureCount();
                }
            }
        }
        
        void incrementFailureCount() {
            failureCount.incrementAndGet();
            updateLastUsed();
        }
        
        void resetFailureCount() {
            failureCount.set(0);
        }
        
        void updateLastUsed() {
            lastUsed.set(System.currentTimeMillis());
        }
    }
}

