package com.realtimecollab.security;

import com.realtimecollab.exception.AuthRateLimitException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

@Component
public class AuthRateLimiter {
    private final int requests;
    private final int maxClients;
    private final long windowNanos;
    private final LongSupplier ticker;
    // Insertion order is expiry order: rejected requests never extend a window.
    private final LinkedHashMap<String, Window> windows = new LinkedHashMap<>();

    @Autowired
    public AuthRateLimiter(AuthRateLimitProperties properties) {
        this(properties, System::nanoTime);
    }

    AuthRateLimiter(AuthRateLimitProperties properties, LongSupplier ticker) {
        this.requests = properties.requests();
        this.maxClients = properties.maxClients();
        this.windowNanos = TimeUnit.SECONDS.toNanos(properties.windowSeconds());
        this.ticker = ticker;
    }

    public synchronized void check(String clientAddress) {
        long now = ticker.getAsLong();
        var expired = windows.values().iterator();
        while (expired.hasNext()) {
            if (now - expired.next().startedAt < windowNanos) break;
            expired.remove();
        }

        String key = clientAddress == null || clientAddress.isBlank() ? "unknown" : clientAddress;
        Window window = windows.get(key);
        if (window == null) {
            if (windows.size() >= maxClients) {
                // Do not evict a live client: that would reset its allowance under pressure.
                throw limited(now, windows.values().iterator().next());
            }
            window = new Window(now);
            windows.put(key, window);
        }
        if (window.used >= requests) throw limited(now, window);
        window.used++;
    }

    private AuthRateLimitException limited(long now, Window window) {
        long remaining = windowNanos - (now - window.startedAt);
        long seconds = Math.max(1, (remaining + TimeUnit.SECONDS.toNanos(1) - 1) / TimeUnit.SECONDS.toNanos(1));
        return new AuthRateLimitException(seconds);
    }

    private static class Window {
        final long startedAt;
        int used;

        Window(long startedAt) { this.startedAt = startedAt; }
    }
}
