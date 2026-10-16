package top.sywyar.pixivdownload.core.download;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import top.sywyar.pixivdownload.plugin.api.download.lifecycle.DownloadAttempt;
import top.sywyar.pixivdownload.plugin.api.download.lifecycle.DownloadEvent;
import top.sywyar.pixivdownload.plugin.api.download.task.DownloadTasks;
import top.sywyar.pixivdownload.plugin.api.web.RequestOwnerIdentity;

import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

@DisplayName("下载执行终态持久化恢复")
class DownloadTaskResultStoreTest {
    @TempDir Path directory;
    private final RequestOwnerIdentity admin = RequestOwnerIdentity.adminScope();
    private final MutableClock clock = new MutableClock();

    private static final class MutableClock extends Clock {
        Instant now = Instant.parse("2020-01-01T00:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
        void expire() { now = now.plus(DownloadTaskRegistry.RETENTION).plusSeconds(1); }
    }

    private DownloadAttempt attempt() { return new DownloadAttempt(UUID.randomUUID(), "example", "opaque/作品"); }
    private DownloadTaskRegistry registry() { return new DownloadTaskRegistry(clock, new DownloadTaskResultStore(directory)); }
    private Path resultPath(DownloadAttempt attempt) {
        String id = attempt.attemptId().toString();
        return directory.resolve(id.substring(0, 2)).resolve(id + ".json");
    }

    @Test @DisplayName("Spring 实际装配启用终态存储")
    void componentWiringPersistsResults() {
        var attempt = attempt();
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(DownloadTaskResultStore.class, () -> new DownloadTaskResultStore(directory));
            context.register(DownloadTaskRegistry.class);
            context.refresh();
            var registry = context.getBean(DownloadTaskRegistry.class);
            registry.register(attempt, null, "example", "标题");
            registry.update(new DownloadEvent(attempt, DownloadEvent.Phase.COMPLETED));
        }
        assertThat(new DownloadTaskResultStore(directory).find(attempt.attemptId(), admin)).isPresent();
    }

    @ParameterizedTest @EnumSource(value = DownloadEvent.Phase.class, names = {"COMPLETED", "FAILED", "CANCELLED"})
    @DisplayName("快照过期和进程重建后仍按执行身份找回终态，不重新导入历史任务")
    void terminalSurvivesSnapshotExpiryAndRestart(DownloadEvent.Phase phase) {
        var registry = registry();
        var attempt = attempt();
        registry.register(attempt, "owner", "example", "标题");
        registry.update(new DownloadEvent(attempt, phase));
        var expected = registry.find(attempt.attemptId(), admin).orElseThrow();
        clock.expire();
        assertThat(registry.snapshot(admin).tasks()).isEmpty();
        for (var current : new DownloadTaskRegistry[]{registry, registry()}) {
            var result = current.find(attempt.attemptId(), admin).orElseThrow();
            assertThat(result).usingRecursiveComparison().ignoringFields("revision").isEqualTo(expected);
            assertThat(current.find(attempt.attemptId(), RequestOwnerIdentity.owner("owner"))).isPresent();
            assertThat(current.find(attempt.attemptId(), RequestOwnerIdentity.owner("other"))).isEmpty();
            assertThat(current.cancel(attempt.attemptId(), admin)).isEqualTo(DownloadTasks.CancelResult.TERMINAL);
            assertThat(current.cancel(attempt.attemptId(), RequestOwnerIdentity.owner("other")))
                    .isEqualTo(DownloadTasks.CancelResult.NOT_FOUND);
            assertThat(current.snapshot(admin).tasks()).isEmpty();
            assertThatThrownBy(() -> current.register(attempt, "owner", "example", "标题"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test @DisplayName("同一作品的新执行不借用旧执行结果，未完成任务不生成回执")
    void distinctAttemptsStayIndependent() {
        var registry = registry();
        var old = attempt();
        var next = attempt();
        registry.register(old, null, "example", "标题");
        registry.update(new DownloadEvent(old, DownloadEvent.Phase.COMPLETED));
        registry.register(next, null, "example", "标题");
        registry.update(new DownloadEvent(next, DownloadEvent.Phase.STARTED));
        clock.expire();
        assertThat(registry.snapshot(admin).tasks()).singleElement().satisfies(value -> {
            assertThat(value.attempt()).isEqualTo(next);
            assertThat(value.terminal()).isFalse();
        });
        assertThat(registry().find(next.attemptId(), admin)).isEmpty();
        assertThat(registry().find(old.attemptId(), admin)).isPresent();
        assertThat(registry().find(old.attemptId(), RequestOwnerIdentity.owner("other"))).isEmpty();
        assertThat(resultPath(next)).doesNotExist();
    }

    @Test @DisplayName("落盘失败保留内存终态，恢复可写后重试且不误报下载失败")
    void failedWriteRetainsResultUntilRetry() throws Exception {
        Path blocked = directory.resolve("blocked");
        var registry = new DownloadTaskRegistry(clock, new DownloadTaskResultStore(blocked));
        var attempt = attempt();
        registry.register(attempt, null, "example", "标题");
        Files.writeString(blocked, "not a directory", StandardCharsets.UTF_8);
        assertThat(registry.update(new DownloadEvent(attempt, DownloadEvent.Phase.COMPLETED))).isTrue();
        clock.expire();
        assertThat(registry.snapshot(admin).tasks()).singleElement()
                .satisfies(value -> assertThat(value.phase()).isEqualTo(DownloadEvent.Phase.COMPLETED));
        Files.delete(blocked);
        clock.expire();
        assertThat(registry.snapshot(admin).tasks()).isEmpty();
        assertThat(new DownloadTaskResultStore(blocked).find(attempt.attemptId(), admin)).isPresent();
    }

    @Test @DisplayName("损坏、超限、身份不符与非终态回执均不能作为成功证据")
    void invalidRecordsFailClosed() throws Exception {
        var store = new DownloadTaskResultStore(directory);
        var attempt = attempt();
        var registry = registry();
        registry.register(attempt, null, "example", "标题");
        registry.update(new DownloadEvent(attempt, DownloadEvent.Phase.COMPLETED));
        String valid = Files.readString(resultPath(attempt), StandardCharsets.UTF_8);
        for (String invalid : new String[]{"{", "null", "x".repeat(DownloadTaskResultStore.MAX_RECORD_BYTES + 1),
                valid.replace(attempt.attemptId().toString(), UUID.randomUUID().toString()),
                valid.replace("COMPLETED", "STARTED"), valid.replace("2020-01-01T00:00:00Z", "invalid")}) {
            Files.writeString(resultPath(attempt), invalid, StandardCharsets.UTF_8);
            assertThatThrownBy(() -> store.find(attempt.attemptId(), admin)).isInstanceOf(UncheckedIOException.class);
        }
        assertThat(store.find(UUID.randomUUID(), admin)).isEmpty();
    }
}
