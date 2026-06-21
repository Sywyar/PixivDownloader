package top.sywyar.pixivdownload.setup;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 登录端点暴力破解防护。
 * 使用分钟级滑动窗口，按客户端 IP 追踪每分钟内的登录尝试次数。
 * 当 setup.login-rate-limit-minute <= 0 时禁用，所有请求直接放行。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LoginRateLimitService {

    private final SetupProperties setupProperties;

    private final ConcurrentHashMap<String, WindowCounter> counters = new ConcurrentHashMap<>();

    /**
     * 检查该 IP 在当前分钟窗口内是否允许继续尝试登录。
     * 若允许则原子地将计数加一并返回 true；若已达上限则返回 false。
     */
    public boolean isAllowed(String ip) {
        int limit = setupProperties.getLoginRateLimitMinute();
        if (limit <= 0) return true;
        long currentWindow = System.currentTimeMillis() / 60_000L;
        WindowCounter counter = counters.compute(ip, (k, existing) -> {
            if (existing == null || existing.window != currentWindow) {
                return new WindowCounter(currentWindow);
            }
            return existing;
        });
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
