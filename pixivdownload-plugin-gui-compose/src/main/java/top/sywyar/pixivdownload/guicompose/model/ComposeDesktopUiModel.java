package top.sywyar.pixivdownload.guicompose.model;

import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiPluginSnapshot;

import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiHost;

import top.sywyar.pixivdownload.guicompose.model.DesktopUiEventProtocol.EventEndpoint;
import top.sywyar.pixivdownload.guicompose.model.DesktopUiEventProtocol.InteractionSignature;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiIcon;
import top.sywyar.pixivdownload.guicompose.model.DesktopUiModel;
import top.sywyar.pixivdownload.guicompose.model.DesktopUiSnapshot;
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiDocument;
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode;
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode.TextStyle;
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode.TextToken;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static top.sywyar.pixivdownload.guicompose.model.GuiActionResponseSafety.sanitizeActionText;
import static top.sywyar.pixivdownload.guicompose.model.DesktopUiNodes.*;

/**
 * Compose 插件拥有的完整桌面文档与事件分派器。
 */
public final class ComposeDesktopUiModel implements DesktopUiModel, AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(ComposeDesktopUiModel.class);
    private final int serverPort;
    private final String rootFolder;
    private final Path configPath;
    private final DesktopUiHost host;
    private final DesktopToolsController tools;
    private final DesktopOnboardingController onboarding;
    private final DesktopStatusController statusController;
    final DesktopNavigationView navigation;
    private final DesktopPluginStatusController pluginStatus;
    private final DesktopSecurityController security;
    private final DesktopAboutView aboutView;
    private final DesktopControlCenterView controlCenterView;
    private final DesktopDirectoryMigrationController directoryMigration;
    private final DesktopConfigurationController configuration;
    private final Supplier<List<DesktopUiPluginSnapshot>> pluginSources;
    private final ExecutorService worker = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "desktop-ui-model");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<String, String> formValues = new ConcurrentHashMap<>();
    private final List<Consumer<DesktopUiSnapshot>> snapshotListeners = new CopyOnWriteArrayList<>();
    private volatile Map<String, Consumer<List<String>>> selectionBindings = Map.of();
    private volatile Map<String, Runnable> actions = Map.of();
    private volatile Map<String, EventEndpoint> eventEndpoints = Map.of();
    private Map<String, InteractionSignature> interactionSignatures = Map.of();
    private long interactionRevisionSequence;
    private volatile DesktopUiSnapshot snapshot;
    private volatile DesktopUiHost.BackendSnapshot backend;
    volatile String statusNotice = "";
    private volatile long backendStateChangedAt = System.currentTimeMillis();
    private volatile boolean busy;
    private volatile DialogState dialogState;
    private volatile AutoCloseable backendSubscription;
    private volatile List<DesktopUiPluginSnapshot> rebuildSources;
    private volatile List<DesktopUiPluginSnapshot.Fingerprint> documentSourceFingerprints = List.of();
    private volatile Locale documentLocale;
    private volatile boolean closed;

    public ComposeDesktopUiModel(
            int serverPort,
            String rootFolder,
            Path configPath,
            DesktopUiHost host,
            Supplier<List<DesktopUiPluginSnapshot>> pluginSources
    ) {
        this.serverPort = serverPort;
        this.rootFolder = Objects.requireNonNull(rootFolder, "rootFolder");
        this.configPath = Objects.requireNonNull(configPath, "configPath");
        this.host = Objects.requireNonNull(host, "host");
        this.tools = new DesktopToolsController(
                this,
                host,
                rootFolder,
                formValues
        );
        this.pluginSources = Objects.requireNonNull(pluginSources, "pluginSources");
        this.configuration = new DesktopConfigurationController(
                this,
                host,
                configPath,
                formValues
        );
        this.backend = host.backendSnapshot();
        this.onboarding = new DesktopOnboardingController(
                this,
                host,
                rootFolder,
                formValues
        );
        this.statusController = new DesktopStatusController(
                this,
                host,
                serverPort,
                rootFolder,
                formValues
        );
        this.navigation = new DesktopNavigationView(this, host, statusController);
        this.pluginStatus = new DesktopPluginStatusController(this, host);
        this.security = new DesktopSecurityController(
                this,
                host,
                formValues
        );
        this.aboutView = new DesktopAboutView(
                this,
                host,
                statusController
        );
        this.controlCenterView = new DesktopControlCenterView(this, host, rootFolder);
        this.directoryMigration = new DesktopDirectoryMigrationController(
                this,
                host,
                formValues
        );
        configuration.load();
        rebuild();
        try {
            backendSubscription = host.subscribeBackend(snapshot -> {
                DesktopUiHost.BackendState previousState = backend.state();
                if (snapshot.state() != previousState)
                    backendStateChangedAt = System.currentTimeMillis();
                backend = snapshot;
                if (snapshot.state() != DesktopUiHost.BackendState.RUNNING) {
                    statusController.resetConnection();
                }
                if (snapshot.state() == DesktopUiHost.BackendState.RUNNING && previousState != DesktopUiHost.BackendState.RUNNING) {
                    executeAsync(() -> {
                        statusController.refreshSnapshot();
                        onboarding.refreshState();
                        pluginStatus.load();
                        rebuild();
                    });
                } else {
                    rebuild();
                }
            });
        } catch (RuntimeException ignored) {
            backendSubscription = null;
        }
        statusController.refresh();
        statusController.startPolling();
        statusController.updates.scheduleInitialUpdateLookup();
        directoryMigration.scheduleSymbolicOrphanCheck();
    }

    @Override
    public DesktopUiSnapshot snapshot() {
        return snapshot;
    }

    /**
     * 订阅实际发布的桌面快照；注册后立即投递当前值，回调应快速返回。
     *
     * @param listener 快照监听器
     * @return 用于取消订阅的句柄
     */
    public synchronized AutoCloseable subscribeSnapshots(Consumer<DesktopUiSnapshot> listener) {
        Consumer<DesktopUiSnapshot> value = Objects.requireNonNull(listener, "listener");
        if (closed) return () -> {};
        snapshotListeners.add(value);
        notifySnapshotListener(value, snapshot);
        return () -> snapshotListeners.remove(value);
    }

    public void dispatch(DesktopUiSnapshot observed, DesktopUiNode.Event event) {
        Objects.requireNonNull(observed, "observed");
        DesktopUiNode.Event value = Objects.requireNonNull(event, "event");
        if (value.type() == DesktopUiNode.EventType.ACTIVATE) {
            dispatch(value.atRevision(observed.revision()));
            return;
        }
        Long interactionRevision = observed.interactionRevisions().get(value.nodeId());
        if (interactionRevision == null) {
            throw new IllegalArgumentException(
                    "snapshot has no interaction revision for node " + value.nodeId());
        }
        dispatch(value.atRevisions(observed.revision(), interactionRevision));
    }

    public void dispatch(long documentRevision, DesktopUiNode.Event event) {
        DesktopUiNode.Event value = Objects.requireNonNull(event, "event");
        if (value.type() != DesktopUiNode.EventType.ACTIVATE) {
            throw new IllegalArgumentException(
                    "value event must be stamped from a desktop UI snapshot");
        }
        dispatch(value.atRevision(documentRevision));
    }

    @Override
    public synchronized void dispatch(DesktopUiNode.Event event) {
        Objects.requireNonNull(event, "event");
        if (closed) return;
        DesktopUiSnapshot currentSnapshot = snapshot;
        EventEndpoint endpoint = eventEndpoints.get(event.nodeId());
        if (event.type() == DesktopUiNode.EventType.ACTIVATE) {
            if (event.documentRevision() != currentSnapshot.revision()) {
                LOG.warn(
                        "Ignored stale desktop UI event (nodeId={}, type={}, reason=stale-document)",
                        event.nodeId(),
                        event.type()
                );
                return;
            }
        } else {
            Long currentInteractionRevision = currentSnapshot.interactionRevisions().get(event.nodeId());
            if (currentInteractionRevision == null || event.interactionRevision() != currentInteractionRevision) {
                LOG.warn(
                        "Ignored stale desktop UI event (nodeId={}, type={}, reason=stale-interaction)",
                        event.nodeId(),
                        event.type()
                );
                return;
            }
        }
        String rejection = DesktopUiEventProtocol.validate(endpoint, event);
        if (rejection != null) {
            LOG.warn(
                    "Ignored invalid desktop UI event (nodeId={}, type={}, reason={})",
                    event.nodeId(),
                    event.type(),
                    rejection
            );
            return;
        }
        String targetId = endpoint.targetId();
        if (event.type() == DesktopUiNode.EventType.ACTIVATE) {
            Runnable action = actions.get(targetId);
            if (action != null) action.run();
            return;
        }
        String value = event.value().values().stream().findFirst().orElse("");
        if (configuration.acceptField(targetId, value)) {
            rebuild();
            return;
        }
        Consumer<List<String>> selection = selectionBindings.get(targetId);
        if (selection != null) {
            acceptSelection(selection, endpoint.node(), event.value());
            rebuild();
            return;
        }
        if (configuration.acceptForm(targetId, value)) {
            rebuild();
            return;
        }
        formValues.put(targetId, value);
        switch (targetId) {
            case "welcome.password" -> onboarding.passwordChanged();
            case "folder.selected" -> tools.selectFolder(value);
            default -> {
            }
        }
        rebuild();
    }

    static void acceptSelection(
            Consumer<List<String>> selection,
            DesktopUiNode node,
            DesktopUiNode.Value value
    ) {
        if (value.kind() != DesktopUiNode.ValueKind.MULTI_SELECTION) {
            selection.accept(value.values());
            return;
        }
        Set<String> selected = Set.copyOf(value.values());
        if (node instanceof DesktopUiNode.Choice choice) {
            selection.accept(choice.options().stream().map(DesktopUiNode.Option::id).filter(selected::contains).toList());
        } else if (node instanceof DesktopUiNode.Table table) {
            selection.accept(table.rows().stream().map(DesktopUiNode.TableRow::id).filter(selected::contains).toList());
        } else if (node instanceof DesktopUiNode.Tree tree) {
            List<String> ordered = new ArrayList<>();
            DesktopUiEventProtocol.collectTreeItemIds(tree.items(), ordered);
            selection.accept(ordered.stream().filter(selected::contains).toList());
        } else {
            throw new IllegalArgumentException("selection binding requires a selectable node");
        }
    }

    synchronized void rebuild() {
        if (closed) return;
        DesktopUiSnapshot published = null;
        List<DesktopUiPluginSnapshot> previousSources = rebuildSources;
        rebuildSources = loadCurrentSources();
        List<DesktopUiPluginSnapshot.Fingerprint> sourceFingerprints = rebuildSources.stream().map(
                DesktopUiPluginSnapshot::fingerprint).toList();
        boolean sourcesChanged = !sourceFingerprints.equals(documentSourceFingerprints);
        Locale currentLocale = Locale.getDefault();
        boolean localeChanged = !currentLocale.equals(documentLocale);
        try {
            Map<String, Consumer<List<String>>> nextSelections = new LinkedHashMap<>();
            Map<String, Runnable> nextActions = new LinkedHashMap<>();
            List<DesktopUiDocument.Page> pages = new ArrayList<>();
            appendHostPages(pages, nextSelections, nextActions);
            List<DesktopUiDocument.Dialog> dialogs = new ArrayList<>();
            if (dialogState != null) dialogs.add(dialog(dialogState, nextActions));
            tools.dialog(nextActions).ifPresent(dialogs::add);
            long candidateRevision = snapshot == null ? 1L : snapshot.revision() + 1L;
            DesktopUiDocument.Tray tray = navigation.tray(nextActions);
            nextActions.put("debug.unlock", configuration::unlockDebug);
            DesktopUiDocument nextDocument = new DesktopUiDocument(
                    pages,
                    dialogs,
                    List.of(new DesktopUiDocument.KeyboardShortcut(
                            "debug.unlock.shortcut",
                            List.of(
                                    keyStroke("ArrowUp"),
                                    keyStroke("ArrowUp"),
                                    keyStroke("ArrowDown"),
                                    keyStroke("ArrowDown"),
                                    keyStroke("ArrowLeft"),
                                    keyStroke("ArrowRight"),
                                    keyStroke("ArrowLeft"),
                                    keyStroke("ArrowRight"),
                                    keyStroke("KeyB"),
                                    keyStroke("KeyA"),
                                    keyStroke("KeyB"),
                                    keyStroke("KeyA")
                            ),
                            "debug.unlock",
                            false
                    )),
                    Optional.of(tray)
            );
            Map<String, EventEndpoint> nextEventEndpoints = DesktopUiEventProtocol.index(
                    nextDocument);
            Map<String, InteractionSignature> nextInteractionSignatures = DesktopUiEventProtocol.interactionSignatures(
                    nextEventEndpoints,
                    sourceFingerprints
            );
            Map<String, Long> nextInteractionRevisions = interactionRevisions(
                    nextInteractionSignatures);
            selectionBindings = Map.copyOf(nextSelections);
            actions = Map.copyOf(nextActions);
            eventEndpoints = nextEventEndpoints;
            if (sourcesChanged || localeChanged || snapshot == null || !nextDocument.equals(snapshot.document())) {
                documentSourceFingerprints = sourceFingerprints;
                documentLocale = currentLocale;
                snapshot = new DesktopUiSnapshot(
                        candidateRevision,
                        nextDocument,
                        nextInteractionRevisions
                );
                published = snapshot;
            }
            interactionSignatures = nextInteractionSignatures;
        } finally {
            rebuildSources = previousSources;
        }
        if (published != null) {
            for (Consumer<DesktopUiSnapshot> listener : snapshotListeners) {
                notifySnapshotListener(listener, published);
            }
        }
    }

    private static void notifySnapshotListener(
            Consumer<DesktopUiSnapshot> listener,
            DesktopUiSnapshot value) {
        try {
            listener.accept(value);
        } catch (RuntimeException failure) {
            LOG.warn("Desktop snapshot listener failed", failure);
        }
    }

    private void appendHostPages(
            List<DesktopUiDocument.Page> pages,
            Map<String, Consumer<List<String>>> nextSelections,
            Map<String, Runnable> nextActions
    ) {
        appendControlCenterPages(pages, nextSelections, nextActions);
    }

    private void appendControlCenterPages(
            List<DesktopUiDocument.Page> pages,
            Map<String, Consumer<List<String>>> nextSelections,
            Map<String, Runnable> nextActions
    ) {
        DesktopUiHost.OnboardingSnapshot onboarding = host.onboardingState(rootFolder);
        pages.add(onboarding.complete() ? controlCenterView.homePage(nextActions) : page(
                "home",
                DesktopUiIcon.HOME,
                this.onboarding.controlCenterPage(onboarding, nextActions)
        ));
        pages.add(page(
                "automation",
                DesktopUiIcon.AUTOMATION,
                controlCenterView.automationPage()
        ));
        pages.add(page(
                "plugins",
                DesktopUiIcon.PLUGIN,
                pluginStatus.controlCenterPage()
        ));
        pages.add(page(
                "tools",
                DesktopUiIcon.TOOLS,
                tools.controlCenterPage(nextActions)
        ));
        pages.add(page("security", DesktopUiIcon.SECURITY, security.page(nextActions)));
        pages.add(page(
                "settings",
                DesktopUiIcon.SETTINGS,
                configuration.controlCenterPage(nextSelections, nextActions),
                DesktopUiNode.Insets.NONE
        ));
        pages.add(page("about", DesktopUiIcon.ABOUT, aboutView.page(nextActions)));
    }

    private Map<String, Long> interactionRevisions(Map<String, InteractionSignature> signatures) {
        Map<String, Long> revisions = new LinkedHashMap<>();
        Map<String, Long> previous = snapshot == null ? Map.of() : snapshot.interactionRevisions();
        signatures.forEach((nodeId, signature) -> {
            Long revision = signature.equals(interactionSignatures.get(nodeId)) ? previous.get(
                    nodeId) : null;
            revisions.put(nodeId, revision == null ? nextInteractionRevision() : revision);
        });
        return Map.copyOf(revisions);
    }

    private long nextInteractionRevision() {
        interactionRevisionSequence = Math.incrementExact(interactionRevisionSequence);
        return interactionRevisionSequence;
    }

    private static DesktopUiDocument.KeyStroke keyStroke(String key) {
        return DesktopUiDocument.KeyStroke.key(key);
    }

    private DesktopUiDocument.Page page(
            String id,
            DesktopUiIcon icon,
            DesktopUiNode content
    ) {
        return page(
                id,
                icon,
                content,
                new DesktopUiNode.Insets(
                        16,
                        24,
                        16,
                        24
                )
        );
    }

    DesktopUiDocument.Page page(
            String id,
            DesktopUiIcon icon,
            DesktopUiNode content,
            DesktopUiNode.Insets padding
    ) {
        return page(
                id,
                icon,
                content,
                padding,
                null
        );
    }

    DesktopUiDocument.Page page(
            String id,
            DesktopUiIcon icon,
            DesktopUiNode content,
            DesktopUiNode.Insets padding,
            DesktopUiNode floatingAction
    ) {
        return new DesktopUiDocument.Page(
                id,
                key("desktop.ui.page." + id),
                icon,
                new DesktopUiNode.Surface(
                        id + ".page",
                        DesktopUiNode.SurfaceStyle.PLAIN,
                        padding,
                        true,
                        true,
                        content
                ),
                Optional.ofNullable(floatingAction)
        );
    }

    private DesktopUiDocument.Dialog dialog(
            DialogState state,
            Map<String, Runnable> nextActions
    ) {
        String dismissAction = state.id() + ".dismiss";
        Runnable dismiss = () -> {
            dialogState = null;
            rebuild();
        };
        nextActions.put(dismissAction, dismiss);
        DesktopUiNode.SurfaceStyle surfaceStyle = switch (state.style()) {
            case SUCCESS -> DesktopUiNode.SurfaceStyle.SUCCESS;
            case WARNING, QUESTION -> DesktopUiNode.SurfaceStyle.WARNING;
            case ERROR -> DesktopUiNode.SurfaceStyle.ERROR;
            case INFO -> DesktopUiNode.SurfaceStyle.INFO;
        };
        DesktopUiNode body = state.content() == null ? column(
                state.id() + ".content",
                new DesktopUiNode.Text(
                        state.id() + ".message",
                        state.message(),
                        TextStyle.BODY,
                        true,
                        true
                ),
                row(
                        state.id() + ".actions",
                        button(
                                state.id() + ".close",
                                dismissAction,
                                "desktop.ui.action.close",
                                true,
                                nextActions,
                                dismiss
                        )
                )
        ) : state.content().build(nextActions, dismissAction, dismiss);
        DesktopUiNode content = new DesktopUiNode.Surface(
                state.id() + ".surface",
                surfaceStyle,
                DesktopUiNode.Insets.all(12),
                true,
                body
        );
        return new DesktopUiDocument.Dialog(
                state.id(),
                state.title(),
                state.style(),
                content,
                dismissAction,
                state.dismissible(),
                state.width(),
                state.height()
        );
    }

    void showDialog(
            String id,
            String titleKey,
            String messageKey,
            DesktopUiDocument.DialogStyle style
    ) {
        showDialog(
                id,
                titleKey,
                key(messageKey),
                style
        );
    }

    void showDialog(
            String id,
            String titleKey,
            TextToken message,
            DesktopUiDocument.DialogStyle style
    ) {
        dialogState = new DialogState(
                id,
                key(titleKey),
                message,
                style,
                null,
                true,
                440,
                0
        );
    }

    void showDialog(
            String id,
            String titleKey,
            DesktopUiDocument.DialogStyle style,
            DialogContent content,
            int width,
            int height
    ) {
        showDialog(
                id,
                titleKey,
                style,
                content,
                true,
                width,
                height
        );
    }

    void showDialog(
            String id,
            String titleKey,
            DesktopUiDocument.DialogStyle style,
            DialogContent content,
            boolean dismissible,
            int width,
            int height
    ) {
        dialogState = new DialogState(
                id,
                key(titleKey),
                null,
                style,
                content,
                dismissible,
                width,
                height
        );
        rebuild();
    }

    void closeDialog() {
        dialogState = null;
    }

    void restartApplication() {
        statusController.restartApplication();
    }

    void loadConfiguration() {
        configuration.load();
    }

    String themePreference() {
        return configuration.themePreference();
    }

    DesktopUiHost.FfmpegProxy proxySettings() {
        return configuration.proxySettings();
    }

    void openDirectoryMigration() {
        directoryMigration.open();
    }

    void loadPluginStatus() {
        pluginStatus.load();
    }

    String localizedCode(String prefix, String code) {
        return pluginStatus.localizedCode(prefix, code);
    }

    long startedPluginCount() {
        return pluginStatus.startedCount();
    }

    int pluginCount() {
        return pluginStatus.count();
    }

    boolean busy() {
        return busy;
    }

    void setBusy(boolean value) {
        busy = value;
    }

    DesktopUiHost.BackendSnapshot backendSnapshot() {
        return backend;
    }

    void runBusy(Runnable action) {
        if (busy || closed) return;
        busy = true;
        rebuild();
        executeAsync(() -> {
            try {
                action.run();
            } catch (RuntimeException failure) {
                statusNotice = safeMessage(failure);
            } finally {
                busy = false;
                rebuild();
            }
        });
    }

    void executeAsync(Runnable action) {
        if (closed) return;
        try {
            worker.execute(action);
        } catch (RejectedExecutionException rejected) {
            if (!closed) throw rejected;
        }
    }

    void refreshOnboarding() {
        onboarding.refreshState();
    }

    @Override
    public synchronized void close() throws Exception {
        if (closed) return;
        closed = true;
        snapshotListeners.clear();
        worker.shutdownNow();
        AutoCloseable subscription = backendSubscription;
        backendSubscription = null;
        if (subscription != null) subscription.close();
    }

    void openWeb(String path) {
        openUri(webUri(path).toString());
    }

    void openUri(String value) {
        runBusy(() -> {
            try {
                host.openExternalUri(URI.create(value));
            } catch (Exception failure) {
                statusNotice = safeMessage(failure);
            }
        });
    }

    URI webUri(String path) {
        try {
            Map<String, String> config = host.applicationConfig().readAll(List.of(
                    "server.port",
                    "server.ssl.enabled",
                    "ssl.domain"
            ));
            int port = parseInt(config.get("server.port"), serverPort);
            boolean https = Boolean.parseBoolean(config.getOrDefault(
                    "server.ssl.enabled",
                    "false"
            ));
            String domain = config.getOrDefault("ssl.domain", "localhost").trim();
            if (domain.isBlank() || domain.contains("://") || domain.contains("/") || domain.contains(
                    "@")) {
                domain = "localhost";
            }
            return URI.create((https ? "https" : "http") + "://" + domain + ":" + port + (path.startsWith(
                    "/") ? path : "/" + path));
        } catch (Exception failure) {
            return URI.create("http://localhost:" + serverPort + (path.startsWith("/") ? path : "/" + path));
        }
    }

    List<DesktopUiPluginSnapshot> currentSources() {
        List<DesktopUiPluginSnapshot> sources = rebuildSources;
        return sources == null ? loadCurrentSources() : sources;
    }

    private List<DesktopUiPluginSnapshot> loadCurrentSources() {
        try {
            List<DesktopUiPluginSnapshot> sources = pluginSources.get();
            return sources == null ? List.of() : List.copyOf(sources);
        } catch (RuntimeException ignored) {
            return List.of();
        }
    }

    String backendMessage() {
        DesktopUiHost.MaintenanceSnapshot maintenance = host.maintenanceSnapshot();
        if (maintenance.active()) return maintenanceMessage(maintenance);
        long elapsed = elapsedSeconds(backendStateChangedAt);
        if (backend.state() == DesktopUiHost.BackendState.STARTING && elapsed >= 10L) {
            return host.message("gui.backend.state.starting.slow", elapsed);
        }
        String exclusiveToolName = tools.exclusiveToolName();
        if (!exclusiveToolName.isBlank()) {
            if (backend.state() == DesktopUiHost.BackendState.STOPPING) {
                return host.message("gui.status.state.stopping-for-tool", exclusiveToolName);
            }
            if (backend.state() == DesktopUiHost.BackendState.STOPPED) {
                long toolElapsed = elapsedSeconds(tools.exclusiveToolStartedAt());
                return host.message(
                        toolElapsed >= 30L ? "gui.status.state.stopped-by-tool.elapsed" : "gui.status.state.stopped-by-tool",
                        toolElapsed >= 30L ? new Object[]{exclusiveToolName, toolElapsed} : new Object[]{exclusiveToolName}
                );
            }
        }
        if (backend.state() == DesktopUiHost.BackendState.RUNNING && !statusController.connected()) {
            return host.message(elapsed < 3L ? "gui.status.state.connecting" : "gui.status.state.connection-failed");
        }
        return host.message(switch (backend.state()) {
            case RUNNING -> "gui.backend.state.running";
            case STARTING -> "gui.backend.state.starting";
            case STOPPING -> "gui.backend.state.stopping";
            case STOPPED -> "gui.backend.state.stopped";
            case FAILED -> "gui.backend.state.failed";
        });
    }

    DesktopUiHost.GuiValue controlCenterSnapshot() {
        return statusController.controlCenterSnapshot();
    }

    long statusLatencyMillis() {
        return statusController.latencyMillis();
    }

    TextStyle backendTextStyle() {
        if (host.maintenanceSnapshot().active()) return TextStyle.WARNING;
        return switch (backend.state()) {
            case RUNNING -> statusController.connected() ? TextStyle.SUCCESS : TextStyle.WARNING;
            case FAILED -> TextStyle.ERROR;
            default -> TextStyle.WARNING;
        };
    }

    private String maintenanceMessage(DesktopUiHost.MaintenanceSnapshot maintenance) {
        if (maintenance.index() <= 0 || nullToEmpty(maintenance.taskName()).isBlank()) {
            return host.message("gui.status.state.maintenance.preparing");
        }
        String task = host.message(
                "gui.maintenance.task." + switch (maintenance.taskName()) {
                    case "database-optimize", "guest-invite-cleanup" -> maintenance.taskName();
                    default -> "other";
                },
                "database-optimize".equals(maintenance.taskName()) || "guest-invite-cleanup".equals(
                        maintenance.taskName()) ? new Object[]{} : new Object[]{maintenance.taskName()}
        );
        String header = host.message(
                "gui.status.state.maintenance",
                task,
                maintenance.index(),
                maintenance.total()
        );
        if (maintenance.unitsTotal() > 0) {
            String progress;
            if (maintenance.unitsDone() > 0 && maintenance.unitsDone() < maintenance.unitsTotal()) {
                long elapsed = elapsedSeconds(maintenance.taskStartedAt());
                long eta = elapsed * ((long) maintenance.unitsTotal() - maintenance.unitsDone()) / maintenance.unitsDone();
                progress = host.message(
                        "gui.status.state.maintenance.progress",
                        maintenance.unitsDone(),
                        maintenance.unitsTotal(),
                        formatCompactDuration(eta)
                );
            } else {
                progress = host.message(
                        "gui.status.state.maintenance.progress.eta-pending",
                        maintenance.unitsDone(),
                        maintenance.unitsTotal()
                );
            }
            return header + "\n" + progress;
        }
        long elapsed = elapsedSeconds(maintenance.taskStartedAt());
        return elapsed >= 30L ? host.message(
                "gui.status.state.maintenance.elapsed",
                task,
                maintenance.index(),
                maintenance.total(),
                elapsed
        ) : header;
    }

    private static long elapsedSeconds(long startedAt) {
        return startedAt <= 0 ? 0 : Math.max(
                0,
                (System.currentTimeMillis() - startedAt) / 1_000L
        );
    }

    private static String formatCompactDuration(long seconds) {
        long safe = Math.max(0L, seconds);
        if (safe < 60L) return safe + "s";
        long hours = safe / 3_600L;
        long minutes = safe % 3_600L / 60L;
        long remaining = safe % 60L;
        if (hours > 0L) return minutes > 0 ? hours + "h" + minutes + "m" : hours + "h";
        return remaining > 0 ? minutes + "m" + remaining + "s" : minutes + "m";
    }

    @FunctionalInterface
    interface DialogContent {
        DesktopUiNode build(
                Map<String, Runnable> actions,
                String dismissAction,
                Runnable dismiss
        );
    }

    private record DialogState(
            String id,
            TextToken title,
            TextToken message,
            DesktopUiDocument.DialogStyle style,
            DialogContent content,
            boolean dismissible,
            int width,
            int height
    ) {
    }

}
