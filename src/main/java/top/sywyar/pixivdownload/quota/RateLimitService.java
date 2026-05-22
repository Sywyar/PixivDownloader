package top.sywyar.pixivdownload.quota;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.setup.guest.GuestInviteConfig;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 请求速率限制服务，使用分钟级滑动窗口追踪每个限流键在当前分钟内的请求总数。
 * <ul>
 *   <li>多人模式游客按 UUID 限流，上限取 {@code multi-mode.request-limit-minute}；</li>
 *   <li>邀请访客按邀请码限流（{@link #isAllowedForInvite}），上限取 {@code guest-invite.request-limit-minute}，
 *       solo / multi 两种模式下都生效。</li>
 * </ul>
 * 对应上限 {@code <= 0} 时该类请求不限流，直接放行。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RateLimitService {

    private final MultiModeConfig multiModeConfig;
    private final GuestInviteConfig guestInviteConfig;
    private final AppMessages messages;

    /** 限流键（多人模式 UUID 或 {@code invite:<code>}）→ 当前分钟窗口计数器 */
    private final ConcurrentHashMap<String, WindowCounter> counters = new ConcurrentHashMap<>();

    /**
     * 多人模式游客：检查该 UUID 在当前分钟窗口内是否允许继续发起请求。
     * 若允许则原子地将计数加一并返回 true；若已达上限则返回 false。
     */
    public boolean isAllowed(String uuid) {
        return isAllowedWithin(uuid, multiModeConfig.getRequestLimitMinute());
    }

    /**
     * 邀请访客：按邀请码限流（同一邀请码跨浏览器共享额度），上限取
     * {@code guest-invite.request-limit-minute}。
     */
    public boolean isAllowedForInvite(String inviteKey) {
        return isAllowedWithin(inviteKey, guestInviteConfig.getRequestLimitMinute());
    }

    private boolean isAllowedWithin(String key, int limit) {
        if (limit <= 0) {
            return true;
        }
        long currentWindow = System.currentTimeMillis() / 60_000L;
        WindowCounter counter = counters.compute(key, (k, existing) -> {
            if (existing == null || existing.window != currentWindow) {
                return new WindowCounter(currentWindow);
            }
            return existing;
        });
        return counter.count.incrementAndGet() <= limit;
    }

    /** 每分钟清理已过期的分钟窗口计数器，防止内存泄漏。 */
    @Scheduled(fixedDelay = 60_000)
    public void cleanupExpiredCounters() {
        long currentWindow = System.currentTimeMillis() / 60_000L;
        int removed = 0;
        var iter = counters.entrySet().iterator();
        while (iter.hasNext()) {
            if (iter.next().getValue().window < currentWindow) {
                iter.remove();
                removed++;
            }
        }
        if (removed > 0) {
            log.debug(message("quota.log.rate-limit.cleanup-expired", removed));
        }
    }

    private String message(String code, Object... args) {
        return messages.getForLog(code, args);
    }

    @RequiredArgsConstructor
    private static class WindowCounter {
        final long window;
        final AtomicInteger count = new AtomicInteger(0);
    }
}
