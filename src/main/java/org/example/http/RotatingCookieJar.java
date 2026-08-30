package org.example.http;

import java.util.List;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Thread-safe cookie header rotator.
 * Keeps the current cookie set stable and switches when HTTP 498 becomes frequent.
 */
public final class RotatingCookieJar {
    private final List<String> cookieHeaders;
    private final int switchWindowSize;
    private final int switchMinSamples;
    private final double switchThreshold;
    private final Deque<Boolean> recentHttp498 = new ArrayDeque<>();
    private int recentHttp498Count = 0;
    private int nextIdx = 0;
    private int currentIdx = 0;

    public RotatingCookieJar(List<String> cookieHeaders) {
        this(cookieHeaders, 50, 20, 0.20);
    }

    public RotatingCookieJar(List<String> cookieHeaders, int switchWindowSize, int switchMinSamples, double switchThreshold) {
        this.cookieHeaders = cookieHeaders == null || cookieHeaders.isEmpty()
                ? List.of("")
                : List.copyOf(cookieHeaders);
        this.switchWindowSize = Math.max(1, switchWindowSize);
        this.switchMinSamples = Math.max(1, Math.min(switchMinSamples, this.switchWindowSize));
        this.switchThreshold = Math.max(0.01, Math.min(1.0, switchThreshold));
    }

    public int size() {
        return cookieHeaders.size();
    }

    public synchronized String currentCookieHeader() {
        return cookieHeaders.get(currentIdx);
    }

    public synchronized String nextCookieHeader() {
        CookieSelection selection = nextCookie();
        return selection.header();
    }

    public synchronized CookieSelection nextCookie() {
        currentIdx = nextIdx;
        String header = cookieHeaders.get(currentIdx);
        nextIdx = (nextIdx + 1) % cookieHeaders.size();
        return new CookieSelection(header, currentIdx, cookieHeaders.size());
    }

    public synchronized void recordStatusCode(int statusCode) {
        if (cookieHeaders.size() <= 1) {
            return;
        }

        boolean isHttp498 = statusCode == 498;
        recentHttp498.addLast(isHttp498);
        if (isHttp498) {
            recentHttp498Count++;
        }
        while (recentHttp498.size() > switchWindowSize) {
            Boolean removed = recentHttp498.removeFirst();
            if (Boolean.TRUE.equals(removed)) {
                recentHttp498Count--;
            }
        }

        int samples = recentHttp498.size();
        if (samples < switchMinSamples) {
            return;
        }
        double ratio = (double) recentHttp498Count / samples;
        if (ratio >= switchThreshold) {
            if (nextIdx == currentIdx) {
                nextIdx = (nextIdx + 1) % cookieHeaders.size();
            }
            recentHttp498.clear();
            recentHttp498Count = 0;
        }
    }

    public String firstCookieHeader() {
        return cookieHeaders.get(0);
    }

    public synchronized int currentIndex() {
        return currentIdx;
    }

    public record CookieSelection(String header, int index, int total) {}
}


