package com.realtimecollab.security;

import com.realtimecollab.exception.AuthRateLimitException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.*;

class AuthRateLimiterTest {
    private final AtomicLong now = new AtomicLong();

    private AuthRateLimiter limiter(int requests, int capacity) {
        return new AuthRateLimiter(new AuthRateLimitProperties(requests, 60, capacity), now::get);
    }

    @Test
    void allowsConfiguredCountThenRejectsWithoutAffectingAnotherClient() {
        var limiter = limiter(2, 10);
        limiter.check("client-a");
        limiter.check("client-a");
        assertThatThrownBy(() -> limiter.check("client-a")).isInstanceOf(AuthRateLimitException.class);
        assertThatCode(() -> limiter.check("client-b")).doesNotThrowAnyException();
    }

    @Test
    void rejectionDoesNotExtendWindowAndAllowanceResetsAtBoundary() {
        var limiter = limiter(1, 10);
        limiter.check("client");
        now.set(TimeUnit.SECONDS.toNanos(30));
        assertThatExceptionOfType(AuthRateLimitException.class).isThrownBy(() -> limiter.check("client"))
                .satisfies(error -> assertThat(error.getRetryAfterSeconds()).isEqualTo(30));
        now.set(TimeUnit.SECONDS.toNanos(60));
        assertThatCode(() -> limiter.check("client")).doesNotThrowAnyException();
    }

    @Test
    void retryAfterRoundsUpToWholeSeconds() {
        var limiter = limiter(1, 10);
        limiter.check("client");
        now.set(TimeUnit.MILLISECONDS.toNanos(59999));
        assertThatExceptionOfType(AuthRateLimitException.class).isThrownBy(() -> limiter.check("client"))
                .satisfies(error -> assertThat(error.getRetryAfterSeconds()).isEqualTo(1));
    }

    @Test
    void fullStoreRejectsNewClientsWithoutResettingLiveBudgets() {
        var limiter = limiter(1, 2);
        limiter.check("first");
        now.set(TimeUnit.SECONDS.toNanos(5));
        limiter.check("second");
        assertThatExceptionOfType(AuthRateLimitException.class).isThrownBy(() -> limiter.check("third"))
                .satisfies(error -> assertThat(error.getRetryAfterSeconds()).isEqualTo(55));
        assertThatThrownBy(() -> limiter.check("first")).isInstanceOf(AuthRateLimitException.class);
        now.set(TimeUnit.SECONDS.toNanos(60));
        assertThatCode(() -> limiter.check("third")).doesNotThrowAnyException();
        assertThatThrownBy(() -> limiter.check("second")).isInstanceOf(AuthRateLimitException.class);
    }

    @Test
    void expiredClientsAreRemovedEvenWhenNoFurtherRequestsComeFromThem() {
        var limiter = limiter(1, 1);
        for (int index = 0; index < 100; index++) {
            now.set(TimeUnit.SECONDS.toNanos(index * 60L));
            limiter.check("client-" + index);
        }
    }

    @Test
    void missingAddressesShareOneFallbackBudget() {
        var limiter = limiter(1, 10);
        limiter.check(null);
        assertThatThrownBy(() -> limiter.check(" ")).isInstanceOf(AuthRateLimitException.class);
    }

    @Test
    void concurrentRequestsCannotOverspendOneBudget() throws Exception {
        var limiter = limiter(20, 10);
        var workers = Executors.newFixedThreadPool(8);
        try {
            var results = new ArrayList<Future<Boolean>>();
            for (int index = 0; index < 100; index++) {
                results.add(workers.submit(() -> {
                    try { limiter.check("shared-client"); return true; }
                    catch (AuthRateLimitException ignored) { return false; }
                }));
            }
            int allowed = 0;
            for (var result : results) if (result.get(5, TimeUnit.SECONDS)) allowed++;
            assertThat(allowed).isEqualTo(20);
        } finally {
            workers.shutdownNow();
        }
    }
}
