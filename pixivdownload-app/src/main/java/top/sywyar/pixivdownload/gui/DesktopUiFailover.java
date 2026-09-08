package top.sywyar.pixivdownload.gui;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.sywyar.pixivdownload.i18n.MessageBundles;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiContext;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiProvider;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiSession;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.BiConsumer;

/** 一个进程中每个 GUI 最多尝试一次；失败后关闭界面资源并尝试剩余提供者。 */
final class DesktopUiFailover implements DesktopUiSession {
    private static final Logger log = LoggerFactory.getLogger(DesktopUiFailover.class);
    private final List<DesktopUiPluginSource> candidates;
    private final Set<String> providerIds;
    private final BiFunction<String, Consumer<Throwable>, DesktopUiContext> contexts;
    private final Consumer<DesktopUiSession> activeChanged;
    private final Runnable unavailable;
    private final BiConsumer<DesktopUiPluginSource, Throwable> failedPlugin;
    private final Set<String> failed = ConcurrentHashMap.newKeySet();
    private volatile DesktopUiPluginSource activeSource;
    private record ActiveUi(DesktopUiPluginSource source, DesktopUiSession session) {}
    private volatile ActiveUi active;
    private volatile boolean closed;
    private boolean unavailableShown;

    DesktopUiFailover(
            List<DesktopUiPluginSource> sources,
            BiFunction<String, Consumer<Throwable>, DesktopUiContext> contexts,
            Consumer<DesktopUiSession> activeChanged,
            Runnable unavailable,
            BiConsumer<DesktopUiPluginSource, Throwable> failedPlugin
    ) {
        candidates = new ArrayList<>(sources.stream()
                .filter(source -> source.plugin() instanceof DesktopUiProvider)
                .sorted(Comparator.comparing(DesktopUiPluginSource::id)).toList());
        providerIds = Set.copyOf(candidates.stream().map(DesktopUiPluginSource::id).toList());
        this.contexts = contexts;
        this.activeChanged = activeChanged;
        this.unavailable = unavailable;
        this.failedPlugin = failedPlugin;
    }

    synchronized void start(String configuredId) {
        if (closed || active != null || unavailableShown) return;
        // 先用既有显式 / 唯一默认规则；故障后的候选按稳定 id 排序，不反复启动同一坏插件。
        if (!candidates.isEmpty()) {
            try {
                List<DesktopUiProvider> available = new ArrayList<>();
                for (DesktopUiPluginSource source : candidates) {
                    if (failed.contains(source.id())) continue;
                    try {
                        available.add(new Candidate(source,
                                ((DesktopUiProvider) source.plugin()).defaultProvider()));
                    } catch (Throwable failure) {
                        rethrowFatal(failure);
                        recordFailure(source.id(), failure);
                    }
                }
                if (available.isEmpty()) {
                    showUnavailable();
                    return;
                }
                var selection = DesktopUiSelector.select(configuredId, available);
                DesktopUiPluginSource preferred = ((Candidate) selection.provider()).source();
                candidates.remove(preferred);
                candidates.add(0, preferred);
            } catch (Throwable failure) {
                rethrowFatal(failure);
                log.error(MessageBundles.getForLog("gui.launcher.log.provider-selection-failed"), failure);
                if (failed.isEmpty()) showUnavailable();
                else launchNext(configuredId == null ? "" : configuredId.trim());
                return;
            }
        }
        launchNext(configuredId == null ? "" : configuredId.trim());
    }

    private void launchNext(String previousId) {
        for (DesktopUiPluginSource source : candidates) {
            if (closed || failed.contains(source.id())) continue;
            activeSource = source;
            try {
                active = new ActiveUi(source, Objects.requireNonNull(((DesktopUiProvider) source.plugin()).launch(
                        contexts.apply(source.id(), failure -> reportFailure(source.id(), failure))), "GUI session"));
                if (failed.contains(source.id())) {
                    closeActive();
                    previousId = source.id();
                    continue;
                }
                activeChanged.accept(this);
                if (!previousId.isBlank() && !previousId.equalsIgnoreCase(source.id())) {
                    String warning = MessageBundles.get("gui.launcher.dialog.provider-fallback", previousId, source.id());
                    log.warn(MessageBundles.getForLog("gui.launcher.dialog.provider-fallback", previousId, source.id()));
                    active.session().showMessage(MessageLevel.WARNING, MessageBundles.get("gui.dialog.warning.title"), warning);
                }
                return;
            } catch (Throwable failure) {
                rethrowFatal(failure);
                recordFailure(source.id(), failure);
                closeActive();
                previousId = source.id();
            }
        }
        showUnavailable();
    }

    void reportFailure(String providerId, Throwable failure) {
        rethrowFatal(failure);
        // 不能在 EDT 上等待 provider 关闭或启动，否则 invokeAndWait / Compose 退出会互相等待。
        DesktopUiPluginSource source = activeSource;
        if (closed || !providerIds.contains(providerId) || !failed.add(providerId)) return;
        logFailure(providerId, failure);
        // 启动前或非活动 GUI 的崩溃也要排除，避免之后回退到已知故障的提供者。
        if (source == null || !source.id().equals(providerId)) return;
        Thread recovery = new Thread(() -> {
            notifyRuntime(source, failure);
            synchronized (this) {
                if (closed || activeSource == null || !activeSource.id().equals(providerId)) return;
                closeActive();
                launchNext(providerId);
            }
        }, "desktop-ui-recovery");
        recovery.setDaemon(false);
        recovery.start();
    }

    private void recordFailure(String id, Throwable failure) {
        failed.add(id);
        logFailure(id, failure);
        candidates.stream().filter(source -> source.id().equals(id)).findFirst()
                .ifPresent(source -> notifyRuntime(source, failure));
    }

    private void notifyRuntime(DesktopUiPluginSource source, Throwable failure) {
        try {
            failedPlugin.accept(source, failure);
        } catch (Throwable reportingFailure) {
            rethrowFatal(reportingFailure);
            logFailure(source.id(), reportingFailure);
        }
    }

    private static void logFailure(String id, Throwable failure) {
        log.error(MessageBundles.getForLog("gui.launcher.log.provider-failed", id), failure);
    }

    private void showUnavailable() {
        activeSource = null;
        activeChanged.accept(null);
        if (!unavailableShown) {
            unavailableShown = true;
            unavailable.run();
        }
    }

    @Override public void activate() {
        invoke(DesktopUiSession::activate);
    }

    @Override public void showMessage(MessageLevel level, String title, String message) {
        invoke(session -> session.showMessage(level, title, message));
    }

    private void invoke(Consumer<DesktopUiSession> action) {
        ActiveUi current = active;
        if (closed || current == null) return;
        try {
            action.accept(current.session());
        } catch (Throwable failure) {
            reportFailure(current.source().id(), failure);
        }
    }

    private void closeActive() {
        ActiveUi previous = active;
        active = null;
        activeChanged.accept(null);
        if (previous != null) {
            try {
                previous.session().close();
            } catch (Throwable failure) {
                rethrowFatal(failure);
                logFailure(previous.source().id(), failure);
            }
        }
    }

    @Override public synchronized void close() {
        closed = true;
        closeActive();
    }

    static void rethrowFatal(Throwable failure) {
        if (failure instanceof VirtualMachineError fatal) throw fatal;
        if (failure instanceof ThreadDeath fatal) throw fatal;
    }

    /** 选择只读取已经成功捕获的元数据，避免反复调用故障插件的 getter。 */
    private record Candidate(DesktopUiPluginSource source, boolean defaultProvider) implements DesktopUiProvider {
        @Override public String id() { return source.id(); }
        @Override public DesktopUiSession launch(DesktopUiContext context) throws Exception {
            return ((DesktopUiProvider) source.plugin()).launch(context);
        }
    }
}
