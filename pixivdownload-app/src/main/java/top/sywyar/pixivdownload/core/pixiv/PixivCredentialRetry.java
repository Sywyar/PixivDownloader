package top.sywyar.pixivdownload.core.pixiv;

import org.springframework.stereotype.Service;
import top.sywyar.pixivdownload.plugin.api.download.context.AcquisitionCredentials;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import top.sywyar.pixivdownload.plugin.api.download.context.AcquisitionCredentialsUnavailableException;
import java.util.regex.Pattern;

/** 仅重试一个只读上游请求；选择顺序和持久化配置不受成功候选影响。 */
@Service
public final class PixivCredentialRetry implements org.springframework.beans.factory.DisposableBean {
    private static final Pattern ACCOUNT = Pattern.compile("(?:^|;\\s*)PHPSESSID=([1-9][0-9]*)_[^;\\s]+");
    private static final Pattern PUBLIC_READ = Pattern.compile("/ajax/(?:"
            + "illust/[1-9][0-9]*(?:/(?:pages|ugoira_meta))?"
            + "|novel/[1-9][0-9]*|novel/(?:series|series_content)/[1-9][0-9]*"
            + "|series/[1-9][0-9]*|user/[1-9][0-9]*/profile/(?:all|top)"
            + "|commission/page/users/[1-9][0-9]*/request|search/(?:artworks|novels)/[^/]+)");
    private static final int MAX_HEALTH = 1024;
    private static final long DEFAULT_COOLDOWN = 30_000;
    private static final long HEALTH_TTL = 24 * 60 * 60 * 1000L;
    private static final long REQUEST_BUDGET = 60_000;
    private static final ScopedValue<RequestBudget> CURRENT_REQUEST = ScopedValue.newInstance();
    private final ScheduledThreadPoolExecutor deadlines = new ScheduledThreadPoolExecutor(1,
            Thread.ofPlatform().daemon().name("pixiv-request-deadline").factory());
    private record RequestBudget(long deadline, ScheduledThreadPoolExecutor scheduler,
                                 AtomicReference<ScheduledFuture<?>> cancellation) {}
    private final Clock clock;
    private final Map<String, Health> health = new LinkedHashMap<>();
    private record Health(long until, int status) {}

    public PixivCredentialRetry() { this(Clock.systemUTC()); }
    PixivCredentialRetry(Clock clock) {
        this.clock = clock;
        deadlines.setRemoveOnCancelPolicy(true);
    }

    @Override public void destroy() { deadlines.shutdownNow(); }

    /** 实际 HTTP 请求共用本次候选遍历的绝对截止；关闭响应后解除取消回调。 */
    public static void bindRequest(org.apache.hc.core5.http.ClassicHttpRequest request) {
        if (!CURRENT_REQUEST.isBound()) return;
        RequestBudget budget = CURRENT_REQUEST.get();
        if (!(request instanceof org.apache.hc.client5.http.classic.methods.HttpUriRequestBase cancellable))
            throw new IllegalStateException("HTTP request cannot enforce acquisition deadline");
        ScheduledFuture<?> timer = budget.scheduler().schedule(cancellable::cancel,
                Math.max(0, budget.deadline() - System.nanoTime()), TimeUnit.NANOSECONDS);
        ScheduledFuture<?> previous = budget.cancellation().getAndSet(timer);
        if (previous != null) previous.cancel(false);
    }

    @FunctionalInterface
    public interface Request<T, X extends Exception> { T get(String credential) throws X; }

    public <T, X extends Exception> T execute(URI uri, String primary, Request<T, X> request) throws X {
        var scope = AcquisitionCredentials.current();
        if (scope == null || !scope.primary().equals(primary)) return request.get(primary);
        boolean media = uri != null && "https".equalsIgnoreCase(uri.getScheme())
                && uri.getHost() != null && uri.getHost().toLowerCase(java.util.Locale.ROOT).endsWith(".pximg.net");
        boolean publicRead = media || uri != null && "https".equalsIgnoreCase(uri.getScheme())
                && "www.pixiv.net".equalsIgnoreCase(uri.getHost()) && PUBLIC_READ.matcher(uri.getPath()).matches();
        String primaryAccount = account(primary);
        long budgetMillis = media ? 600_000 : REQUEST_BUDGET;
        long started = clock.millis();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budgetMillis);
        long retryAfter = Long.MAX_VALUE;
        for (String candidate : AcquisitionCredentials.candidates(primary)) {
            scope.checkCancelled();
            if (clock.millis() - started >= budgetMillis || System.nanoTime() >= deadline)
                throw new AcquisitionCredentialsUnavailableException(DEFAULT_COOLDOWN);
            String candidateAccount = account(candidate);
            if (!publicRead && !candidate.equals(primary)
                    && (primaryAccount == null || !primaryAccount.equals(candidateAccount))) continue;
            if (candidateAccount == null) continue;
            String key = "credential:" + digest(candidate);
            String accountKey = "account:" + digest(candidateAccount);
            Health blocked = blocked(key);
            if (blocked == null) blocked = blocked(accountKey);
            if (blocked != null) {
                if (blocked.status() == 429)
                    retryAfter = Math.min(retryAfter, Math.max(1, blocked.until() - clock.millis()));
                continue;
            }
            RequestBudget budget = new RequestBudget(deadline, deadlines, new AtomicReference<>());
            try {
                T result = ScopedValue.where(CURRENT_REQUEST, budget).call(() -> request.get(candidate));
                scope.checkCancelled();
                if (System.nanoTime() >= deadline) throw new AcquisitionCredentialsUnavailableException(DEFAULT_COOLDOWN);
                return result;
            } catch (PixivAjaxException failure) {
                scope.checkCancelled();
                if (System.nanoTime() >= deadline) throw new AcquisitionCredentialsUnavailableException(DEFAULT_COOLDOWN);
                if (failure.failure() != PixivAjaxFailure.HTTP_STATUS
                        || failure.statusCode() != 401 && failure.statusCode() != 429) throw failure;
                long delay = failure.statusCode() == 401 ? HEALTH_TTL
                        : Math.max(DEFAULT_COOLDOWN, failure.retryAfterMillis());
                if (failure.statusCode() == 429) retryAfter = Math.min(retryAfter, delay);
                block(failure.statusCode() == 401 ? key : accountKey,
                        new Health(saturatingAdd(clock.millis(), delay), failure.statusCode()));
            } catch (Exception failure) {
                scope.checkCancelled();
                if (System.nanoTime() >= deadline)
                    throw new AcquisitionCredentialsUnavailableException(DEFAULT_COOLDOWN);
                throw failure;
            } finally {
                ScheduledFuture<?> timer = budget.cancellation().getAndSet(null);
                if (timer != null) timer.cancel(false);
            }
        }
        scope.checkCancelled();
        throw new AcquisitionCredentialsUnavailableException(retryAfter == Long.MAX_VALUE ? 0 : retryAfter);
    }

    private synchronized Health blocked(String key) {
        Health value = health.get(key);
        if (value != null && value.until() <= clock.millis()) { health.remove(key); return null; }
        return value;
    }
    private synchronized void block(String key, Health value) {
        health.entrySet().removeIf(entry -> entry.getValue().until() <= clock.millis());
        // 容量耗尽时保留已有冷却，拒绝新记录，不能靠淘汰解除其它账号的限流。
        if (health.size() >= MAX_HEALTH && !health.containsKey(key))
            throw new AcquisitionCredentialsUnavailableException(DEFAULT_COOLDOWN);
        Health previous = health.get(key);
        if (previous == null || previous.until() < value.until()) health.put(key, value);
    }
    private static String account(String cookie) {
        var matcher = ACCOUNT.matcher(cookie);
        return matcher.find() ? matcher.group(1) : null;
    }
    private static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static long saturatingAdd(long a, long b) { return b > Long.MAX_VALUE - a ? Long.MAX_VALUE : a + b; }
    static long retryAfter(String value, Clock clock) {
        if (value == null || value.length() > 128) return 0;
        try {
            if (value.matches("[0-9]+")) {
                long seconds = Long.parseLong(value);
                return seconds > Long.MAX_VALUE / 1000 ? Long.MAX_VALUE : seconds * 1000;
            }
            return Math.max(0, ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - clock.millis());
        } catch (RuntimeException invalid) { return 0; }
    }
}
