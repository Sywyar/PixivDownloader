package top.sywyar.pixivdownload.tts;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("邀请访客在线 TTS 限流")
class TtsRateLimitServiceTest {

    @Test
    @DisplayName("同一 subject 共享窗口而不同 subject 相互隔离")
    void isolatesCountersBySubject() {
        TtsRateLimitService service = new TtsRateLimitService(config(2));

        assertThat(service.isAllowed("invite:1")).isTrue();
        assertThat(service.isAllowed("invite:1")).isTrue();
        assertThat(service.isAllowed("invite:1")).isFalse();
        assertThat(service.isAllowed("invite:2")).isTrue();
    }

    @Test
    @DisplayName("非正上限关闭限流")
    void disablesNonPositiveLimit() {
        TtsRateLimitService service = new TtsRateLimitService(config(0));

        assertThat(service.isAllowed("invite:1")).isTrue();
        assertThat(service.isAllowed("invite:1")).isTrue();
    }

    @Test
    @DisplayName("插件重建后的服务实例不继承旧计数器")
    void rebuiltServiceHasIndependentCounters() {
        TtsRateLimitService first = new TtsRateLimitService(config(1));
        TtsRateLimitService second = new TtsRateLimitService(config(1));

        assertThat(first.isAllowed("invite:1")).isTrue();
        assertThat(first.isAllowed("invite:1")).isFalse();
        assertThat(second.isAllowed("invite:1")).isTrue();
    }

    @Test
    @DisplayName("热重绑配置后同一服务实例立即使用新上限且保留已有计数")
    void observesReboundLimitWithoutResettingCounters() {
        TtsGuestRateLimitConfig config = config(2);
        TtsRateLimitService service = new TtsRateLimitService(config);

        assertThat(service.isAllowed("invite:1")).isTrue();
        config.setTtsRequestLimitMinute(1);

        assertThat(service.getLimitPerMinute()).isEqualTo(1);
        assertThat(service.isAllowed("invite:1")).isFalse();
    }

    @Test
    @DisplayName("容量拒绝告警不得记录不透明 subject")
    void capacityWarningDoesNotLogOpaqueSubject() {
        TtsRateLimitService service = new TtsRateLimitService(config(1));
        for (int index = 0; index < TtsRateLimitService.MAX_TRACKED_KEYS; index++) {
            assertThat(service.isAllowed("fixture:" + index)).isTrue();
        }
        Logger logger = (Logger) LoggerFactory.getLogger(TtsRateLimitService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            String opaqueSubject = "invite:secret-stable-id";

            assertThat(service.isAllowed(opaqueSubject)).isFalse();

            assertThat(appender.list)
                    .extracting(ILoggingEvent::getFormattedMessage)
                    .singleElement()
                    .asString()
                    .doesNotContain(opaqueSubject)
                    .contains("denying new subject");
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    @DisplayName("新分钟的首个请求先清理过期容量再接纳新 subject")
    void newWindowLazilyReclaimsExpiredCapacity() {
        AtomicLong clock = new AtomicLong();
        TtsRateLimitService service = new TtsRateLimitService(config(1), clock::get);
        for (int index = 0; index < TtsRateLimitService.MAX_TRACKED_KEYS; index++) {
            assertThat(service.isAllowed("fixture:" + index)).isTrue();
        }

        clock.set(60_000L);

        assertThat(service.isAllowed("invite:new-window")).isTrue();
    }

    @Test
    @DisplayName("分钟交界的迟到旧请求不得回退窗口或恢复已过期容量")
    void staleRequestCannotMoveWindowBackwards() throws Exception {
        CountDownLatch oldWindowCaptured = new CountDownLatch(1);
        CountDownLatch releaseOldRequest = new CountDownLatch(1);
        LongSupplier clock = () -> {
            if (!Thread.currentThread().getName().equals("tts-old-window-request")) {
                return 60_000L;
            }
            oldWindowCaptured.countDown();
            try {
                if (!releaseOldRequest.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("timed out waiting to release old request");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("old request interrupted", interrupted);
            }
            return 0L;
        };
        TtsRateLimitService service = new TtsRateLimitService(config(1), clock);
        FutureTask<Boolean> oldRequest =
                new FutureTask<>(() -> service.isAllowed("invite:shared"));
        Thread oldRequestThread = new Thread(oldRequest, "tts-old-window-request");
        oldRequestThread.start();

        assertThat(oldWindowCaptured.await(5, TimeUnit.SECONDS)).isTrue();
        try {
            assertThat(service.isAllowed("invite:shared")).isTrue();
        } finally {
            releaseOldRequest.countDown();
        }

        assertThat(oldRequest.get(5, TimeUnit.SECONDS)).isFalse();
        assertThat(service.isAllowed("invite:shared")).isFalse();
    }

    private static TtsGuestRateLimitConfig config(int limit) {
        TtsGuestRateLimitConfig config = new TtsGuestRateLimitConfig();
        config.setTtsRequestLimitMinute(limit);
        return config;
    }
}
