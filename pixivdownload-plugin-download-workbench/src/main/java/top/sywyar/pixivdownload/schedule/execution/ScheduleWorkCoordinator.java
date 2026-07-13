package top.sywyar.pixivdownload.schedule.execution;

import org.springframework.core.task.TaskExecutor;
import top.sywyar.pixivdownload.core.schedule.ScheduledPendingWork;
import top.sywyar.pixivdownload.core.schedule.ScheduledTaskStore;
import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledCancellation;
import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledExecutionException;
import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledFailure;
import top.sywyar.pixivdownload.plugin.api.schedule.network.ScheduledNetworkRoute;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledPendingReplayPolicy;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledTaskDefinition;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledWorkSink;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWork;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWorkContext;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWorkExecutor;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWorkKey;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWorkResult;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWorkRunStatistics;
import top.sywyar.pixivdownload.schedule.ScheduleRunQueue;
import top.sywyar.pixivdownload.schedule.persistence.ScheduleWorkPersistenceCodec;
import top.sywyar.pixivdownload.schedule.security.ScheduleCredentialRedactor;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 宿主拥有的有界作品协调器。内存只保留至多 {@code maxInFlight} 个 Future；每个已接受作品在 worker
 * 完成前必定已成为成功终态或已完整耐久进入 pending。
 */
final class ScheduleWorkCoordinator implements ScheduledWorkSink {

    @FunctionalInterface
    interface BatchBarrier {
        void afterAttempt(long attemptedWorkCount) throws Exception;
    }

    private final long taskId;
    private final ScheduledTaskDefinition task;
    private final ScheduledNetworkRoute route;
    private final ScheduledCancellation cancellation;
    private final ScheduleCredentialMaterial credential;
    private final ScheduledTaskStore store;
    private final ScheduleWorkPersistenceCodec persistenceCodec;
    private final Map<String, ScheduledWorkExecutor> executors;
    private final ScheduleRunQueue.Run runQueue;
    private final TaskExecutor taskExecutor;
    private final ScheduleWorkConcurrencyLimiter concurrencyLimiter;
    private final BlockingQueue<Future<Completion>> completions = new LinkedBlockingQueue<>();
    private final int maxInFlight;
    private final Map<String, Integer> maxInFlightByType;
    private final Map<String, Integer> inFlightByType = new HashMap<>();
    private final Map<Future<Completion>, InFlightWork> inFlightWork = new HashMap<>();
    private final Map<String, Integer> consecutiveCredentialFailures = new HashMap<>();
    private final int pendingMaxAttempts;
    private final int credentialFailureLimit;
    private final long politeDelayMillis;
    private final BatchBarrier batchBarrier;
    private final Consumer<ScheduleExecutionResult.PendingExhausted> pendingExhaustedListener;
    private final Map<ScheduledWorkKey, ScheduledPendingWork> pending = new LinkedHashMap<>();
    private final Set<ScheduledWorkKey> seen = new HashSet<>();
    private final Map<String, MutableStatistics> statistics = new LinkedHashMap<>();
    private final List<ScheduleExecutionResult.PendingExhausted> pendingExhausted = new ArrayList<>();

    private int inFlight;
    private int completedWorkCount;
    private long attemptedWorkCount;
    private ScheduledExecutionException terminalFailure;
    private boolean accepting = true;
    private boolean interruptedWhileWaiting;

    ScheduleWorkCoordinator(
            long taskId,
            ScheduledTaskDefinition task,
            ScheduledNetworkRoute route,
            ScheduledCancellation cancellation,
            ScheduleCredentialMaterial credential,
            ScheduledTaskStore store,
            ScheduleWorkPersistenceCodec persistenceCodec,
            Map<String, ScheduledWorkExecutor> executors,
            ScheduleRunQueue.Run runQueue,
            TaskExecutor taskExecutor,
            ScheduleWorkConcurrencyLimiter concurrencyLimiter,
            int maxInFlight,
            Map<String, Integer> maxInFlightByType,
            int pendingMaxAttempts,
            int credentialFailureLimit,
            long politeDelayMillis,
            BatchBarrier batchBarrier,
            Consumer<ScheduleExecutionResult.PendingExhausted> pendingExhaustedListener) {
        this.taskId = taskId;
        this.task = task;
        this.route = route;
        this.cancellation = cancellation;
        this.credential = credential;
        this.store = store;
        this.persistenceCodec = persistenceCodec;
        this.executors = Map.copyOf(executors);
        this.runQueue = runQueue;
        this.taskExecutor = Objects.requireNonNull(taskExecutor, "taskExecutor");
        this.concurrencyLimiter = Objects.requireNonNull(
                concurrencyLimiter, "concurrencyLimiter");
        this.maxInFlight = Math.max(
                1, Math.min(maxInFlight, ScheduleExecutionEngine.MAX_WORK_IN_FLIGHT));
        if (maxInFlightByType == null
                || !maxInFlightByType.keySet().equals(executors.keySet())) {
            throw new IllegalArgumentException("work concurrency limits must match executors");
        }
        Map<String, Integer> limits = new LinkedHashMap<>();
        maxInFlightByType.forEach((workType, limit) -> {
            if (limit == null || limit <= 0) {
                throw new IllegalArgumentException("work concurrency limit must be positive");
            }
            limits.put(workType, Math.min(this.maxInFlight, limit));
            inFlightByType.put(workType, 0);
            consecutiveCredentialFailures.put(workType, 0);
        });
        this.maxInFlightByType = Map.copyOf(limits);
        this.pendingMaxAttempts = Math.max(1, pendingMaxAttempts);
        this.credentialFailureLimit = Math.max(1, credentialFailureLimit);
        this.politeDelayMillis = Math.max(0L, politeDelayMillis);
        this.batchBarrier = batchBarrier;
        this.pendingExhaustedListener = pendingExhaustedListener;
        for (String workType : executors.keySet()) {
            statistics.put(workType, new MutableStatistics());
        }
    }

    void loadPending(List<ScheduledPendingWork> rows) throws ScheduledExecutionException {
        for (ScheduledPendingWork row : rows) {
            ScheduledWork work;
            try {
                work = persistenceCodec.fromPendingWork(row);
            } catch (IllegalArgumentException failure) {
                throw new ScheduledExecutionException(
                        ScheduledFailure.Category.PAYLOAD_UNSUPPORTED,
                        "schedule.pending.payload-invalid");
            }
            if (!executors.containsKey(work.key().workType())) {
                throw new ScheduledExecutionException(
                        ScheduledFailure.Category.PAYLOAD_UNSUPPORTED,
                        "schedule.pending.work-type-unplanned");
            }
            pending.put(work.key(), row);
        }
    }

    /**
     * ALWAYS 来源在发现前重放全部 pending，使来源网络临时失败也不阻塞已知作品恢复；
     * REDISCOVERED_ONLY 来源只允许通过来源本轮重新提交的 key 触发重试。
     */
    void replayUnseenPending(ScheduledPendingReplayPolicy replayPolicy)
            throws ScheduledExecutionException {
        if (replayPolicy != ScheduledPendingReplayPolicy.ALWAYS) {
            return;
        }
        for (ScheduledPendingWork row : new ArrayList<>(pending.values())) {
            ensureAcceptingAndNotCancelled();
            ScheduledWork work = persistenceCodec.fromPendingWork(row);
            if (seen.contains(work.key())) {
                continue;
            }
            discover(work);
            if (row.attempts() >= pendingMaxAttempts) {
                seen.add(work.key());
                runQueue.mark(work.key().id(), work.key().workType(),
                        ScheduleRunQueue.STATUS_FAILED, row.reasonCode());
                continue;
            }
            submitAccepted(work, true);
        }
    }

    boolean isPending(ScheduledWorkKey key) {
        return pending.containsKey(key);
    }

    @Override
    public void submit(ScheduledWork submitted) throws ScheduledExecutionException {
        ensureAcceptingAndNotCancelled();
        validateSubmittedWork(submitted);
        ScheduledPendingWork pendingRow = pending.get(submitted.key());
        ScheduledWork work = pendingRow == null
                ? submitted
                : persistenceCodec.fromPendingWork(pendingRow);
        discover(work);
        if (pendingRow != null && pendingRow.attempts() >= pendingMaxAttempts) {
            seen.add(work.key());
            runQueue.mark(work.key().id(), work.key().workType(),
                    ScheduleRunQueue.STATUS_FAILED, pendingRow.reasonCode());
            return;
        }
        submitAccepted(work, pendingRow != null);
    }

    @Override
    public void completeLocally(ScheduledWork work, ScheduledWorkResult result)
            throws ScheduledExecutionException {
        ensureAcceptingAndNotCancelled();
        validateSubmittedWork(work);
        result = validateResult(result);
        if (!executors.containsKey(work.key().workType())) {
            throw new ScheduledExecutionException(
                    ScheduledFailure.Category.PAYLOAD_UNSUPPORTED,
                    "schedule.work.type-unplanned");
        }
        discover(work);
        if (!seen.add(work.key())) {
            return;
        }
        if (result.outcome() == ScheduledWorkResult.Outcome.COMPLETED) {
            throw new ScheduledExecutionException(
                    ScheduledFailure.Category.INTERNAL,
                    "schedule.work.local-completed-forbidden");
        }
        store.deletePendingWork(taskId, work.key().workType(), work.key().id());
        pending.remove(work.key());
        markResult(work, result);
    }

    private void submitAccepted(ScheduledWork work, boolean retry) throws ScheduledExecutionException {
        ensureAcceptingAndNotCancelled();
        throwTerminalFailure();
        ScheduledWorkExecutor executor = executors.get(work.key().workType());
        if (executor == null) {
            throw new ScheduledExecutionException(
                    ScheduledFailure.Category.PAYLOAD_UNSUPPORTED,
                    "schedule.work.executor-unavailable");
        }
        if (!seen.add(work.key())) {
            return;
        }
        String workType = work.key().workType();
        while (inFlight >= maxInFlight
                || inFlightByType.get(workType) >= maxInFlightByType.get(workType)) {
            awaitOne();
            throwTerminalFailure();
        }
        ScheduleWorkConcurrencyLimiter.Permit concurrencyPermit =
                concurrencyLimiter.acquire(
                        workType, maxInFlightByType.get(workType), cancellation);
        TrackedWorkFuture future = new TrackedWorkFuture(
                () -> executeOne(work, executor, retry), completions,
                concurrencyPermit::close);
        MutableStatistics typeStatistics = statistics.get(workType);
        typeStatistics.attempted++;
        attemptedWorkCount++;
        try {
            taskExecutor.execute(future);
            inFlight++;
            inFlightByType.merge(workType, 1, Integer::sum);
            inFlightWork.put(future, new InFlightWork(work, retry, workType));
        } catch (RuntimeException failure) {
            future.cancel(false);
            completions.remove(future);
            recordCompletion(Completion.failure(work, retry, new ScheduledFailure(
                    ScheduledFailure.Category.INTERNAL,
                    "schedule.work.dispatch-failed", 0L)));
            throw new ScheduledExecutionException(
                    ScheduledFailure.Category.INTERNAL,
                    "schedule.work.dispatch-failed");
        }
        try {
            batchBarrier.afterAttempt(attemptedWorkCount);
        } catch (CoordinatorSignal signal) {
            throw signal;
        } catch (Exception failure) {
            throw new CoordinatorSignal(failure);
        }
        sleepPolitely();
    }

    private Completion executeOne(
            ScheduledWork work,
            ScheduledWorkExecutor executor,
            boolean retry) {
        try {
            cancellation.throwIfCancellationRequested();
            try (var handle = credential.openHandle()) {
                ScheduledWorkContext context = new ScheduledWorkContext() {
                    @Override
                    public ScheduledTaskDefinition task() {
                        return task;
                    }

                    @Override
                    public ScheduledNetworkRoute route() {
                        return route;
                    }

                    @Override
                    public top.sywyar.pixivdownload.plugin.api.schedule.credential.ScheduledCredentialHandle credential() {
                        return handle;
                    }

                    @Override
                    public ScheduledCancellation cancellation() {
                        return cancellation;
                    }
                };
                ScheduledWorkResult result = executor.execute(work, context);
                if (result == null) {
                    return Completion.failure(work, retry, new ScheduledFailure(
                            ScheduledFailure.Category.INTERNAL,
                            "schedule.work.null-result", 0L));
                }
                return Completion.success(work, retry, validateResult(result));
            }
        } catch (ScheduledExecutionException failure) {
            return Completion.failure(work, retry, sanitizeFailure(failure.toFailure()));
        } catch (Throwable ignored) {
            return Completion.failure(work, retry, new ScheduledFailure(
                    ScheduledFailure.Category.INTERNAL,
                    "schedule.work.plugin-failure", 0L));
        }
    }

    @Override
    public void drain() throws ScheduledExecutionException {
        RuntimeException drainFailure = null;
        while (inFlight > 0) {
            try {
                awaitOne();
            } catch (RuntimeException failure) {
                if (drainFailure == null) {
                    drainFailure = failure;
                }
            }
        }
        if (interruptedWhileWaiting) {
            interruptedWhileWaiting = false;
            Thread.currentThread().interrupt();
        }
        if (drainFailure != null) {
            throw drainFailure;
        }
        throwTerminalFailure();
    }

    void stopAccepting() {
        accepting = false;
    }

    long attemptedWorkCount() {
        return attemptedWorkCount;
    }

    int completedWorkCount() {
        return completedWorkCount;
    }

    Map<String, ScheduledWorkRunStatistics> statistics() {
        Map<String, ScheduledWorkRunStatistics> result = new LinkedHashMap<>();
        statistics.forEach((workType, value) -> result.put(workType, value.freeze()));
        return Map.copyOf(result);
    }

    List<ScheduleExecutionResult.PendingExhausted> pendingExhausted() {
        return List.copyOf(pendingExhausted);
    }

    private void awaitOne() {
        Future<Completion> future = null;
        InFlightWork dispatched = null;
        while (dispatched == null) {
            try {
                future = completions.take();
                dispatched = inFlightWork.remove(future);
            } catch (InterruptedException failure) {
                interruptedWhileWaiting = true;
                setTerminalFailure(ScheduledExecutionException.cancelled());
            }
        }
        try {
            Completion completion = null;
            while (completion == null) {
                try {
                    completion = future.get();
                } catch (InterruptedException failure) {
                    interruptedWhileWaiting = true;
                    setTerminalFailure(ScheduledExecutionException.cancelled());
                }
            }
            recordCompletion(completion);
        } catch (ExecutionException failure) {
            ScheduledExecutionException terminal = new ScheduledExecutionException(
                    ScheduledFailure.Category.INTERNAL,
                    "schedule.work.infrastructure-failure");
            try {
                recordCompletion(Completion.failure(
                        dispatched.work(), dispatched.retry(), terminal.toFailure()));
            } finally {
                setTerminalFailure(terminal);
            }
        } catch (CancellationException failure) {
            ScheduledExecutionException terminal = new ScheduledExecutionException(
                    ScheduledFailure.Category.INTERNAL,
                    "schedule.work.infrastructure-cancelled");
            try {
                recordCompletion(Completion.failure(
                        dispatched.work(), dispatched.retry(), terminal.toFailure()));
            } finally {
                setTerminalFailure(terminal);
            }
        } finally {
            inFlight--;
            inFlightByType.computeIfPresent(
                    dispatched.workType(), (ignored, count) -> count - 1);
        }
    }

    private void recordCompletion(Completion completion) {
        ScheduledWork work = completion.work();
        String workType = work.key().workType();
        MutableStatistics typeStatistics = statistics.get(workType);
        if (completion.result() != null) {
            store.deletePendingWork(taskId, work.key().workType(), work.key().id());
            pending.remove(work.key());
            switch (completion.result().outcome()) {
                case COMPLETED -> {
                    typeStatistics.completed++;
                    completedWorkCount++;
                    consecutiveCredentialFailures.put(workType, 0);
                }
                case ALREADY_COMPLETED -> {
                    typeStatistics.alreadyCompleted++;
                    consecutiveCredentialFailures.put(workType, 0);
                }
                case SKIPPED -> typeStatistics.skipped++;
            }
            markResult(work, completion.result());
            return;
        }

        ScheduledFailure failure = completion.failure();
        if (failure.category() == ScheduledFailure.Category.NOT_FOUND) {
            store.deletePendingWork(taskId, work.key().workType(), work.key().id());
            pending.remove(work.key());
            typeStatistics.skipped++;
            runQueue.mark(work.key().id(), work.key().workType(),
                    ScheduleRunQueue.STATUS_FAILED, failure.code());
            return;
        }

        long now = System.currentTimeMillis();
        ScheduledPendingWork previous = pending.get(work.key());
        int attempts = completion.retry() && previous != null
                ? previous.attempts() + 1
                : 0;
        Long firstSeen = previous == null ? now : previous.firstSeenTime();
        ScheduledPendingWork durable = persistenceCodec.toPendingWork(
                taskId, work, failure.code(), "{}", attempts, firstSeen,
                completion.retry() ? now : null);
        store.upsertPendingWork(durable);
        pending.put(work.key(), durable);
        typeStatistics.pending++;
        runQueue.mark(work.key().id(), work.key().workType(),
                ScheduleRunQueue.STATUS_FAILED, failure.code());
        if (completion.retry() && attempts == pendingMaxAttempts) {
            ScheduleExecutionResult.PendingExhausted event =
                    new ScheduleExecutionResult.PendingExhausted(
                            work.key().workType(), work.key().id(), attempts, now, failure.code());
            pendingExhausted.add(event);
            try {
                pendingExhaustedListener.accept(event);
            } catch (RuntimeException ignored) {
                // 通知采集是 best-effort，不能改变作品耐久记账或本轮结果。
            }
        }
        switch (failure.category()) {
            case CREDENTIAL_INVALID -> {
                int failureCount = consecutiveCredentialFailures.merge(
                        workType, 1, Integer::sum);
                if (failureCount >= credentialFailureLimit && terminalFailure == null) {
                    terminalFailure = new ScheduleCredentialCircuitOpenException(
                            failureCount, failure.code());
                }
            }
            case INVALID_DEFINITION, PAYLOAD_UNSUPPORTED -> setTerminalFailure(
                    new ScheduledExecutionException(
                            failure.category(), failure.code(), failure.retryAfterMillis()));
            case RATE_LIMITED, CHALLENGE -> setTerminalFailure(
                    new ScheduledExecutionException(
                            failure.category(), failure.code(), failure.retryAfterMillis()));
            case CANCELLED -> setTerminalFailure(ScheduledExecutionException.cancelled());
            default -> {
                // 可重试与稳定不可用失败已耐久进入 pending，允许轮末 checkpoint 门控继续。
            }
        }
    }

    private void discover(ScheduledWork work) {
        runQueue.discovered(work.key().id(), work.key().workType());
        String title = work.presentation().title();
        Integer xRestrict = parseInteger(work.presentation().attributes().get("xRestrict"));
        Boolean ai = parseBoolean(work.presentation().attributes().get("ai"));
        runQueue.setMeta(work.key().id(), work.key().workType(), title, xRestrict, ai);
    }

    private void markResult(ScheduledWork work, ScheduledWorkResult result) {
        String title = result.attributes().get("title");
        if (title != null) {
            runQueue.setMeta(
                    work.key().id(), work.key().workType(), title,
                    parseInteger(result.attributes().get("xRestrict")),
                    parseBoolean(result.attributes().get("ai")));
        }
        String status = switch (result.outcome()) {
            case COMPLETED -> ScheduleRunQueue.STATUS_DOWNLOADED;
            case ALREADY_COMPLETED -> ScheduleRunQueue.STATUS_SKIPPED_DOWNLOADED;
            case SKIPPED -> ScheduleRunQueue.STATUS_SKIPPED_FILTER;
        };
        runQueue.mark(work.key().id(), work.key().workType(), status, result.resultCode());
        if (Boolean.parseBoolean(result.attributes().get("autoTranslateSubmitted"))) {
            runQueue.markAutoTranslateSubmitted(work.key().id(), work.key().workType());
        }
    }

    private void throwTerminalFailure() throws ScheduledExecutionException {
        if (terminalFailure != null) {
            throw terminalFailure;
        }
    }

    private void setTerminalFailure(ScheduledExecutionException failure) {
        if (terminalFailure == null) {
            terminalFailure = failure;
        }
    }

    private void sleepPolitely() throws ScheduledExecutionException {
        long remaining = politeDelayMillis;
        while (remaining > 0L) {
            cancellation.throwIfCancellationRequested();
            long slice = Math.min(remaining, 100L);
            try {
                Thread.sleep(slice);
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw ScheduledExecutionException.cancelled();
            }
            remaining -= slice;
        }
    }

    private void ensureAcceptingAndNotCancelled() throws ScheduledExecutionException {
        if (!accepting) {
            throw ScheduledExecutionException.cancelled();
        }
        cancellation.throwIfCancellationRequested();
    }

    private void validateSubmittedWork(ScheduledWork work) throws ScheduledExecutionException {
        try {
            persistenceCodec.validateWork(work);
        } catch (IllegalArgumentException failure) {
            throw new ScheduledExecutionException(
                    ScheduledFailure.Category.PAYLOAD_UNSUPPORTED,
                    "schedule.work.payload-invalid");
        }
    }

    private static ScheduledWorkResult validateResult(ScheduledWorkResult result)
            throws ScheduledExecutionException {
        if (result == null || !isSafeMachineCode(result.resultCode())
                || result.attributes().size() > 16) {
            throw invalidWorkResult();
        }
        int totalBytes = 0;
        for (Map.Entry<String, String> entry : result.attributes().entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (key == null || value == null
                    || !key.matches("[A-Za-z][A-Za-z0-9._-]{0,63}")
                    || ScheduleCredentialRedactor.isSensitiveFieldName(key)
                    || ScheduleCredentialRedactor.containsCredentialMaterial(value)) {
                throw invalidWorkResult();
            }
            int keyBytes = key.getBytes(StandardCharsets.UTF_8).length;
            int valueBytes = value.getBytes(StandardCharsets.UTF_8).length;
            totalBytes += keyBytes + valueBytes;
            if (valueBytes > 4_096 || totalBytes > 16_384) {
                throw invalidWorkResult();
            }
        }
        return result;
    }

    private static ScheduledExecutionException invalidWorkResult() {
        return new ScheduledExecutionException(
                ScheduledFailure.Category.PAYLOAD_UNSUPPORTED,
                "schedule.work.invalid-result");
    }

    private static ScheduledFailure sanitizeFailure(ScheduledFailure failure) {
        String code = failure.code();
        if (isSafeMachineCode(code)) {
            return failure;
        }
        return new ScheduledFailure(
                ScheduledFailure.Category.INTERNAL,
                "schedule.work.invalid-failure-code",
                0L);
    }

    private static boolean isSafeMachineCode(String code) {
        return code != null
                && code.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
                && !ScheduleCredentialRedactor.containsCredentialMaterial(code);
    }

    private static Integer parseInteger(String value) {
        try {
            return value == null ? null : Integer.valueOf(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static Boolean parseBoolean(String value) {
        return value == null ? null : Boolean.valueOf(value);
    }

    static final class CoordinatorSignal extends RuntimeException {
        private final Exception failure;

        CoordinatorSignal(Exception failure) {
            super(failure.getMessage(), null, false, false);
            this.failure = failure;
        }

        Exception failure() {
            return failure;
        }
    }

    record InFlightWork(ScheduledWork work, boolean retry, String workType) {
    }

    /**
     * 直接交给宿主 {@link TaskExecutor} 的 Future。排队期取消立即发布；运行期取消则等 callable 真正退出后发布，
     * 防止协调器在插件行为仍运行时提前释放执行租约。
     */
    private static final class TrackedWorkFuture extends FutureTask<Completion> {
        private final BlockingQueue<Future<Completion>> completionQueue;
        private final Runnable completionAction;
        private final AtomicBoolean completionPublished = new AtomicBoolean();
        private final Object lifecycleMonitor = new Object();
        private boolean runStarted;
        private boolean runFinished;

        private TrackedWorkFuture(
                Callable<Completion> callable,
                BlockingQueue<Future<Completion>> completionQueue,
                Runnable completionAction) {
            super(callable);
            this.completionQueue = completionQueue;
            this.completionAction = completionAction;
        }

        @Override
        public void run() {
            synchronized (lifecycleMonitor) {
                if (runStarted || isCancelled()) {
                    return;
                }
                runStarted = true;
            }
            try {
                super.run();
            } finally {
                boolean publishCancelled;
                synchronized (lifecycleMonitor) {
                    runFinished = true;
                    publishCancelled = isCancelled();
                }
                if (publishCancelled) {
                    publishCompletion();
                }
            }
        }

        @Override
        protected void done() {
            boolean publishNow;
            synchronized (lifecycleMonitor) {
                publishNow = !isCancelled() || !runStarted || runFinished;
            }
            if (publishNow) {
                publishCompletion();
            }
        }

        private void publishCompletion() {
            if (completionPublished.compareAndSet(false, true)) {
                try {
                    completionAction.run();
                } finally {
                    completionQueue.add(this);
                }
            }
        }
    }

    private record Completion(
            ScheduledWork work,
            boolean retry,
            ScheduledWorkResult result,
            ScheduledFailure failure
    ) {
        static Completion success(ScheduledWork work, boolean retry, ScheduledWorkResult result) {
            return new Completion(work, retry, result, null);
        }

        static Completion failure(ScheduledWork work, boolean retry, ScheduledFailure failure) {
            return new Completion(work, retry, null, failure);
        }
    }

    private static final class MutableStatistics {
        private long attempted;
        private long completed;
        private long alreadyCompleted;
        private long skipped;
        private long pending;

        ScheduledWorkRunStatistics freeze() {
            return new ScheduledWorkRunStatistics(
                    attempted, completed, alreadyCompleted, skipped, pending);
        }
    }
}
