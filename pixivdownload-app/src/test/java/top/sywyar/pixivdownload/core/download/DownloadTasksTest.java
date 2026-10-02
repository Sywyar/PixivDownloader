package top.sywyar.pixivdownload.core.download;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.plugin.api.download.lifecycle.*;
import top.sywyar.pixivdownload.plugin.api.download.queue.QueueTaskTracker;
import top.sywyar.pixivdownload.plugin.api.download.submission.*;
import top.sywyar.pixivdownload.plugin.api.download.task.DownloadTasks;
import top.sywyar.pixivdownload.plugin.api.web.RequestOwnerIdentity;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.runtime.ExternalCapabilityOwner;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

@DisplayName("公开下载任务与幂等快照")
class DownloadTasksTest {
    private final DownloadLifecycleRegistry lifecycle = new DownloadLifecycleRegistry();
    private final ExternalCapabilityOwner owner = new ExternalCapabilityOwner("example", "example", 1, 1);
    private final RequestOwnerIdentity admin = RequestOwnerIdentity.adminScope();
    private final List<DownloadEvent.Phase> events = new ArrayList<>();
    private final List<Runnable> queue = new ArrayList<>();
    private final AtomicInteger executions = new AtomicInteger();

    private void install() {
        var tracker = new QueueTaskTracker("example");
        var handler = new DownloadSubmissionHandler() {
            @Override public String workType() { return "example"; }
            @Override public void submit(DownloadSubmission submission, DownloadAttempt attempt, String credential) {
                var task = tracker.prepareQueued(null);
                lifecycle.track(attempt, task, null, "example", "作品", true);
                task.bind(() -> {
                    lifecycle.publish(new DownloadEvent(attempt, DownloadEvent.Phase.STARTED));
                    executions.incrementAndGet();
                    lifecycle.publish(new DownloadEvent(attempt, DownloadEvent.Phase.COMPLETED));
                });
                queue.add(task);
            }
        };
        lifecycle.register(owner, new DownloadLifecycleRegistry.Contribution(
                List.of(event -> events.add(event.phase())), List.of(), List.of(), Map.of("example", handler)));
    }

    private DownloadSubmission command() {
        return new DownloadSubmission(UUID.randomUUID(), "example", "opaque/work:一", Map.of());
    }

    @Test @DisplayName("相同命令只排队一次，重试保留原任务与终态")
    void duplicateHasSameTaskAndExecutesOnce() {
        install();
        var command = command();
        var first = lifecycle.submit(command, "secret", admin);
        var duplicate = lifecycle.submit(command, "secret", admin);
        assertThat(first.duplicate()).isFalse();
        assertThat(duplicate.duplicate()).isTrue();
        assertThat(duplicate.task().attempt()).isEqualTo(first.task().attempt());
        assertThat(first.task().phase()).isEqualTo(DownloadEvent.Phase.QUEUED);
        assertThat(queue).hasSize(1);
        queue.get(0).run();
        assertThat(executions).hasValue(1);
        assertThat(events).containsExactly(DownloadEvent.Phase.ACCEPTED, DownloadEvent.Phase.QUEUED,
                DownloadEvent.Phase.STARTED, DownloadEvent.Phase.COMPLETED);
        assertThat(lifecycle.submit(command, "secret", admin).task().phase()).isEqualTo(DownloadEvent.Phase.COMPLETED);
        assertThat(lifecycle.snapshot(admin).toString()).doesNotContain("secret");
    }

    @Test @DisplayName("相同幂等键变更选项或凭据会冲突，不能再次执行")
    void conflictingInputIsRejected() {
        install();
        var command = command();
        lifecycle.submit(command, "secret", admin);
        assertThatThrownBy(() -> lifecycle.submit(command, "different", admin))
                .isInstanceOfSatisfying(DownloadTaskException.class,
                        failure -> assertThat(failure.code()).isEqualTo(DownloadTaskException.Code.CONFLICT));
        var changed = new DownloadSubmission(command.requestId(), command.workType(), command.workId(),
                Map.of("format", "epub"));
        assertThatThrownBy(() -> lifecycle.submit(changed, "secret", admin)).isInstanceOf(DownloadTaskException.class);
        assertThat(queue).hasSize(1);
    }

    @Test @DisplayName("同步提交绑定执行句柄时不会重复发布接纳事件")
    void synchronousSubmissionPublishesAdmissionOnce() {
        var handler = new DownloadSubmissionHandler() {
            @Override public String workType() { return "example"; }
            @Override public void submit(DownloadSubmission command, DownloadAttempt attempt, String credential) {
                var task = new QueueTaskTracker("example").beginRunning(null);
                try {
                    lifecycle.track(attempt, task, null, "example", "Title", false);
                    lifecycle.publish(new DownloadEvent(attempt, DownloadEvent.Phase.STARTED));
                    lifecycle.publish(new DownloadEvent(attempt, DownloadEvent.Phase.COMPLETED));
                } finally { task.completeRunning(); }
            }
        };
        lifecycle.register(owner, new DownloadLifecycleRegistry.Contribution(
                List.of(event -> events.add(event.phase())), List.of(), List.of(), Map.of("example", handler)));
        lifecycle.submit(command(), null, admin);
        assertThat(events).containsExactly(DownloadEvent.Phase.ACCEPTED,
                DownloadEvent.Phase.STARTED, DownloadEvent.Phase.COMPLETED);
    }

    @Test @DisplayName("排队取消产生终态且旧句柄不影响同作品的新下载")
    void cancellationOnlyTargetsCapturedTask() {
        install();
        var first = lifecycle.submit(command(), null, admin).task();
        var second = lifecycle.submit(command(), null, admin).task();
        assertThat(lifecycle.cancel(first.attempt().attemptId(), admin)).isEqualTo(DownloadTasks.CancelResult.REQUESTED);
        queue.forEach(Runnable::run);
        assertThat(executions).hasValue(1);
        assertThat(lifecycle.find(first.attempt().attemptId(), admin).orElseThrow().phase())
                .isEqualTo(DownloadEvent.Phase.CANCELLED);
        assertThat(lifecycle.find(second.attempt().attemptId(), admin).orElseThrow().phase())
                .isEqualTo(DownloadEvent.Phase.COMPLETED);
        assertThat(lifecycle.cancel(first.attempt().attemptId(), admin)).isEqualTo(DownloadTasks.CancelResult.TERMINAL);
    }

    @Test @DisplayName("能力缺席不假报成功，未授权身份不能提交或读取管理员任务")
    void absenceAndOwnerIsolation() {
        assertThatThrownBy(() -> lifecycle.submit(command(), null, admin))
                .isInstanceOfSatisfying(DownloadTaskException.class,
                        failure -> assertThat(failure.code()).isEqualTo(DownloadTaskException.Code.UNAVAILABLE));
        install();
        var item = lifecycle.submit(command(), null, admin).task();
        var visitor = RequestOwnerIdentity.owner("visitor");
        assertThat(lifecycle.snapshot(visitor).tasks()).isEmpty();
        assertThat(lifecycle.find(item.attempt().attemptId(), visitor)).isEmpty();
        assertThat(lifecycle.cancel(item.attempt().attemptId(), visitor)).isEqualTo(DownloadTasks.CancelResult.NOT_FOUND);
        assertThatThrownBy(() -> lifecycle.submit(command(), null, visitor)).isInstanceOf(DownloadTaskException.class);
    }

    @Test @DisplayName("选项扩展按顺序组合，观察失败不回滚完成事实")
    void hookOrderingAndObserverIsolation() {
        DownloadOptionsHook first = (attempt, options) -> Map.of("format", options.get("format") + "-first");
        DownloadOptionsHook second = (attempt, options) -> Map.of("format", options.get("format") + "-second");
        lifecycle.register(owner, new DownloadLifecycleRegistry.Contribution(
                List.of(event -> { throw new IllegalStateException("observer failed"); }), List.of(),
                List.of(new DownloadLifecycleRegistry.Hook(20, "second", second),
                        new DownloadLifecycleRegistry.Hook(10, "first", first)), Map.of()));
        var attempt = new DownloadAttempt(UUID.randomUUID(), "example", "id");
        assertThat(lifecycle.options(attempt, Map.of("format", "original")))
                .containsEntry("format", "original-first-second");
        lifecycle.register(attempt, null, "example", "title");
        lifecycle.publish(new DownloadEvent(attempt, DownloadEvent.Phase.COMPLETED));
        assertThat(lifecycle.find(attempt.attemptId(), admin).orElseThrow().phase())
                .isEqualTo(DownloadEvent.Phase.COMPLETED);
        assertThat(lifecycle.observerFailureCount()).isEqualTo(1);
        lifecycle.withdraw(owner);
        assertThat(lifecycle.options(attempt, Map.of("format", "original"))).containsEntry("format", "original");
    }

    @Test @DisplayName("任务实际退出但未报告成功时补失败终态")
    void missingCompletionIsFailure() {
        var task = new QueueTaskTracker("example").prepareQueued(null);
        var attempt = new DownloadAttempt(UUID.randomUUID(), "example", "id");
        lifecycle.track(attempt, task, null, "example", "title", true);
        task.bind(() -> {});
        task.run();
        assertThat(lifecycle.find(attempt.attemptId(), admin).orElseThrow().phase()).isEqualTo(DownloadEvent.Phase.FAILED);
    }

    @Test @DisplayName("容量耗尽明确拒绝，终态过期后释放幂等键且活动任务仍保留")
    void boundedRetentionPreservesActiveTasks() {
        var now = new java.util.concurrent.atomic.AtomicReference<>(java.time.Instant.parse("2020-01-01T00:00:00Z"));
        var clock = new java.time.Clock() {
            @Override public java.time.ZoneId getZone() { return java.time.ZoneOffset.UTC; }
            @Override public java.time.Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public java.time.Instant instant() { return now.get(); }
        };
        var registry = new DownloadTaskRegistry(clock);
        var command = command();
        var first = registry.reserve(command, null, admin).task();
        for (int i = 1; i < DownloadTaskRegistry.MAX_TASKS; i++)
            registry.reserve(command(), null, admin);
        assertThatThrownBy(() -> registry.reserve(command(), null, admin))
                .isInstanceOfSatisfying(DownloadTaskException.class,
                        error -> assertThat(error.code()).isEqualTo(DownloadTaskException.Code.CAPACITY_EXCEEDED));
        registry.update(new DownloadEvent(first.attempt(), DownloadEvent.Phase.COMPLETED));
        now.set(now.get().plus(DownloadTaskRegistry.RETENTION).plusSeconds(1));
        assertThat(registry.find(first.attempt().attemptId(), admin)).isEmpty();
        assertThat(registry.snapshot(admin).tasks()).hasSize(DownloadTaskRegistry.MAX_TASKS - 1);
        var next = registry.reserve(command, null, admin);
        assertThat(next.duplicate()).isFalse();
        assertThat(next.task().attempt()).isNotEqualTo(first.attempt());
    }

    @Test @DisplayName("前置规则在提交处理器和网络访问之前拒绝任务")
    void admissionPrecedesHandler() {
        install();
        lifecycle.register(new ExternalCapabilityOwner("policy", "policy", 1, 2),
                new DownloadLifecycleRegistry.Contribution(List.of(),
                        List.of(attempt -> DownloadAdmissionPolicy.Decision.REJECT)));
        assertThatThrownBy(() -> lifecycle.submit(command(), null, admin)).isInstanceOf(DownloadTaskException.class);
        assertThat(queue).isEmpty();
        assertThat(lifecycle.snapshot(admin).tasks()).singleElement()
                .satisfies(task -> assertThat(task.phase()).isEqualTo(DownloadEvent.Phase.FAILED));
    }
}
