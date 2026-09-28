package top.sywyar.pixivdownload.ffmpeg;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import top.sywyar.pixivdownload.core.ffmpeg.FfmpegProcessGate;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("FFmpeg 共享并发预算")
class FfmpegProcessGateAdapterTest {
    @Test
    @DisplayName("配置限制实际并发且重复关闭不会额外释放")
    void limitsConcurrencyAndReleasesOnce() throws Exception {
        FfmpegProperties properties = new FfmpegProperties();
        properties.setMaxConcurrent(2);
        FfmpegProcessGate gate = new FfmpegProcessGateAdapter(properties);
        var workers = Executors.newSingleThreadExecutor();
        var first = gate.acquire(() -> false);
        var second = gate.acquire(() -> false);
        try {
            CountDownLatch waiting = new CountDownLatch(1);
            var next = workers.submit(() -> {
                waiting.countDown();
                return gate.acquire(() -> false);
            });
            assertThat(waiting.await(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> next.get(300, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);
            first.close();
            try (var third = next.get(5, TimeUnit.SECONDS)) {
                first.close();
                var blocked = workers.submit(() -> gate.acquire(() -> false));
                assertThatThrownBy(() -> blocked.get(300, TimeUnit.MILLISECONDS))
                        .isInstanceOf(TimeoutException.class);
                second.close();
                blocked.get(5, TimeUnit.SECONDS).close();
            }
        } finally {
            first.close();
            second.close();
            workers.shutdownNow();
            assertThat(workers.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    @DisplayName("排队任务取消不占用进程预算")
    void cancellationDoesNotConsumePermit() throws Exception {
        FfmpegProperties properties = new FfmpegProperties();
        properties.setMaxConcurrent(1);
        FfmpegProcessGate gate = new FfmpegProcessGateAdapter(properties);
        var workers = Executors.newSingleThreadExecutor();
        AtomicBoolean cancelled = new AtomicBoolean();
        CountDownLatch waiting = new CountDownLatch(1);
        try (var held = gate.acquire(() -> false)) {
            var next = workers.submit(() -> gate.acquire(() -> {
                waiting.countDown();
                return cancelled.get();
            }));
            assertThat(waiting.await(5, TimeUnit.SECONDS)).isTrue();
            cancelled.set(true);
            assertThatThrownBy(() -> next.get(5, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(CancellationException.class);
        } finally {
            workers.shutdownNow();
        }
        try (var available = gate.acquire(() -> false)) {
            assertThat(available).isNotNull();
        }
    }

    @Test
    @DisplayName("配置绑定采用显式值并拒绝无界或非法并发")
    void bindsAndValidatesConfiguration() {
        var context = new ApplicationContextRunner()
                .withUserConfiguration(FfmpegProperties.class,
                        org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration.class);
        context.withPropertyValues("ffmpeg.max-concurrent=3").run(app ->
                assertThat(app.getBean(FfmpegProperties.class).getMaxConcurrent()).isEqualTo(3));
        for (String value : new String[]{"0", "-1", "9", "invalid"}) {
            context.withPropertyValues("ffmpeg.max-concurrent=" + value).run(app ->
                    assertThat(app).hasFailed());
        }
    }
}
