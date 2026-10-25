package top.sywyar.pixivdownload.core.download;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import top.sywyar.pixivdownload.plugin.api.download.lifecycle.DownloadAttempt;
import top.sywyar.pixivdownload.plugin.api.download.lifecycle.DownloadEvent;
import top.sywyar.pixivdownload.plugin.api.download.task.DownloadTasks;
import top.sywyar.pixivdownload.plugin.api.download.submission.DownloadSubmission;
import top.sywyar.pixivdownload.plugin.api.download.submission.DownloadTaskException;
import top.sywyar.pixivdownload.plugin.api.web.RequestOwnerIdentity;

import java.io.UncheckedIOException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

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

    @Test @DisplayName("终态写入缓慢时其他任务仍可登记和查询，原结果只保存一次")
    void slowSaveDoesNotBlockOtherTasks() throws Exception {
        var store = spy(new DownloadTaskResultStore(directory));
        var registry = new DownloadTaskRegistry(clock, store);
        var finished = attempt();
        registry.register(finished, "owner", "example", "标题");
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(call -> {
            entered.countDown();
            assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
            return call.callRealMethod();
        }).when(store).save(any(), any());
        var workers = Executors.newFixedThreadPool(2);
        try {
            var saving = workers.submit(() -> registry.update(new DownloadEvent(finished, DownloadEvent.Phase.COMPLETED)));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            workers.submit(() -> {
                var next = attempt();
                registry.register(next, "owner", "example", "另一个任务");
                assertThat(registry.find(finished.attemptId(), admin).orElseThrow().terminal()).isTrue();
                assertThat(registry.find(next.attemptId(), RequestOwnerIdentity.owner("other"))).isEmpty();
                assertThat(registry.snapshot(admin).tasks()).hasSize(2);
                assertThat(registry.cancel(next.attemptId(), admin)).isEqualTo(DownloadTasks.CancelResult.NOT_CANCELLABLE);
                assertThat(registry.update(new DownloadEvent(finished, DownloadEvent.Phase.FAILED))).isFalse();
            }).get(2, TimeUnit.SECONDS);
            release.countDown();
            assertThat(saving.get(5, TimeUnit.SECONDS)).isTrue();
            verify(store, times(1)).save(any(), any());
            assertThat(registry().find(finished.attemptId(), admin).orElseThrow().phase())
                    .isEqualTo(DownloadEvent.Phase.COMPLETED);
        } finally {
            release.countDown();
            workers.shutdown();
            assertThat(workers.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @ParameterizedTest @ValueSource(strings = {"find", "cancel", "register"})
    @DisplayName("历史回执读取缓慢不阻断其他任务，并保留查询取消与重复登记语义")
    void slowArchiveReadDoesNotBlockOtherTasks(String operation) throws Exception {
        var archived = attempt();
        var previous = registry();
        previous.register(archived, "owner", "example", "标题");
        previous.update(new DownloadEvent(archived, DownloadEvent.Phase.COMPLETED));
        var store = spy(new DownloadTaskResultStore(directory));
        var registry = new DownloadTaskRegistry(clock, store);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(call -> {
            entered.countDown();
            assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
            return call.callRealMethod();
        }).when(store).find(eq(archived.attemptId()), any());
        var workers = Executors.newFixedThreadPool(2);
        try {
            var reading = workers.submit(() -> {
                switch (operation) {
                    case "find" -> assertThat(registry.find(archived.attemptId(), admin).orElseThrow().terminal()).isTrue();
                    case "cancel" -> assertThat(registry.cancel(archived.attemptId(), admin)).isEqualTo(DownloadTasks.CancelResult.TERMINAL);
                    case "register" -> assertThatThrownBy(() -> registry.register(archived, "owner", "example", "替换"))
                            .isInstanceOf(IllegalArgumentException.class);
                    default -> throw new AssertionError(operation);
                }
            });
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            workers.submit(() -> {
                var next = attempt();
                registry.register(next, "owner", "example", "另一个任务");
                assertThat(registry.find(next.attemptId(), admin)).isPresent();
                assertThat(registry.snapshot(admin).tasks()).singleElement()
                        .satisfies(value -> assertThat(value.attempt()).isEqualTo(next));
            }).get(2, TimeUnit.SECONDS);
            release.countDown();
            reading.get(5, TimeUnit.SECONDS);
            assertThat(registry.find(archived.attemptId(), RequestOwnerIdentity.owner("other"))).isEmpty();
        } finally {
            release.countDown();
            workers.shutdown();
            assertThat(workers.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test @DisplayName("过期重试写入缓慢时不重复保存，也不提前移除终态")
    void slowRetryRetainsResultWithoutBlockingSnapshots() throws Exception {
        var store = spy(new DownloadTaskResultStore(directory));
        var registry = new DownloadTaskRegistry(clock, store);
        var attempt = attempt();
        registry.register(attempt, "owner", "example", "标题");
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doThrow(new IOException("storage unavailable")).doAnswer(call -> {
            entered.countDown();
            assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
            return call.callRealMethod();
        }).when(store).save(any(), any());
        registry.update(new DownloadEvent(attempt, DownloadEvent.Phase.COMPLETED));
        clock.expire();
        var workers = Executors.newFixedThreadPool(2);
        try {
            var retrying = workers.submit(() -> registry.snapshot(admin));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(workers.submit(() -> registry.snapshot(admin)).get(2, TimeUnit.SECONDS).tasks())
                    .singleElement().satisfies(value -> assertThat(value.terminal()).isTrue());
            verify(store, times(2)).save(any(), any());
            release.countDown();
            assertThat(retrying.get(5, TimeUnit.SECONDS).tasks()).isEmpty();
            assertThat(registry().find(attempt.attemptId(), admin)).isPresent();
        } finally {
            release.countDown();
            workers.shutdown();
            assertThat(workers.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test @DisplayName("并发登记同一执行不能越过历史核对或替换所属者")
    void concurrentRegistrationKeepsIdentity() throws Exception {
        var store = spy(new DownloadTaskResultStore(directory));
        var registry = new DownloadTaskRegistry(clock, store);
        var attempt = attempt();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var contender = new CountDownLatch(1);
        var contenderThread = new AtomicReference<Thread>();
        doAnswer(call -> {
            entered.countDown();
            assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
            return call.callRealMethod();
        }).when(store).find(eq(attempt.attemptId()), any());
        var workers = Executors.newFixedThreadPool(2);
        try {
            var registering = workers.submit(() -> registry.register(attempt, "owner", "example", "标题"));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            var duplicate = workers.submit(() -> {
                contenderThread.set(Thread.currentThread());
                contender.countDown();
                assertThatThrownBy(() -> registry.register(attempt, "other", "example", "替换"))
                        .isInstanceOf(IllegalArgumentException.class);
            });
            assertThat(contender.await(5, TimeUnit.SECONDS)).isTrue();
            org.awaitility.Awaitility.await().atMost(2, TimeUnit.SECONDS)
                    .until(() -> contenderThread.get().getState() == Thread.State.WAITING);
            assertThat(registry.snapshot(admin).tasks()).isEmpty();
            release.countDown();
            registering.get(5, TimeUnit.SECONDS);
            duplicate.get(5, TimeUnit.SECONDS);
            verify(store, times(1)).find(eq(attempt.attemptId()), any());
            assertThat(registry.find(attempt.attemptId(), RequestOwnerIdentity.owner("other"))).isEmpty();
            assertThat(registry.find(attempt.attemptId(), RequestOwnerIdentity.owner("owner"))).isPresent();
        } finally {
            release.countDown();
            workers.shutdown();
            assertThat(workers.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test @DisplayName("历史核对占用任务容量，失败后归还且不留下可见记录")
    void pendingRegistrationUsesAndReleasesCapacity() throws Exception {
        var store = spy(new DownloadTaskResultStore(directory));
        var registry = new DownloadTaskRegistry(clock, store);
        var attempt = attempt();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(call -> {
            entered.countDown();
            assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
            throw new UncheckedIOException(new IOException("unreadable result"));
        }).when(store).find(eq(attempt.attemptId()), any());
        var workers = Executors.newSingleThreadExecutor();
        try {
            var registering = workers.submit(() -> assertThatThrownBy(
                    () -> registry.register(attempt, "owner", "example", "标题"))
                    .isInstanceOf(UncheckedIOException.class));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            for (int i = 0; i < DownloadTaskRegistry.MAX_TASKS - 1; i++) {
                registry.reserve(new DownloadSubmission(UUID.randomUUID(), "example", "work", Map.of()), "", admin);
            }
            var next = new DownloadSubmission(UUID.randomUUID(), "example", "work", Map.of());
            assertThatThrownBy(() -> registry.reserve(next, "", admin)).isInstanceOfSatisfying(
                    DownloadTaskException.class, failure -> assertThat(failure.code())
                            .isEqualTo(DownloadTaskException.Code.CAPACITY_EXCEEDED));
            release.countDown();
            registering.get(5, TimeUnit.SECONDS);
            registry.reserve(next, "", admin);
            assertThat(registry.snapshot(admin).tasks()).hasSize(DownloadTaskRegistry.MAX_TASKS)
                    .noneMatch(value -> value.attempt().equals(attempt));
        } finally {
            release.countDown();
            workers.shutdown();
            assertThat(workers.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
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
