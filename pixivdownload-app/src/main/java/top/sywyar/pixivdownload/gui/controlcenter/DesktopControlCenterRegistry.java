package top.sywyar.pixivdownload.gui.controlcenter;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopAutomationSnapshot;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopAutomationSource;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopAutomationTaskContribution;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopControlCenterAvailability;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopDashboardCardContribution;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopDashboardSnapshot;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopDashboardSource;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopRunningTaskContribution;
import top.sywyar.pixivdownload.plugin.api.gui.document.DesktopUiNode.TextToken;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.runtime.ExternalCapabilityInvocationRegistry;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.runtime.ExternalCapabilityOwner;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

/** 宿主拥有的桌面控制中心物化缓存；只保存精确 owner publication 的有界纯值。 */
@Slf4j
@Component
public final class DesktopControlCenterRegistry implements DisposableBean {

    static final int MAX_CARDS_PER_OWNER = 32;
    static final int MAX_RUNNING_TASKS_PER_OWNER = 32;
    static final int MAX_AUTOMATION_TASKS_PER_OWNER = 100;
    static final int MAX_NEXT_RUNS_PER_TASK = 64;
    static final Duration STALE_AFTER = Duration.ofSeconds(60);
    private static final Duration SOURCE_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration NEXT_RUN_WINDOW = Duration.ofHours(24);
    private static final Duration FUTURE_OBSERVATION_TOLERANCE = Duration.ofSeconds(1);
    private static final int MAX_TOKEN_ARGUMENTS = 8;
    private static final int MAX_TOKEN_TEXT_LENGTH = 512;

    private static final Comparator<OwnedDashboardCard> CARD_ORDER = Comparator
            .comparingInt((OwnedDashboardCard item) -> item.card().order())
            .thenComparing(item -> item.owner().pluginId())
            .thenComparing(item -> item.card().cardId());
    private static final Comparator<OwnedRunningTask> TASK_ORDER = Comparator
            .comparingInt((OwnedRunningTask item) -> statusPriority(item.task().status()))
            .thenComparingInt(item -> item.task().order())
            .thenComparing(item -> item.owner().pluginId())
            .thenComparing(item -> item.task().taskId());
    private static final Comparator<DesktopAutomationTaskContribution> AUTOMATION_TASK_ORDER = Comparator
            .comparingInt(DesktopAutomationTaskContribution::order)
            .thenComparing(DesktopAutomationTaskContribution::taskId);

    private final Object lock = new Object();
    private final Map<Long, OwnerEntry> owners = new LinkedHashMap<>();
    private final Predicate<ExternalCapabilityOwner> admission;
    private final Executor executor;
    private final ExecutorService ownedExecutor;
    private final Clock clock;
    private final Duration sourceTimeout;

    @Autowired
    public DesktopControlCenterRegistry(ExternalCapabilityInvocationRegistry invocations) {
        this(invocations::acceptsInvocations, newWorkerExecutor(), Clock.systemUTC(), SOURCE_TIMEOUT, true);
    }

    DesktopControlCenterRegistry(Predicate<ExternalCapabilityOwner> admission,
                                 Executor executor,
                                 Clock clock,
                                 Duration sourceTimeout) {
        this(admission, executor, clock, sourceTimeout, false);
    }

    private DesktopControlCenterRegistry(Predicate<ExternalCapabilityOwner> admission,
                                         Executor executor,
                                         Clock clock,
                                         Duration sourceTimeout,
                                         boolean ownsExecutor) {
        this.admission = Objects.requireNonNull(admission, "admission");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.ownedExecutor = ownsExecutor ? (ExecutorService) executor : null;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.sourceTimeout = Objects.requireNonNull(sourceTimeout, "sourceTimeout");
        if (sourceTimeout.isNegative() || sourceTimeout.isZero()) {
            throw new IllegalArgumentException("sourceTimeout must be positive");
        }
    }

    /** 发布一个精确 owner 的事实源代理，注册过程不调用插件代码。 */
    public void registerPrepared(ExternalCapabilityOwner owner,
                                 List<DesktopDashboardSource> dashboards,
                                 List<DesktopAutomationSource> automations) {
        Objects.requireNonNull(owner, "owner");
        OwnerEntry entry = new OwnerEntry(owner, single(dashboards), single(automations));
        synchronized (lock) {
            owners.values().removeIf(current -> current.owner.pluginId().equals(owner.pluginId()));
            owners.put(owner.publicationId(), entry);
        }
    }

    /** 只撤回精确 publication；旧 owner 不能删除替代者。 */
    public void unregisterPrepared(ExternalCapabilityOwner owner) {
        if (owner == null) {
            return;
        }
        synchronized (lock) {
            OwnerEntry current = owners.get(owner.publicationId());
            if (current != null && current.owner.equals(owner)) {
                owners.remove(owner.publicationId());
            }
        }
    }

    /** 为每个 owner 至多启动一次非阻塞物化。 */
    public void refresh() {
        Instant now = clock.instant();
        for (OwnerEntry entry : entries()) {
            if (!admission.test(entry.owner)) {
                continue;
            }
            if (entry.inFlight.get()) {
                if (!entry.startedAt.plus(sourceTimeout).isAfter(now)) {
                    entry.materialized = entry.materialized.stale();
                    reportOnce(entry.timeoutReported, entry.owner, "CONTROL_CENTER_SOURCE_TIMEOUT", null);
                }
                continue;
            }
            if (!entry.inFlight.compareAndSet(false, true)) {
                continue;
            }
            entry.startedAt = now;
            try {
                executor.execute(() -> materialize(entry));
            } catch (RuntimeException rejected) {
                entry.inFlight.set(false);
                entry.materialized = entry.materialized.stale();
                reportOnce(entry.timeoutReported, entry.owner, "CONTROL_CENTER_EXECUTOR_REJECTED", rejected);
            }
        }
    }

    /** 只返回仍允许新调用的精确 publication。 */
    public Snapshot snapshot() {
        List<OwnedDashboardCard> cards = new ArrayList<>();
        List<OwnedRunningTask> tasks = new ArrayList<>();
        List<OwnedAutomationSnapshot> automations = new ArrayList<>();
        for (OwnerEntry entry : entries()) {
            if (!admission.test(entry.owner)) {
                continue;
            }
            Owner owner = Owner.from(entry.owner);
            Materialized materialized = entry.materialized;
            materialized.cards.forEach(card -> cards.add(new OwnedDashboardCard(owner, card)));
            materialized.runningTasks.forEach(task -> tasks.add(new OwnedRunningTask(owner, task)));
            if (materialized.automation != null) {
                automations.add(new OwnedAutomationSnapshot(owner, materialized.automation));
            }
        }
        cards.sort(CARD_ORDER);
        tasks.sort(TASK_ORDER);
        automations.sort(Comparator.comparing(item -> item.owner().pluginId()));
        return new Snapshot(cards, tasks, automations, clock.instant());
    }

    private void materialize(OwnerEntry entry) {
        try {
            if (!isCurrent(entry) || !admission.test(entry.owner)) {
                return;
            }
            Instant now = clock.instant();
            Materialized current = entry.materialized;
            List<DesktopDashboardCardContribution> cards = current.cards;
            List<DesktopRunningTaskContribution> runningTasks = current.runningTasks;
            DesktopAutomationSnapshot automation = current.automation;
            if (entry.dashboard != null) {
                try {
                    DashboardValues values = dashboard(entry.dashboard.snapshot(), now);
                    cards = values.cards;
                    runningTasks = values.runningTasks;
                    entry.dashboardFailureReported.set(false);
                } catch (Throwable failure) {
                    rethrowFatal(failure);
                    cards = staleCards(cards);
                    runningTasks = staleTasks(runningTasks);
                    reportOnce(entry.dashboardFailureReported, entry.owner,
                            "CONTROL_CENTER_DASHBOARD_SOURCE_FAILED", failure);
                }
            }
            if (entry.automation != null) {
                try {
                    automation = automation(entry.automation.snapshot(), now);
                    entry.automationFailureReported.set(false);
                } catch (Throwable failure) {
                    rethrowFatal(failure);
                    automation = automation == null
                            ? new DesktopAutomationSnapshot(
                                    List.of(), DesktopControlCenterAvailability.UNAVAILABLE, now)
                            : staleAutomation(automation);
                    reportOnce(entry.automationFailureReported, entry.owner,
                            "CONTROL_CENTER_AUTOMATION_SOURCE_FAILED", failure);
                }
            }
            if (isCurrent(entry) && admission.test(entry.owner)) {
                entry.timeoutReported.set(false);
                entry.materialized = new Materialized(cards, runningTasks, automation);
            }
        } finally {
            entry.inFlight.set(false);
        }
    }

    private DashboardValues dashboard(DesktopDashboardSnapshot snapshot, Instant now) {
        Objects.requireNonNull(snapshot, "dashboard snapshot");
        requireObservedAt(snapshot.observedAt(), now);
        boolean stale = isStale(snapshot.observedAt(), now);
        List<DesktopDashboardCardContribution> cards = new ArrayList<>();
        Set<String> cardIds = new LinkedHashSet<>();
        for (DesktopDashboardCardContribution card : snapshot.cards()) {
            if (cards.size() == MAX_CARDS_PER_OWNER) break;
            if (valid(card, now) && cardIds.add(card.cardId())) {
                cards.add(withAvailability(card,
                        stale(card.availability(), stale || isStale(card.observedAt(), now))));
            }
        }
        List<DesktopRunningTaskContribution> tasks = new ArrayList<>();
        Set<String> taskIds = new LinkedHashSet<>();
        for (DesktopRunningTaskContribution task : snapshot.runningTasks()) {
            if (tasks.size() == MAX_RUNNING_TASKS_PER_OWNER) break;
            if (valid(task, now) && taskIds.add(task.taskId())) {
                tasks.add(withAvailability(task,
                        stale(task.availability(), stale || isStale(task.observedAt(), now))));
            }
        }
        return new DashboardValues(cards, tasks);
    }

    private DesktopAutomationSnapshot automation(DesktopAutomationSnapshot snapshot, Instant now) {
        Objects.requireNonNull(snapshot, "automation snapshot");
        requireObservedAt(snapshot.observedAt(), now);
        boolean stale = isStale(snapshot.observedAt(), now);
        List<DesktopAutomationTaskContribution> tasks = new ArrayList<>();
        Set<String> taskIds = new LinkedHashSet<>();
        for (DesktopAutomationTaskContribution task : snapshot.tasks()) {
            if (tasks.size() == MAX_AUTOMATION_TASKS_PER_OWNER) break;
            DesktopAutomationTaskContribution safe = safeAutomationTask(task, now);
            if (safe != null && taskIds.add(safe.taskId())) {
                tasks.add(safe);
            }
        }
        tasks.sort(AUTOMATION_TASK_ORDER);
        return new DesktopAutomationSnapshot(tasks, stale(snapshot.availability(), stale), snapshot.observedAt());
    }

    private static DesktopAutomationTaskContribution safeAutomationTask(
            DesktopAutomationTaskContribution task, Instant now) {
        if (task == null || !validToken(task.title()) || !validToken(task.triggerSummary())
                || !validObservedAt(task.observedAt(), now)) {
            return null;
        }
        Instant end = now.plus(NEXT_RUN_WINDOW);
        List<Instant> nextRuns = task.nextRuns().stream()
                .filter(run -> !run.isBefore(now) && !run.isAfter(end))
                .distinct()
                .sorted()
                .limit(MAX_NEXT_RUNS_PER_TASK)
                .toList();
        return new DesktopAutomationTaskContribution(task.taskId(), task.order(), task.title(),
                task.triggerSummary(), task.status(), task.lastResult(), nextRuns, task.observedAt());
    }

    private static boolean valid(DesktopDashboardCardContribution card, Instant now) {
        return card != null && validToken(card.title()) && validToken(card.primaryValue())
                && validToken(card.supportingText()) && validObservedAt(card.observedAt(), now);
    }

    private static boolean valid(DesktopRunningTaskContribution task, Instant now) {
        return task != null && validToken(task.title()) && validToken(task.supportingText())
                && validObservedAt(task.observedAt(), now);
    }

    private static boolean validToken(TextToken token) {
        if (token == null || token.arguments().size() > MAX_TOKEN_ARGUMENTS) return false;
        if (tooLong(token.namespace()) || tooLong(token.key()) || tooLong(token.fallback())) return false;
        return token.arguments().stream().noneMatch(DesktopControlCenterRegistry::tooLong);
    }

    private static boolean tooLong(String value) {
        return value != null && value.length() > MAX_TOKEN_TEXT_LENGTH;
    }

    private static void requireObservedAt(Instant observedAt, Instant now) {
        if (!validObservedAt(observedAt, now)) {
            throw new IllegalArgumentException("control-center observation time is in the future");
        }
    }

    private static boolean validObservedAt(Instant observedAt, Instant now) {
        return observedAt != null && !observedAt.isAfter(now.plus(FUTURE_OBSERVATION_TOLERANCE));
    }

    private static boolean isStale(Instant observedAt, Instant now) {
        return observedAt.isBefore(now.minus(STALE_AFTER));
    }

    private boolean isCurrent(OwnerEntry entry) {
        synchronized (lock) {
            return owners.get(entry.owner.publicationId()) == entry;
        }
    }

    private List<OwnerEntry> entries() {
        synchronized (lock) {
            return List.copyOf(owners.values());
        }
    }

    private static <T> T single(List<T> values) {
        List<T> copy = values == null ? List.of() : List.copyOf(values);
        return copy.size() == 1 ? Objects.requireNonNull(copy.get(0)) : null;
    }

    private static DesktopControlCenterAvailability stale(
            DesktopControlCenterAvailability availability, boolean stale) {
        return stale && availability == DesktopControlCenterAvailability.AVAILABLE
                ? DesktopControlCenterAvailability.STALE : availability;
    }

    private static List<DesktopDashboardCardContribution> staleCards(
            List<DesktopDashboardCardContribution> cards) {
        return cards.stream().map(card -> withAvailability(card,
                stale(card.availability(), true))).toList();
    }

    private static List<DesktopRunningTaskContribution> staleTasks(
            List<DesktopRunningTaskContribution> tasks) {
        return tasks.stream().map(task -> withAvailability(task,
                stale(task.availability(), true))).toList();
    }

    private static DesktopAutomationSnapshot staleAutomation(DesktopAutomationSnapshot snapshot) {
        if (snapshot == null) return null;
        return new DesktopAutomationSnapshot(snapshot.tasks(),
                stale(snapshot.availability(), true), snapshot.observedAt());
    }

    private static DesktopDashboardCardContribution withAvailability(
            DesktopDashboardCardContribution card, DesktopControlCenterAvailability availability) {
        return new DesktopDashboardCardContribution(card.cardId(), card.order(), card.title(), card.primaryValue(),
                card.supportingText(), card.tone(), card.icon(), availability, card.observedAt());
    }

    private static DesktopRunningTaskContribution withAvailability(
            DesktopRunningTaskContribution task, DesktopControlCenterAvailability availability) {
        return new DesktopRunningTaskContribution(task.taskId(), task.order(), task.title(), task.supportingText(),
                task.status(), task.progress(), availability, task.observedAt());
    }

    private static int statusPriority(DesktopRunningTaskContribution.Status status) {
        return switch (status) {
            case RUNNING -> 0;
            case PREPARING -> 1;
            case FINALIZING -> 2;
            case QUEUED -> 3;
            case UNKNOWN -> 4;
        };
    }

    private static void rethrowFatal(Throwable failure) {
        if (failure instanceof VirtualMachineError error) throw error;
        if (failure instanceof ThreadDeath death) throw death;
    }

    private static void reportOnce(AtomicBoolean reported,
                                   ExternalCapabilityOwner owner,
                                   String diagnosticCode,
                                   Throwable failure) {
        if (reported.compareAndSet(false, true)) {
            log.warn("desktop control-center source degraded: code={}, pluginId={}, packageId={}, generation={}, "
                            + "publication={}, failureType={}",
                    diagnosticCode, owner.pluginId(), owner.packageId(), owner.pluginGeneration(),
                    owner.publicationId(), failure == null ? "none" : failure.getClass().getName());
        }
    }

    private static ExecutorService newWorkerExecutor() {
        AtomicInteger sequence = new AtomicInteger();
        ThreadFactory factory = task -> {
            Thread thread = new Thread(task, "desktop-control-center-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        return new ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(32), factory, new ThreadPoolExecutor.AbortPolicy());
    }

    @Override
    public void destroy() {
        if (ownedExecutor != null) {
            ownedExecutor.shutdownNow();
        }
    }

    public record Owner(String pluginId, String packageId, long generation, long publication) {
        private static Owner from(ExternalCapabilityOwner owner) {
            return new Owner(owner.pluginId(), owner.packageId(), owner.pluginGeneration(), owner.publicationId());
        }
    }

    public record OwnedDashboardCard(Owner owner, DesktopDashboardCardContribution card) {
        public OwnedDashboardCard {
            owner = Objects.requireNonNull(owner, "owner");
            card = Objects.requireNonNull(card, "card");
        }
    }

    public record OwnedRunningTask(Owner owner, DesktopRunningTaskContribution task) {
        public OwnedRunningTask {
            owner = Objects.requireNonNull(owner, "owner");
            task = Objects.requireNonNull(task, "task");
        }
    }

    public record OwnedAutomationSnapshot(Owner owner, DesktopAutomationSnapshot snapshot) {
        public OwnedAutomationSnapshot {
            owner = Objects.requireNonNull(owner, "owner");
            snapshot = Objects.requireNonNull(snapshot, "snapshot");
        }
    }

    public record Snapshot(List<OwnedDashboardCard> cards,
                           List<OwnedRunningTask> runningTasks,
                           List<OwnedAutomationSnapshot> automations,
                           Instant observedAt) {
        public Snapshot {
            cards = List.copyOf(cards);
            runningTasks = List.copyOf(runningTasks);
            automations = List.copyOf(automations);
            observedAt = Objects.requireNonNull(observedAt, "observedAt");
        }
    }

    private static final class OwnerEntry {
        private final ExternalCapabilityOwner owner;
        private final DesktopDashboardSource dashboard;
        private final DesktopAutomationSource automation;
        private final AtomicBoolean inFlight = new AtomicBoolean();
        private final AtomicBoolean dashboardFailureReported = new AtomicBoolean();
        private final AtomicBoolean automationFailureReported = new AtomicBoolean();
        private final AtomicBoolean timeoutReported = new AtomicBoolean();
        private volatile Instant startedAt = Instant.EPOCH;
        private volatile Materialized materialized = Materialized.empty();

        private OwnerEntry(ExternalCapabilityOwner owner,
                           DesktopDashboardSource dashboard,
                           DesktopAutomationSource automation) {
            this.owner = owner;
            this.dashboard = dashboard;
            this.automation = automation;
        }
    }

    private record DashboardValues(List<DesktopDashboardCardContribution> cards,
                                   List<DesktopRunningTaskContribution> runningTasks) {
        private DashboardValues {
            cards = List.copyOf(cards);
            runningTasks = List.copyOf(runningTasks);
        }
    }

    private record Materialized(List<DesktopDashboardCardContribution> cards,
                                List<DesktopRunningTaskContribution> runningTasks,
                                DesktopAutomationSnapshot automation) {
        private Materialized {
            cards = List.copyOf(cards);
            runningTasks = List.copyOf(runningTasks);
        }

        private static Materialized empty() {
            return new Materialized(List.of(), List.of(), null);
        }

        private Materialized stale() {
            return new Materialized(staleCards(cards), staleTasks(runningTasks), staleAutomation(automation));
        }
    }
}
