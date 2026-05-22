package top.sywyar.pixivdownload.setup;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import top.sywyar.pixivdownload.quota.MultiModeConfig;
import top.sywyar.pixivdownload.setup.guest.GuestInviteConfig;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Static resource request rate limit, with a per-minute sliding window.
 * <ul>
 *   <li>Multi-mode guests are limited by client IP ({@code multi-mode.static-resource-request-limit-minute}).</li>
 *   <li>Invited guests are limited by invite code ({@code guest-invite.static-resource-request-limit-minute}),
 *       in both solo and multi mode.</li>
 * </ul>
 * Disabled when the relevant limit is {@code <= 0}.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class StaticResourceRateLimitService {

    static final int MAX_TRACKED_IPS = 50_000;

    private final MultiModeConfig multiModeConfig;
    private final GuestInviteConfig guestInviteConfig;

    private final ConcurrentHashMap<String, WindowCounter> counters = new ConcurrentHashMap<>();

    /** Multi-mode guests: rate limit by client IP. */
    public boolean isAllowed(String ip) {
        return isAllowedWithin(ip, multiModeConfig.getStaticResourceRequestLimitMinute());
    }

    /** Invited guests: rate limit by invite code (shared across browsers using the same code). */
    public boolean isAllowedForInvite(String inviteKey) {
        return isAllowedWithin(inviteKey, guestInviteConfig.getStaticResourceRequestLimitMinute());
    }

    private boolean isAllowedWithin(String key, int limit) {
        if (limit <= 0) {
            return true;
        }
        long currentWindow = System.currentTimeMillis() / 60_000L;
        boolean atCapacity = counters.size() >= MAX_TRACKED_IPS;
        WindowCounter counter = counters.compute(key, (k, existing) -> {
            if (existing == null) {
                if (atCapacity) {
                    return null;
                }
                return new WindowCounter(currentWindow);
            }
            if (existing.window != currentWindow) {
                return new WindowCounter(currentWindow);
            }
            return existing;
        });
        if (counter == null) {
            log.warn("Static resource rate limit tracker at capacity ({} keys), denying new key {}", MAX_TRACKED_IPS, key);
            return false;
        }
        return counter.count.incrementAndGet() <= limit;
    }

    @Scheduled(fixedDelay = 60_000)
    public void cleanupExpiredCounters() {
        long currentWindow = System.currentTimeMillis() / 60_000L;
        counters.entrySet().removeIf(e -> e.getValue().window < currentWindow);
    }

    @RequiredArgsConstructor
    private static class WindowCounter {
        final long window;
        final AtomicInteger count = new AtomicInteger(0);
    }
}
