package top.sywyar.pixivdownload.tts;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

/**
 * 邀请访客的在线 TTS（语音合成）请求限流，按邀请会话计每分钟窗口。
 *
 * <p>仅作用于携带有效邀请会话的访客；管理员 / solo 拥有者不受限——
 * 调用方（{@link top.sywyar.pixivdownload.tts.controller.TtsController}）负责在调用前判定身份。
 * {@code guest-invite.tts-request-limit-minute <= 0} 时关闭限流。
 *
 * <p>计数状态按分钟原子换代；新窗口的首个请求回收上一窗口全部 subject，
 * 跨窗口并发请求不会让活动窗口倒退或重新写入过期计数。
 */
@Service
@Slf4j
public class TtsRateLimitService {

    static final int MAX_TRACKED_KEYS = 50_000;
    private static final long WINDOW_MILLIS = 60_000L;

    private final TtsGuestRateLimitConfig config;
    private final LongSupplier currentTimeMillis;

    private final AtomicReference<WindowState> windowState =
            new AtomicReference<>(new WindowState(Long.MIN_VALUE));

    public TtsRateLimitService(TtsGuestRateLimitConfig config) {
        this(config, System::currentTimeMillis);
    }

    TtsRateLimitService(TtsGuestRateLimitConfig config, LongSupplier currentTimeMillis) {
        this.config = Objects.requireNonNull(config, "config");
        this.currentTimeMillis = Objects.requireNonNull(currentTimeMillis, "currentTimeMillis");
    }

    public int getLimitPerMinute() {
        return config.getTtsRequestLimitMinute();
    }

    public boolean isAllowed(String key) {
        int limit = getLimitPerMinute();
        if (limit <= 0) {
            return true;
        }
        long requestedWindow = currentTimeMillis.getAsLong() / WINDOW_MILLIS;
        while (true) {
            WindowState state = currentWindow(requestedWindow);
            WindowCounter counter = state.counterFor(key);
            if (counter == null) {
                if (windowState.get() != state) {
                    continue;
                }
                log.warn("TTS rate limit tracker at capacity ({} keys), denying new subject",
                        MAX_TRACKED_KEYS);
                return false;
            }
            int count = counter.count.incrementAndGet();
            if (windowState.get() == state) {
                return count <= limit;
            }
        }
    }

    private WindowState currentWindow(long requestedWindow) {
        while (true) {
            WindowState current = windowState.get();
            if (requestedWindow <= current.window) {
                return current;
            }
            WindowState replacement = new WindowState(requestedWindow);
            if (windowState.compareAndSet(current, replacement)) {
                return replacement;
            }
        }
    }

    private static class WindowCounter {
        final AtomicInteger count = new AtomicInteger(0);
    }

    private static class WindowState {
        final long window;
        final ConcurrentHashMap<String, WindowCounter> counters = new ConcurrentHashMap<>();
        final AtomicInteger trackedKeys = new AtomicInteger();

        private WindowState(long window) {
            this.window = window;
        }

        private WindowCounter counterFor(String key) {
            return counters.computeIfAbsent(key, ignored -> {
                if (trackedKeys.incrementAndGet() > MAX_TRACKED_KEYS) {
                    trackedKeys.decrementAndGet();
                    return null;
                }
                return new WindowCounter();
            });
        }
    }
}
