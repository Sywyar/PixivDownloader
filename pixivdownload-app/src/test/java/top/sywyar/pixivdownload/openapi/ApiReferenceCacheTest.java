package top.sywyar.pixivdownload.openapi;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Timeout(30)
class ApiReferenceCacheTest {
    @Test
    void warmsBeforeAnyReadAndServesTheFirstLocaleWhileTheNextIsRendering() throws Exception {
        var first = new ApiReferenceDocument(new byte[] {1});
        var second = new ApiReferenceDocument(new byte[] {2});
        var secondStarted = new CountDownLatch(1);
        var releaseSecond = new CountDownLatch(1);
        var calls = new AtomicInteger();
        var inputs = inputs(false);
        try (var cache = new ApiReferenceCache(List.of(Locale.ENGLISH, Locale.JAPANESE), () -> inputs, (locale, access) -> {
            calls.incrementAndGet();
            if (locale.equals(Locale.ENGLISH)) return first;
            secondStarted.countDown();
            releaseSecond.await();
            return second;
        })) {
            cache.start();
            cache.start();
            assertThat(secondStarted.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(cache.read(Locale.ENGLISH)).isSameAs(first);
            releaseSecond.countDown();
            assertThat(cache.read(Locale.JAPANESE)).isSameAs(second);
            assertThat(cache.read(Locale.ENGLISH)).isSameAs(first);
            assertThat(calls.get()).isEqualTo(2);
        }
    }

    @Test
    void discardsAChangedGenerationAndRefreshesAgainWithoutAnHttpRequest() throws Exception {
        var inputs = new AtomicReference<>(inputs(false));
        var firstStarted = new CountDownLatch(1);
        var releaseFirst = new CountDownLatch(1);
        var updated = new CountDownLatch(1);
        var backToSolo = new CountDownLatch(1);
        var calls = new AtomicInteger();
        var expected = new ApiReferenceDocument(new byte[] {1});
        try (var cache = new ApiReferenceCache(List.of(Locale.ENGLISH), inputs::get, (locale, access) -> {
            if (calls.getAndIncrement() == 0) {
                firstStarted.countDown();
                releaseFirst.await();
            } else if (access.multi()) {
                updated.countDown();
                return expected;
            } else {
                backToSolo.countDown();
            }
            return new ApiReferenceDocument(new byte[] {0});
        })) {
            cache.start();
            assertThat(firstStarted.await(10, TimeUnit.SECONDS)).isTrue();
            inputs.set(inputs(true));
            releaseFirst.countDown();
            assertThat(updated.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(cache.read(Locale.ENGLISH)).isSameAs(expected);
            inputs.set(inputs(false));
            assertThat(backToSolo.await(12, TimeUnit.SECONDS)).isTrue();
            assertThat(cache.read(Locale.ENGLISH)).isNotSameAs(expected);
        }
    }

    @Test
    void retriesAFailedLocaleWithoutLosingTheOtherLanguage() throws Exception {
        var attempts = new AtomicInteger();
        var english = new ApiReferenceDocument(new byte[] {1});
        var japanese = new ApiReferenceDocument(new byte[] {2});
        var retry = new CountDownLatch(1);
        var inputs = inputs(false);
        try (var cache = new ApiReferenceCache(List.of(Locale.ENGLISH, Locale.JAPANESE), () -> inputs, (locale, access) -> {
            if (locale.equals(Locale.ENGLISH)) return english;
            if (attempts.getAndIncrement() == 0) throw new IllegalArgumentException("invalid test schema");
            retry.countDown();
            return japanese;
        })) {
            cache.start();
            assertThatThrownBy(() -> cache.read(Locale.JAPANESE)).hasRootCauseMessage("invalid test schema");
            assertThat(cache.read(Locale.ENGLISH)).isSameAs(english);
            assertThat(retry.await(12, TimeUnit.SECONDS)).isTrue();
            // The worker publishes immediately after the renderer returns.
            org.awaitility.Awaitility.await().ignoreExceptions().untilAsserted(() ->
                    assertThat(cache.read(Locale.JAPANESE)).isSameAs(japanese));
        }
    }

    private static ApiReferenceCache.Inputs inputs(boolean multi) {
        return new ApiReferenceCache.Inputs(1, List.of(), Map.of(), 1,
                new ApiReferenceSecurity.InstanceAccess(true, multi));
    }
}
