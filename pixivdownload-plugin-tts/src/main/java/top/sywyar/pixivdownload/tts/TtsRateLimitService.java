package top.sywyar.pixivdownload.tts;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 邀请访客的在线 TTS（语音合成）请求限流，按邀请会话计每分钟窗口。
 *
 * <p>仅作用于携带有效邀请会话的访客；管理员 / solo 拥有者不受限——
 * 调用方（{@link top.sywyar.pixivdownload.tts.controller.TtsController}）负责在调用前判定身份。
 * {@code guest-invite.tts-request-limit-minute <= 0} 时关闭限流。
 *
 * <p>内存滑动窗口清理沿用 {@code StaticResourceRateLimitService} 的既有模式（早于 MaintenanceCoordinator 规则，保持一致）。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class TtsRateLimitService {

    static final int MAX_TRACKED_KEYS = 50_000;

    private final TtsGuestRateLimitConfig config;

    private final ConcurrentHashMap<String, WindowCounter> counters = new ConcurrentHashMap<>();

    public int getLimitPerMinute() {
        return config.getTtsRequestLimitMinute();
    }

    public boolean isAllowed(String key) {
        int limit = getLimitPerMinute();
        if (limit <= 0) {
            return true;
        }
        long currentWindow = System.currentTimeMillis() / 60_000L;
        boolean atCapacity = counters.size() >= MAX_TRACKED_KEYS;
        WindowCounter counter = counters.compute(key, (k, existing) -> {
            if (existing == null) {
                return atCapacity ? null : new WindowCounter(currentWindow);
            }
            if (existing.window != currentWindow) {
                return new WindowCounter(currentWindow);
            }
            return existing;
        });
        if (counter == null) {
            log.warn("TTS rate limit tracker at capacity ({} keys), denying new subject", MAX_TRACKED_KEYS);
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
