package top.sywyar.pixivdownload.core.download;

import top.sywyar.pixivdownload.plugin.api.download.task.DownloadTaskProgress;

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
    public record SubmissionType(String pluginId, String schemaJson) {}
    public record Contribution(List<DownloadObserver> observers, List<DownloadAdmissionPolicy> policies,
                               List<Hook> hooks, Map<String, DownloadSubmissionHandler> handlers,
                               Map<String, String> inputSchemas, java.util.function.BooleanSupplier accepting) {
        public Contribution(List<DownloadObserver> observers, List<DownloadAdmissionPolicy> policies,
                            List<Hook> hooks, Map<String, DownloadSubmissionHandler> handlers,
                            Map<String, String> inputSchemas) {
            this(observers, policies, hooks, handlers, inputSchemas, () -> true);
        }
        public Contribution(List<DownloadObserver> observers, List<DownloadAdmissionPolicy> policies,
                            List<Hook> hooks, Map<String, DownloadSubmissionHandler> handlers) {
            this(observers, policies, hooks, handlers, Map.of());
        }
        public Contribution(List<DownloadObserver> observers, List<DownloadAdmissionPolicy> policies) {
            this(observers, policies, List.of(), Map.of());
        }
        public Contribution {
            observers = List.copyOf(observers);
            policies = List.copyOf(policies);
            hooks = List.copyOf(hooks);
            handlers = Map.copyOf(handlers);
            inputSchemas = Map.copyOf(inputSchemas);
            Objects.requireNonNull(accepting);
            if (!handlers.keySet().containsAll(inputSchemas.keySet()))
                throw new IllegalArgumentException("download schema without submission handler");
        }
    }

    private volatile Map<ExternalCapabilityOwner, Contribution> snapshot = Map.of();
    private final AtomicLong observerFailures = new AtomicLong();
    private volatile ExternalCapabilityOwner lastObserverFailure;
    private final DownloadTaskRegistry tasks;
    private final top.sywyar.pixivdownload.core.database.DatabaseAvailability database;

    public DownloadLifecycleRegistry() { this(new DownloadTaskRegistry(), () -> true); }
    @Autowired
    public DownloadLifecycleRegistry(DownloadTaskRegistry tasks,
            top.sywyar.pixivdownload.core.database.DatabaseAvailability database) {
        this.tasks = Objects.requireNonNull(tasks, "tasks");
        this.database = Objects.requireNonNull(database, "database");
    }

    public synchronized void register(ExternalCapabilityOwner owner, Contribution contribution) {
        for (var existing : snapshot.entrySet()) {
            if (!existing.getKey().equals(owner) && contribution.handlers().keySet().stream()
                    .anyMatch(existing.getValue().handlers()::containsKey)) {
                throw new IllegalArgumentException("duplicate download submission type");
            }
        }
        var next = new LinkedHashMap<>(snapshot);
        next.put(owner, contribution);
        snapshot = Collections.unmodifiableMap(next);
    }

    public synchronized void withdraw(ExternalCapabilityOwner owner) {
        var next = new LinkedHashMap<>(snapshot);
        next.remove(owner);
        snapshot = Collections.unmodifiableMap(next);
    }

    /** API reference readers see only owners still accepting new calls, including during drain. */
    public Map<String, SubmissionType> submissionTypes() {
        var types = new java.util.TreeMap<String, SubmissionType>();
        snapshot.forEach((owner, contribution) -> {
            if (!contribution.handlers().isEmpty() && contribution.accepting().getAsBoolean()) {
                contribution.handlers().keySet().forEach(type ->
                        types.put(type, new SubmissionType(owner.pluginId(), contribution.inputSchemas().get(type))));
            }
        });
        return Collections.unmodifiableMap(types);
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
        database.requireAvailable();
        tasks.register(attempt, ownerUuid, queueType, title);
    }

    @Override
    public void reportProgress(DownloadAttempt attempt, QueueTaskTracker.Task task, DownloadTaskProgress progress) {
        tasks.reportProgress(attempt, task, progress);
    }

    @Override
    public void track(DownloadAttempt attempt, QueueTaskTracker.Task task, String ownerUuid,
                      String queueType, String title, boolean queued) {
        track(attempt, task, ownerUuid, queueType, title, queued, null);
    }

    @Override
    public void track(DownloadAttempt attempt, QueueTaskTracker.Task task, String ownerUuid,
                      String queueType, String title, boolean queued, java.nio.file.Path outputDirectory) {
        database.requireAvailable();
        if (outputDirectory != null && !outputDirectory.isAbsolute())
            throw new IllegalArgumentException("Task output directory must be absolute");
        tasks.register(attempt, ownerUuid, queueType, title,
                outputDirectory == null ? null : outputDirectory.normalize().toString());
        tasks.attach(attempt, task);
        task.onTermination(() -> publish(new DownloadEvent(attempt, task.isCancellationRequested()
                ? DownloadEvent.Phase.CANCELLED : DownloadEvent.Phase.FAILED)));
        publish(new DownloadEvent(attempt, queued ? DownloadEvent.Phase.QUEUED : DownloadEvent.Phase.ACCEPTED));
    }

    @Override
    public Map<String, String> options(DownloadAttempt attempt, Map<String, String> options) {
        database.requireAvailable();
        record Invocation(int order, String owner, String name, DownloadOptionsHook callback) {}
        var chain = new ArrayList<Invocation>();
        snapshot.forEach((owner, contribution) -> contribution.hooks().forEach(hook ->
                chain.add(new Invocation(hook.order(), owner.pluginId(), hook.name(), hook.callback()))));
        chain.sort(Comparator.comparingInt(Invocation::order).thenComparing(Invocation::owner)
                .thenComparing(Invocation::name));
        Map<String, String> current = DownloadSubmission.copyOptions(options);
        for (Invocation invocation : chain) {
            database.requireAvailable();
            try {
                current = DownloadSubmission.copyOptions(invocation.callback().customize(attempt, current));
                database.requireAvailable();
            } catch (top.sywyar.pixivdownload.core.database.DatabaseUnavailableException unavailable) {
                throw unavailable;
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
        if (!database.available()) throw new DownloadTaskException(DownloadTaskException.Code.UNAVAILABLE);
        DownloadSubmissionHandler handler = snapshot.values().stream()
                .map(c -> c.handlers().get(submission.workType())).filter(Objects::nonNull).findFirst().orElse(null);
        var reservation = tasks.reserve(submission, credential, owner, handler != null);
        return executeSubmission(submission, credential, owner, reservation, handler);
    }

    /** 当前具有提交能力的 owner，不保留插件对象。 */
    public java.util.Set<String> submissionOwners() {
        return snapshot.entrySet().stream().filter(entry -> !entry.getValue().handlers().isEmpty())
                .map(entry -> entry.getKey().pluginId()).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    @Override
    public Receipt submitForProgram(DownloadSubmission submission, String credential,
            top.sywyar.pixivdownload.plugin.api.web.ProgramIdentity program) {
        Objects.requireNonNull(program, "program");
        if (!program.permits("core", "downloads.create"))
            throw DownloadTaskException.missingCreatePermission("core");
        var publication = snapshot.entrySet().stream()
                .filter(entry -> entry.getValue().handlers().containsKey(submission.workType()))
                .findFirst().orElseThrow(() -> new DownloadTaskException(DownloadTaskException.Code.UNSUPPORTED_WORK_TYPE));
        if (!program.permits(publication.getKey().pluginId(), "downloads.create"))
            throw DownloadTaskException.missingCreatePermission(publication.getKey().pluginId());
        if (!database.available()) throw new DownloadTaskException(DownloadTaskException.Code.UNAVAILABLE);
        var reservation = tasks.reserveForProgram(submission, credential, program);
        return executeSubmission(submission, credential, RequestOwnerIdentity.owner(program.id().toString()),
                reservation, publication.getValue().handlers().get(submission.workType()));
    }

    private Receipt executeSubmission(DownloadSubmission submission, String credential, RequestOwnerIdentity owner,
                                      DownloadTaskRegistry.Reservation reservation, DownloadSubmissionHandler handler) {
        if (reservation.duplicate()) return new Receipt(reservation.task(), true);
        DownloadAttempt attempt = reservation.task().attempt();
        try {
            publish(new DownloadEvent(attempt, DownloadEvent.Phase.ACCEPTED));
            checkAdmission(attempt);
            database.requireAvailable();
            var failure = handler.submitAndReportFailure(submission, attempt, credential);
            if (failure != null) throw new DownloadTaskException(failure);
            database.requireAvailable();
            var result = tasks.find(attempt.attemptId(), owner).orElseThrow();
            if (result.phase() == DownloadEvent.Phase.ACCEPTED)
                throw new DownloadTaskException(DownloadTaskException.Code.REJECTED);
            return new Receipt(result, false);
        } catch (VirtualMachineError | ThreadDeath fatal) {
            throw fatal;
        } catch (Throwable failure) {
            var reason = failure instanceof DownloadTaskException known && known.code() == DownloadTaskException.Code.WORK_NOT_FOUND
                    ? DownloadEvent.FailureReason.WORK_NOT_FOUND : null;
            publish(new DownloadEvent(attempt, DownloadEvent.Phase.FAILED, reason));
            if (failure instanceof DownloadTaskException known) throw known;
            if (failure instanceof ExternalCapabilityUnavailableException
                    || failure instanceof top.sywyar.pixivdownload.core.database.DatabaseUnavailableException)
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

    @Override public HistoryPage history(HistoryCursor before, int limit, RequestOwnerIdentity owner) {
        return tasks.history(before, limit, owner);
    }

    @Override
    public CancelResult cancel(UUID taskId, RequestOwnerIdentity owner) { return tasks.cancel(taskId, owner); }

    @Override
    public void checkAdmission(DownloadAttempt attempt) {
        database.requireAvailable();
        for (Contribution contribution : snapshot.values()) {
            for (DownloadAdmissionPolicy policy : contribution.policies()) {
                database.requireAvailable();
                try {
                    var decision = policy.evaluate(attempt);
                    database.requireAvailable();
                    if (decision != DownloadAdmissionPolicy.Decision.ALLOW) {
                        throw new DownloadAdmissionRejectedException();
                    }
                } catch (top.sywyar.pixivdownload.core.database.DatabaseUnavailableException unavailable) {
                    throw unavailable;
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
        if (!database.available()) {
            if (event.phase() == DownloadEvent.Phase.CANCELLED || event.phase() == DownloadEvent.Phase.FAILED) {
                tasks.update(event);
                return;
            }
            throw new top.sywyar.pixivdownload.core.database.DatabaseUnavailableException();
        }
        if (!tasks.update(event)) return;
        for (var publication : snapshot.entrySet()) {
            for (DownloadObserver observer : publication.getValue().observers()) {
                if (!database.available()) return;
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
