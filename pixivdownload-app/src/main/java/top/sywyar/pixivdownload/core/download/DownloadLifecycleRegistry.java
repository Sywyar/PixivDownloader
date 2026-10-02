package top.sywyar.pixivdownload.core.download;

import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.plugin.api.download.lifecycle.*;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.runtime.ExternalCapabilityOwner;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import top.sywyar.pixivdownload.plugin.api.download.queue.QueueTaskTracker;
import top.sywyar.pixivdownload.plugin.api.download.submission.*;
import top.sywyar.pixivdownload.plugin.api.download.task.*;
import top.sywyar.pixivdownload.plugin.api.web.RequestOwnerIdentity;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.runtime.ExternalCapabilityUnavailableException;

/** 保存宿主代理；回调在锁外执行，生命周期租约由 capability runtime 管理。 */
@Component
public final class DownloadLifecycleRegistry implements DownloadLifecycle, DownloadTasks {
    public record Hook(int order, String name, DownloadOptionsHook callback) {}
    public record Contribution(List<DownloadObserver> observers, List<DownloadAdmissionPolicy> policies,
                               List<Hook> hooks, Map<String, DownloadSubmissionHandler> handlers) {
        public Contribution(List<DownloadObserver> observers, List<DownloadAdmissionPolicy> policies) {
            this(observers, policies, List.of(), Map.of());
        }
        public Contribution {
            observers = List.copyOf(observers);
            policies = List.copyOf(policies);
            hooks = List.copyOf(hooks);
            handlers = Map.copyOf(handlers);
        }
    }

    private final Map<ExternalCapabilityOwner, Contribution> owners = new LinkedHashMap<>();
    private volatile Map<ExternalCapabilityOwner, Contribution> snapshot = Map.of();
    private final AtomicLong observerFailures = new AtomicLong();
    private volatile ExternalCapabilityOwner lastObserverFailure;
    private final DownloadTaskRegistry tasks;

    public DownloadLifecycleRegistry() { this(new DownloadTaskRegistry()); }
    @Autowired
    public DownloadLifecycleRegistry(DownloadTaskRegistry tasks) {
        this.tasks = Objects.requireNonNull(tasks, "tasks");
    }

    public synchronized void register(ExternalCapabilityOwner owner, Contribution contribution) {
        for (var existing : owners.entrySet()) {
            if (!existing.getKey().equals(owner) && contribution.handlers().keySet().stream()
                    .anyMatch(existing.getValue().handlers()::containsKey)) {
                throw new IllegalArgumentException("duplicate download submission type");
            }
        }
        owners.put(owner, contribution);
        snapshot = Collections.unmodifiableMap(new LinkedHashMap<>(owners));
    }

    public synchronized void withdraw(ExternalCapabilityOwner owner) {
        owners.remove(owner);
        snapshot = Collections.unmodifiableMap(new LinkedHashMap<>(owners));
    }

    /** 累计失败诊断不保存异常或插件引用。 */
    public long observerFailureCount() {
        return observerFailures.get();
    }

    public java.util.Optional<ExternalCapabilityOwner> lastObserverFailure() {
        return java.util.Optional.ofNullable(lastObserverFailure);
    }

    @Override
    public void register(DownloadAttempt attempt, String ownerUuid, String queueType, String title) {
        tasks.register(attempt, ownerUuid, queueType, title);
    }

    @Override
    public void track(DownloadAttempt attempt, QueueTaskTracker.Task task, String ownerUuid,
                      String queueType, String title, boolean queued) {
        tasks.register(attempt, ownerUuid, queueType, title);
        tasks.attach(attempt, task);
        task.onTermination(() -> publish(new DownloadEvent(attempt, task.isCancellationRequested()
                ? DownloadEvent.Phase.CANCELLED : DownloadEvent.Phase.FAILED)));
        publish(new DownloadEvent(attempt, queued ? DownloadEvent.Phase.QUEUED : DownloadEvent.Phase.ACCEPTED));
    }

    @Override
    public Map<String, String> options(DownloadAttempt attempt, Map<String, String> options) {
        record Invocation(int order, String owner, String name, DownloadOptionsHook callback) {}
        var chain = new ArrayList<Invocation>();
        snapshot.forEach((owner, contribution) -> contribution.hooks().forEach(hook ->
                chain.add(new Invocation(hook.order(), owner.pluginId(), hook.name(), hook.callback()))));
        chain.sort(Comparator.comparingInt(Invocation::order).thenComparing(Invocation::owner)
                .thenComparing(Invocation::name));
        Map<String, String> current = DownloadSubmission.copyOptions(options);
        for (Invocation invocation : chain) {
            try {
                current = DownloadSubmission.copyOptions(invocation.callback().customize(attempt, current));
            } catch (VirtualMachineError | ThreadDeath fatal) {
                throw fatal;
            } catch (Throwable rejected) {
                throw new DownloadAdmissionRejectedException();
            }
        }
        return current;
    }

    @Override
    public Receipt submit(DownloadSubmission submission, String credential, RequestOwnerIdentity owner) {
        var reservation = tasks.reserve(submission, credential, owner);
        if (reservation.duplicate()) return new Receipt(reservation.task(), true);
        DownloadAttempt attempt = reservation.task().attempt();
        DownloadSubmissionHandler handler = snapshot.values().stream()
                .map(c -> c.handlers().get(submission.workType())).filter(Objects::nonNull).findFirst().orElse(null);
        try {
            if (handler == null) throw new DownloadTaskException(DownloadTaskException.Code.UNAVAILABLE);
            publish(new DownloadEvent(attempt, DownloadEvent.Phase.ACCEPTED));
            checkAdmission(attempt);
            handler.submit(submission, attempt, credential);
            var result = tasks.find(attempt.attemptId(), owner).orElseThrow();
            if (result.phase() == DownloadEvent.Phase.ACCEPTED)
                throw new DownloadTaskException(DownloadTaskException.Code.REJECTED);
            return new Receipt(result, false);
        } catch (VirtualMachineError | ThreadDeath fatal) {
            throw fatal;
        } catch (Throwable failure) {
            publish(new DownloadEvent(attempt, DownloadEvent.Phase.FAILED));
            if (failure instanceof DownloadTaskException known) throw known;
            if (failure instanceof ExternalCapabilityUnavailableException)
                throw new DownloadTaskException(DownloadTaskException.Code.UNAVAILABLE);
            throw new DownloadTaskException(DownloadTaskException.Code.REJECTED);
        }
    }

    @Override
    public java.util.Optional<DownloadTaskSnapshot> find(UUID taskId, RequestOwnerIdentity owner) {
        return tasks.find(taskId, owner);
    }

    @Override
    public Snapshot snapshot(RequestOwnerIdentity owner) { return tasks.snapshot(owner); }

    @Override
    public CancelResult cancel(UUID taskId, RequestOwnerIdentity owner) { return tasks.cancel(taskId, owner); }

    @Override
    public void checkAdmission(DownloadAttempt attempt) {
        for (Contribution contribution : snapshot.values()) {
            for (DownloadAdmissionPolicy policy : contribution.policies()) {
                try {
                    if (policy.evaluate(attempt) != DownloadAdmissionPolicy.Decision.ALLOW) {
                        throw new DownloadAdmissionRejectedException();
                    }
                } catch (VirtualMachineError | ThreadDeath fatal) {
                    throw fatal;
                } catch (Throwable rejected) {
                    throw new DownloadAdmissionRejectedException();
                }
            }
        }
    }

    @Override
    public void publish(DownloadEvent event) {
        if (!tasks.update(event)) return;
        for (var publication : snapshot.entrySet()) {
            for (DownloadObserver observer : publication.getValue().observers()) {
                try {
                    observer.onDownloadEvent(event);
                } catch (VirtualMachineError | ThreadDeath fatal) {
                    throw fatal;
                } catch (Throwable failure) {
                    lastObserverFailure = publication.getKey();
                    observerFailures.incrementAndGet();
                }
            }
        }
    }
}
