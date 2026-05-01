package top.sywyar.pixivdownload.setup;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import top.sywyar.pixivdownload.quota.MultiModeConfig;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Static resource request rate limit by client IP.
 * Disabled when multi-mode.static-resource-request-limit-minute <= 0.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class StaticResourceRateLimitService {

    static final int MAX_TRACKED_IPS = 50_000;

    private final MultiModeConfig multiModeConfig;

    private final ConcurrentHashMap<String, WindowCounter> counters = new ConcurrentHashMap<>();

    public boolean isAllowed(String ip) {
        int limit = multiModeConfig.getStaticResourceRequestLimitMinute();
        if (limit <= 0) {
            return true;
        }
        long currentWindow = System.currentTimeMillis() / 60_000L;
        boolean atCapacity = counters.size() >= MAX_TRACKED_IPS;
        WindowCounter counter = counters.compute(ip, (k, existing) -> {
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
            log.warn("Static resource rate limit tracker at capacity ({} IPs), denying new IP {}", MAX_TRACKED_IPS, ip);
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
