package top.sywyar.pixivdownload.setup;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import top.sywyar.pixivdownload.core.appconfig.MultiModeConfig;
import top.sywyar.pixivdownload.setup.guest.config.GuestInviteConfig;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 静态资源请求按固定分钟窗口限流。
 * <ul>
 *   <li>多人模式游客按客户端 IP 计数（{@code multi-mode.static-resource-request-limit-minute}）。</li>
 *   <li>邀请访客在两种模式下均按邀请码计数（{@code guest-invite.static-resource-request-limit-minute}）。</li>
 * </ul>
 * 对应上限 {@code <= 0} 时禁用。
 */
@Service
@RequiredArgsConstructor
public class StaticResourceRateLimitService {

    static final int MAX_TRACKED_IPS = 50_000;

    private final MultiModeConfig multiModeConfig;
    private final GuestInviteConfig guestInviteConfig;

    private final ConcurrentHashMap<String, WindowCounter> counters = new ConcurrentHashMap<>();

    /** 多人模式游客按客户端 IP 限流。 */
    public boolean isAllowed(String ip) {
        return isAllowedWithin(ip, multiModeConfig.getStaticResourceRequestLimitMinute());
    }

    /** 邀请访客按邀请码限流，同一邀请码的浏览器共享计数。 */
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
