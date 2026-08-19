package top.sywyar.pixivdownload.gui;

import top.sywyar.pixivdownload.core.asset.BoundedImageDecoder;
import top.sywyar.pixivdownload.common.AppVersion;
import top.sywyar.pixivdownload.gui.config.RepositoryConfigValidator;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiModel;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiProvider;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigCondition;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigContribution;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigActionContribution;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigActionPayloadField;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigActionPayloadType;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigActionResultArgument;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigActionResultCondition;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigActionResultRule;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigActionResultSource;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigActionResultSummary;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigEffect;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigFieldContribution;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigFieldLayoutContribution;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigFieldType;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigGroupContribution;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigGroups;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigPresetContribution;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigSectionContribution;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigSectionLayout;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigSectionNoticeContribution;
import top.sywyar.pixivdownload.plugin.api.gui.GuiOnboardingStepContribution;
import top.sywyar.pixivdownload.plugin.api.gui.RepositoryConfigEntry;
import top.sywyar.pixivdownload.plugin.api.gui.TrustedKeyConfigEntry;
import top.sywyar.pixivdownload.plugin.api.gui.document.DesktopUiDocument;
import top.sywyar.pixivdownload.plugin.api.gui.document.DesktopUiNode;
import top.sywyar.pixivdownload.plugin.api.gui.document.DesktopUiNode.Alignment;
import top.sywyar.pixivdownload.plugin.api.gui.document.DesktopUiNode.ButtonStyle;
import top.sywyar.pixivdownload.plugin.api.gui.document.DesktopUiNode.ChoiceStyle;
import top.sywyar.pixivdownload.plugin.api.gui.document.DesktopUiNode.ContainerLayout;
import top.sywyar.pixivdownload.plugin.api.gui.document.DesktopUiNode.InputKind;
import top.sywyar.pixivdownload.plugin.api.gui.document.DesktopUiNode.NumberStyle;
import top.sywyar.pixivdownload.plugin.api.gui.document.DesktopUiNode.SelectionMode;
import top.sywyar.pixivdownload.plugin.api.gui.document.DesktopUiNode.TextStyle;
import top.sywyar.pixivdownload.plugin.api.gui.document.DesktopUiNode.TextToken;
import top.sywyar.pixivdownload.plugin.api.gui.document.DesktopUiNode.ToggleStyle;
import top.sywyar.pixivdownload.plugin.api.web.AccessPolicy;
import top.sywyar.pixivdownload.plugin.api.web.HttpMethod;
import top.sywyar.pixivdownload.plugin.api.web.NavigationContribution;
import top.sywyar.pixivdownload.plugin.api.web.NavigationPlacements;
import top.sywyar.pixivdownload.plugin.api.web.WebRouteContribution;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** App-owned complete desktop document and event dispatcher. */
final class AppDesktopUiModel implements DesktopUiModel, AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(AppDesktopUiModel.class);
    private static final String APP_OWNER = "app";
    static final int MAX_ACTION_TEXT_CODE_POINTS = 512;
    private static final int MAX_ACTION_SUMMARY_CODE_POINTS = 2_048;
    private static final int MAX_ACTION_SUMMARY_ITEMS = 20;
    private static final Pattern SAFE_JSON_SEGMENT = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final Set<String> SENSITIVE_RESULT_MARKERS = Set.of(
            "password", "passwd", "passphrase", "secret", "token", "cookie", "session", "sessid",
            "bearer", "authorization", "credential", "privatekey", "apikey", "accesskey", "authkey",
            "signingkey", "encryptionkey", "decryptionkey", "clientsecret", "rawbody", "error",
            "exception", "stacktrace", "traceback", "html");
    private static final List<GuiConfigGroupContribution> CORE_GROUPS = List.of(
            group("interface", "gui.config.category.interface", 0),
            group(GuiConfigGroups.SERVER, "gui.config.group.server", 100),
            group(GuiConfigGroups.DOWNLOAD, "gui.config.group.download", 200),
            group(GuiConfigGroups.PLUGINS, "gui.config.group.plugins", 300),
            group(GuiConfigGroups.PROXY, "gui.config.group.proxy", 400),
            group(GuiConfigGroups.GUEST_INVITE, "gui.config.group.guest-invite", 600),
            group(GuiConfigGroups.SECURITY, "gui.config.group.security", 700),
            group(GuiConfigGroups.MAINTENANCE, "gui.config.group.maintenance", 800),
            group(GuiConfigGroups.HTTPS, "gui.config.group.https", 900),
            group(GuiConfigGroups.UPDATE, "gui.config.group.update", 1000),
            group(GuiConfigGroups.SCHEDULE, "gui.config.group.schedule", 1100)
    );

    private final int serverPort;
    private final String rootFolder;
    private final Path configPath;
    private final DesktopUiHost host;
    private final Supplier<List<DesktopUiPluginSource>> pluginSources;
    private final Optional<DesktopUiNode.ImageData> applicationIcon;
    private final String licenseText;
    private final ExecutorService worker = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "desktop-ui-model");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicLong revision = new AtomicLong();
    private final Map<FieldKey, String> values = new ConcurrentHashMap<>();
    private final Map<FieldKey, String> savedValues = new ConcurrentHashMap<>();
    private volatile Set<FieldKey> storedCredentialFields = Set.of();
    private final Map<String, String> formValues = new ConcurrentHashMap<>();
    private volatile List<ConfigField> configFields = List.of();
    private volatile List<ConfigSection> configSections = List.of();
    private volatile List<RepositoryConfigEntry> pluginRepositories = List.of();
    private volatile List<RepositoryConfigEntry> savedPluginRepositories = List.of();
    private volatile boolean pluginRepositoriesLoaded;
    private volatile boolean debugUnlocked;
    private volatile String pluginRepositoriesLoadFailure = "";
    private volatile String selectedRepositoryRow;
    private volatile int editingRepositoryIndex = -1;
    private volatile String repositoryUnknownProxyPolicy = "";
    private volatile List<TrustedKeyConfigEntry> repositoryTrustedKeys = List.of();
    private volatile String selectedTrustedKeyRow;
    private volatile int editingTrustedKeyIndex = -1;
    private volatile String repositoryFormErrorKey = "";
    private volatile Map<String, ConfigField> fieldBindings = Map.of();
    private volatile Map<String, Consumer<List<String>>> selectionBindings = Map.of();
    private volatile Map<String, Runnable> actions = Map.of();
    private volatile Map<String, EventEndpoint> eventEndpoints = Map.of();
    private volatile DesktopUiDocument document;
    private volatile DesktopUiHost.BackendSnapshot backend;
    private volatile String statusNotice = "";
    private volatile String configNotice = "";
    private volatile TextToken configNoticeToken;
    private volatile String pluginsNotice = "";
    private volatile TextToken securityNotice = key("gui.security.status.idle");
    private volatile String welcomeNotice = "";
    private volatile String classifierNotice = "";
    private volatile String folderNotice = "";
    private volatile String backfillNotice = "";
    private volatile String migrationNotice = "";
    private volatile String statusPort = "--";
    private volatile String statusMode = "--";
    private volatile String statusStartTime = "--";
    private volatile String statusProtocol = "--";
    private volatile String connectivityDetails = "";
    private volatile boolean statusConnected;
    private volatile boolean connectivityChecking;
    private volatile long lastConnectivityCheckAt;
    private volatile long backendStateChangedAt = System.currentTimeMillis();
    private volatile boolean ffmpegInstalling;
    private volatile double ffmpegProgress;
    private volatile PendingInstall pendingOfficialUpdate;
    private volatile PendingInstall pendingNightlyUpdate;
    private volatile boolean updateInstalling;
    private volatile boolean downloadingNightly;
    private volatile long updateReceivedBytes;
    private volatile long updateTotalBytes;
    private volatile String pathPrefixAppRoot = "";
    private volatile List<PathPrefixRow> pathPrefixes = List.of();
    private volatile List<PluginStatusRow> pluginStatuses = List.of();
    private volatile List<DesktopUiNode.TableRow> folderRows = List.of();
    private volatile Map<String, DesktopUiHost.FolderArtwork> folderArtworks = Map.of();
    private volatile List<Path> classifierFolders = List.of();
    private volatile Map<String, Path> classifierPaths = Map.of();
    private volatile List<Path> classifierImages = List.of();
    private volatile DesktopUiHost.ImageClassifierServer classifierServer =
            new DesktopUiHost.ImageClassifierServer(false, "");
    private volatile DesktopUiHost.ImageClassifierArtwork classifierArtwork;
    private volatile int classifierGroupIndex;
    private final Map<Path, DesktopUiNode.ImageData> classifierImageCache = new ConcurrentHashMap<>();
    private volatile String selectedFolderRow;
    private volatile boolean recoveryMode;
    private volatile boolean busy;
    private volatile boolean autoStartSupported;
    private volatile boolean autoStartEnabled;
    private volatile long securityFormRevision;
    private volatile long welcomeFormRevision;
    private volatile int welcomeStep;
    private volatile boolean onboardingBatchVisited;
    private volatile Set<String> completedOnboardingSteps = Set.of();
    private volatile boolean weakPasswordConfirmationPending;
    private volatile DialogState dialogState;
    private volatile ToolDialog toolDialog;
    private volatile boolean folderCheckerRestartBackend;
    private volatile String exclusiveToolName = "";
    private volatile long exclusiveToolStartedAt;
    private volatile AutoCloseable backendSubscription;
    private volatile List<DesktopUiPluginSource> rebuildSources;
    private volatile List<DesktopUiPluginSource.Fingerprint> documentSourceFingerprints = List.of();
    private volatile Locale documentLocale;
    private volatile boolean closed;

    AppDesktopUiModel(int serverPort, String rootFolder, Path configPath, DesktopUiHost host,
                      Supplier<List<DesktopUiPluginSource>> pluginSources) {
        this.serverPort = serverPort;
        this.rootFolder = Objects.requireNonNull(rootFolder, "rootFolder");
        this.configPath = Objects.requireNonNull(configPath, "configPath");
        this.host = Objects.requireNonNull(host, "host");
        this.pluginSources = Objects.requireNonNull(pluginSources, "pluginSources");
        this.autoStartSupported = host.autoStartSupported();
        this.autoStartEnabled = autoStartSupported && host.autoStartEnabled();
        this.applicationIcon = loadApplicationIcon();
        this.licenseText = loadLicenseText();
        this.backend = host.backendSnapshot();
        this.welcomeStep = initialWelcomeStep();
        loadConfiguration();
        loadToolDefaults();
        rebuild();
        try {
            backendSubscription = host.subscribeBackend(snapshot -> {
                DesktopUiHost.BackendState previousState = backend.state();
                if (snapshot.state() != previousState) backendStateChangedAt = System.currentTimeMillis();
                backend = snapshot;
                if (snapshot.state() != DesktopUiHost.BackendState.RUNNING) statusConnected = false;
                if (snapshot.state() == DesktopUiHost.BackendState.RUNNING
                        && previousState != DesktopUiHost.BackendState.RUNNING) {
                    executeAsync(() -> {
                        refreshStatusSnapshot();
                        refreshOnboardingState();
                        loadPluginStatus();
                        rebuild();
                    });
                } else {
                    rebuild();
                }
            });
        } catch (RuntimeException ignored) {
            backendSubscription = null;
        }
        refreshStatus();
        startStatusPolling();
        scheduleInitialUpdateLookup();
        scheduleSymbolicOrphanCheck();
    }

    @Override public DesktopUiDocument document() { return document; }
    @Override public long revision() { return revision.get(); }

    @Override
    public synchronized void dispatch(DesktopUiNode.Event event) {
        Objects.requireNonNull(event, "event");
        if (closed) return;
        long currentRevision = revision.get();
        if (event.documentRevision() != currentRevision) {
            LOG.warn("Ignored stale desktop UI event (nodeId={}, type={}, eventRevision={}, currentRevision={})",
                    event.nodeId(), event.type(), event.documentRevision(), currentRevision);
            return;
        }
        EventEndpoint endpoint = eventEndpoints.get(event.nodeId());
        String rejection = validateEvent(endpoint, event);
        if (rejection != null) {
            LOG.warn("Ignored invalid desktop UI event (nodeId={}, type={}, reason={})",
                    event.nodeId(), event.type(), rejection);
            return;
        }
        String targetId = endpoint.targetId();
        if (event.type() == DesktopUiNode.EventType.ACTIVATE) {
            Runnable action = actions.get(targetId);
            if (action != null) action.run();
            return;
        }
        ConfigField field = fieldBindings.get(targetId);
        String value = event.value().values().stream().findFirst().orElse("");
        if (field != null) {
            values.put(field.key(), value);
            rebuild();
            return;
        }
        Consumer<List<String>> selection = selectionBindings.get(targetId);
        if (selection != null) {
            acceptSelection(selection, event.value());
            rebuild();
            return;
        }
        formValues.put(targetId, value);
        switch (targetId) {
            case "interface.language" -> applyLocale(value);
            case "welcome.password" -> weakPasswordConfirmationPending = false;
            case "folder.selected" -> {
                selectedFolderRow = value.isBlank() ? null : value;
            }
            case "config.market.repositories.selected" -> {
                selectedRepositoryRow = value.isBlank() ? null : value;
            }
            case "config.market.repository.proxy", "config.market.repository.trusted.selected" -> {
                if (targetId.endsWith("trusted.selected")) {
                    selectedTrustedKeyRow = value.isBlank() ? null : value;
                }
            }
            default -> { }
        }
        rebuild();
    }

    static void acceptSelection(Consumer<List<String>> selection, DesktopUiNode.Value value) {
        selection.accept(value.values());
    }

    private synchronized void rebuild() {
        if (closed) return;
        List<DesktopUiPluginSource> previousSources = rebuildSources;
        rebuildSources = loadCurrentSources();
        List<DesktopUiPluginSource.Fingerprint> sourceFingerprints = rebuildSources.stream()
                .map(DesktopUiPluginSource::fingerprint).toList();
        boolean sourcesChanged = !sourceFingerprints.equals(documentSourceFingerprints);
        Locale currentLocale = Locale.getDefault();
        boolean localeChanged = !currentLocale.equals(documentLocale);
        try {
            Map<String, ConfigField> nextBindings = new LinkedHashMap<>();
            Map<String, Consumer<List<String>>> nextSelections = new LinkedHashMap<>();
            Map<String, Runnable> nextActions = new LinkedHashMap<>();
            List<DesktopUiDocument.Page> pages = new ArrayList<>();
            if (!host.onboardingState(rootFolder).complete()) pages.add(page("welcome", welcomePage(nextActions),
                    new DesktopUiNode.Insets(24, 32, 24, 32)));
            pages.add(page("status", statusPage(nextActions)));
            pages.add(page("config", configPage(nextBindings, nextSelections, nextActions),
                    DesktopUiNode.Insets.NONE));
            pages.add(page("plugins", pluginsPage(nextActions)));
            pages.add(page("tools", toolsPage(nextActions)));
            pages.add(page("security", securityPage(nextActions)));
            pages.add(page("about", aboutPage(nextActions)));
            fieldBindings = Map.copyOf(nextBindings);
            selectionBindings = Map.copyOf(nextSelections);
            List<DesktopUiDocument.Dialog> dialogs = new ArrayList<>();
            if (dialogState != null) dialogs.add(dialog(dialogState, nextActions));
            if (toolDialog != null) dialogs.add(toolDialog(toolDialog, nextActions));
            DesktopUiDocument.Tray tray = tray(nextActions);
            nextActions.put("debug.unlock", this::unlockDebugConfiguration);
            actions = Map.copyOf(nextActions);
            DesktopUiDocument nextDocument = new DesktopUiDocument(pages, dialogs,
                    List.of(new DesktopUiDocument.KeyboardShortcut(
                    "debug.unlock.shortcut", List.of(
                    keyStroke("ArrowUp"), keyStroke("ArrowUp"), keyStroke("ArrowDown"), keyStroke("ArrowDown"),
                    keyStroke("ArrowLeft"), keyStroke("ArrowRight"), keyStroke("ArrowLeft"), keyStroke("ArrowRight"),
                    keyStroke("KeyB"), keyStroke("KeyA"), keyStroke("KeyB"), keyStroke("KeyA")),
                    "debug.unlock", false)), Optional.of(tray));
            eventEndpoints = indexEventEndpoints(nextDocument);
            if (sourcesChanged || localeChanged || !nextDocument.equals(document)) {
                document = nextDocument;
                documentSourceFingerprints = sourceFingerprints;
                documentLocale = currentLocale;
                revision.incrementAndGet();
            }
        } finally {
            rebuildSources = previousSources;
        }
    }

    static Map<String, EventEndpoint> indexEventEndpoints(DesktopUiDocument document) {
        Map<String, EventEndpoint> endpoints = new LinkedHashMap<>();
        document.pages().forEach(page -> indexNode(page.content(), endpoints));
        for (DesktopUiDocument.Dialog dialog : document.dialogs()) {
            indexNode(dialog.content(), endpoints);
            if (dialog.dismissible()) putEventEndpoint(endpoints, dialog.id(), new EventEndpoint(
                    dialog.dismissActionId(), DesktopUiNode.EventType.ACTIVATE, true, null));
        }
        document.shortcuts().forEach(shortcut -> putEventEndpoint(endpoints, shortcut.id(), new EventEndpoint(
                shortcut.actionId(), DesktopUiNode.EventType.ACTIVATE, true, null)));
        document.tray().ifPresent(tray -> tray.items().stream()
                .filter(item -> item.role() == DesktopUiDocument.TrayItemRole.DISPATCH)
                .forEach(item -> putEventEndpoint(endpoints, item.id(), new EventEndpoint(
                        item.actionId(), DesktopUiNode.EventType.ACTIVATE, true, null))));
        return Map.copyOf(endpoints);
    }

    private static void indexNode(DesktopUiNode node, Map<String, EventEndpoint> endpoints) {
        EventEndpoint endpoint = null;
        if (node instanceof DesktopUiNode.TextInput value) endpoint = new EventEndpoint(
                value.bindingId(), DesktopUiNode.EventType.CHANGE, value.enabled(), value);
        else if (node instanceof DesktopUiNode.Toggle value) endpoint = new EventEndpoint(
                value.bindingId(), DesktopUiNode.EventType.CHANGE, value.enabled(), value);
        else if (node instanceof DesktopUiNode.Choice value) endpoint = new EventEndpoint(
                value.bindingId(), DesktopUiNode.EventType.SELECTION, value.enabled(), value);
        else if (node instanceof DesktopUiNode.NumberInput value) endpoint = new EventEndpoint(
                value.bindingId(), DesktopUiNode.EventType.CHANGE, value.enabled(), value);
        else if (node instanceof DesktopUiNode.Table value) endpoint = new EventEndpoint(
                value.bindingId(), DesktopUiNode.EventType.SELECTION, value.enabled(), value);
        else if (node instanceof DesktopUiNode.Tree value) endpoint = new EventEndpoint(
                value.bindingId(), DesktopUiNode.EventType.SELECTION, value.enabled(), value);
        else if (node instanceof DesktopUiNode.Button value) endpoint = new EventEndpoint(
                value.actionId(), DesktopUiNode.EventType.ACTIVATE, value.enabled(), value);
        else if (node instanceof DesktopUiNode.Link value) endpoint = new EventEndpoint(
                value.actionId(), DesktopUiNode.EventType.ACTIVATE, value.enabled(), value);
        if (endpoint != null) putEventEndpoint(endpoints, node.id(), endpoint);
        node.childNodes().forEach(child -> indexNode(child, endpoints));
    }

    private static void putEventEndpoint(Map<String, EventEndpoint> endpoints, String id,
                                         EventEndpoint endpoint) {
        if (endpoints.putIfAbsent(id, endpoint) != null) {
            throw new IllegalArgumentException("duplicate desktop UI event endpoint id: " + id);
        }
    }

    static String validateEvent(EventEndpoint endpoint, DesktopUiNode.Event event) {
        if (endpoint == null) return "unknown node";
        if (!endpoint.enabled()) return "node is disabled";
        if (endpoint.eventType() != event.type()) return "event type does not match node";
        DesktopUiNode node = endpoint.node();
        if (node == null || node instanceof DesktopUiNode.Button || node instanceof DesktopUiNode.Link) {
            return event.value().kind() == DesktopUiNode.ValueKind.NONE ? null : "action carries a value";
        }
        if (node instanceof DesktopUiNode.TextInput input) {
            if (event.value().kind() != DesktopUiNode.ValueKind.TEXT || event.value().values().size() != 1) {
                return "text input requires one text value";
            }
            return null;
        }
        if (node instanceof DesktopUiNode.Toggle) {
            return event.value().kind() == DesktopUiNode.ValueKind.BOOLEAN
                    && event.value().values().size() == 1 ? null : "toggle requires one boolean value";
        }
        if (node instanceof DesktopUiNode.NumberInput input) {
            if (event.value().kind() != DesktopUiNode.ValueKind.NUMBER || event.value().values().size() != 1) {
                return "number input requires one numeric value";
            }
            try {
                int value = new BigDecimal(first(event.value().values())).intValueExact();
                if (value < input.minimum() || value > input.maximum()) return "number is outside bounds";
                return Math.floorMod(value - input.minimum(), input.step()) == 0
                        ? null : "number does not align with step";
            } catch (ArithmeticException invalid) {
                return "number must be an integer";
            }
        }
        if (node instanceof DesktopUiNode.Choice choice) {
            String kindError = validateSelectionKind(choice.selectionMode(), event.value());
            if (kindError != null) return kindError;
            Map<String, Boolean> options = choice.options().stream().collect(java.util.stream.Collectors.toMap(
                    DesktopUiNode.Option::id, DesktopUiNode.Option::enabled));
            for (String id : event.value().values()) {
                if (!options.containsKey(id)) return "unknown choice option";
                if (!options.get(id)) return "choice option is disabled";
            }
            return null;
        }
        if (node instanceof DesktopUiNode.Table table) {
            String kindError = validateSelectionKind(table.selectionMode(), event.value());
            if (kindError != null) return kindError;
            Set<String> ids = table.rows().stream().map(DesktopUiNode.TableRow::id)
                    .collect(java.util.stream.Collectors.toSet());
            return ids.containsAll(event.value().values()) ? null : "unknown table row";
        }
        if (node instanceof DesktopUiNode.Tree tree) {
            String kindError = validateSelectionKind(tree.selectionMode(), event.value());
            if (kindError != null) return kindError;
            Set<String> ids = new LinkedHashSet<>();
            collectTreeItemIds(tree.items(), ids);
            return ids.containsAll(event.value().values()) ? null : "unknown tree item";
        }
        return "node does not emit events";
    }

    private static String validateSelectionKind(SelectionMode mode, DesktopUiNode.Value value) {
        DesktopUiNode.ValueKind expected = mode == SelectionMode.SINGLE
                ? DesktopUiNode.ValueKind.SELECTION : DesktopUiNode.ValueKind.MULTI_SELECTION;
        return value.kind() == expected ? null : "selection kind does not match selection mode";
    }

    private static void collectTreeItemIds(List<DesktopUiNode.TreeItem> items, Set<String> ids) {
        for (DesktopUiNode.TreeItem item : items) {
            ids.add(item.id());
            collectTreeItemIds(item.children(), ids);
        }
    }

    private static String first(List<String> values) {
        return values.isEmpty() ? "" : values.get(0);
    }

    record EventEndpoint(String targetId, DesktopUiNode.EventType eventType,
                         boolean enabled, DesktopUiNode node) { }

    private static DesktopUiDocument.KeyStroke keyStroke(String key) {
        return DesktopUiDocument.KeyStroke.key(key);
    }

    private void unlockDebugConfiguration() {
        if (debugUnlocked) return;
        debugUnlocked = true;
        configNotice = "";
        configNoticeToken = key("gui.config.notice.debug-unlocked");
        rebuild();
    }

    private DesktopUiDocument.Page page(String id, DesktopUiNode content) {
        return page(id, content, new DesktopUiNode.Insets(16, 24, 16, 24));
    }

    private DesktopUiDocument.Page page(String id, DesktopUiNode content, DesktopUiNode.Insets padding) {
        return new DesktopUiDocument.Page(id, key("desktop.ui.page." + id),
                new DesktopUiNode.Surface(id + ".page", DesktopUiNode.SurfaceStyle.PLAIN,
                        padding, true, true, content));
    }

    private DesktopUiDocument.Dialog dialog(DialogState state, Map<String, Runnable> nextActions) {
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
        DesktopUiNode body = state.content() == null
                ? column(state.id() + ".content",
                        new DesktopUiNode.Text(state.id() + ".message", state.message(), TextStyle.BODY,
                                true, true),
                        row(state.id() + ".actions",
                                button(state.id() + ".close", dismissAction, "desktop.ui.action.close",
                                        true, nextActions, dismiss)))
                : state.content().build(nextActions, dismissAction, dismiss);
        DesktopUiNode content = new DesktopUiNode.Surface(state.id() + ".surface", surfaceStyle,
                DesktopUiNode.Insets.all(12), true, body);
        return new DesktopUiDocument.Dialog(state.id(), state.title(), state.style(), content,
                dismissAction, state.dismissible(), state.width(), state.height());
    }

    private void showDialog(String id, String titleKey, String messageKey,
                            DesktopUiDocument.DialogStyle style) {
        showDialog(id, titleKey, key(messageKey), style);
    }

    private void showDialog(String id, String titleKey, TextToken message,
                            DesktopUiDocument.DialogStyle style) {
        dialogState = new DialogState(id, key(titleKey), message, style, null, true, 440, 0);
    }

    private void showDialog(String id, String titleKey, DesktopUiDocument.DialogStyle style,
                            DialogContent content, int width, int height) {
        showDialog(id, titleKey, style, content, true, width, height);
    }

    private void showDialog(String id, String titleKey, DesktopUiDocument.DialogStyle style,
                            DialogContent content, boolean dismissible, int width, int height) {
        dialogState = new DialogState(id, key(titleKey), null, style, content, dismissible, width, height);
        rebuild();
    }

    private DesktopUiDocument.Dialog toolDialog(ToolDialog value, Map<String, Runnable> nextActions) {
        String base = value == ToolDialog.IMAGE_CLASSIFIER ? "classifier.dialog" : "folder.dialog";
        String dismissAction = base + ".dismiss";
        Runnable dismiss = () -> {
            toolDialog = null;
            if (value == ToolDialog.FOLDER_CHECKER) closeFolderCheckerDialog();
            else rebuild();
        };
        nextActions.put(dismissAction, dismiss);
        DesktopUiNode content = value == ToolDialog.IMAGE_CLASSIFIER
                ? classifierDialogContent(nextActions) : folderDialogContent(nextActions);
        String title = value == ToolDialog.IMAGE_CLASSIFIER
                ? "gui.tools.card.image-classifier.title" : "gui.tools.card.folder-checker.title";
        return new DesktopUiDocument.Dialog(base, key(title), DesktopUiDocument.DialogStyle.INFO,
                new DesktopUiNode.Dock(base + ".layout", 12,
                        null, scroll(base + ".scroll", content),
                        row(base + ".actions",
                                button(base + ".close", dismissAction, "desktop.ui.action.close",
                                        !busy, nextActions, dismiss)), null, null),
                dismissAction, !busy, value == ToolDialog.IMAGE_CLASSIFIER ? 1180 : 860,
                value == ToolDialog.IMAGE_CLASSIFIER ? 760 : 640);
    }

    private DesktopUiNode classifierDialogContent(Map<String, Runnable> nextActions) {
        DesktopUiNode top = row("classifier.top",
                input("classifier.default-folder.input", "classifier.default-folder",
                        "gui.image-classifier.label.folder-path", null, InputKind.DIRECTORY,
                        form("classifier.default-folder", ""), !busy),
                button("classifier.open", "classifier.open", "gui.image-classifier.button.open", !busy,
                        nextActions, this::scanClassifierFolders),
                button("classifier.settings", "classifier.settings",
                        "gui.image-classifier.button.settings", !busy, nextActions,
                        this::showClassifierSettings),
                raw("classifier.server", classifierServer.available()
                        ? host.message("gui.image-classifier.server.ok")
                        : host.message("gui.image-classifier.server.connect-failed"),
                        classifierServer.available() ? TextStyle.SUCCESS : TextStyle.WARNING));
        DesktopUiNode center = new DesktopUiNode.Split("classifier.center", DesktopUiNode.Axis.HORIZONTAL,
                0.78d, classifierPreview(nextActions), classifierCategories(nextActions));
        return new DesktopUiNode.Dock("classifier.dialog.content", 10, top, center,
                classifierStatus(), null, null);
    }

    private DesktopUiNode classifierPreview(Map<String, Runnable> nextActions) {
        List<DesktopUiNode> thumbnails = new ArrayList<>();
        int start = classifierGroupIndex * 10;
        int end = Math.min(start + 10, classifierImages.size());
        for (int index = start; index < end; index++) {
            int imageIndex = index;
            Path image = classifierImages.get(index);
            String base = "classifier.image." + index;
            List<DesktopUiNode> content = new ArrayList<>();
            materializeImage(image).ifPresent(data -> content.add(new DesktopUiNode.Image(base + ".preview",
                    data, TextToken.raw(image.getFileName().toString()), 160, 150,
                    DesktopUiNode.ScaleMode.FIT)));
            content.add(raw(base + ".name", image.getFileName().toString(), TextStyle.CAPTION));
            content.add(rawButton(base + ".open", base + ".open", image.getFileName().toString(),
                    !busy, nextActions, () -> showClassifierImage(imageIndex)));
            thumbnails.add(new DesktopUiNode.Surface(base, DesktopUiNode.SurfaceStyle.CARD,
                    DesktopUiNode.Insets.all(6), true, column(base + ".content", content)));
        }
        if (thumbnails.isEmpty()) {
            thumbnails.add(text("classifier.images.empty", "gui.image-classifier.thumbnail.empty",
                    TextStyle.CAPTION));
        }
        int groups = classifierImages.isEmpty() ? 0 : (classifierImages.size() + 9) / 10;
        return column("classifier.preview",
                new DesktopUiNode.Container("classifier.thumbnails", ContainerLayout.GRID, 5, 8,
                        Alignment.STRETCH, thumbnails),
                row("classifier.group.navigation",
                        button("classifier.group.previous", "classifier.group.previous",
                                "gui.image-classifier.button.prev-group", !busy && classifierGroupIndex > 0,
                                nextActions, () -> moveClassifierGroup(-1)),
                        raw("classifier.group.position", groups == 0 ? "--"
                                : (classifierGroupIndex + 1) + " / " + groups, TextStyle.CAPTION),
                        button("classifier.group.next", "classifier.group.next",
                                "gui.image-classifier.button.next-group",
                                !busy && classifierGroupIndex + 1 < groups,
                                nextActions, () -> moveClassifierGroup(1))));
    }

    private DesktopUiNode classifierCategories(Map<String, Runnable> nextActions) {
        List<DesktopUiHost.ImageClassifierTarget> targets = parseTargets(form("classifier.targets", ""));
        List<DesktopUiNode> categories = new ArrayList<>();
        for (int index = 0; index < targets.size(); index++) {
            int targetIndex = index;
            DesktopUiHost.ImageClassifierTarget target = targets.get(index);
            String label = index + "  " + (target.remark().isBlank() ? target.folder() : target.remark());
            categories.add(new DesktopUiNode.Surface("classifier.category." + index,
                    DesktopUiNode.SurfaceStyle.MUTED, DesktopUiNode.Insets.all(6), true,
                    column("classifier.category." + index + ".content",
                            rawButton("classifier.category." + index + ".select",
                                    "classifier.category." + index + ".select", label, !busy,
                                    nextActions, () -> {
                                        formValues.put("classifier.target", "target." + targetIndex);
                                        rebuild();
                                    }),
                            raw("classifier.category." + index + ".path", target.folder(), TextStyle.CODE))));
        }
        if (categories.isEmpty()) categories.add(text("classifier.categories.empty",
                "gui.image-classifier.validation.target-folders-not-configured", TextStyle.WARNING));
        String source = form("classifier.source", "");
        String target = form("classifier.target", "");
        List<DesktopUiNode> actions = new ArrayList<>();
        actions.add(button("classifier.classify", "classifier.classify",
                "gui.image-classifier.button.classify-folder",
                !busy && !source.isBlank() && !target.isBlank() && !classifierImages.isEmpty(),
                nextActions, this::classifyFolder));
        if (boolForm("classifier.show-skip", true)) {
            actions.add(button("classifier.skip", "classifier.skip", "gui.image-classifier.button.skip-folder",
                    !busy && !source.isBlank(), nextActions, () -> moveClassifierFolder(1)));
        }
        actions.add(button("classifier.previous", "classifier.previous",
                "gui.image-classifier.button.prev-folder", !busy && classifierFolderIndex() > 0,
                nextActions, () -> moveClassifierFolder(-1)));
        actions.add(button("classifier.refresh", "classifier.refresh",
                "gui.image-classifier.button.refresh-thumbnails", !busy && !source.isBlank(),
                nextActions, this::refreshClassifierFolder));
        return column("classifier.sidebar",
                group("classifier.categories", "gui.image-classifier.section.categories",
                        scroll("classifier.categories.scroll", column("classifier.categories.rows", categories))),
                group("classifier.actions", "gui.image-classifier.section.classify",
                        column("classifier.actions.content", actions)));
    }

    private DesktopUiNode classifierStatus() {
        String source = form("classifier.source", "");
        Path folder = classifierPath(source);
        if (folder == null) return status("classifier.notice", classifierNotice.isBlank()
                ? host.message("gui.image-classifier.status.select-parent-folder") : classifierNotice);
        int groups = classifierImages.isEmpty() ? 0 : (classifierImages.size() + 9) / 10;
        String value = host.message("gui.image-classifier.status.current-folder", folder.getFileName(),
                classifierImages.size(), groups == 0 ? 0 : classifierGroupIndex + 1, groups);
        if (classifierArtwork != null) {
            value += "   " + host.message(classifierArtwork.xRestrict() == null
                    ? "gui.image-classifier.status.tag.unknown"
                    : classifierArtwork.xRestrict() == 2 ? "gui.image-classifier.status.tag.r18g"
                    : classifierArtwork.xRestrict() == 1 ? "gui.image-classifier.status.tag.r18"
                    : "gui.image-classifier.status.tag.sfw");
        }
        return status("classifier.notice", classifierNotice.isBlank() ? value : classifierNotice);
    }

    private DesktopUiNode folderDialogContent(Map<String, Runnable> nextActions) {
        DesktopUiHost.FolderArtwork selected = selectedFolderRow == null
                ? null : folderArtworks.get(selectedFolderRow);
        return column("folder.dialog.content",
                text("tools.folder.help", "desktop.ui.tools.folder.description", TextStyle.CAPTION),
                input("tools.folder.db", "tools.folder.db", "gui.tools.form.database-path", null,
                        InputKind.FILE, form("tools.folder.db", host.resolveDatabasePath(rootFolder).toString()), !busy),
                button("tools.folder.check", "tools.folder.check", "gui.folder-checker.button.check-folders",
                        !busy, nextActions, this::checkFolders),
                status("tools.folder.notice", folderNotice.isBlank()
                        ? host.message("gui.tools.folder-checker.status.preparing") : folderNotice),
                folderTable(),
                row("tools.folder.selected",
                        raw("tools.folder.selected-id", host.message("gui.folder-checker.label.selected-id",
                                selected == null ? host.message("gui.value.none") : selected.artworkId()),
                                TextStyle.BODY),
                        button("tools.folder.copy", "tools.folder.copy", "gui.folder-checker.button.copy-id",
                                !busy && selected != null, nextActions, this::copySelectedFolderId)),
                input("tools.folder.new-path", "tools.folder.new-path", "gui.folder-checker.label.new-path", null,
                        InputKind.DIRECTORY, form("tools.folder.new-path", ""), !busy),
                button("tools.folder.update", "tools.folder.update", "gui.folder-checker.button.update-db",
                        !busy && selected != null, nextActions, this::requestFolderUpdate));
    }

    private void openToolDialog(ToolDialog value) {
        if (busy) return;
        toolDialog = value;
        rebuild();
        if (value == ToolDialog.IMAGE_CLASSIFIER
                && !form("classifier.default-folder", "").isBlank()) scanClassifierFolders();
    }

    private void openFolderCheckerDialog() {
        if (busy || toolDialog != null) return;
        if (backend.state() != DesktopUiHost.BackendState.RUNNING
                && backend.state() != DesktopUiHost.BackendState.STOPPED) {
            showDialog("folder.backend-busy", "gui.dialog.please-wait.title",
                    "gui.message.backend-busy", DesktopUiDocument.DialogStyle.WARNING);
            rebuild();
            return;
        }
        folderCheckerRestartBackend = backend.state() == DesktopUiHost.BackendState.RUNNING;
        exclusiveToolName = host.message("gui.tools.card.folder-checker.title");
        exclusiveToolStartedAt = System.currentTimeMillis();
        if (!folderCheckerRestartBackend) {
            toolDialog = ToolDialog.FOLDER_CHECKER;
            rebuild();
            return;
        }
        busy = true;
        rebuild();
        if (!host.stopBackend(() -> {
            busy = false;
            toolDialog = ToolDialog.FOLDER_CHECKER;
            folderNotice = host.message("gui.tools.folder-checker.status.opened");
            rebuild();
        })) {
            busy = false;
            exclusiveToolName = "";
            exclusiveToolStartedAt = 0L;
            folderCheckerRestartBackend = false;
            showDialog("folder.backend-busy", "gui.dialog.please-wait.title",
                    "gui.message.backend-busy", DesktopUiDocument.DialogStyle.WARNING);
            rebuild();
        }
    }

    private void closeFolderCheckerDialog() {
        boolean restart = folderCheckerRestartBackend;
        folderCheckerRestartBackend = false;
        exclusiveToolName = "";
        exclusiveToolStartedAt = 0L;
        if (!restart) {
            rebuild();
            return;
        }
        busy = true;
        folderNotice = host.message("gui.tools.folder-checker.status.restoring");
        rebuild();
        if (!host.startBackend(() -> {
            busy = false;
            folderNotice = host.message("gui.tools.folder-checker.status.completed");
            rebuild();
        })) {
            busy = false;
            folderNotice = host.message("gui.tools.folder-checker.status.closed");
            rebuild();
        }
    }

    private void openToolLog(String name) {
        runBusy(() -> {
            try (DesktopUiHost.ToolLogSession log = host.openToolLog(name)) {
                log.openLatestInBrowser();
            } catch (Exception failure) {
                LOG.warn("Unable to open desktop tool log {}", name, failure);
                showDialog("tools.log.error", "gui.dialog.error.title", "desktop.ui.tools.log-open-failed",
                        DesktopUiDocument.DialogStyle.ERROR);
            }
        });
    }

    private DesktopUiNode welcomePage(Map<String, Runnable> nextActions) {
        int step = normalizeWelcomeStep(welcomeStep);
        if (step != welcomeStep) welcomeStep = step;
        return switch (step) {
            case 1 -> welcomeServiceStep(nextActions);
            case 2 -> welcomeConfigStep(nextActions);
            case 3 -> welcomeProxyStep(nextActions);
            case 4 -> welcomeStartStep(nextActions);
            case 5 -> welcomePluginStep(nextActions);
            case 6 -> welcomeAdvancedStep(nextActions);
            default -> welcomeDoneStep(nextActions);
        };
    }

    private DesktopUiNode welcomeServiceStep(Map<String, Runnable> nextActions) {
        return welcomeStep("welcome.service", "gui.welcome.status.title", "gui.welcome.status.subtitle",
                List.of(
                raw("welcome.service.state", backendMessage(), backend.state() == DesktopUiHost.BackendState.RUNNING
                        ? TextStyle.SUCCESS : backend.state() == DesktopUiHost.BackendState.FAILED
                        ? TextStyle.ERROR : TextStyle.WARNING),
                bullet("welcome.service.point1", "gui.welcome.status.point1"),
                bullet("welcome.service.point2", "gui.welcome.status.point2"),
                bullet("welcome.service.point3", "gui.welcome.status.point3")),
                endRow("welcome.service.actions",
                        button("welcome.service.next", "welcome.service.next", "gui.welcome.nav.next",
                                backend.state() == DesktopUiHost.BackendState.RUNNING, nextActions,
                                () -> goWelcomeStep(2))));
    }

    private DesktopUiNode welcomeConfigStep(Map<String, Runnable> nextActions) {
        DesktopUiHost.OnboardingSnapshot onboarding = host.onboardingState(rootFolder);
        List<DesktopUiNode> content = new ArrayList<>();
        DesktopUiNode actions;
        if (onboarding.setupComplete()) {
            content.add(text("welcome.config.done", "gui.welcome.config.done", TextStyle.SUCCESS));
            actions = endRow("welcome.config.actions",
                    backWelcomeButton("welcome.config.back", 1, nextActions),
                    nextWelcomeButton("welcome.config.next", 3, nextActions));
        } else {
            content.add(bullet("welcome.config.account", "gui.welcome.config.point.account"));
            content.add(new DesktopUiNode.Form("welcome.config.form", DesktopUiNode.FormStyle.COMPACT, null,
                    List.of(
                            new DesktopUiNode.FormRow("welcome.config.username", key("gui.welcome.config.username"),
                                    null, input("welcome.username.input", "welcome.username",
                                    "gui.welcome.config.username", null, InputKind.TEXT,
                                    form("welcome.username", ""), !busy), null),
                            new DesktopUiNode.FormRow("welcome.config.password", key("gui.welcome.config.password"),
                                    null, new DesktopUiNode.TextInput("welcome.password.input", "welcome.password",
                                    key("gui.welcome.config.password"), null, InputKind.PASSWORD, "", 18, 1,
                                    !busy && backend.state() == DesktopUiHost.BackendState.RUNNING,
                                    welcomeFormRevision), null))));
            if (!welcomeNotice.isBlank()) content.add(status("welcome.config.notice", welcomeNotice));
            content.add(secondary("welcome.config.change", "gui.welcome.config.point.change"));
            actions = endRow("welcome.config.actions",
                    backWelcomeButton("welcome.config.back", 1, nextActions),
                    button("welcome.config.submit", "welcome.config.submit", "gui.welcome.config.submit",
                            !busy && backend.state() == DesktopUiHost.BackendState.RUNNING,
                            nextActions, this::submitSetup));
        }
        return welcomeStep("welcome.config", "gui.welcome.config.title", "gui.welcome.config.body",
                content, actions);
    }

    private DesktopUiNode welcomeProxyStep(Map<String, Runnable> nextActions) {
        boolean enabled = boolForm("welcome.proxy.enabled", true);
        List<DesktopUiNode> content = new ArrayList<>();
        content.add(bullet("welcome.proxy.usage", "gui.welcome.proxy.point.usage"));
        content.add(bullet("welcome.proxy.docker", "gui.welcome.proxy.point.docker"));
        content.add(toggle("welcome.proxy.enabled.input", "welcome.proxy.enabled", "gui.welcome.proxy.enabled",
                enabled, !busy));
        content.add(new DesktopUiNode.Form("welcome.proxy.form", DesktopUiNode.FormStyle.COMPACT, null,
                List.of(
                        new DesktopUiNode.FormRow("welcome.proxy.host", key("gui.welcome.proxy.host"), null,
                                input("welcome.proxy.host.input", "welcome.proxy.host", "gui.welcome.proxy.host",
                                        null, InputKind.TEXT, form("welcome.proxy.host", host.defaultProxyHost()),
                                        !busy && enabled), null),
                        new DesktopUiNode.FormRow("welcome.proxy.port", key("gui.welcome.proxy.port"), null,
                                input("welcome.proxy.port.input", "welcome.proxy.port", "gui.welcome.proxy.port",
                                        null, InputKind.NUMBER,
                                        form("welcome.proxy.port", Integer.toString(host.defaultProxyPort())),
                                        !busy && enabled), null))));
        if (!welcomeNotice.isBlank()) content.add(status("welcome.proxy.notice", welcomeNotice));
        content.add(secondary("welcome.proxy.change", "gui.welcome.proxy.point.change"));
        return welcomeStep("welcome.proxy", "gui.welcome.proxy.title", "gui.welcome.proxy.body", content,
                endRow("welcome.proxy.actions",
                backWelcomeButton("welcome.proxy.back", 2, nextActions),
                button("welcome.proxy.next", "welcome.proxy.next", "gui.welcome.nav.next", !busy,
                        nextActions, this::saveWelcomeProxy)));
    }

    private DesktopUiNode welcomeStartStep(Map<String, Runnable> nextActions) {
        return welcomeStep("welcome.start", "gui.welcome.start.title", "gui.welcome.start.body", List.of(
                bullet("welcome.start.kinds", "gui.welcome.start.point.kinds"),
                bullet("welcome.start.keepopen", "gui.welcome.start.point.keepopen"),
                bullet("welcome.start.formats", "gui.welcome.start.point.formats"),
                button("welcome.start.open", "welcome.start.open", "gui.welcome.start.button", true,
                        nextActions, () -> openWeb("/pixiv-batch.html")),
                secondary("welcome.start.waiting", "gui.welcome.start.waiting")),
                endRow("welcome.start.actions",
                        backWelcomeButton("welcome.start.back", 3, nextActions),
                        nextWelcomeButton("welcome.start.next", onboardingPluginStep().isPresent() ? 5 : 6,
                                nextActions)));
    }

    private DesktopUiNode welcomePluginStep(Map<String, Runnable> nextActions) {
        Optional<PluginOnboardingStep> selected = onboardingPluginStep();
        if (selected.isEmpty()) return welcomeAdvancedStep(nextActions);
        PluginOnboardingStep entry = selected.orElseThrow();
        GuiOnboardingStepContribution step = entry.step();
        String base = "welcome.plugin." + safeId(step.stepId());
        List<DesktopUiNode> nodes = new ArrayList<>();
        int index = 0;
        for (String bullet : step.bulletKeys()) nodes.add(new DesktopUiNode.Text(base + ".bullet." + index++,
                token(step.i18nNamespace(), bullet, bullet), TextStyle.BULLET, true, true));
        String openAction = base + ".open";
        nextActions.put(openAction, () -> openWeb(step.actionHref()));
        nodes.add(new DesktopUiNode.Button(base + ".open.button", openAction,
                token(step.i18nNamespace(), step.actionLabelKey(), step.actionLabelKey()), null,
                ButtonStyle.NORMAL, true));
        nodes.add(new DesktopUiNode.Text(base + ".waiting",
                token(step.i18nNamespace(), step.waitingKey(), step.waitingKey()),
                TextStyle.SECONDARY, true, true));
        return welcomeStep(base, token(step.i18nNamespace(), step.titleKey(), step.titleKey()),
                token(step.i18nNamespace(), step.bodyKey(), step.bodyKey()), nodes,
                endRow(base + ".actions", backWelcomeButton(base + ".back", 4, nextActions),
                        nextWelcomeButton(base + ".finish", 6, nextActions)));
    }

    private DesktopUiNode welcomeAdvancedStep(Map<String, Runnable> nextActions) {
        boolean ffmpegReady = host.locateFfmpeg().isPresent();
        return welcomeStep("welcome.advanced", "gui.welcome.advanced.title", "gui.welcome.advanced.body",
                List.of(
                text("welcome.scripts.title", "gui.welcome.scripts.title", TextStyle.HEADING),
                secondary("welcome.scripts.intro", "gui.welcome.scripts.intro"),
                bullet("welcome.scripts.page", "gui.welcome.scripts.point.page"),
                bullet("welcome.scripts.toolbox", "gui.welcome.scripts.point.toolbox"),
                secondary("welcome.scripts.install", "gui.welcome.scripts.install"),
                text("welcome.ffmpeg.title", "gui.welcome.ffmpeg.title", TextStyle.HEADING),
                secondary("welcome.ffmpeg.intro", "gui.welcome.ffmpeg.intro"),
                raw("welcome.ffmpeg.state", host.message("gui.welcome.ffmpeg.state",
                        host.message(ffmpegReady ? "gui.welcome.ffmpeg.state.ready"
                                : "gui.welcome.ffmpeg.state.missing")),
                        ffmpegReady ? TextStyle.SUCCESS : TextStyle.WARNING),
                secondary("welcome.ffmpeg.install", "gui.welcome.ffmpeg.install"),
                text("welcome.reopen.title", "gui.welcome.done.reopen.title", TextStyle.HEADING),
                secondary("welcome.reopen.body", "gui.welcome.done.reopen")),
                endRow("welcome.advanced.actions",
                        backWelcomeButton("welcome.advanced.back", onboardingPluginStep().isPresent() ? 5 : 4,
                                nextActions),
                        nextWelcomeButton("welcome.advanced.next", 7, nextActions)));
    }

    private DesktopUiNode welcomeDoneStep(Map<String, Runnable> nextActions) {
        return welcomeStep("welcome.done", "gui.welcome.done.title", "gui.welcome.done.body", List.of(
                bullet("welcome.done.start", "gui.welcome.done.point.start"),
                bullet("welcome.done.advanced", "gui.welcome.done.point.advanced")),
                endRow("welcome.done.actions",
                        backWelcomeButton("welcome.done.back", 6, nextActions),
                        button("welcome.done.finish", "welcome.done.finish", "gui.welcome.done.button",
                                !busy, nextActions, this::finishOnboarding)));
    }

    private DesktopUiNode welcomeStep(String id, String titleKey, String bodyKey,
                                      List<? extends DesktopUiNode> content, DesktopUiNode actions) {
        return welcomeStep(id, key(titleKey), key(bodyKey), content, actions);
    }

    private DesktopUiNode welcomeStep(String id, TextToken title, TextToken body,
                                      List<? extends DesktopUiNode> content, DesktopUiNode actions) {
        return new DesktopUiNode.Dock(id + ".layout", 16,
                column(id + ".header", List.of(
                        new DesktopUiNode.Text(id + ".title", title, TextStyle.TITLE, true, false),
                        new DesktopUiNode.Text(id + ".body", body, TextStyle.SECONDARY, true, false))),
                scroll(id + ".scroll", column(id + ".content", content)), actions, null, null);
    }

    private DesktopUiNode.Button backWelcomeButton(String id, int target,
                                                    Map<String, Runnable> nextActions) {
        return button(id, id, "gui.welcome.nav.prev", !busy, nextActions, () -> goWelcomeStep(target));
    }

    private DesktopUiNode.Button nextWelcomeButton(String id, int target,
                                                    Map<String, Runnable> nextActions) {
        return button(id, id, "gui.welcome.nav.next", !busy, nextActions, () -> goWelcomeStep(target));
    }

    private void goWelcomeStep(int target) {
        welcomeStep = normalizeWelcomeStep(Math.max(1, Math.min(7, target)));
        welcomeNotice = "";
        host.saveOnboardingProgress(welcomeStep);
        rebuild();
    }

    private int normalizeWelcomeStep(int step) {
        return step == 5 && onboardingPluginStep().isEmpty() ? 6 : step;
    }

    private int initialWelcomeStep() {
        DesktopUiHost.OnboardingSnapshot onboarding = host.onboardingState(rootFolder);
        int incomplete = backend.state() == DesktopUiHost.BackendState.RUNNING
                ? !onboarding.setupComplete() ? 2 : !onboarding.proxyConfigured() ? 3 : 4 : 1;
        return normalizeWelcomeStep(Math.max(incomplete, Math.max(1, Math.min(7, onboarding.progress()))));
    }

    private Optional<PluginOnboardingStep> onboardingPluginStep() {
        Map<String, GuiOnboardingStepContribution> unique = new LinkedHashMap<>();
        Set<String> duplicates = new LinkedHashSet<>();
        for (DesktopUiPluginSource source : currentSources()) {
            try {
                List<GuiOnboardingStepContribution> steps = source.plugin().guiOnboardingSteps();
                if (steps == null) continue;
                for (GuiOnboardingStepContribution step : steps) {
                    if (step == null || !validOnboardingStep(step) || duplicates.contains(step.stepId())) continue;
                    if (unique.putIfAbsent(step.stepId(), step) != null) {
                        unique.remove(step.stepId());
                        duplicates.add(step.stepId());
                    }
                }
            } catch (RuntimeException ignored) {
                // Optional plugin onboarding is isolated.
            }
        }
        return unique.values().stream()
                .sorted(Comparator.comparingInt(GuiOnboardingStepContribution::order)
                        .thenComparing(GuiOnboardingStepContribution::stepId))
                .findFirst().map(PluginOnboardingStep::new);
    }

    private DesktopUiNode statusPage(Map<String, Runnable> nextActions) {
        Optional<DesktopUiHost.FfmpegInstallation> ffmpeg = host.locateFfmpeg();
        List<DesktopUiNode> webActions = webEntryButtons(
                NavigationPlacements.GUI_STATUS_ACTIONS, "status.web", nextActions);
        webActions.add(0, button("status.web.batch", "status.web.batch", "gui.action.open-batch", true,
                nextActions, () -> openWeb("/pixiv-batch.html")));
        List<DesktopUiNode> children = new ArrayList<>();
        children.add(raw("status.backend.state", backendMessage(), backendTextStyle()));
        if (!statusNotice.isBlank()) children.add(new DesktopUiNode.Surface("status.notice",
                DesktopUiNode.SurfaceStyle.WARNING, new DesktopUiNode.Insets(8, 12, 8, 12), true,
                status("status.notice.text", statusNotice)));
        if (pendingOfficialUpdate != null) {
            children.add(updateBanner("official", pendingOfficialUpdate, false, nextActions));
        }
        if (pendingNightlyUpdate != null) {
            children.add(updateBanner("nightly", pendingNightlyUpdate, true, nextActions));
        }
        children.add(new DesktopUiNode.Form("status.grid", DesktopUiNode.FormStyle.KEY_VALUE, null, List.of(
                new DesktopUiNode.FormRow("status.port.row", key("gui.status.label.port"), null,
                        raw("status.port.value", statusPort, TextStyle.EMPHASIS), null),
                new DesktopUiNode.FormRow("status.mode.row", key("gui.status.label.mode"), null,
                        raw("status.mode.value", statusMode, TextStyle.EMPHASIS), null),
                new DesktopUiNode.FormRow("status.start-time.row", key("gui.status.label.start-time"), null,
                        raw("status.start-time.value", statusStartTime, TextStyle.EMPHASIS), null),
                new DesktopUiNode.FormRow("status.https.row", key("gui.status.label.https"), null,
                        raw("status.https.value", statusProtocol, TextStyle.EMPHASIS), null),
                new DesktopUiNode.FormRow("status.connectivity.row",
                        key("gui.status.label.pixiv-connectivity"), null,
                        row("status.connectivity.value",
                                raw("status.connectivity.text", connectivityDetails, TextStyle.BODY),
                                button("status.connectivity.check", "status.connectivity.check",
                                        "gui.status.pixiv-connectivity.action.check",
                                        !busy && !connectivityChecking
                                                && backend.state() == DesktopUiHost.BackendState.RUNNING,
                                        nextActions, this::checkConnectivity)), null))));
        children.add(text("status.web.hint", "gui.status.hint.web-console", TextStyle.CAPTION));
        List<DesktopUiNode> ffmpegNodes = new ArrayList<>();
        ffmpegNodes.add(text("status.ffmpeg.intro", "gui.ffmpeg.panel.intro", TextStyle.BODY));
        ffmpegNodes.add(status("status.ffmpeg.state", ffmpeg.isPresent()
                ? host.message("gui.ffmpeg.badge.ready") : host.message("gui.ffmpeg.badge.missing")));
        ffmpeg.ifPresent(value -> {
            ffmpegNodes.add(raw("status.ffmpeg.source", host.message("gui.ffmpeg.source.label",
                    localizedCode("ffmpeg.source.", value.source().name().toLowerCase(Locale.ROOT))),
                    TextStyle.CAPTION));
            ffmpegNodes.add(raw("status.ffmpeg.path", host.message("gui.ffmpeg.path.label",
                    value.ffmpegPath() == null ? "--" : value.ffmpegPath()), TextStyle.CODE));
        });
        ffmpegNodes.add(row("status.ffmpeg.actions",
                button("status.ffmpeg.install", "status.ffmpeg.install", "gui.ffmpeg.action.download-to-managed",
                        !busy && host.supportsManagedFfmpegInstall(), nextActions, this::requestFfmpegInstall),
                button("status.ffmpeg.open", "status.ffmpeg.open", "gui.ffmpeg.action.open-dir",
                        !busy, nextActions, this::openFfmpegDirectory)));
        if (ffmpegInstalling) ffmpegNodes.add(new DesktopUiNode.Progress("status.ffmpeg.progress",
                ffmpegProgress, ffmpegProgress <= 0d, statusNotice.isBlank() ? null : TextToken.raw(statusNotice)));
        children.add(group("status.ffmpeg", "gui.ffmpeg.panel.title", column("status.ffmpeg.content", ffmpegNodes)));
        DesktopUiNode actions = column("status.actions",
                group("status.web", "gui.action.group.navigation", row("status.web.actions", webActions)),
                group("status.functions", "gui.action.group.functions", row("status.function.actions",
                        button("status.open-folder", "status.open-folder", "gui.action.open-download-directory",
                                !busy, nextActions, this::openDownloadDirectory),
                        button("status.restart", "status.restart", "gui.action.restart-service",
                                !busy, nextActions, this::requestBackendRestart),
                        button("status.check-update", "status.check-update", "gui.update.action.check",
                                !busy, nextActions, this::checkUpdates),
                        button("status.migrate-directory", "status.migrate-directory",
                                "gui.action.migrate-directory", !busy, nextActions,
                                this::openDirectoryMigration),
                        button("status.refresh", "status.refresh", "gui.plugins.action.refresh", !busy,
                                nextActions, this::refreshStatus))));
        return new DesktopUiNode.Dock("status.root", 12, null,
                scroll("status.scroll", column("status.content", children)), actions, null, null);
    }

    private DesktopUiNode updateBanner(String id, PendingInstall update, boolean nightly,
                                        Map<String, Runnable> nextActions) {
        String base = "status.update." + id;
        List<DesktopUiNode> content = new ArrayList<>();
        content.add(raw(base + ".text", host.message(nightly
                ? "gui.update.banner.nightly-text" : "gui.update.banner.text",
                host.applicationVersion(), update.latestVersion()), TextStyle.HEADING));
        if (updateInstalling && downloadingNightly == nightly) {
            double progress = updateTotalBytes > 0
                    ? Math.min(1d, (double) updateReceivedBytes / updateTotalBytes) : 0d;
            TextToken label = updateTotalBytes > 0
                    ? appToken("gui.update.banner.progress.label", formatSize(updateReceivedBytes),
                    formatSize(updateTotalBytes), Math.round(progress * 100d))
                    : TextToken.raw(formatSize(updateReceivedBytes));
            content.add(new DesktopUiNode.Progress(base + ".progress", progress,
                    updateTotalBytes <= 0, label));
        }
        content.add(row(base + ".actions",
                button(base + ".notes", base + ".notes", nightly
                                ? "gui.update.banner.view-diff" : "gui.update.banner.view-log",
                        !updateInstalling, nextActions, () -> showUpdateNotes(update, nightly)),
                button(base + ".install", base + ".install", nightly
                                ? "gui.update.banner.install.nightly" : "gui.update.banner.install",
                        !busy && !updateInstalling, nextActions, () -> requestUpdateInstall(update, nightly)),
                button(base + ".dismiss", base + ".dismiss", "gui.update.banner.dismiss",
                        !updateInstalling, nextActions, () -> dismissUpdate(nightly))));
        return new DesktopUiNode.Surface(base, nightly ? DesktopUiNode.SurfaceStyle.INFO
                : DesktopUiNode.SurfaceStyle.SUCCESS, new DesktopUiNode.Insets(8, 12, 8, 12), true,
                column(base + ".content", content));
    }

    private DesktopUiNode configPage(Map<String, ConfigField> nextBindings,
                                     Map<String, Consumer<List<String>>> nextSelections,
                                     Map<String, Runnable> nextActions) {
        Map<String, GuiConfigGroupContribution> groups = new LinkedHashMap<>();
        CORE_GROUPS.forEach(group -> groups.put(group.groupId(), group));
        for (ConfigField field : configFields) {
            if (field.group() != null) groups.putIfAbsent(field.group().groupId(), field.group());
        }
        for (ConfigSection section : configSections) groups.putIfAbsent(section.group().groupId(), section.group());

        Set<FieldKey> claimed = configSections.stream().flatMap(section -> section.layouts().stream())
                .map(ConfigLayout::field).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Set<String> visibleGroups = new LinkedHashSet<>();
        configFields.stream().filter(field -> field.spec().contributesGroupVisibility())
                .map(field -> field.spec().groupId()).forEach(visibleGroups::add);
        configSections.stream().filter(ConfigSection::contributesGroupVisibility)
                .map(section -> section.group().groupId()).forEach(visibleGroups::add);
        Set<FieldKey> locked = lockedFields();
        Set<FieldKey> rendered = new LinkedHashSet<>();
        List<GuiConfigGroupContribution> orderedGroups = groups.values().stream()
                .filter(group -> !"interface".equals(group.groupId()))
                .filter(group -> visibleGroups.contains(group.groupId()))
                .sorted(Comparator.comparingInt(GuiConfigGroupContribution::order)
                        .thenComparing(GuiConfigGroupContribution::groupId))
                .toList();

        List<DesktopUiNode.Tab> tabs = new ArrayList<>();
        tabs.add(new DesktopUiNode.Tab("interface", key("gui.config.category.interface"),
                interfaceSettings()));
        if (boolForm("interface.config-menu-expand-all",
                Boolean.parseBoolean(selected("app.config-menu-expand-all", "false")))) {
            for (GuiConfigGroupContribution group : orderedGroups) {
                List<DesktopUiNode> nodes = configGroupNodes(group, claimed, rendered, locked,
                        nextBindings, nextSelections, nextActions);
                if (!nodes.isEmpty()) tabs.add(configGroupTab(group, nodes));
            }
        } else {
            addConfigCategory(tabs, "download", "gui.config.group.download", orderedGroups,
                    Set.of(GuiConfigGroups.DOWNLOAD), claimed, rendered, locked,
                    nextBindings, nextSelections, nextActions);
            addConfigCategory(tabs, "runtime-network", "gui.config.category.runtime-network", orderedGroups,
                    Set.of(GuiConfigGroups.SERVER, GuiConfigGroups.PROXY, GuiConfigGroups.HTTPS,
                            GuiConfigGroups.UPDATE), claimed, rendered, locked,
                    nextBindings, nextSelections, nextActions);
            addConfigCategory(tabs, "access-control", "gui.config.category.access-control", orderedGroups,
                    Set.of(GuiConfigGroups.GUEST_INVITE, GuiConfigGroups.SECURITY), claimed, rendered, locked,
                    nextBindings, nextSelections, nextActions);
            addConfigCategory(tabs, "automation-maintenance", "gui.config.category.automation-maintenance",
                    orderedGroups, Set.of(GuiConfigGroups.SCHEDULE, GuiConfigGroups.MAINTENANCE),
                    claimed, rendered, locked, nextBindings, nextSelections, nextActions);
            Set<String> remaining = orderedGroups.stream().map(GuiConfigGroupContribution::groupId)
                    .filter(id -> !renderedGroup(id)).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            remaining.add(GuiConfigGroups.PLUGINS);
            addPluginConfigCategory(tabs, orderedGroups, remaining,
                    claimed, rendered, locked, nextBindings, nextSelections, nextActions);
        }
        List<DesktopUiNode> bottom = new ArrayList<>();
        bottom.add(row("config.actions",
                button("config.open", "config.open", "gui.button.open-config", !busy,
                        nextActions, this::openConfigFile),
                button("config.save", "config.save", "gui.button.save", !busy, nextActions, this::saveConfiguration),
                button("config.reset", "config.reset", "gui.button.reset-defaults", !busy, nextActions,
                        this::requestConfigurationReset),
                button("config.reload", "config.reload", "desktop.ui.action.reload", !busy, nextActions,
                        this::reloadConfiguration)));
        if (configNoticeToken != null) {
            bottom.add(new DesktopUiNode.Text("config.notice.plugin", configNoticeToken,
                    TextStyle.CAPTION, true, true));
        } else if (!configNotice.isBlank()) {
            bottom.add(status("config.notice", configNotice));
        }
        return new DesktopUiNode.Dock("config.root", 0, null,
                new DesktopUiNode.Tabs("config.tabs", tabs),
                new DesktopUiNode.Surface("config.bottom", DesktopUiNode.SurfaceStyle.PLAIN,
                        DesktopUiNode.Insets.all(8), true, column("config.bottom.content", bottom)),
                null, null);
    }

    private static boolean renderedGroup(String id) {
        return Set.of(GuiConfigGroups.DOWNLOAD, GuiConfigGroups.SERVER, GuiConfigGroups.PROXY,
                GuiConfigGroups.HTTPS, GuiConfigGroups.UPDATE, GuiConfigGroups.GUEST_INVITE,
                GuiConfigGroups.SECURITY, GuiConfigGroups.SCHEDULE, GuiConfigGroups.MAINTENANCE)
                .contains(id);
    }

    private void addConfigCategory(List<DesktopUiNode.Tab> tabs, String id, String label,
                                   List<GuiConfigGroupContribution> groups, Set<String> groupIds,
                                   Set<FieldKey> claimed, Set<FieldKey> rendered, Set<FieldKey> locked,
                                   Map<String, ConfigField> nextBindings,
                                   Map<String, Consumer<List<String>>> nextSelections,
                                   Map<String, Runnable> nextActions) {
        List<DesktopUiNode> content = new ArrayList<>();
        for (GuiConfigGroupContribution group : groups) {
            if (!groupIds.contains(group.groupId())) continue;
            List<DesktopUiNode> nodes = configGroupNodes(group, claimed, rendered, locked,
                    nextBindings, nextSelections, nextActions);
            if (!nodes.isEmpty()) content.add(new DesktopUiNode.Group(
                    "config.category." + id + "." + safeId(group.groupId()),
                    token(group.i18nNamespace(), group.labelKey(), group.groupId()),
                    new DesktopUiNode.Container(
                            "config.category." + id + "." + safeId(group.groupId()) + ".content",
                            ContainerLayout.COLUMN, 1, 0, Alignment.STRETCH, nodes)));
        }
        if (!content.isEmpty()) tabs.add(new DesktopUiNode.Tab(id, key(label),
                scroll("config.category." + id + ".scroll",
                        new DesktopUiNode.Surface("config.category." + id + ".padding",
                                DesktopUiNode.SurfaceStyle.PLAIN, DesktopUiNode.Insets.all(16), true,
                                column("config.category." + id + ".content", content)))));
    }

    private DesktopUiNode.Tab configGroupTab(GuiConfigGroupContribution group, List<DesktopUiNode> nodes) {
        String id = "config." + safeId(group.groupId());
        return new DesktopUiNode.Tab(id,
                token(group.i18nNamespace(), group.labelKey(), group.groupId()),
                configGroupContent(id, nodes));
    }

    private void addPluginConfigCategory(List<DesktopUiNode.Tab> tabs,
                                         List<GuiConfigGroupContribution> groups, Set<String> groupIds,
                                         Set<FieldKey> claimed, Set<FieldKey> rendered, Set<FieldKey> locked,
                                         Map<String, ConfigField> nextBindings,
                                         Map<String, Consumer<List<String>>> nextSelections,
                                         Map<String, Runnable> nextActions) {
        List<DesktopUiNode.Tab> scopes = new ArrayList<>();
        List<DesktopUiNode.Tab> pluginTabs = new ArrayList<>();
        for (GuiConfigGroupContribution group : groups) {
            if (!groupIds.contains(group.groupId())) continue;
            List<DesktopUiNode> nodes = configGroupNodes(group, claimed, rendered, locked,
                    nextBindings, nextSelections, nextActions);
            if (nodes.isEmpty()) continue;
            if (GuiConfigGroups.PLUGINS.equals(group.groupId())) {
                scopes.add(new DesktopUiNode.Tab("plugin-market-settings",
                        key("gui.config.scope.plugin-market-settings"),
                        configGroupContent("config.category.plugins.market", nodes)));
            } else {
                pluginTabs.add(configGroupTab(group, nodes));
            }
        }
        DesktopUiNode pluginSettings = pluginTabs.isEmpty()
                ? text("config.category.plugins.settings.empty", "gui.config.scope.plugins.empty", TextStyle.BODY)
                : new DesktopUiNode.Tabs("config.category.plugins.settings.tabs", pluginTabs);
        scopes.add(new DesktopUiNode.Tab("plugin-settings", key("gui.config.scope.plugins"), pluginSettings));
        tabs.add(new DesktopUiNode.Tab("plugins", key("gui.config.group.plugins"),
                new DesktopUiNode.Tabs("config.category.plugins.scopes", scopes)));
    }

    private static DesktopUiNode configGroupContent(String id, List<DesktopUiNode> nodes) {
        return scroll(id + ".scroll", new DesktopUiNode.Surface(id + ".padding",
                DesktopUiNode.SurfaceStyle.PLAIN, DesktopUiNode.Insets.all(16), true,
                new DesktopUiNode.Container(id + ".content", ContainerLayout.COLUMN,
                        1, 0, Alignment.STRETCH, nodes)));
    }

    private List<DesktopUiNode> configGroupNodes(GuiConfigGroupContribution group,
                                                 Set<FieldKey> claimed, Set<FieldKey> rendered,
                                                 Set<FieldKey> locked,
                                                 Map<String, ConfigField> nextBindings,
                                                 Map<String, Consumer<List<String>>> nextSelections,
                                                 Map<String, Runnable> nextActions) {
        List<DesktopUiNode> nodes = new ArrayList<>();
        configSections.stream()
                .filter(section -> group.groupId().equals(section.group().groupId()))
                .sorted(Comparator.comparingInt(ConfigSection::order).thenComparing(ConfigSection::id))
                .map(section -> configSectionNode(section, rendered, locked,
                        nextBindings, nextSelections, nextActions))
                .forEach(nodes::add);
        configFields.stream()
                .filter(field -> group.groupId().equals(field.spec().groupId()))
                .filter(field -> !claimed.contains(field.key()))
                .filter(this::visible)
                .sorted(Comparator.comparingInt(field -> field.spec().order()))
                .filter(field -> rendered.add(field.key()))
                .map(field -> configFieldNode(field, locked, nextBindings, nextSelections, nextActions))
                .forEach(nodes::add);
        if (GuiConfigGroups.SERVER.equals(group.groupId())) {
            String binding = "config.autostart";
            String helpKey = autoStartSupported
                    ? "gui.config.field.autostart.help"
                    : "gui.config.field.autostart.unsupported.help";
            nextSelections.put(binding, values -> updateAutoStart(Boolean.parseBoolean(first(values))));
            nodes.add(new DesktopUiNode.Form("config.autostart.form", DesktopUiNode.FormStyle.RESPONSIVE,
                    key("gui.punctuation.colon"), List.of(new DesktopUiNode.FormRow(
                            "config.autostart.row", key("gui.config.field.autostart.label"), key(helpKey),
                            new DesktopUiNode.Toggle("config.autostart.input", binding,
                                    key("gui.config.field.autostart.label"), key(helpKey), ToggleStyle.CHECKBOX,
                                    autoStartEnabled, autoStartSupported && !busy), null))));
        }
        if (GuiConfigGroups.PLUGINS.equals(group.groupId())) {
            nodes.add(pluginRepositorySection(nextActions));
        }
        return nodes;
    }

    private DesktopUiNode pluginRepositorySection(Map<String, Runnable> nextActions) {
        List<DesktopUiNode> nodes = new ArrayList<>();
        nodes.add(text("config.market.heading", "gui.config.market.repos.heading", TextStyle.HEADING));
        nodes.add(new DesktopUiNode.Surface("config.market.risk", DesktopUiNode.SurfaceStyle.WARNING,
                DesktopUiNode.Insets.all(10), true,
                text("config.market.risk.text", "gui.config.market.repo.risk", TextStyle.CAPTION)));
        if (!pluginRepositoriesLoaded) {
            nodes.add(new DesktopUiNode.Text("config.market.read-failed",
                    appToken("gui.config.market.repo.read-failed", pluginRepositoriesLoadFailure),
                    TextStyle.ERROR, true, true));
        }
        List<DesktopUiNode.TableRow> rows = new ArrayList<>();
        for (int index = 0; index < pluginRepositories.size(); index++) {
            RepositoryConfigEntry entry = pluginRepositories.get(index);
            rows.add(new DesktopUiNode.TableRow(repositoryRowId(index), List.of(
                    entry.id(), host.message(entry.enabled()
                            ? "gui.config.market.table.yes" : "gui.config.market.table.no"),
                    entry.manifestUrl(), repositoryProxyLabel(entry.proxyPolicy()))));
        }
        int selected = repositoryRowIndex(selectedRepositoryRow);
        nodes.add(new DesktopUiNode.Table("config.market.repositories",
                "config.market.repositories.selected",
                List.of(
                        new DesktopUiNode.TableColumn("id", key("gui.config.market.table.col.id"), 120),
                        new DesktopUiNode.TableColumn("enabled", key("gui.config.market.table.col.enabled"), 60),
                        new DesktopUiNode.TableColumn("url", key("gui.config.market.table.col.url"), 320),
                        new DesktopUiNode.TableColumn("proxy", key("gui.config.market.table.col.proxy"), 160)),
                rows, SelectionMode.SINGLE,
                selected >= 0 && selected < rows.size() ? List.of(repositoryRowId(selected)) : List.of(),
                pluginRepositoriesLoaded && !busy));
        boolean selectedEntry = pluginRepositoriesLoaded && selected >= 0 && selected < pluginRepositories.size();
        nodes.add(row("config.market.repository.actions",
                button("config.market.repository.add", "config.market.repository.add",
                        "gui.config.market.action.add", pluginRepositoriesLoaded && !busy, nextActions,
                        () -> openRepositoryEditor(-1)),
                button("config.market.repository.edit", "config.market.repository.edit",
                        "gui.config.market.action.edit", selectedEntry && !busy, nextActions,
                        () -> openRepositoryEditor(repositoryRowIndex(selectedRepositoryRow))),
                button("config.market.repository.delete", "config.market.repository.delete",
                        "gui.config.market.action.delete", selectedEntry && !busy, nextActions,
                        this::requestRepositoryDelete),
                button("config.market.repository.up", "config.market.repository.up",
                        "gui.config.market.action.up", selectedEntry && selected > 0 && !busy, nextActions,
                        () -> moveRepository(-1)),
                button("config.market.repository.down", "config.market.repository.down",
                        "gui.config.market.action.down",
                        selectedEntry && selected < pluginRepositories.size() - 1 && !busy, nextActions,
                        () -> moveRepository(1))));

        List<DesktopUiNode> webEntries = webEntryButtons(
                NavigationPlacements.PLUGINS_SEGMENT, "config.market.web", nextActions);
        if (!webEntries.isEmpty()) {
            nodes.add(text("config.market.web.heading", "gui.config.market.open.heading", TextStyle.HEADING));
            nodes.add(row("config.market.web.actions", webEntries));
            nodes.add(text("config.market.web.hint", "gui.config.market.open.hint", TextStyle.CAPTION));
        }
        return column("config.market.section", nodes);
    }

    private String repositoryProxyLabel(String policyId) {
        for (DesktopUiHost.RepositoryProxyPolicy policy : DesktopUiHost.RepositoryProxyPolicy.values()) {
            if (policy.configId().equalsIgnoreCase(nullToEmpty(policyId))) {
                return host.message("gui.config.market.repo.proxy." + policy.configId());
            }
        }
        return nullToEmpty(policyId);
    }

    private void openRepositoryEditor(int index) {
        if (!pluginRepositoriesLoaded || index >= pluginRepositories.size()) return;
        editingRepositoryIndex = index;
        repositoryFormErrorKey = "";
        selectedTrustedKeyRow = null;
        RepositoryConfigEntry existing = index < 0 ? null : pluginRepositories.get(index);
        putForm("config.market.repository.id", existing == null ? "" : existing.id());
        putForm("config.market.repository.url", existing == null ? "" : existing.manifestUrl());
        putForm("config.market.repository.enabled", Boolean.toString(existing == null || existing.enabled()));
        String proxyPolicy = existing == null
                ? DesktopUiHost.RepositoryProxyPolicy.DEFAULT.configId() : existing.proxyPolicy();
        boolean knownPolicy = java.util.Arrays.stream(DesktopUiHost.RepositoryProxyPolicy.values())
                .anyMatch(policy -> policy.configId().equalsIgnoreCase(proxyPolicy));
        repositoryUnknownProxyPolicy = knownPolicy ? "" : proxyPolicy;
        putForm("config.market.repository.proxy", knownPolicy ? proxyPolicy : "unknown-policy");
        putForm("config.market.repository.allow-redirects",
                Boolean.toString(existing != null && existing.allowRedirects()));
        putForm("config.market.repository.strict-https",
                Boolean.toString(existing == null || existing.strictHttps()));
        putForm("config.market.repository.allow-non-public",
                Boolean.toString(existing != null && existing.allowNonPublicAddresses()));
        putForm("config.market.repository.use-proxy", Boolean.toString(existing != null && existing.useProxy()));
        putForm("config.market.repository.connect-timeout", overrideText(existing == null ? 0 : existing.connectTimeoutMs()));
        putForm("config.market.repository.read-timeout", overrideText(existing == null ? 0 : existing.readTimeoutMs()));
        putForm("config.market.repository.max-manifest", overrideText(existing == null ? 0 : existing.maxManifestBytes()));
        putForm("config.market.repository.max-package", overrideText(existing == null ? 0 : existing.maxPackageBytes()));
        TrustedKeyConfigEntry official = officialRepositoryKey();
        putForm("config.market.repository.inherit-official",
                Boolean.toString(existing != null && official != null && existing.trustedKeys().contains(official)));
        repositoryTrustedKeys = existing == null ? List.of() : existing.trustedKeys().stream()
                .filter(trusted -> official == null || !trusted.equals(official)).toList();
        showRepositoryEditorDialog();
    }

    private void showRepositoryEditorDialog() {
        String title = editingRepositoryIndex < 0
                ? "gui.config.market.repo.dialog.add.title" : "gui.config.market.repo.dialog.edit.title";
        showDialog("config.market.repository.dialog", title, DesktopUiDocument.DialogStyle.INFO,
                this::repositoryEditorContent, 720, 760);
    }

    private DesktopUiNode repositoryEditorContent(Map<String, Runnable> nextActions,
                                                   String dismissAction, Runnable dismiss) {
        List<DesktopUiNode> fields = new ArrayList<>();
        List<DesktopUiNode.FormRow> details = new ArrayList<>();
        details.add(formRow("config.market.repository.id.row", "gui.config.market.repo.field.id", null,
                input("config.market.repository.id.input", "config.market.repository.id",
                "gui.config.market.repo.field.id", null, InputKind.TEXT,
                form("config.market.repository.id", ""), true)));
        details.add(formRow("config.market.repository.url.row", "gui.config.market.repo.field.url", null,
                input("config.market.repository.url.input", "config.market.repository.url",
                "gui.config.market.repo.field.url", null, InputKind.TEXT,
                form("config.market.repository.url", ""), true)));
        details.add(formRow("config.market.repository.enabled.row", "gui.config.market.repo.field.enabled", null,
                toggle("config.market.repository.enabled.input", "config.market.repository.enabled",
                "gui.config.market.repo.field.enabled",
                boolForm("config.market.repository.enabled", true), true)));

        List<DesktopUiNode.Option> policies = new ArrayList<>();
        if (!repositoryUnknownProxyPolicy.isBlank()) {
            policies.add(new DesktopUiNode.Option("unknown-policy",
                    appToken("gui.config.market.repo.proxy.unknown-display", repositoryUnknownProxyPolicy), true));
        }
        for (DesktopUiHost.RepositoryProxyPolicy policy : DesktopUiHost.RepositoryProxyPolicy.values()) {
            policies.add(new DesktopUiNode.Option(policy.configId(),
                    key("gui.config.market.repo.proxy." + policy.configId()), true));
        }
        String selectedPolicy = form("config.market.repository.proxy",
                DesktopUiHost.RepositoryProxyPolicy.DEFAULT.configId());
        details.add(formRow("config.market.repository.proxy.row", "gui.config.market.repo.field.proxy", null,
                choice("config.market.repository.proxy.input", "config.market.repository.proxy",
                "gui.config.market.repo.field.proxy", null, policies, selectedPolicy, true)));
        String persistedPolicy = "unknown-policy".equals(selectedPolicy)
                ? repositoryUnknownProxyPolicy : selectedPolicy;
        boolean custom = DesktopUiHost.RepositoryProxyPolicy.CUSTOM.configId().equalsIgnoreCase(persistedPolicy);
        if (custom) {
            details.add(formRow("config.market.repository.allow-redirects.row",
                    "gui.config.market.repo.custom.allow-redirects", null,
                    toggle("config.market.repository.allow-redirects.input",
                    "config.market.repository.allow-redirects",
                    "gui.config.market.repo.custom.allow-redirects",
                    boolForm("config.market.repository.allow-redirects", false), true)));
            details.add(formRow("config.market.repository.strict-https.row",
                    "gui.config.market.repo.custom.strict-https", null,
                    toggle("config.market.repository.strict-https.input",
                    "config.market.repository.strict-https",
                    "gui.config.market.repo.custom.strict-https",
                    boolForm("config.market.repository.strict-https", true), true)));
            details.add(formRow("config.market.repository.allow-non-public.row",
                    "gui.config.market.repo.custom.allow-non-public", null,
                    toggle("config.market.repository.allow-non-public.input",
                    "config.market.repository.allow-non-public",
                    "gui.config.market.repo.custom.allow-non-public",
                    boolForm("config.market.repository.allow-non-public", false), true)));
            details.add(formRow("config.market.repository.use-proxy.row",
                    "gui.config.market.repo.custom.use-proxy", null,
                    toggle("config.market.repository.use-proxy.input", "config.market.repository.use-proxy",
                    "gui.config.market.repo.custom.use-proxy",
                    boolForm("config.market.repository.use-proxy", false), true)));
        }
        fields.add(new DesktopUiNode.Form("config.market.repository.details",
                DesktopUiNode.FormStyle.RESPONSIVE, key("gui.punctuation.colon"), details));
        if (custom || DesktopUiHost.RepositoryProxyPolicy.PROXY_TRUSTED.configId()
                .equalsIgnoreCase(persistedPolicy)) {
            fields.add(new DesktopUiNode.Surface("config.market.repository.policy-risk",
                    DesktopUiNode.SurfaceStyle.WARNING, DesktopUiNode.Insets.all(8), true,
                    text("config.market.repository.policy-risk.text", custom
                            ? "gui.config.market.repo.custom.risk"
                            : "gui.config.market.repo.proxy-trusted.risk", TextStyle.CAPTION)));
        }

        fields.add(text("config.market.repository.trust.heading",
                "gui.config.market.repo.trust.heading", TextStyle.HEADING));
        fields.add(text("config.market.repository.trust.hint",
                "gui.config.market.repo.trust.hint", TextStyle.CAPTION));
        fields.add(toggle("config.market.repository.inherit-official.input",
                "config.market.repository.inherit-official",
                "gui.config.market.repo.trust.inherit-official",
                boolForm("config.market.repository.inherit-official", false), officialRepositoryKey() != null));
        List<DesktopUiNode.TableRow> trustedRows = new ArrayList<>();
        for (int index = 0; index < repositoryTrustedKeys.size(); index++) {
            TrustedKeyConfigEntry trusted = repositoryTrustedKeys.get(index);
            trustedRows.add(new DesktopUiNode.TableRow(trustedKeyRowId(index), List.of(
                    trusted.keyId(), trusted.algorithm(), trustedKeyStateLabel(trusted.state()),
                    trusted.publisher(), trusted.trustLabel())));
        }
        int selectedTrusted = trustedKeyRowIndex(selectedTrustedKeyRow);
        fields.add(new DesktopUiNode.Table("config.market.repository.trusted",
                "config.market.repository.trusted.selected",
                List.of(
                        new DesktopUiNode.TableColumn("key-id", key("gui.config.market.repo.trust.table.col.key-id"), 150),
                        new DesktopUiNode.TableColumn("algorithm", key("gui.config.market.repo.trust.table.col.algorithm"), 90),
                        new DesktopUiNode.TableColumn("state", key("gui.config.market.repo.trust.table.col.state"), 90),
                        new DesktopUiNode.TableColumn("publisher", key("gui.config.market.repo.trust.table.col.publisher"), 140),
                        new DesktopUiNode.TableColumn("trust-label", key("gui.config.market.repo.trust.table.col.trust-label"), 160)),
                trustedRows, SelectionMode.SINGLE,
                selectedTrusted >= 0 && selectedTrusted < trustedRows.size()
                        ? List.of(trustedKeyRowId(selectedTrusted)) : List.of(), true));
        boolean hasTrustedSelection = selectedTrusted >= 0 && selectedTrusted < repositoryTrustedKeys.size();
        fields.add(row("config.market.repository.trusted.actions",
                button("config.market.repository.trusted.add", "config.market.repository.trusted.add",
                        "gui.config.market.repo.trust.action.add", true, nextActions,
                        () -> openTrustedKeyEditor(-1)),
                button("config.market.repository.trusted.edit", "config.market.repository.trusted.edit",
                        "gui.config.market.repo.trust.action.edit", hasTrustedSelection, nextActions,
                        () -> openTrustedKeyEditor(trustedKeyRowIndex(selectedTrustedKeyRow))),
                button("config.market.repository.trusted.delete", "config.market.repository.trusted.delete",
                        "gui.config.market.repo.trust.action.delete", hasTrustedSelection, nextActions,
                        this::deleteTrustedKey)));

        fields.add(new DesktopUiNode.Form("config.market.repository.overrides",
                DesktopUiNode.FormStyle.RESPONSIVE, key("gui.punctuation.colon"), List.of(
                formRow("config.market.repository.connect-timeout.row",
                        "gui.config.market.repo.field.connect-timeout", "gui.config.market.repo.override.hint",
                        input("config.market.repository.connect-timeout.input",
                "config.market.repository.connect-timeout",
                "gui.config.market.repo.field.connect-timeout", "gui.config.market.repo.override.hint",
                InputKind.NUMBER, form("config.market.repository.connect-timeout", ""), true)),
                formRow("config.market.repository.read-timeout.row",
                        "gui.config.market.repo.field.read-timeout", "gui.config.market.repo.override.hint",
                        input("config.market.repository.read-timeout.input", "config.market.repository.read-timeout",
                "gui.config.market.repo.field.read-timeout", "gui.config.market.repo.override.hint",
                InputKind.NUMBER, form("config.market.repository.read-timeout", ""), true)),
                formRow("config.market.repository.max-manifest.row",
                        "gui.config.market.repo.field.max-manifest", "gui.config.market.repo.override.hint",
                        input("config.market.repository.max-manifest.input", "config.market.repository.max-manifest",
                "gui.config.market.repo.field.max-manifest", "gui.config.market.repo.override.hint",
                InputKind.NUMBER, form("config.market.repository.max-manifest", ""), true)),
                formRow("config.market.repository.max-package.row",
                        "gui.config.market.repo.field.max-package", "gui.config.market.repo.override.hint",
                        input("config.market.repository.max-package.input", "config.market.repository.max-package",
                "gui.config.market.repo.field.max-package", "gui.config.market.repo.override.hint",
                InputKind.NUMBER, form("config.market.repository.max-package", ""), true)))));
        if (!repositoryFormErrorKey.isBlank()) {
            fields.add(text("config.market.repository.error", repositoryFormErrorKey, TextStyle.ERROR));
        }
        return new DesktopUiNode.Dock("config.market.repository.dialog.layout", 12,
                null, scroll("config.market.repository.dialog.scroll",
                        column("config.market.repository.dialog.fields", fields)),
                row("config.market.repository.dialog.actions",
                        button("config.market.repository.dialog.save", "config.market.repository.dialog.save",
                                "gui.config.market.repo.dialog.ok", true, nextActions, this::saveRepositoryEditor),
                        button("config.market.repository.dialog.cancel", dismissAction,
                                "gui.config.market.repo.dialog.cancel", true, nextActions, dismiss)), null, null);
    }

    private void saveRepositoryEditor() {
        String id = form("config.market.repository.id", "").trim();
        String url = form("config.market.repository.url", "").trim();
        String selectedPolicy = form("config.market.repository.proxy",
                DesktopUiHost.RepositoryProxyPolicy.DEFAULT.configId());
        String policy = "unknown-policy".equals(selectedPolicy) ? repositoryUnknownProxyPolicy : selectedPolicy;
        List<RepositoryConfigEntry> others = new ArrayList<>(pluginRepositories);
        if (editingRepositoryIndex >= 0 && editingRepositoryIndex < others.size()) {
            others.remove(editingRepositoryIndex);
        }
        String error = RepositoryConfigValidator.validateId(id, others, host.reservedPluginRepositoryIds());
        boolean strictHttps = !DesktopUiHost.RepositoryProxyPolicy.CUSTOM.configId().equalsIgnoreCase(policy)
                || boolForm("config.market.repository.strict-https", true);
        if (error == null) error = RepositoryConfigValidator.validateManifestUrl(url, strictHttps);
        if (error == null) error = RepositoryConfigValidator.validateProxyPolicy(policy);
        if (error == null) error = RepositoryConfigValidator.validateTimeoutOverride(
                form("config.market.repository.connect-timeout", ""));
        if (error == null) error = RepositoryConfigValidator.validateTimeoutOverride(
                form("config.market.repository.read-timeout", ""));
        if (error == null) error = RepositoryConfigValidator.validateSizeOverride(
                form("config.market.repository.max-manifest", ""));
        if (error == null) error = RepositoryConfigValidator.validateSizeOverride(
                form("config.market.repository.max-package", ""));
        List<TrustedKeyConfigEntry> trustedKeys = repositoryTrustedKeysForSave();
        if (error == null && hasDuplicateTrustedKeyIds(trustedKeys)) {
            error = "gui.config.market.repo.trust.error.key-id-duplicate";
        }
        if (error != null) {
            repositoryFormErrorKey = error;
            rebuild();
            return;
        }
        RepositoryConfigEntry existing = editingRepositoryIndex < 0 ? null
                : pluginRepositories.get(editingRepositoryIndex);
        RepositoryConfigEntry entry = new RepositoryConfigEntry(id,
                existing == null ? "" : existing.displayNameKey(), url,
                boolForm("config.market.repository.enabled", true), policy,
                boolForm("config.market.repository.allow-redirects", false),
                boolForm("config.market.repository.strict-https", true),
                boolForm("config.market.repository.allow-non-public", false),
                boolForm("config.market.repository.use-proxy", false),
                RepositoryConfigValidator.parseOverride(form("config.market.repository.connect-timeout", "")),
                RepositoryConfigValidator.parseOverride(form("config.market.repository.read-timeout", "")),
                RepositoryConfigValidator.parseOverride(form("config.market.repository.max-manifest", "")),
                RepositoryConfigValidator.parseOverride(form("config.market.repository.max-package", "")),
                trustedKeys, existing == null ? new LinkedHashMap<>() : existing.extraFields());
        List<RepositoryConfigEntry> updated = new ArrayList<>(pluginRepositories);
        int selected;
        if (editingRepositoryIndex < 0) {
            updated.add(entry);
            selected = updated.size() - 1;
        } else {
            updated.set(editingRepositoryIndex, entry);
            selected = editingRepositoryIndex;
        }
        pluginRepositories = List.copyOf(updated);
        selectedRepositoryRow = repositoryRowId(selected);
        dialogState = null;
        rebuild();
    }

    private void requestRepositoryDelete() {
        int selected = repositoryRowIndex(selectedRepositoryRow);
        if (selected < 0 || selected >= pluginRepositories.size()) return;
        RepositoryConfigEntry entry = pluginRepositories.get(selected);
        showDialog("config.market.repository.delete", "gui.config.market.repo.delete.title",
                DesktopUiDocument.DialogStyle.QUESTION,
                (nextActions, dismissAction, dismiss) -> column("config.market.repository.delete.content",
                        new DesktopUiNode.Text("config.market.repository.delete.message",
                                appToken("gui.config.market.repo.delete.confirm", entry.id()),
                                TextStyle.BODY, true, true),
                        row("config.market.repository.delete.actions",
                                button("config.market.repository.delete.confirm",
                                        "config.market.repository.delete.confirm",
                                        "gui.config.market.action.delete", true, nextActions, () -> {
                                            List<RepositoryConfigEntry> updated = new ArrayList<>(pluginRepositories);
                                            updated.remove(selected);
                                            pluginRepositories = List.copyOf(updated);
                                            selectedRepositoryRow = null;
                                            dialogState = null;
                                            rebuild();
                                        }),
                                button("config.market.repository.delete.cancel", dismissAction,
                                        "desktop.ui.action.cancel", true, nextActions, dismiss))), 460, 0);
    }

    private void moveRepository(int delta) {
        int selected = repositoryRowIndex(selectedRepositoryRow);
        int target = selected + delta;
        if (selected < 0 || target < 0 || target >= pluginRepositories.size()) return;
        List<RepositoryConfigEntry> updated = new ArrayList<>(pluginRepositories);
        RepositoryConfigEntry moved = updated.remove(selected);
        updated.add(target, moved);
        pluginRepositories = List.copyOf(updated);
        selectedRepositoryRow = repositoryRowId(target);
        rebuild();
    }

    private void openTrustedKeyEditor(int index) {
        if (index >= repositoryTrustedKeys.size()) return;
        editingTrustedKeyIndex = index;
        repositoryFormErrorKey = "";
        TrustedKeyConfigEntry existing = index < 0 ? null : repositoryTrustedKeys.get(index);
        putForm("config.market.trusted-key.id", existing == null ? "" : existing.keyId());
        putForm("config.market.trusted-key.algorithm", existing == null ? "Ed25519" : existing.algorithm());
        putForm("config.market.trusted-key.public-key", existing == null ? "" : existing.publicKey());
        putForm("config.market.trusted-key.state", existing == null ? "ACTIVE" : existing.state());
        putForm("config.market.trusted-key.publisher", existing == null ? "" : existing.publisher());
        putForm("config.market.trusted-key.trust-label", existing == null ? "" : existing.trustLabel());
        String title = index < 0 ? "gui.config.market.repo.trust.dialog.add.title"
                : "gui.config.market.repo.trust.dialog.edit.title";
        showDialog("config.market.trusted-key.dialog", title, DesktopUiDocument.DialogStyle.INFO,
                this::trustedKeyEditorContent, false, 600, 0);
    }

    private DesktopUiNode trustedKeyEditorContent(Map<String, Runnable> nextActions,
                                                   String dismissAction, Runnable dismiss) {
        List<DesktopUiNode.FormRow> fields = new ArrayList<>();
        fields.add(formRow("config.market.trusted-key.id.row", "gui.config.market.repo.trust.field.key-id", null,
                input("config.market.trusted-key.id.input", "config.market.trusted-key.id",
                "gui.config.market.repo.trust.field.key-id", null, InputKind.TEXT,
                form("config.market.trusted-key.id", ""), true)));
        fields.add(formRow("config.market.trusted-key.algorithm.row",
                "gui.config.market.repo.trust.field.algorithm", null,
                input("config.market.trusted-key.algorithm.input", "config.market.trusted-key.algorithm",
                "gui.config.market.repo.trust.field.algorithm", null, InputKind.TEXT,
                form("config.market.trusted-key.algorithm", "Ed25519"), true)));
        fields.add(formRow("config.market.trusted-key.public-key.row",
                "gui.config.market.repo.trust.field.public-key", "gui.config.market.repo.trust.public-key.hint",
                input("config.market.trusted-key.public-key.input", "config.market.trusted-key.public-key",
                "gui.config.market.repo.trust.field.public-key",
                "gui.config.market.repo.trust.public-key.hint", InputKind.MULTILINE,
                form("config.market.trusted-key.public-key", ""), true)));
        List<DesktopUiNode.Option> states = List.of(
                new DesktopUiNode.Option("ACTIVE", key("gui.config.market.repo.trust.state.active"), true),
                new DesktopUiNode.Option("RETIRED", key("gui.config.market.repo.trust.state.retired"), true),
                new DesktopUiNode.Option("REVOKED", key("gui.config.market.repo.trust.state.revoked"), true));
        fields.add(formRow("config.market.trusted-key.state.row", "gui.config.market.repo.trust.field.state", null,
                choice("config.market.trusted-key.state.input", "config.market.trusted-key.state",
                "gui.config.market.repo.trust.field.state", null, states,
                form("config.market.trusted-key.state", "ACTIVE"), true)));
        fields.add(formRow("config.market.trusted-key.publisher.row",
                "gui.config.market.repo.trust.field.publisher", null,
                input("config.market.trusted-key.publisher.input", "config.market.trusted-key.publisher",
                "gui.config.market.repo.trust.field.publisher", null, InputKind.TEXT,
                form("config.market.trusted-key.publisher", ""), true)));
        fields.add(formRow("config.market.trusted-key.trust-label.row",
                "gui.config.market.repo.trust.field.trust-label", null,
                input("config.market.trusted-key.trust-label.input", "config.market.trusted-key.trust-label",
                "gui.config.market.repo.trust.field.trust-label", null, InputKind.TEXT,
                form("config.market.trusted-key.trust-label", ""), true)));
        List<DesktopUiNode> content = new ArrayList<>();
        content.add(new DesktopUiNode.Form("config.market.trusted-key.form",
                DesktopUiNode.FormStyle.RESPONSIVE, key("gui.punctuation.colon"), fields));
        if (!repositoryFormErrorKey.isBlank()) {
            content.add(text("config.market.trusted-key.error", repositoryFormErrorKey, TextStyle.ERROR));
        }
        String cancelAction = "config.market.trusted-key.cancel";
        nextActions.put(cancelAction, this::showRepositoryEditorDialog);
        return column("config.market.trusted-key.dialog.content",
                column("config.market.trusted-key.dialog.fields", content),
                row("config.market.trusted-key.dialog.actions",
                        button("config.market.trusted-key.save", "config.market.trusted-key.save",
                                "gui.config.market.repo.dialog.ok", true, nextActions, this::saveTrustedKeyEditor),
                        button("config.market.trusted-key.cancel", cancelAction,
                                "gui.config.market.repo.dialog.cancel", true, nextActions,
                                this::showRepositoryEditorDialog)));
    }

    private void saveTrustedKeyEditor() {
        List<String> otherIds = new ArrayList<>();
        for (int index = 0; index < repositoryTrustedKeys.size(); index++) {
            if (index != editingTrustedKeyIndex) otherIds.add(repositoryTrustedKeys.get(index).keyId());
        }
        if (boolForm("config.market.repository.inherit-official", false)) {
            TrustedKeyConfigEntry official = officialRepositoryKey();
            if (official != null) otherIds.add(official.keyId());
        }
        String error = RepositoryConfigValidator.validateTrustedKey(
                form("config.market.trusted-key.id", ""),
                form("config.market.trusted-key.algorithm", ""),
                form("config.market.trusted-key.public-key", ""),
                form("config.market.trusted-key.state", ""), otherIds);
        if (error != null) {
            repositoryFormErrorKey = error;
            rebuild();
            return;
        }
        TrustedKeyConfigEntry existing = editingTrustedKeyIndex < 0 ? null
                : repositoryTrustedKeys.get(editingTrustedKeyIndex);
        TrustedKeyConfigEntry trusted = new TrustedKeyConfigEntry(
                form("config.market.trusted-key.id", ""),
                form("config.market.trusted-key.algorithm", ""),
                form("config.market.trusted-key.public-key", ""),
                form("config.market.trusted-key.state", ""),
                form("config.market.trusted-key.publisher", ""),
                form("config.market.trusted-key.trust-label", ""),
                existing == null ? new LinkedHashMap<>() : existing.extraFields());
        List<TrustedKeyConfigEntry> updated = new ArrayList<>(repositoryTrustedKeys);
        int selected;
        if (editingTrustedKeyIndex < 0) {
            updated.add(trusted);
            selected = updated.size() - 1;
        } else {
            updated.set(editingTrustedKeyIndex, trusted);
            selected = editingTrustedKeyIndex;
        }
        repositoryTrustedKeys = List.copyOf(updated);
        selectedTrustedKeyRow = trustedKeyRowId(selected);
        repositoryFormErrorKey = "";
        showRepositoryEditorDialog();
    }

    private void deleteTrustedKey() {
        int selected = trustedKeyRowIndex(selectedTrustedKeyRow);
        if (selected < 0 || selected >= repositoryTrustedKeys.size()) return;
        List<TrustedKeyConfigEntry> updated = new ArrayList<>(repositoryTrustedKeys);
        updated.remove(selected);
        repositoryTrustedKeys = List.copyOf(updated);
        selectedTrustedKeyRow = null;
        rebuild();
    }

    private List<TrustedKeyConfigEntry> repositoryTrustedKeysForSave() {
        List<TrustedKeyConfigEntry> trusted = new ArrayList<>();
        TrustedKeyConfigEntry official = officialRepositoryKey();
        if (official != null && boolForm("config.market.repository.inherit-official", false)) {
            trusted.add(official);
        }
        for (TrustedKeyConfigEntry key : repositoryTrustedKeys) {
            if (official == null || !key.equals(official)) trusted.add(key);
        }
        return List.copyOf(trusted);
    }

    private TrustedKeyConfigEntry officialRepositoryKey() {
        try {
            return host.officialPluginRepositoryKey();
        } catch (RuntimeException unsupported) {
            return null;
        }
    }

    private static boolean hasDuplicateTrustedKeyIds(List<TrustedKeyConfigEntry> keys) {
        Set<String> seen = new LinkedHashSet<>();
        for (TrustedKeyConfigEntry key : keys) {
            String id = key.keyId().trim().toLowerCase(Locale.ROOT);
            if (!id.isBlank() && !seen.add(id)) return true;
        }
        return false;
    }

    private String trustedKeyStateLabel(String state) {
        String normalized = nullToEmpty(state).trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "active", "retired", "revoked" -> host.message(
                    "gui.config.market.repo.trust.state." + normalized);
            default -> state;
        };
    }

    private void putForm(String key, String value) {
        formValues.put(key, value == null ? "" : value);
    }

    private static String overrideText(long value) {
        return value > 0 ? Long.toString(value) : "";
    }

    private static String repositoryRowId(int index) { return "repository." + index; }
    private static int repositoryRowIndex(String id) { return indexedRow(id, "repository."); }
    private static String trustedKeyRowId(int index) { return "trusted-key." + index; }
    private static int trustedKeyRowIndex(String id) { return indexedRow(id, "trusted-key."); }

    private static int indexedRow(String id, String prefix) {
        if (id == null || !id.startsWith(prefix)) return -1;
        return parseInt(id.substring(prefix.length()), -1);
    }

    private DesktopUiNode configSectionNode(ConfigSection section, Set<FieldKey> rendered,
                                             Set<FieldKey> locked,
                                             Map<String, ConfigField> nextBindings,
                                             Map<String, Consumer<List<String>>> nextSelections,
                                             Map<String, Runnable> nextActions) {
        String base = "config.section." + safeId(section.id());
        List<DesktopUiNode> nodes = new ArrayList<>();
        if (section.layout() != GuiConfigSectionLayout.CARD_SWITCHER) {
            nodes.addAll(configNoticeNodes(base, section.notices(), null));
        }
        if (section.title() != null) {
            nodes.add(new DesktopUiNode.Text(base + ".title", section.title().token(),
                    TextStyle.HEADING, true, true));
        }
        if (section.help() != null) {
            nodes.add(new DesktopUiNode.Text(base + ".help", section.help().token(),
                    TextStyle.BODY, true, true));
        }
        if (section.layout() == GuiConfigSectionLayout.CARD_SWITCHER) {
            Map<String, List<ConfigLayout>> cards = new LinkedHashMap<>();
            section.layouts().stream().filter(layout -> layout.cardId() != null)
                    .forEach(layout -> cards.computeIfAbsent(layout.cardId(), ignored -> new ArrayList<>()).add(layout));
            if (!cards.isEmpty()) {
                nodes.addAll(presetNodes(base, section, null, section.presets().stream()
                        .filter(preset -> preset.cardId() == null).toList(), nextSelections));
                String binding = base + ".card.selection";
                String selectedCard = form(binding, cards.keySet().iterator().next());
                if (!cards.containsKey(selectedCard)) selectedCard = cards.keySet().iterator().next();
                List<DesktopUiNode.Option> options = new ArrayList<>();
                for (Map.Entry<String, List<ConfigLayout>> entry : cards.entrySet()) {
                    String cardId = entry.getKey();
                    LocalizedText label = entry.getValue().stream().map(ConfigLayout::cardLabel)
                            .filter(Objects::nonNull).findFirst().orElse(LocalizedText.raw(cardId));
                    options.add(new DesktopUiNode.Option(cardId, label.token(), true));
                }
                nextSelections.put(binding, values -> {
                    String value = first(values);
                    if (cards.containsKey(value)) {
                        formValues.put(binding, value);
                        rebuild();
                    }
                });
                TextToken layoutLabel = section.layoutLabel() == null
                        ? key("gui.config.section.card.label") : section.layoutLabel().token();
                TextToken layoutHelp = section.layoutHelp() == null
                        ? key("gui.config.section.card.help") : section.layoutHelp().token();
                nodes.add(new DesktopUiNode.Form(base + ".card.selector.form",
                        DesktopUiNode.FormStyle.RESPONSIVE, key("gui.punctuation.colon"),
                        List.of(new DesktopUiNode.FormRow(base + ".card.selector.row", layoutLabel, layoutHelp,
                                new DesktopUiNode.Choice(base + ".card.selector", binding, layoutLabel, layoutHelp,
                                        ChoiceStyle.COMBO_BOX, SelectionMode.SINGLE, options,
                                        List.of(selectedCard), !busy), null))));
                nodes.addAll(configNoticeNodes(base, section.notices(), selectedCard));
                nodes.addAll(sectionContent(section, selectedCard, cards.get(selectedCard), rendered, locked,
                        nextBindings, nextSelections, nextActions));
                nodes.addAll(actionNodes(base, section.actions().stream()
                        .filter(action -> action.cardId() == null).toList(), nextActions));
            } else {
                nodes.addAll(configNoticeNodes(base, section.notices(), null));
                nodes.addAll(sectionContent(section, null, section.layouts(), rendered, locked,
                        nextBindings, nextSelections, nextActions));
            }
        } else if (section.layout()
                == GuiConfigSectionLayout.COMPACT_GRID) {
            nodes.addAll(presetNodes(base, section, null, section.presets().stream()
                    .filter(preset -> preset.cardId() == null).toList(), nextSelections));
            List<DesktopUiNode> compact = new ArrayList<>();
            List<DesktopUiNode> normal = new ArrayList<>();
            List<GuiConfigEffect> compactEffects = new ArrayList<>();
            for (ConfigLayout layout : section.layouts()) {
                ConfigField field = field(layout.field());
                if (field == null || !visible(field) || !rendered.add(field.key())) continue;
                if (field.spec().type() == GuiConfigFieldType.BOOL) {
                    String binding = bindingId(field.key());
                    nextBindings.put(binding, field);
                    compact.add(new DesktopUiNode.Toggle(binding + ".input", binding,
                            token(field.namespace(), field.spec().labelKey(), field.spec().key()),
                            optionalToken(field.namespace(), field.spec().helpKey()), ToggleStyle.CHECKBOX,
                            Boolean.parseBoolean(values.getOrDefault(field.key(), field.spec().defaultValue())),
                            enabled(field) && !locked.contains(field.key())));
                    compactEffects.add(field.spec().effect());
                } else {
                    normal.add(configFieldNode(field, locked, nextBindings, nextSelections, nextActions));
                }
            }
            if (!compact.isEmpty()) {
                TextToken layoutLabel = section.layoutLabel() == null
                        ? key("gui.config.section.compact.label") : section.layoutLabel().token();
                TextToken layoutHelp = section.layoutHelp() == null
                        ? key("gui.config.section.compact.help") : section.layoutHelp().token();
                nodes.add(new DesktopUiNode.Form(base + ".grid.form", DesktopUiNode.FormStyle.RESPONSIVE,
                        key("gui.punctuation.colon"), List.of(new DesktopUiNode.FormRow(base + ".grid.row",
                        layoutLabel, layoutHelp,
                        new DesktopUiNode.Container(base + ".grid", ContainerLayout.GRID, 2, 8,
                                Alignment.START, compact),
                        effectNode(base + ".grid", strongestEffect(compactEffects))))));
            }
            nodes.addAll(normal);
            nodes.addAll(actionNodes(base, section.actions().stream()
                    .filter(action -> action.cardId() == null).toList(), nextActions));
        } else {
            nodes.addAll(sectionContent(section, null, section.layouts(), rendered, locked,
                    nextBindings, nextSelections, nextActions));
        }
        return column(base, nodes);
    }

    private static List<DesktopUiNode> configNoticeNodes(String base, List<ConfigNotice> notices,
                                                          String selectedCard) {
        return notices.stream()
                .filter(notice -> notice.cardIds().isEmpty() || notice.cardIds().contains(selectedCard))
                .map(notice -> (DesktopUiNode) new DesktopUiNode.Text(
                        base + ".notice." + safeId(notice.id()), notice.text().token(),
                        TextStyle.CAPTION, true, true))
                .toList();
    }

    private static GuiConfigEffect strongestEffect(List<GuiConfigEffect> effects) {
        if (effects.contains(GuiConfigEffect.PROCESS_RESTART)) return GuiConfigEffect.PROCESS_RESTART;
        if (effects.contains(GuiConfigEffect.BACKEND_RESTART)) return GuiConfigEffect.BACKEND_RESTART;
        return GuiConfigEffect.HOT_RELOAD;
    }

    private List<DesktopUiNode> sectionContent(ConfigSection section, String cardId,
                                               List<ConfigLayout> layouts, Set<FieldKey> rendered,
                                               Set<FieldKey> locked,
                                               Map<String, ConfigField> nextBindings,
                                               Map<String, Consumer<List<String>>> nextSelections,
                                               Map<String, Runnable> nextActions) {
        String base = "config.section." + safeId(section.id())
                + (cardId == null ? "" : ".card." + safeId(cardId));
        List<DesktopUiNode> nodes = new ArrayList<>();
        List<ConfigPreset> presets = section.presets().stream()
                .filter(preset -> Objects.equals(cardId, preset.cardId())).toList();
        nodes.addAll(presetNodes(base, section, cardId, presets, nextSelections));
        for (ConfigLayout layout : layouts) {
            ConfigField field = field(layout.field());
            if (field != null && visible(field) && rendered.add(field.key())) {
                nodes.add(configFieldNode(field, locked, nextBindings, nextSelections, nextActions));
            }
        }
        List<ConfigAction> actions = section.actions().stream()
                .filter(action -> Objects.equals(cardId, action.cardId())).toList();
        nodes.addAll(actionNodes(base, actions, nextActions));
        return nodes;
    }

    private List<DesktopUiNode> presetNodes(String base, ConfigSection section, String cardId,
                                            List<ConfigPreset> presets,
                                            Map<String, Consumer<List<String>>> nextSelections) {
        if (presets.isEmpty()) return List.of();
        String binding = base + ".preset";
        ConfigPreset selected = selectedPreset(presets);
        List<DesktopUiNode.Option> options = presets.stream()
                .map(preset -> new DesktopUiNode.Option(presetOptionId(preset), preset.label().token(), true))
                .toList();
        nextSelections.put(binding, values -> presets.stream()
                .filter(preset -> presetOptionId(preset).equals(first(values))).findFirst()
                .ifPresent(this::applyPreset));
        LocalizedText label = section.presetLabel() == null
                ? LocalizedText.app("gui.config.section.preset.label") : section.presetLabel();
        TextToken help = section.presetHelp() == null ? key("gui.config.section.preset.help")
                : section.presetHelp().token();
        DesktopUiNode.Choice choice = new DesktopUiNode.Choice(base + ".preset.input", binding,
                label.token(), help, ChoiceStyle.COMBO_BOX, SelectionMode.SINGLE, options,
                selected == null ? List.of() : List.of(presetOptionId(selected)), !busy);
        return List.of(new DesktopUiNode.Form(base + ".preset.form", DesktopUiNode.FormStyle.RESPONSIVE,
                key("gui.punctuation.colon"), List.of(new DesktopUiNode.FormRow(
                base + ".preset.row", label.token(), help, choice, null))));
    }

    private List<DesktopUiNode> actionNodes(String base, List<ConfigAction> configActions,
                                            Map<String, Runnable> nextActions) {
        List<DesktopUiNode> nodes = new ArrayList<>();
        int index = 0;
        for (ConfigAction action : configActions) {
            String id = base + ".action." + index++ + "." + safeId(action.owner() + "." + action.spec().actionId());
            String target = id + ".run";
            nextActions.put(target, () -> runConfigAction(action));
            nodes.add(new DesktopUiNode.Button(id, target, action.label().token(),
                    action.help() == null ? null : action.help().token(), ButtonStyle.NORMAL, !busy));
        }
        return nodes;
    }

    private DesktopUiNode pluginsPage(Map<String, Runnable> nextActions) {
        List<DesktopUiNode> header = new ArrayList<>();
        header.add(text("plugins.title", "gui.plugins.title", TextStyle.HEADING));
        header.add(text("plugins.intro", "gui.plugins.intro", TextStyle.CAPTION));
        if (recoveryMode) {
            header.add(new DesktopUiNode.Surface("plugins.recovery", DesktopUiNode.SurfaceStyle.WARNING,
                    new DesktopUiNode.Insets(8, 12, 8, 12), true,
                    text("plugins.recovery.text", "gui.plugins.recovery", TextStyle.WARNING)));
        }
        List<DesktopUiNode> center = new ArrayList<>();
        if (!pluginsNotice.isBlank()) center.add(status("plugins.notice", pluginsNotice));
        if (pluginStatuses.isEmpty() && pluginsNotice.isBlank()) {
            center.add(text("plugins.empty", "gui.plugins.state.empty", TextStyle.CAPTION));
        }
        for (PluginStatusRow plugin : pluginStatuses) center.add(pluginCard(plugin));
        DesktopUiNode actions = row("plugins.actions",
                button("plugins.refresh", "plugins.refresh", "gui.plugins.action.refresh", !busy,
                        nextActions, this::refreshPlugins),
                button("plugins.open", "plugins.open", "gui.plugins.action.open-web", true,
                        nextActions, () -> openWeb("/plugin-manage.html")));
        return new DesktopUiNode.Dock("plugins.root", 12,
                column("plugins.header", header),
                scroll("plugins.scroll", column("plugins.list", center)),
                actions, null, null);
    }

    private DesktopUiNode pluginCard(PluginStatusRow plugin) {
        TextStyle statusStyle = switch (nullToEmpty(plugin.statusCode())) {
            case "STARTED" -> TextStyle.SUCCESS;
            case "FAILED", "INCOMPATIBLE", "MISSING_REQUIRED", "INCOMPATIBLE_REQUIRED" -> TextStyle.ERROR;
            case "DISABLED", "STOPPED", "UNLOADED" -> TextStyle.CAPTION;
            default -> TextStyle.WARNING;
        };
        DesktopUiNode header = new DesktopUiNode.Dock("plugins.card." + plugin.id() + ".header", 8,
                null, null, null,
                raw("plugins.card." + plugin.id() + ".name", plugin.name(), TextStyle.HEADING),
                raw("plugins.card." + plugin.id() + ".status",
                        localizedCode("gui.plugins.status.", plugin.statusCode()), statusStyle));
        return new DesktopUiNode.Surface("plugins.card." + plugin.id(), DesktopUiNode.SurfaceStyle.CARD,
                new DesktopUiNode.Insets(8, 12, 8, 12), true,
                column("plugins.card." + plugin.id() + ".content", header,
                        raw("plugins.card." + plugin.id() + ".secondary",
                                pluginSecondary(plugin), TextStyle.CAPTION)));
    }

    private String pluginSecondary(PluginStatusRow plugin) {
        List<String> parts = new ArrayList<>();
        String source = switch (nullToEmpty(plugin.source())) {
            case "built-in" -> "built-in";
            case "external" -> "external";
            case "not-installed" -> "not-installed";
            default -> "unknown";
        };
        parts.add(host.message("gui.plugins.source." + source));
        if (plugin.required()) parts.add(host.message("gui.plugins.tag.required"));
        if (plugin.managed() && !nullToEmpty(plugin.phaseCode()).isBlank()) {
            parts.add(localizedCode("gui.plugins.phase.", plugin.phaseCode()));
        }
        if (!nullToEmpty(plugin.version()).isBlank()) {
            parts.add(host.message("gui.plugins.version", plugin.version()));
        }
        if (!nullToEmpty(plugin.verificationStatus()).isBlank()) {
            parts.add(localizedCode("gui.plugins.verification.", plugin.verificationStatus()));
        }
        return String.join("  ·  ", parts);
    }

    private String localizedCode(String prefix, String code) {
        if (code == null || code.isBlank()) return "";
        String key = prefix + code;
        String localized = host.message(key);
        return localized.equals(key) ? code : localized;
    }

    private DesktopUiNode toolsPage(Map<String, Runnable> nextActions) {
        List<DesktopUiNode> cards = new ArrayList<>();
        cards.add(group("tools.overview", "gui.tools.card.overview.title",
                column("tools.overview.content",
                        raw("tools.backend", host.message("gui.tools.backend-status", backendMessage()), TextStyle.BODY),
                        raw("tools.exclusive", host.message("gui.tools.exclusive-tool",
                                exclusiveToolName.isBlank() ? host.message("gui.value.none") : exclusiveToolName),
                                TextStyle.BODY),
                        text("tools.overview.hint", "gui.tools.card.overview.hint", TextStyle.CAPTION))));
        cards.add(group("tools.image-classifier", "gui.tools.card.image-classifier.title",
                column("tools.image-classifier.summary",
                        text("tools.image-classifier.description", "gui.tools.card.image-classifier.description",
                                TextStyle.CAPTION),
                        row("tools.image-classifier.actions",
                                button("tools.image-classifier.open", "tools.image-classifier.open",
                                        "gui.tools.action.open-image-classifier", !busy, nextActions,
                                        () -> openToolDialog(ToolDialog.IMAGE_CLASSIFIER))))));
        cards.add(group("tools.folder-checker", "gui.tools.card.folder-checker.title",
                column("tools.folder-checker.summary",
                        text("tools.folder-checker.description", "gui.tools.card.folder-checker.description",
                                TextStyle.CAPTION),
                        row("tools.folder-checker.actions",
                                button("tools.folder-checker.open", "tools.folder-checker.open",
                                        "gui.tools.action.open-folder-checker", !busy, nextActions,
                                        this::openFolderCheckerDialog)))));
        cards.add(group("tools.backfill", "gui.tools.card.backfill.title",
                column("tools.backfill.content",
                        new DesktopUiNode.Form("tools.backfill.form", DesktopUiNode.FormStyle.COMPACT,
                                null, List.of(
                                new DesktopUiNode.FormRow("tools.backfill.db.row",
                                        key("gui.tools.form.database-path"), null,
                                        input("tools.backfill.db", "tools.backfill.db",
                                                "gui.tools.form.database-path", null, InputKind.FILE,
                                                form("tools.backfill.db",
                                                        host.resolveDatabasePath(rootFolder).toString()), !busy), null),
                                new DesktopUiNode.FormRow("tools.backfill.proxy.row",
                                        key("gui.tools.form.proxy"), null,
                                        new DesktopUiNode.Dock("tools.backfill.proxy.controls", 8,
                                                null,
                                                input("tools.backfill.proxy-host", "tools.backfill.proxy-host",
                                                        "gui.tools.form.proxy-host", null, InputKind.TEXT,
                                                        form("tools.backfill.proxy-host", host.defaultProxyHost()),
                                                        !busy && boolForm("tools.backfill.proxy", true)),
                                                null,
                                                row("tools.backfill.proxy.start",
                                                toggle("tools.backfill.proxy", "tools.backfill.proxy",
                                                        "gui.tools.form.use-proxy",
                                                        boolForm("tools.backfill.proxy", true), !busy),
                                                text("tools.backfill.proxy-host.label",
                                                        "gui.tools.form.proxy-host", TextStyle.BODY)),
                                                row("tools.backfill.proxy.end",
                                                text("tools.backfill.proxy-port.label",
                                                        "gui.tools.form.proxy-port", TextStyle.BODY),
                                                number("tools.backfill.proxy-port", "tools.backfill.proxy-port",
                                                        "gui.tools.form.proxy-port", null,
                                                        intForm("tools.backfill.proxy-port", host.defaultProxyPort()),
                                                        1, 65_535,
                                                        !busy && boolForm("tools.backfill.proxy", true)))), null),
                                new DesktopUiNode.FormRow("tools.backfill.delay.row",
                                        key("gui.tools.form.delay-ms"), null,
                                        number("tools.backfill.delay", "tools.backfill.delay",
                                                "gui.tools.form.delay-ms", null,
                                                intForm("tools.backfill.delay", 1000), 0,
                                                Integer.MAX_VALUE, !busy),
                                        new DesktopUiNode.Text("tools.backfill.limit-hint",
                                                key("gui.tools.form.limit-hint"), TextStyle.CAPTION,
                                                false, false)),
                                new DesktopUiNode.FormRow("tools.backfill.limit.row",
                                        key("gui.tools.form.limit"), null,
                                        number("tools.backfill.limit", "tools.backfill.limit",
                                                "gui.tools.form.limit", null,
                                                intForm("tools.backfill.limit", 0), 0,
                                                Integer.MAX_VALUE, !busy),
                                        toggle("tools.backfill.dry", "tools.backfill.dry",
                                                "gui.tools.form.dry-run",
                                                boolForm("tools.backfill.dry", false), !busy)))),
                        status("tools.backfill.notice", backfillNotice.isBlank()
                                ? host.message("gui.tools.backfill.status.idle") : backfillNotice),
                        row("tools.backfill.actions",
                                button("tools.backfill.run", "tools.backfill.run", "gui.tools.action.start-backfill",
                                        !busy, nextActions, this::runBackfill),
                                button("tools.backfill.log", "tools.backfill.log", "gui.tools.action.open-log-page",
                                        !busy && Files.isRegularFile(Path.of("log", "html",
                                                "artworks-backfill-latest.html")),
                                        nextActions, () -> openToolLog("artworks-backfill"))))));
        cards.add(group("tools.migration", "gui.tools.card.migration.title",
                column("tools.migration.content",
                        new DesktopUiNode.Form("tools.migration.form", DesktopUiNode.FormStyle.COMPACT,
                                null, List.of(
                                new DesktopUiNode.FormRow("tools.migration.db.row",
                                        key("gui.tools.form.database-path"), null,
                                        input("tools.migration.db", "tools.migration.db",
                                                "gui.tools.form.database-path", null, InputKind.FILE,
                                                form("tools.migration.db",
                                                        host.resolveDatabasePath(rootFolder).toString()), !busy), null),
                                new DesktopUiNode.FormRow("tools.migration.root.row",
                                        key("gui.tools.form.root-folder"), null,
                                        input("tools.migration.root", "tools.migration.root",
                                                "gui.tools.form.root-folder", null, InputKind.DIRECTORY,
                                                form("tools.migration.root", rootFolder), !busy), null))),
                        text("tools.migration.description", "gui.tools.card.migration.description", TextStyle.CAPTION),
                        status("tools.migration.notice", migrationNotice.isBlank()
                                ? host.message("gui.tools.migration.status.idle") : migrationNotice),
                        row("tools.migration.actions",
                                button("tools.migration.run", "tools.migration.run", "gui.tools.action.start-migration",
                                        !busy, nextActions, this::runMigration),
                                button("tools.migration.log", "tools.migration.log",
                                        "gui.tools.action.open-migration-log-page",
                                        !busy && Files.isRegularFile(Path.of("log", "html",
                                                "json-to-sqlite-migration-latest.html")), nextActions,
                                        () -> openToolLog("json-to-sqlite-migration"))))));
        return scroll("tools.scroll", column("tools.root", cards));
    }

    private DesktopUiNode securityPage(Map<String, Runnable> nextActions) {
        TextStyle noticeStyle = securityNotice.key().contains(".error.")
                || securityNotice.key().contains(".validation.") ? TextStyle.ERROR
                : securityNotice.key().endsWith(".success") ? TextStyle.SUCCESS : TextStyle.CAPTION;
        DesktopUiNode form = new DesktopUiNode.Form("security.form", DesktopUiNode.FormStyle.COMPACT,
                null, List.of(
                new DesktopUiNode.FormRow("security.current.row", key("gui.security.field.current-password"),
                        null, passwordInput("security.current.input", "security.current",
                        "gui.security.field.current-password", !busy), null),
                new DesktopUiNode.FormRow("security.new.row", key("gui.security.field.new-password"),
                        null, passwordInput("security.new.input", "security.new",
                        "gui.security.field.new-password", !busy), null),
                new DesktopUiNode.FormRow("security.confirm.row", key("gui.security.field.confirm-password"),
                        null, passwordInput("security.confirm.input", "security.confirm",
                        "gui.security.field.confirm-password", !busy), null)));
        DesktopUiNode bottom = column("security.bottom",
                text("security.description", "gui.security.card.change-password.description", TextStyle.CAPTION),
                new DesktopUiNode.Text("security.notice", securityNotice, noticeStyle, true, false),
                row("security.actions",
                        button("security.submit", "security.submit", "gui.security.action.submit", !busy,
                                nextActions, this::changePassword)));
        return scroll("security.scroll", column("security.root",
                group("security.card", "gui.security.card.change-password.title",
                        column("security.card.layout", form, bottom))));
    }

    private DesktopUiNode aboutPage(Map<String, Runnable> nextActions) {
        List<DesktopUiNode> header = new ArrayList<>();
        applicationIcon.ifPresent(icon -> header.add(new DesktopUiNode.Image(
                "about.icon", icon, key("desktop.ui.about.icon-alt"), 48, 48,
                DesktopUiNode.ScaleMode.FIT)));
        header.add(alignedRaw("about.name", host.applicationName(), TextStyle.TITLE,
                DesktopUiNode.TextAlignment.CENTER));
        String version = host.applicationVersion().isBlank()
                ? host.message("app.version.unknown") : host.applicationVersion();
        header.add(alignedText("about.description", "gui.about.description", TextStyle.BODY,
                DesktopUiNode.TextAlignment.CENTER));
        String projectAction = "about.project.open";
        nextActions.put(projectAction, () -> openUri(host.projectUrl()));
        header.add(new DesktopUiNode.Link("about.project", projectAction,
                TextToken.raw(host.projectUrl()), null, true));
        header.add(new DesktopUiNode.Container("about.metadata", ContainerLayout.FLOW, 1, 12,
                Alignment.CENTER, List.of(
                        new DesktopUiNode.Text("about.version", appToken("gui.about.version", version),
                                TextStyle.CAPTION, false, false, DesktopUiNode.TextAlignment.CENTER),
                        alignedText("about.license.badge", "gui.about.license.badge", TextStyle.SUCCESS,
                                DesktopUiNode.TextAlignment.CENTER),
                        new DesktopUiNode.Text("about.tech",
                                appToken("gui.about.tech", AppVersion.getKotlinVersionOrDefault("--")),
                                TextStyle.CAPTION, false, false, DesktopUiNode.TextAlignment.CENTER))));

        DesktopUiNode summary = column("about.summary",
                new DesktopUiNode.Surface("about.header", DesktopUiNode.SurfaceStyle.PLAIN,
                        new DesktopUiNode.Insets(0, 0, 8, 0), true,
                        new DesktopUiNode.Container("about.header.content", ContainerLayout.COLUMN,
                                1, 6, Alignment.CENTER, header)),
                group("about.disclaimer", "gui.about.disclaimer.title",
                        text("about.disclaimer.text", "gui.about.disclaimer.text", TextStyle.BODY)),
                alignedText("about.license.title", "gui.about.license.title", TextStyle.EMPHASIS,
                        DesktopUiNode.TextAlignment.CENTER));
        return new DesktopUiNode.Dock("about.root", 8, summary,
                scroll("about.license.scroll", new DesktopUiNode.Text(
                        "about.license.text", TextToken.raw(licenseText), TextStyle.CODE,
                        true, true)),
                null, null, null);
    }

    private DesktopUiNode interfaceSettings() {
        List<DesktopUiNode.Option> locales = new ArrayList<>();
        locales.add(new DesktopUiNode.Option("follow-system", key("gui.interface.language.option.follow-system"), true));
        host.visibleLocales().forEach(locale -> locales.add(new DesktopUiNode.Option(
                safeId(locale.tag()), TextToken.raw(locale.nativeName()), true)));

        List<DesktopUiNode.Option> providers = new ArrayList<>();
        for (DesktopUiPluginSource source : currentSources().stream()
                .sorted(Comparator.comparing(DesktopUiPluginSource::id)).toList()) {
            try {
                if (source.plugin() instanceof DesktopUiProvider && validId(source.id())) {
                    providers.add(new DesktopUiNode.Option(source.id(), pluginToken(source,
                            source.plugin().displayName(), source.id()), true));
                }
            } catch (RuntimeException ignored) {
                // One invalid optional provider does not remove the settings page.
            }
        }

        Locale locale = Locale.getDefault();
        Map<String, String> themeNames = new LinkedHashMap<>();
        for (DesktopUiPluginSource source : currentSources()) {
            try {
                source.plugin().guiThemes().stream().filter(Objects::nonNull)
                        .filter(theme -> validId(theme.themeId()))
                        .forEach(theme -> themeNames.putIfAbsent(theme.themeId(), theme.displayName(locale)));
            } catch (RuntimeException ignored) {
                // One invalid optional contribution does not remove the settings page.
            }
        }
        List<DesktopUiNode.Option> themes = themeNames.entrySet().stream()
                .map(entry -> new DesktopUiNode.Option(entry.getKey(), TextToken.raw(entry.getValue()), true))
                .toList();

        List<DesktopUiNode> nodes = new ArrayList<>();
        nodes.add(formField("interface.language", key("gui.interface.language.label"),
                key("gui.interface.language.help"),
                choice("interface.language.input", "interface.language", "gui.interface.language.label",
                        null, locales, selected("app.language", "follow-system"), true),
                GuiConfigEffect.HOT_RELOAD));
        nodes.add(formField("interface.provider", key("gui.interface.provider.label"),
                key("gui.interface.provider.help"),
                choice("interface.provider.input", "interface.provider", "gui.interface.provider.label",
                        null, providers, selected("app.gui-provider", "gui-swing"), !providers.isEmpty()),
                GuiConfigEffect.PROCESS_RESTART));
        nodes.add(formField("interface.theme", key("gui.interface.theme.label"),
                key("gui.interface.theme.help"),
                choice("interface.theme.input", "interface.theme", "gui.interface.theme.label",
                        null, themes, selected("app.theme", "system"), !themes.isEmpty()),
                GuiConfigEffect.HOT_RELOAD));
        nodes.add(formField("interface.config-menu-expand-all",
                key("gui.interface.config-menu-expand-all.label"),
                key("gui.interface.config-menu-expand-all.help"),
                new DesktopUiNode.Toggle("interface.config-menu-expand-all.input",
                        "interface.config-menu-expand-all", key("gui.interface.config-menu-expand-all.label"),
                        null, ToggleStyle.CHECKBOX,
                        boolForm("interface.config-menu-expand-all",
                                Boolean.parseBoolean(selected("app.config-menu-expand-all", "false"))), true),
                GuiConfigEffect.HOT_RELOAD));
        return scroll("interface.scroll", new DesktopUiNode.Surface("interface.padding",
                DesktopUiNode.SurfaceStyle.PLAIN, DesktopUiNode.Insets.all(16), true,
                column("interface.content", nodes)));
    }

    private DesktopUiNode configFieldNode(ConfigField field, Set<FieldKey> locked,
                                          Map<String, ConfigField> nextBindings,
                                          Map<String, Consumer<List<String>>> nextSelections,
                                          Map<String, Runnable> nextActions) {
        String binding = bindingId(field.key());
        GuiConfigFieldContribution spec = field.spec();
        String value = values.getOrDefault(field.key(), spec.defaultValue());
        TextToken label = token(field.namespace(), spec.labelKey(), spec.key());
        TextToken help = optionalToken(field.namespace(), spec.helpKey());
        boolean enabled = enabled(field) && !locked.contains(field.key());
        String nodeId = binding + ".input";
        DesktopUiNode node = switch (spec.type()) {
            case BOOL -> new DesktopUiNode.Toggle(nodeId, binding, label, help,
                    ToggleStyle.CHECKBOX, Boolean.parseBoolean(value), enabled);
            case INT, PORT -> new DesktopUiNode.TextInput(nodeId, binding, label, null,
                    InputKind.NUMBER, value, 12, 1, enabled);
            case ENUM -> {
                List<DesktopUiNode.Option> options = new ArrayList<>();
                for (int index = 0; index < spec.enumValues().size(); index++) {
                    String option = spec.enumValues().get(index);
                    options.add(new DesktopUiNode.Option("option." + index, enumToken(field, option), true));
                }
                int selectedIndex = spec.enumValues().indexOf(value);
                nextSelections.put(binding, selectedIds -> {
                    String selectedId = first(selectedIds);
                    int index = selectedId.startsWith("option.")
                            ? parseInt(selectedId.substring("option.".length()), -1) : -1;
                    if (index >= 0 && index < spec.enumValues().size()) {
                        values.put(field.key(), spec.enumValues().get(index));
                        if (field.affectsConditions()) rebuild();
                    }
                });
                yield new DesktopUiNode.Choice(nodeId, binding, label, help,
                        ChoiceStyle.COMBO_BOX, SelectionMode.SINGLE, options,
                        selectedIndex < 0 ? List.of() : List.of("option." + selectedIndex), enabled);
            }
            case PATH_DIR, PATH_FILE, STRING, PASSWORD -> new DesktopUiNode.TextInput(
                    nodeId, binding, label, help, switch (spec.type()) {
                        case PATH_DIR -> InputKind.DIRECTORY;
                        case PATH_FILE -> InputKind.FILE;
                        case PASSWORD -> InputKind.PASSWORD;
                        default -> InputKind.TEXT;
                    }, spec.sensitive() ? "" : value, 32, 1, enabled);
        };
        if (spec.type() != GuiConfigFieldType.ENUM) nextBindings.put(binding, field);
        if (spec.sensitive() && field.owner() != null) {
            String actionId = binding + ".clear";
            node = new DesktopUiNode.Dock(binding + ".credential", 4, null, node,
                    text(binding + ".credential-status", storedCredentialFields.contains(field.key())
                                    ? "gui.credential.status.saved" : "gui.credential.status.not-saved",
                            TextStyle.CAPTION), null,
                    button(binding + ".clear.button", actionId, "desktop.ui.config.clear-secret", !busy,
                            nextActions, () -> clearCredential(field)));
        }
        return formField(binding, label, help, node, spec.effect());
    }

    private static DesktopUiNode.Form formField(String id, TextToken label, TextToken help,
                                                DesktopUiNode field, GuiConfigEffect effect) {
        return new DesktopUiNode.Form(id + ".form", DesktopUiNode.FormStyle.RESPONSIVE,
                key("gui.punctuation.colon"), List.of(new DesktopUiNode.FormRow(
                id + ".row", label, help, field, effectNode(id, effect))));
    }

    private static DesktopUiNode.Text effectNode(String id, GuiConfigEffect effect) {
        String key = switch (effect) {
            case HOT_RELOAD -> "gui.label.hot-reload";
            case BACKEND_RESTART -> "gui.label.restart-required";
            case PROCESS_RESTART -> "gui.label.process-restart-required";
        };
        TextStyle style = switch (effect) {
            case HOT_RELOAD -> TextStyle.SUCCESS;
            case BACKEND_RESTART -> TextStyle.WARNING;
            case PROCESS_RESTART -> TextStyle.ERROR;
        };
        return new DesktopUiNode.Text(id + ".effect", key(key), style, false, false);
    }

    private ConfigField field(FieldKey key) {
        return configFields.stream().filter(candidate -> candidate.key().equals(key)).findFirst().orElse(null);
    }

    private Set<FieldKey> lockedFields() {
        Set<FieldKey> locked = new LinkedHashSet<>();
        for (ConfigSection section : configSections) {
            Map<String, List<ConfigPreset>> groups = new LinkedHashMap<>();
            for (ConfigPreset preset : section.presets()) {
                String card = section.layout()
                        == GuiConfigSectionLayout.CARD_SWITCHER
                        ? nullToEmpty(preset.cardId()) : "";
                groups.computeIfAbsent(card, ignored -> new ArrayList<>()).add(preset);
            }
            for (List<ConfigPreset> presets : groups.values()) {
                ConfigPreset selected = selectedPreset(presets);
                if (selected != null) {
                    selected.spec().lockedFieldKeys().stream()
                            .map(key -> new FieldKey(selected.owner(), key)).forEach(locked::add);
                }
            }
        }
        return Set.copyOf(locked);
    }

    private ConfigPreset selectedPreset(List<ConfigPreset> presets) {
        ConfigPreset fallback = null;
        for (ConfigPreset preset : presets) {
            if (preset.spec().values().isEmpty() && fallback == null) fallback = preset;
            if (preset.spec().matchFieldKey() == null) continue;
            String actual = values.getOrDefault(
                    new FieldKey(preset.owner(), preset.spec().matchFieldKey()), "");
            String expected = preset.spec().matchValue();
            boolean matches = switch (preset.spec().matchMode()) {
                case EQUALS_IGNORE_CASE -> actual.equalsIgnoreCase(expected);
                case TRIMMED_TRAILING_SLASH_IGNORE_CASE ->
                        trimTrailingSlashes(actual).equalsIgnoreCase(trimTrailingSlashes(expected));
            };
            if (matches) return preset;
        }
        return fallback;
    }

    private void applyPreset(ConfigPreset preset) {
        preset.spec().values().forEach((key, value) -> values.put(new FieldKey(preset.owner(), key), value));
        configNotice = "";
        configNoticeToken = null;
        rebuild();
    }

    private void runConfigAction(ConfigAction action) {
        configNotice = "";
        configNoticeToken = action.sendingNotice() == null
                ? appToken("gui.config.action.notice.sending", action.spec().actionId())
                : action.sendingNotice().token();
        runBusy(() -> {
            try {
                Map<String, Object> payload = actionPayload(action);
                DesktopUiHost.GuiResponse response = host.guiPostJson(action.spec().endpoint(), payload,
                        action.readTimeoutMillis(), action.owner());
                configNoticeToken = actionNotice(action, response);
            } catch (Exception failure) {
                configNoticeToken = appToken("gui.config.action.notice.failed",
                        action.spec().actionId(), safeMessage(failure));
            }
        });
    }

    private Map<String, Object> actionPayload(ConfigAction action) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        Map<String, String> credentials = null;
        for (GuiConfigActionPayloadField mapping : action.spec().payloadFields()) {
            String value;
            if (mapping.fieldKey() == null) {
                value = mapping.literalValue();
            } else {
                FieldKey key = new FieldKey(action.owner(), mapping.fieldKey());
                ConfigField field = field(key);
                value = values.getOrDefault(key, "");
                if (field != null && field.spec().sensitive() && value.isBlank()) {
                    if (credentials == null) credentials = host.readCredentials(action.owner());
                    value = credentials.getOrDefault(mapping.fieldKey(), "");
                }
            }
            putPayload(payload, mapping.payloadPath(), value, mapping.valueType());
        }
        return payload;
    }

    @SuppressWarnings("unchecked")
    private static void putPayload(Map<String, Object> root, String path, String value,
                                   GuiConfigActionPayloadType type) {
        String[] parts = path.split("\\.");
        Map<String, Object> current = root;
        for (int i = 0; i < parts.length - 1; i++) {
            Object child = current.computeIfAbsent(parts[i], ignored -> new LinkedHashMap<String, Object>());
            if (!(child instanceof Map<?, ?>)) throw new IllegalArgumentException("Conflicting action payload path");
            current = (Map<String, Object>) child;
        }
        String leaf = parts[parts.length - 1];
        Object converted = switch (type) {
            case INT -> parseInt(value, 0);
            case BOOLEAN -> Boolean.parseBoolean(value);
            case STRING -> nullToEmpty(value);
        };
        if (current.putIfAbsent(leaf, converted) != null) {
            throw new IllegalArgumentException("Duplicate action payload path");
        }
    }

    private TextToken actionNotice(ConfigAction action, DesktopUiHost.GuiResponse response) {
        ActionResult result = ActionResult.from(response, action.spec().resultSummary());
        for (GuiConfigActionResultRule rule : action.spec().resultRules().stream()
                .sorted(Comparator.comparingInt(GuiConfigActionResultRule::order)).toList()) {
            if (rule.conditions().stream().allMatch(condition -> matches(result, condition))) {
                List<String> arguments = rule.arguments().stream()
                        .map(argument -> argumentValue(result, argument)).toList();
                String namespace = rule.i18nNamespace() == null ? action.namespace() : rule.i18nNamespace();
                return token(namespace, rule.noticeKey(), rule.noticeKey(), arguments);
            }
        }
        if (!response.reachable()) {
            return appToken("gui.config.action.notice.unreachable", action.spec().actionId());
        }
        if (response.is2xx()) {
            return appToken("gui.config.action.notice.success", action.spec().actionId());
        }
        return appToken("gui.config.action.notice.failed", action.spec().actionId(), "HTTP " + response.status());
    }

    private static boolean matches(ActionResult result, GuiConfigActionResultCondition condition) {
        String actual = result.value(condition.source(), condition.path());
        return switch (condition.operator()) {
            case TRUE -> Boolean.parseBoolean(actual);
            case FALSE -> !Boolean.parseBoolean(actual);
            case EQUALS -> actual.equals(condition.value());
            case NOT_EQUALS -> !actual.equals(condition.value());
            case GREATER_THAN -> parseInt(actual, 0) > parseInt(condition.value(), 0);
            case CONTAINS -> actual.contains(condition.value());
            case BLANK -> actual.isBlank();
            case NOT_BLANK -> !actual.isBlank();
        };
    }

    private static String argumentValue(ActionResult result, GuiConfigActionResultArgument argument) {
        String value = result.value(argument.source(), argument.path());
        return value.isBlank() ? sanitizeActionText(argument.defaultValue()) : value;
    }

    private static boolean validOnboardingStep(GuiOnboardingStepContribution step) {
        return validId(step.stepId()) && validId(step.i18nNamespace())
                && validId(step.titleKey()) && validId(step.bodyKey())
                && validId(step.actionLabelKey()) && safeHref(step.actionHref())
                && validId(step.waitingKey()) && validId(step.completionKey())
                && step.bulletKeys().stream().allMatch(AppDesktopUiModel::validId);
    }

    private List<NavigationContribution> webEntries(String placement) {
        List<NavigationContribution> entries = new ArrayList<>();
        for (DesktopUiPluginSource source : currentSources()) {
            try {
                for (NavigationContribution contribution : source.plugin().navigation()) {
                    if (contribution != null
                            && contribution.placements().contains(placement)
                            && contribution.visibleTo() != null && contribution.visibleTo().supportsUiVisibility()
                            && safeHref(contribution.href())) {
                        entries.add(contribution);
                    }
                }
            } catch (RuntimeException ignored) {
                // Optional plugin entry is isolated.
            }
        }
        entries.sort(Comparator.comparingInt(NavigationContribution::priority)
                .thenComparing(NavigationContribution::id));
        return entries;
    }

    private List<DesktopUiNode> webEntryButtons(String placement, String base,
                                                Map<String, Runnable> nextActions) {
        List<DesktopUiNode> nodes = new ArrayList<>();
        int index = 0;
        for (NavigationContribution entry : webEntries(placement)) {
            String id = base + "." + index++;
            String action = id + ".open";
            nextActions.put(action, () -> openWeb(entry.href()));
            nodes.add(new DesktopUiNode.Button(id, action,
                    token(entry.labelNamespace(), entry.labelI18nKey(), entry.id()),
                    null, ButtonStyle.NORMAL, true));
        }
        return nodes;
    }

    private DesktopUiDocument.Tray tray(Map<String, Runnable> nextActions) {
        List<DesktopUiDocument.TrayItem> items = new ArrayList<>();
        items.add(DesktopUiDocument.TrayItem.activate(
                "tray.show", key("gui.tray.menu.show-main-window")));
        items.add(DesktopUiDocument.TrayItem.separator("tray.separator.actions"));
        nextActions.put("tray.batch.open", () -> openWeb("/pixiv-batch.html"));
        items.add(DesktopUiDocument.TrayItem.dispatch(
                "tray.batch", key("gui.action.open-batch"), "tray.batch.open"));
        int index = 0;
        for (NavigationContribution entry : webEntries(NavigationPlacements.GUI_TRAY_ACTIONS)) {
            String id = "tray.web." + index++;
            String action = id + ".open";
            nextActions.put(action, () -> openWeb(entry.href()));
            items.add(DesktopUiDocument.TrayItem.dispatch(id,
                    token(entry.labelNamespace(), entry.labelI18nKey(), entry.id()), action));
        }
        nextActions.put("tray.download-folder.open", this::openDownloadDirectory);
        items.add(DesktopUiDocument.TrayItem.dispatch("tray.download-folder",
                key("gui.action.open-download-directory"), "tray.download-folder.open"));
        items.add(DesktopUiDocument.TrayItem.separator("tray.separator.exit"));
        nextActions.put("tray.exit", host::requestApplicationExit);
        items.add(DesktopUiDocument.TrayItem.dispatch(
                "tray.exit", key("gui.action.exit"), "tray.exit"));
        return new DesktopUiDocument.Tray(TextToken.raw(host.applicationName()), items);
    }

    private void submitSetup() {
        String username = form("welcome.username", "").trim();
        String password = form("welcome.password", "");
        if (username.isBlank()) {
            welcomeNotice = host.message("gui.welcome.config.invalid.username");
            rebuild();
            return;
        }
        if (password.length() < host.minimumPasswordLength()) {
            welcomeNotice = host.message("gui.welcome.config.invalid.password");
            rebuild();
            return;
        }
        if (password.length() < host.recommendedPasswordLength() && !weakPasswordConfirmationPending) {
            weakPasswordConfirmationPending = true;
            welcomeNotice = host.message("gui.welcome.config.password-warning.message");
            rebuild();
            return;
        }
        weakPasswordConfirmationPending = false;
        welcomeNotice = host.message("gui.welcome.config.submitting");
        runBusy(() -> {
            DesktopUiHost.GuiResponse response = host.guiPostJson("setup/init",
                    Map.of("username", username, "password", password, "mode", "solo"), 5_000);
            if (response.is2xx()) {
                formValues.remove("welcome.password");
                welcomeFormRevision++;
                goWelcomeStep(3);
            } else {
                welcomeNotice = host.message("gui.welcome.config.failed", responseDetail(response));
            }
        });
    }

    private void saveWelcomeProxy() {
        String hostValue = form("welcome.proxy.host", "").trim();
        int port = intForm("welcome.proxy.port", 0);
        boolean enabled = boolForm("welcome.proxy.enabled", true);
        if (enabled && hostValue.isBlank()) {
            welcomeNotice = host.message("gui.welcome.proxy.invalid.host");
            rebuild();
            return;
        }
        if (enabled && (port < 1 || port > 65_535)) {
            welcomeNotice = host.message("gui.welcome.proxy.invalid.port");
            rebuild();
            return;
        }
        if (!enabled && hostValue.isBlank()) hostValue = host.defaultProxyHost();
        if (!enabled && (port < 1 || port > 65_535)) port = host.defaultProxyPort();
        String savedHost = hostValue;
        int savedPort = port;
        runBusy(() -> {
            try {
                host.applicationConfig().writeAll(Map.of(
                        "proxy.enabled", Boolean.toString(enabled),
                        "proxy.host", savedHost,
                        "proxy.port", Integer.toString(savedPort)));
                host.markOnboardingProxyConfigured();
                host.guiPostJson("config/reload", Map.of("changedKeys",
                        List.of("proxy.enabled", "proxy.host", "proxy.port")), 5_000);
                goWelcomeStep(4);
            } catch (Exception failure) {
                welcomeNotice = host.message("gui.welcome.proxy.failed", safeMessage(failure));
            }
        });
    }

    private void finishOnboarding() {
        if (!host.onboardingState(rootFolder).setupComplete()) {
            welcomeNotice = host.message("gui.welcome.config.waiting");
            welcomeStep = 2;
            rebuild();
            return;
        }
        host.markOnboardingSeen();
        host.markOnboardingFinished();
        rebuild();
    }

    private void refreshStatus() {
        runBusy(() -> {
            refreshStatusSnapshot();
            refreshOnboardingState();
            loadPluginStatus();
        });
    }

    private void startStatusPolling() {
        executeAsync(() -> {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    java.util.concurrent.TimeUnit.SECONDS.sleep(3L);
                    refreshStatusSnapshot();
                    if (backend.state() == DesktopUiHost.BackendState.RUNNING
                            && System.currentTimeMillis() - lastConnectivityCheckAt >= 60_000L) {
                        checkConnectivity();
                    }
                    rebuild();
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
    }

    private void refreshStatusSnapshot() {
        DesktopUiHost.GuiResponse response = host.guiGet("status", 2_000);
        statusConnected = response.successful() && response.body() != null;
        if (statusConnected) {
            DesktopUiHost.GuiValue body = response.body();
            statusPort = body.path("port").asText(Integer.toString(serverPort));
            statusMode = localizedCode("gui.mode.", body.path("mode").asText("--"));
            statusStartTime = body.path("startTime").asText("--");
            statusProtocol = host.message(body.path("httpsEnabled").asBoolean(false)
                    ? "gui.status.https.enabled" : "gui.status.https.disabled");
        } else {
            statusPort = Integer.toString(serverPort);
            statusMode = statusStartTime = statusProtocol = "--";
        }
    }

    private void refreshOnboardingState() {
        DesktopUiHost.GuiResponse response = host.guiGet("onboarding", 2_000);
        if (!response.is2xx() || response.body() == null) return;
        onboardingBatchVisited |= response.body().path("batchVisited").asBoolean(false);
        Set<String> completed = new LinkedHashSet<>();
        for (DesktopUiHost.GuiValue value : response.body().path("completedSteps")) {
            if (value != null && value.isTextual() && !value.asText().isBlank()) completed.add(value.asText().trim());
        }
        completedOnboardingSteps = Set.copyOf(completed);
        int next = welcomeStep;
        if (next == 1 && backend.state() == DesktopUiHost.BackendState.RUNNING) next = 2;
        if (next == 4 && onboardingBatchVisited) next = onboardingPluginStep().isPresent() ? 5 : 6;
        Optional<PluginOnboardingStep> pluginStep = onboardingPluginStep();
        if (next == 5 && pluginStep.isPresent()
                && completedOnboardingSteps.contains(pluginStep.orElseThrow().step().completionKey())) {
            host.markOnboardingSeen();
            next = 6;
        }
        if (next != welcomeStep) {
            welcomeStep = next;
            host.saveOnboardingProgress(next);
        }
    }

    private void checkConnectivity() {
        if (connectivityChecking || backend.state() != DesktopUiHost.BackendState.RUNNING) return;
        connectivityChecking = true;
        lastConnectivityCheckAt = System.currentTimeMillis();
        connectivityDetails = host.message("gui.status.pixiv-connectivity.checking");
        rebuild();
        executeAsync(() -> {
            try {
                DesktopUiHost.GuiResponse response = host.guiGet("pixiv-connectivity", 10_000);
                if (!response.reachable() || response.body() == null) {
                    connectivityDetails = host.message("gui.status.pixiv-connectivity.unavailable");
                } else {
                    DesktopUiHost.GuiValue body = response.body();
                    boolean reachable = body.path("reachable").asBoolean(false);
                    int status = body.path("statusCode").asInt(0);
                    long latency = body.path("latencyMs").asLong(0);
                    connectivityDetails = reachable
                            ? host.message(status > 0 ? "gui.status.pixiv-connectivity.reachable"
                                            : "gui.status.pixiv-connectivity.reachable-no-status",
                                    status > 0 ? new Object[]{status, latency} : new Object[]{latency})
                            : host.message(status > 0 ? "gui.status.pixiv-connectivity.unreachable-with-status"
                                            : "gui.status.pixiv-connectivity.unreachable",
                                    status > 0 ? new Object[]{status, latency}
                                            : new Object[]{connectivityReason(body.path("errorType").asText(""))});
                }
            } catch (RuntimeException failure) {
                LOG.warn("Pixiv connectivity check failed", failure);
                connectivityDetails = host.message("gui.status.pixiv-connectivity.unavailable");
            } finally {
                connectivityChecking = false;
                rebuild();
            }
        });
    }

    private String connectivityReason(String errorType) {
        return host.message("gui.status.pixiv-connectivity.reason." + switch (nullToEmpty(errorType)) {
            case "timeout", "interrupted", "network" -> errorType;
            default -> "unknown";
        });
    }

    private void requestFfmpegInstall() {
        if (!host.supportsManagedFfmpegInstall()) {
            showDialog("ffmpeg.unsupported", "gui.dialog.info.title",
                    "gui.ffmpeg.dialog.unsupported.message", DesktopUiDocument.DialogStyle.INFO);
            rebuild();
            return;
        }
        showDialog("ffmpeg.confirm", "gui.ffmpeg.dialog.install.title",
                DesktopUiDocument.DialogStyle.QUESTION,
                (nextActions, dismissAction, dismiss) -> column("ffmpeg.confirm.content",
                        text("ffmpeg.confirm.message", "gui.ffmpeg.dialog.install.confirm.message",
                                TextStyle.BODY),
                        row("ffmpeg.confirm.actions",
                                button("ffmpeg.confirm.install", "ffmpeg.confirm.install",
                                        "gui.ffmpeg.action.download", true, nextActions, () -> {
                                            dialogState = null;
                                            installFfmpeg();
                                        }),
                                button("ffmpeg.confirm.cancel", dismissAction,
                                        "desktop.ui.action.cancel", true, nextActions, dismiss))), 520, 0);
    }

    private void installFfmpeg() {
        if (busy) return;
        ffmpegInstalling = true;
        ffmpegProgress = 0d;
        runBusy(() -> {
            try {
                DesktopUiHost.FfmpegProxy proxy = proxySettings();
                DesktopUiHost.FfmpegInstallation installed = host.installManagedFfmpeg(proxy,
                        (stage, current, total) -> {
                            statusNotice = host.message("gui.ffmpeg.install.stage."
                                    + stage.name().toLowerCase(Locale.ROOT));
                            ffmpegProgress = total > 0 ? Math.min(1d, (double) current / total) : 0d;
                            rebuild();
                        });
                statusNotice = "";
                showDialog("ffmpeg.success", "gui.ffmpeg.dialog.install-success.title",
                        appToken("gui.ffmpeg.dialog.install-success.message",
                                localizedCode("ffmpeg.source.", installed.source().name()
                                        .toLowerCase(Locale.ROOT)), installed.ffmpegPath()),
                        DesktopUiDocument.DialogStyle.SUCCESS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                LOG.error("Managed FFmpeg installation was interrupted", interrupted);
                showDialog("ffmpeg.failed", "gui.ffmpeg.dialog.install-failed.title",
                        "desktop.ui.ffmpeg.install-failed", DesktopUiDocument.DialogStyle.ERROR);
            } catch (Exception failure) {
                LOG.error("Managed FFmpeg installation failed", failure);
                showDialog("ffmpeg.failed", "gui.ffmpeg.dialog.install-failed.title",
                        "desktop.ui.ffmpeg.install-failed", DesktopUiDocument.DialogStyle.ERROR);
            } finally {
                ffmpegInstalling = false;
            }
        });
    }

    private void openDownloadDirectory() {
        runBusy(() -> {
            try {
                Path directory = Path.of(rootFolder).toAbsolutePath().normalize();
                if (!Files.isDirectory(directory)) {
                    showDialog("download-folder.missing", "gui.dialog.info.title",
                            appToken("gui.status.dialog.download-folder-missing", directory),
                            DesktopUiDocument.DialogStyle.WARNING);
                    return;
                }
                host.openLocalPath(directory);
            } catch (Exception failure) {
                LOG.warn("Unable to open the download directory", failure);
                showDialog("download-folder.failed", "gui.dialog.error.title",
                        "desktop.ui.action.failed", DesktopUiDocument.DialogStyle.ERROR);
            }
        });
    }

    private void requestBackendRestart() {
        showDialog("backend.restart", "gui.action.restart-service", DesktopUiDocument.DialogStyle.QUESTION,
                (nextActions, dismissAction, dismiss) -> column("backend.restart.content",
                        text("backend.restart.message", "gui.status.dialog.restart.confirm.message",
                                TextStyle.BODY),
                        row("backend.restart.actions",
                                button("backend.restart.confirm", "backend.restart.confirm",
                                        "gui.action.restart-service", true, nextActions, () -> {
                                            dialogState = null;
                                            runBusy(() -> statusNotice = host.restartBackend(this::refreshStatus)
                                                    ? host.message("gui.status.state.restarting")
                                                    : host.message("gui.message.backend-busy"));
                                        }),
                                button("backend.restart.cancel", dismissAction,
                                        "desktop.ui.action.cancel", true, nextActions, dismiss))), 500, 0);
    }

    private void checkUpdates() {
        runBusy(() -> {
            DesktopUiHost.GuiResponse response = host.guiGet("update/check?force=true", 30_000);
            if (!response.is2xx() || response.body() == null) {
                LOG.warn("Desktop update check failed: reachable={}, status={}",
                        response.reachable(), response.status());
                showDialog("update.check-failed", "gui.dialog.error.title",
                        "gui.update.dialog.check-failed.message", DesktopUiDocument.DialogStyle.WARNING);
                return;
            }
            DesktopUiHost.GuiValue result = response.body();
            applyUpdateResult(result);
            if (!result.path("enabled").asBoolean(false)) {
                showDialog("update.disabled", "gui.dialog.info.title",
                        "gui.update.dialog.disabled.message", DesktopUiDocument.DialogStyle.INFO);
            } else if (!result.path("checkSucceeded").asBoolean(false)) {
                LOG.warn("Desktop update check rejected: {}", result.path("error").asText("unknown"));
                showDialog("update.check-failed", "gui.dialog.error.title",
                        "gui.update.dialog.check-failed.message", DesktopUiDocument.DialogStyle.WARNING);
            } else if (pendingOfficialUpdate == null && pendingNightlyUpdate == null) {
                showDialog("update.up-to-date", "gui.dialog.info.title",
                        appToken("gui.update.dialog.up-to-date.message",
                                result.path("currentVersion").asText("--")),
                        DesktopUiDocument.DialogStyle.INFO);
            }
        });
    }

    private void scheduleInitialUpdateLookup() {
        executeAsync(() -> {
            try {
                java.util.concurrent.TimeUnit.SECONDS.sleep(3L);
                for (int attempt = 0; attempt < 24; attempt++) {
                    DesktopUiHost.GuiResponse response = host.guiGet("update/last", 5_000);
                    if (response.is2xx() && response.body() != null) {
                        applyUpdateResult(response.body());
                        rebuild();
                        return;
                    }
                    java.util.concurrent.TimeUnit.MILLISECONDS.sleep(2_500L);
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
    }

    private void applyUpdateResult(DesktopUiHost.GuiValue result) {
        pendingOfficialUpdate = result.path("updateAvailable").asBoolean(false)
                ? pendingInstall(result) : null;
        DesktopUiHost.GuiValue nightly = result.path("nightlyAlternative");
        pendingNightlyUpdate = nightly.path("updateAvailable").asBoolean(false)
                ? pendingInstall(nightly) : null;
    }

    private static PendingInstall pendingInstall(DesktopUiHost.GuiValue value) {
        return new PendingInstall(value.path("assetUrl").asText(""),
                value.path("assetSizeBytes").asLong(0L), value.path("releaseNotes").asText(""),
                value.path("releaseNotesUrl").asText(""), value.path("latestVersion").asText(""));
    }

    private void dismissUpdate(boolean nightly) {
        if (nightly) pendingNightlyUpdate = null;
        else pendingOfficialUpdate = null;
        rebuild();
    }

    private void showUpdateNotes(PendingInstall update, boolean nightly) {
        String notes = update.releaseNotes().isBlank()
                ? host.message(nightly ? "gui.update.dialog.view-diff.empty"
                : "gui.update.dialog.view-log.empty") : update.releaseNotes();
        showDialog("update.notes", nightly ? "gui.update.dialog.view-diff.title"
                        : "gui.update.dialog.view-log.title", DesktopUiDocument.DialogStyle.INFO,
                (nextActions, dismissAction, dismiss) -> new DesktopUiNode.Dock("update.notes.layout", 12,
                        null, scroll("update.notes.scroll",
                        new DesktopUiNode.Text("update.notes.text", TextToken.raw(notes), TextStyle.BODY,
                                true, true)),
                        row("update.notes.actions", button("update.notes.close", dismissAction,
                                "desktop.ui.action.close", true, nextActions, dismiss)), null, null),
                640, 420);
    }

    private void requestUpdateInstall(PendingInstall update, boolean nightly) {
        if (updateInstalling) return;
        if (!host.launchedFromExecutable()) {
            showDialog("update.jar", "gui.update.dialog.install.title",
                    DesktopUiDocument.DialogStyle.QUESTION,
                    (nextActions, dismissAction, dismiss) -> column("update.jar.content",
                            new DesktopUiNode.Text("update.jar.message",
                                    appToken("gui.update.dialog.jar-launch.message", update.latestVersion()),
                                    TextStyle.BODY, true, true),
                            row("update.jar.actions",
                                    button("update.jar.open", "update.jar.open",
                                            "desktop.ui.action.open", true, nextActions, () -> {
                                                dialogState = null;
                                                openUri(update.releaseNotesUrl().isBlank()
                                                        ? host.releasesUrl() : update.releaseNotesUrl());
                                            }),
                                    button("update.jar.cancel", dismissAction, "desktop.ui.action.cancel",
                                            true, nextActions, dismiss))), 520, 0);
            return;
        }
        if (nightly) {
            persistCheckNightly(true);
            startUpdateDownload(update, true);
            return;
        }
        formValues.put("update.keep-nightly", "true");
        showDialog("update.confirm", "gui.update.dialog.install.title",
                DesktopUiDocument.DialogStyle.QUESTION,
                (nextActions, dismissAction, dismiss) -> {
                    List<DesktopUiNode> nodes = new ArrayList<>();
                    nodes.add(new DesktopUiNode.Text("update.confirm.message",
                            appToken("gui.update.dialog.install.confirm.message", update.latestVersion(),
                                    formatSize(update.size())), TextStyle.BODY, true, true));
                    if (host.currentVersionNightly()) {
                        nodes.add(toggle("update.confirm.keep-nightly", "update.keep-nightly",
                                "gui.update.dialog.install.confirm.check-nightly-checkbox",
                                boolForm("update.keep-nightly", true), true));
                    }
                    nodes.add(row("update.confirm.actions",
                            button("update.confirm.install", "update.confirm.install",
                                    "gui.update.banner.install", true, nextActions, () -> {
                                        if (host.currentVersionNightly()) {
                                            persistCheckNightly(boolForm("update.keep-nightly", true));
                                        }
                                        dialogState = null;
                                        startUpdateDownload(update, false);
                                    }),
                            button("update.confirm.cancel", dismissAction, "desktop.ui.action.cancel",
                                    true, nextActions, dismiss)));
                    return column("update.confirm.content", nodes);
                }, 520, 0);
    }

    private void startUpdateDownload(PendingInstall update, boolean nightly) {
        if (updateInstalling) return;
        dialogState = null;
        updateInstalling = true;
        downloadingNightly = nightly;
        updateReceivedBytes = 0L;
        updateTotalBytes = update.size();
        rebuild();
        executeAsync(() -> {
            try {
                DesktopUiHost.GuiResponse started = host.guiForm("POST", "update/download?channel="
                        + (nightly ? "nightly" : "official"), null, 10_000);
                if (!started.is2xx() || started.body() != null && started.body().hasNonNull("error")) {
                    throw new IllegalStateException("download start rejected: " + started.status());
                }
                while (!Thread.currentThread().isInterrupted()) {
                    java.util.concurrent.TimeUnit.SECONDS.sleep(1L);
                    DesktopUiHost.GuiResponse response = host.guiGet("update/download/progress", 5_000);
                    if (!response.is2xx() || response.body() == null) continue;
                    DesktopUiHost.GuiValue progress = response.body();
                    updateReceivedBytes = progress.path("received").asLong(updateReceivedBytes);
                    updateTotalBytes = progress.path("total").asLong(updateTotalBytes);
                    rebuild();
                    if (progress.path("failed").asBoolean(false) || progress.hasNonNull("error")) {
                        throw new IllegalStateException(progress.path("error").asText("download failed"));
                    }
                    if (progress.path("done").asBoolean(false)) break;
                }
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                DesktopUiHost.GuiResponse install = host.guiForm("POST", "update/install", "", 10_000);
                if (!install.is2xx() || install.body() != null && install.body().hasNonNull("error")) {
                    throw new IllegalStateException("installer launch rejected: " + install.status());
                }
                updateInstalling = false;
                showDialog("update.launched", "gui.dialog.info.title",
                        "gui.update.dialog.installer-launched.message", DesktopUiDocument.DialogStyle.SUCCESS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                updateInstalling = false;
            } catch (Exception failure) {
                LOG.error("Desktop update install failed", failure);
                updateInstalling = false;
                showDialog("update.failed", "gui.dialog.error.title",
                        "desktop.ui.update.download-failed", DesktopUiDocument.DialogStyle.ERROR);
            } finally {
                rebuild();
            }
        });
    }

    private void persistCheckNightly(boolean enabled) {
        try {
            host.applicationConfig().write("update.check-nightly", Boolean.toString(enabled));
        } catch (Exception failure) {
            LOG.warn("Unable to persist update.check-nightly", failure);
        }
    }

    private void openDirectoryMigration() {
        runBusy(() -> {
            DesktopUiHost.GuiResponse response = host.guiGet("path-prefixes", 10_000);
            if (!response.is2xx() || response.body() == null) {
                showDialog("migrate.unreachable", "gui.dialog.error.title",
                        "gui.migrate-dir.error.unreachable", DesktopUiDocument.DialogStyle.ERROR);
                return;
            }
            DesktopUiHost.GuiValue body = response.body();
            List<PathPrefixRow> rows = new ArrayList<>();
            for (DesktopUiHost.GuiValue prefix : body.path("prefixes")) {
                rows.add(new PathPrefixRow(prefix.path("id").asLong(), prefix.path("path").asText(""),
                        prefix.path("downloadRoot").asBoolean(false),
                        prefix.path("symbolic").asBoolean(false)));
            }
            if (rows.isEmpty()) {
                showDialog("migrate.empty", "gui.dialog.info.title", "gui.migrate-dir.empty",
                        DesktopUiDocument.DialogStyle.INFO);
                return;
            }
            formValues.keySet().removeIf(key -> key.startsWith("migrate.prefix."));
            rows.forEach(row -> formValues.put(migrationBinding(row), ""));
            pathPrefixAppRoot = body.path("appRoot").asText("");
            pathPrefixes = List.copyOf(rows);
            showDirectoryMigrationDialog();
        });
    }

    private void showDirectoryMigrationDialog() {
        showDialog("migrate.directory", "gui.migrate-dir.title", DesktopUiDocument.DialogStyle.WARNING,
                (nextActions, dismissAction, dismiss) -> {
                    List<DesktopUiNode> rows = new ArrayList<>();
                    rows.add(new DesktopUiNode.Surface("migrate.warning", DesktopUiNode.SurfaceStyle.WARNING,
                            DesktopUiNode.Insets.all(10), true,
                            text("migrate.warning.text", "gui.migrate-dir.warning", TextStyle.BODY)));
                    for (PathPrefixRow prefix : pathPrefixes) {
                        String base = "migrate.prefix." + prefix.id();
                        List<DesktopUiNode> card = new ArrayList<>();
                        if (prefix.downloadRoot()) {
                            card.add(text(base + ".root", prefix.symbolic()
                                    ? "gui.migrate-dir.symbolic-chip" : "gui.migrate-dir.root-chip",
                                    TextStyle.SUCCESS));
                        }
                        card.add(new DesktopUiNode.Text(base + ".current",
                                appToken("gui.migrate-dir.current-value", prefix.path()),
                                TextStyle.CODE, true, true));
                        card.add(input(base + ".new", migrationBinding(prefix),
                                "gui.migrate-dir.column.new", "gui.migrate-dir.new.placeholder",
                                InputKind.DIRECTORY, form(migrationBinding(prefix), ""), !busy));
                        rows.add(new DesktopUiNode.Surface(base + ".card", DesktopUiNode.SurfaceStyle.CARD,
                                DesktopUiNode.Insets.all(10), true, column(base + ".content", card)));
                    }
                    rows.add(row("migrate.actions",
                            button("migrate.apply", "migrate.apply", "desktop.ui.action.apply",
                                    !busy, nextActions, this::prepareDirectoryMigration),
                            button("migrate.cancel", dismissAction, "desktop.ui.action.cancel",
                                    !busy, nextActions, dismiss)));
                    return new DesktopUiNode.Dock("migrate.layout", 12, null,
                            scroll("migrate.scroll", column("migrate.rows", rows)), null, null, null);
                }, 700, Math.min(720, 210 + pathPrefixes.size() * 115));
    }

    private void prepareDirectoryMigration() {
        List<MigrationChange> changes = new ArrayList<>();
        PathPrefixRow rootChange = null;
        for (PathPrefixRow prefix : pathPrefixes) {
            String value = form(migrationBinding(prefix), "").trim();
            if (value.isBlank() || sameDirectory(value, prefix.path())) continue;
            changes.add(new MigrationChange(prefix.id(), value));
            if (prefix.downloadRoot()) rootChange = prefix;
        }
        if (changes.isEmpty()) {
            showDialog("migrate.no-change", "gui.dialog.info.title", "gui.migrate-dir.no-change",
                    DesktopUiDocument.DialogStyle.INFO);
            rebuild();
            return;
        }
        confirmDirectoryMigration(new MigrationPlan(List.copyOf(changes), rootChange));
    }

    private void confirmDirectoryMigration(MigrationPlan plan) {
        PathPrefixRow root = plan.root();
        if (root == null) {
            showMigrationConfirmation(appToken("gui.migrate-dir.confirm", plan.changes().size()),
                    () -> applyDirectoryMigration(plan.changes(), null, null));
            return;
        }
        String newRoot = plan.changes().stream().filter(change -> change.id() == root.id())
                .map(MigrationChange::path).findFirst().orElse("");
        if (root.symbolic()) {
            String relative = relativeToAppRoot(newRoot, pathPrefixAppRoot);
            boolean inside = relative != null;
            TextToken message = appToken(inside ? "gui.migrate-dir.symbolic-sync.inside.message"
                    : "gui.migrate-dir.symbolic-sync.outside.message", root.path(), newRoot,
                    inside ? relative : "");
            showMigrationChoice(message,
                    () -> applyDirectoryMigration(inside
                                    ? plan.changes().stream().filter(change -> change.id() != root.id()).toList()
                                    : plan.changes(),
                            inside ? relative : newRoot, null),
                    () -> applyDirectoryMigration(plan.changes(), null, null));
            return;
        }
        showMigrationChoice(appToken("gui.migrate-dir.root-sync.message", root.path(), newRoot),
                () -> applyDirectoryMigration(plan.changes(), newRoot, null),
                () -> applyDirectoryMigration(plan.changes(), null, root.path()));
    }

    private void showMigrationConfirmation(TextToken message, Runnable confirm) {
        showDialog("migrate.confirm", "gui.migrate-dir.title", DesktopUiDocument.DialogStyle.QUESTION,
                (nextActions, dismissAction, dismiss) -> column("migrate.confirm.content",
                        new DesktopUiNode.Text("migrate.confirm.message", message, TextStyle.BODY, true, true),
                        row("migrate.confirm.actions",
                                button("migrate.confirm.yes", "migrate.confirm.yes", "desktop.ui.action.confirm",
                                        true, nextActions, confirm),
                                button("migrate.confirm.no", dismissAction, "desktop.ui.action.cancel",
                                        true, nextActions, dismiss))), 560, 0);
    }

    private void showMigrationChoice(TextToken message, Runnable yes, Runnable no) {
        showDialog("migrate.root-choice", "gui.migrate-dir.title", DesktopUiDocument.DialogStyle.QUESTION,
                (nextActions, dismissAction, dismiss) -> column("migrate.root-choice.content",
                        new DesktopUiNode.Text("migrate.root-choice.message", message,
                                TextStyle.BODY, true, true),
                        row("migrate.root-choice.actions",
                                button("migrate.root-choice.yes", "migrate.root-choice.yes",
                                        "desktop.ui.action.yes", true, nextActions, yes),
                                button("migrate.root-choice.no", "migrate.root-choice.no",
                                        "desktop.ui.action.no", true, nextActions, no),
                                button("migrate.root-choice.cancel", dismissAction,
                                        "desktop.ui.action.cancel", true, nextActions, dismiss))), 620, 0);
    }

    private void applyDirectoryMigration(List<MigrationChange> changes,
                                         String rootSyncPath, String registerOldRoot) {
        dialogState = null;
        runBusy(() -> {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("updates", changes.stream().map(change -> Map.<String, Object>of(
                    "id", change.id(), "path", change.path())).toList());
            if (registerOldRoot != null && !registerOldRoot.isBlank()) {
                payload.put("registerPaths", List.of(registerOldRoot));
            }
            DesktopUiHost.GuiResponse response = host.guiPostJson("path-prefixes", payload, 15_000);
            DesktopUiHost.GuiValue body = response.body();
            if (!response.is2xx() || body == null) {
                showDialog("migrate.unreachable", "gui.dialog.error.title",
                        "gui.migrate-dir.error.unreachable", DesktopUiDocument.DialogStyle.ERROR);
                return;
            }
            if (!body.path("success").asBoolean(false)) {
                StringBuilder details = new StringBuilder();
                for (DesktopUiHost.GuiValue error : body.path("errors")) {
                    details.append("\n• ").append(migrateReason(error.path("reason").asText("")));
                }
                showDialog("migrate.failed", "gui.dialog.error.title",
                        appToken("gui.migrate-dir.failed", details), DesktopUiDocument.DialogStyle.ERROR);
                return;
            }
            int applied = body.path("applied").asInt(0);
            if (rootSyncPath == null) {
                showDialog("migrate.success", "gui.dialog.info.title",
                        appToken("gui.migrate-dir.success", applied), DesktopUiDocument.DialogStyle.SUCCESS);
                return;
            }
            try {
                host.applicationConfig().write("download.root-folder",
                        host.normalizeRootFolder(rootSyncPath));
                loadConfiguration();
            } catch (Exception failure) {
                LOG.warn("Unable to persist migrated download root", failure);
                showDialog("migrate.persist-failed", "gui.dialog.error.title",
                        appToken("gui.migrate-dir.root-sync.persist-failed", applied),
                        DesktopUiDocument.DialogStyle.ERROR);
                return;
            }
            TextToken success = appToken(applied > 0 ? "gui.migrate-dir.root-sync.success"
                    : "gui.migrate-dir.symbolic-sync.config-only.success",
                    applied > 0 ? applied : rootSyncPath);
            showDialog("migrate.restart", "gui.migrate-dir.title", DesktopUiDocument.DialogStyle.SUCCESS,
                    (nextActions, dismissAction, dismiss) -> column("migrate.restart.content",
                            new DesktopUiNode.Text("migrate.restart.message", success,
                                    TextStyle.BODY, true, true),
                            row("migrate.restart.actions",
                                    button("migrate.restart.now", "migrate.restart.now",
                                            "gui.action.restart-application", true, nextActions, () -> {
                                                dialogState = null;
                                                restartApplication();
                                            }),
                                    button("migrate.restart.later", dismissAction,
                                            "gui.action.restart-later", true, nextActions, dismiss))), 560, 0);
        });
    }

    private String migrateReason(String code) {
        return host.message("gui.migrate-dir.reason." + switch (nullToEmpty(code)) {
            case "invalid", "not-absolute", "not-exist", "not-directory", "duplicate", "conflict",
                    "unknown-id" -> code;
            default -> "unknown";
        });
    }

    private boolean sameDirectory(String first, String second) {
        return normalizeDirectory(first).equals(normalizeDirectory(second));
    }

    private String normalizeDirectory(String value) {
        return host.stripTrailingPathSeparators(nullToEmpty(value)).replace('\\', '/')
                .toLowerCase(Locale.ROOT);
    }

    private static String relativeToAppRoot(String newRoot, String appRoot) {
        if (nullToEmpty(newRoot).isBlank() || nullToEmpty(appRoot).isBlank()) return null;
        try {
            Path app = Path.of(appRoot).toAbsolutePath().normalize();
            Path target = Path.of(newRoot).toAbsolutePath().normalize();
            return target.equals(app) || !target.startsWith(app) ? null
                    : app.relativize(target).toString().replace('\\', '/');
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String migrationBinding(PathPrefixRow row) {
        return "migrate.prefix." + row.id();
    }

    private void scheduleSymbolicOrphanCheck() {
        executeAsync(() -> {
            try {
                java.util.concurrent.TimeUnit.SECONDS.sleep(3L);
                for (int attempt = 0; attempt < 24; attempt++) {
                    DesktopUiHost.GuiResponse response = host.guiGet("path-prefixes", 10_000);
                    if (response.is2xx() && response.body() != null) {
                        DesktopUiHost.GuiValue body = response.body();
                        if (body.path("symbolicOrphan").asBoolean(false)) {
                            showSymbolicOrphanDialog(body.path("symbolicOrphanSuggestedPath").asText(""), null);
                        }
                        return;
                    }
                    java.util.concurrent.TimeUnit.MILLISECONDS.sleep(2_500L);
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
    }

    private void showSymbolicOrphanDialog(String suggestedPath, TextToken failure) {
        formValues.put("symbolic-orphan.path", nullToEmpty(suggestedPath));
        showDialog("symbolic-orphan", "gui.startup.symbolic-orphan.title",
                DesktopUiDocument.DialogStyle.WARNING,
                (nextActions, dismissAction, dismiss) -> {
                    List<DesktopUiNode> nodes = new ArrayList<>();
                    if (failure != null) nodes.add(new DesktopUiNode.Surface("symbolic-orphan.failure",
                            DesktopUiNode.SurfaceStyle.ERROR, DesktopUiNode.Insets.all(8), true,
                            new DesktopUiNode.Text("symbolic-orphan.failure.text", failure,
                                    TextStyle.ERROR, true, true)));
                    nodes.add(text("symbolic-orphan.message", "gui.startup.symbolic-orphan.message",
                            TextStyle.BODY));
                    nodes.add(input("symbolic-orphan.path.input", "symbolic-orphan.path",
                            "gui.migrate-dir.column.original", "gui.migrate-dir.new.placeholder",
                            InputKind.DIRECTORY, form("symbolic-orphan.path", suggestedPath), true));
                    nodes.add(row("symbolic-orphan.actions",
                            button("symbolic-orphan.repair", "symbolic-orphan.repair",
                                    "desktop.ui.action.confirm", true, nextActions,
                                    this::repairSymbolicOrphan),
                            button("symbolic-orphan.cancel", "symbolic-orphan.cancel",
                                    "desktop.ui.action.cancel", true, nextActions,
                                    this::confirmSymbolicOrphanCancel)));
                    return column("symbolic-orphan.content", nodes);
                }, false, 620, 0);
    }

    private void confirmSymbolicOrphanCancel() {
        String path = form("symbolic-orphan.path", "");
        showDialog("symbolic-orphan.cancel", "gui.startup.symbolic-orphan.title",
                DesktopUiDocument.DialogStyle.WARNING,
                (nextActions, dismissAction, dismiss) -> column("symbolic-orphan.cancel.content",
                        text("symbolic-orphan.cancel.message",
                                "gui.startup.symbolic-orphan.cancel-confirm", TextStyle.BODY),
                        row("symbolic-orphan.cancel.actions",
                                button("symbolic-orphan.cancel.yes", "symbolic-orphan.cancel.yes",
                                        "desktop.ui.action.yes", true, nextActions, () -> {
                                            dialogState = null;
                                            rebuild();
                                        }),
                                button("symbolic-orphan.cancel.no", "symbolic-orphan.cancel.no",
                                        "desktop.ui.action.no", true, nextActions,
                                        () -> showSymbolicOrphanDialog(path, null)))), false, 560, 0);
    }

    private void repairSymbolicOrphan() {
        String path = form("symbolic-orphan.path", "").trim();
        if (path.isBlank()) {
            showSymbolicOrphanDialog("", key("gui.migrate-dir.reason.invalid"));
            return;
        }
        dialogState = null;
        runBusy(() -> {
            DesktopUiHost.GuiResponse response = host.guiPostJson("path-prefixes/pin",
                    Map.of("path", path), 15_000);
            if (response.is2xx() && response.body() != null
                    && response.body().path("success").asBoolean(false)) {
                showDialog("symbolic-orphan.success", "gui.dialog.info.title",
                        appToken("gui.startup.symbolic-orphan.success", path),
                        DesktopUiDocument.DialogStyle.SUCCESS);
                return;
            }
            String reason = response.body() == null ? host.message("gui.migrate-dir.error.unreachable")
                    : migrateReason(response.body().path("errors").path(0).path("reason").asText(""));
            showSymbolicOrphanDialog(path, appToken("gui.startup.symbolic-orphan.failed", reason));
        });
    }

    private void openFfmpegDirectory() {
        runBusy(() -> {
            try {
                Path directory = host.locateFfmpeg().map(DesktopUiHost.FfmpegInstallation::homeDir)
                        .filter(Objects::nonNull).filter(Files::isDirectory)
                        .orElseGet(host::managedFfmpegDirectory);
                host.openLocalPath(Files.createDirectories(directory));
            } catch (Exception failure) {
                statusNotice = host.message("gui.ffmpeg.dialog.open-dir-failed.message", safeMessage(failure));
            }
        });
    }

    private void restartApplication() {
        runBusy(() -> statusNotice = host.restartApplication()
                ? host.message("gui.config.notice.process-restarting")
                : host.message("desktop.ui.action.failed"));
    }

    private void refreshPlugins() {
        runBusy(this::loadPluginStatus);
    }

    private void loadPluginStatus() {
        DesktopUiHost.GuiResponse response = host.guiGet("plugins/status", 5_000);
        if (!response.reachable()) {
            pluginsNotice = host.message("gui.plugins.state.offline");
            pluginStatuses = List.of();
            return;
        }
        if (!response.is2xx() || response.body() == null) {
            pluginsNotice = response.status() == 403
                    ? host.message("gui.plugins.state.forbidden") : host.message("gui.plugins.state.error");
            pluginStatuses = List.of();
            return;
        }
        recoveryMode = response.body().path("recoveryMode").asBoolean(false);
        List<PluginStatusRow> rows = new ArrayList<>();
        for (DesktopUiHost.GuiValue plugin : response.body().path("plugins")) {
            String id = plugin.path("id").asText("unknown");
            rows.add(new PluginStatusRow(safeId(id), plugin.path("name").asText(id),
                    nullableText(plugin, "source"), nullableText(plugin, "status"),
                    nullableText(plugin, "runtimePhase"), plugin.path("managed").asBoolean(false),
                    plugin.path("required").asBoolean(false), nullableText(plugin, "version"),
                    nullableText(plugin.path("verification"), "status")));
        }
        pluginStatuses = List.copyOf(rows);
        pluginsNotice = rows.isEmpty() ? host.message("gui.plugins.state.empty") : "";
    }

    private static String nullableText(DesktopUiHost.GuiValue value, String field) {
        DesktopUiHost.GuiValue child = value == null ? null : value.get(field);
        return child == null || child.isNull() ? null : child.asText();
    }

    private void saveConfiguration() {
        runBusy(() -> saveConfiguration(false));
    }

    private void saveConfiguration(boolean symbolicRootPinned) {
        List<ConfigField> changed = configFields.stream()
                .filter(field -> !field.spec().sensitive()
                        ? !Objects.equals(values.get(field.key()), savedValues.get(field.key()))
                        : !values.getOrDefault(field.key(), "").isBlank())
                .toList();
        boolean repositoriesChanged = !pluginRepositories.equals(savedPluginRepositories);
        Map<String, String> interfaceValues = pendingInterfaceValues();
        boolean interfaceChanged = interfaceValues.entrySet().stream().anyMatch(entry -> !Objects.equals(
                entry.getValue(), savedValues.get(new FieldKey(null, entry.getKey()))));
        if (!pluginRepositoriesLoaded) {
            setConfigNotice(host.message("gui.config.market.repo.read-failed", pluginRepositoriesLoadFailure));
            return;
        }
        if (changed.isEmpty() && !repositoriesChanged && !interfaceChanged) {
            setConfigNotice(host.message("gui.config.notice.saved-no-change"));
            return;
        }
        try {
            validate(changed);
            ConfigField rootField = changed.stream()
                    .filter(field -> field.owner() == null && "download.root-folder".equals(field.spec().key()))
                    .findFirst().orElse(null);
            if (!symbolicRootPinned && rootField != null) {
                String path = symbolicRootPathToPin(savedValues.get(rootField.key()), values.get(rootField.key()));
                if (path != null) {
                    showSymbolicRootPinDialog(path);
                    return;
                }
            }
            persist(changed, repositoriesChanged, interfaceChanged ? interfaceValues : Map.of());
            Set<String> hotKeys = new LinkedHashSet<>();
            boolean backendRestart = repositoriesChanged;
            boolean processRestart = interfaceChanged && !Objects.equals(
                    interfaceValues.get("app.gui-provider"),
                    savedValues.getOrDefault(new FieldKey(null, "app.gui-provider"), "gui-swing"));
            for (ConfigField field : changed) {
                switch (field.spec().effect()) {
                    case HOT_RELOAD -> hotKeys.add(field.spec().key());
                    case BACKEND_RESTART -> backendRestart = true;
                    case PROCESS_RESTART -> processRestart = true;
                }
                if (field.spec().sensitive()) {
                    values.put(field.key(), "");
                    Set<FieldKey> stored = new LinkedHashSet<>(storedCredentialFields);
                    stored.add(field.key());
                    storedCredentialFields = Set.copyOf(stored);
                } else {
                    savedValues.put(field.key(), values.get(field.key()));
                }
            }
            if (repositoriesChanged) savedPluginRepositories = List.copyOf(pluginRepositories);
            if (interfaceChanged) {
                interfaceValues.forEach((key, value) -> savedValues.put(new FieldKey(null, key), value));
            }
            boolean hotReloaded = hotKeys.isEmpty() || host.guiPostJson("config/reload",
                    Map.of("changedKeys", List.copyOf(hotKeys)), 5_000).is2xx();
            if (processRestart) {
                setConfigNotice(host.message("gui.config.notice.saved-process"));
                showConfigurationRestartDialog(true);
            } else if (backendRestart) {
                setConfigNotice(host.message("gui.config.notice.saved"));
                showConfigurationRestartDialog(false);
            } else {
                setConfigNotice(host.message(hotReloaded
                        ? "gui.config.notice.saved-hot" : "gui.config.notice.saved-hot-failed"));
            }
        } catch (Exception failure) {
            setConfigNotice(host.message("gui.config.dialog.save-failed.message", safeMessage(failure)));
        }
    }

    private String symbolicRootPathToPin(String oldValue, String newValue) {
        try {
            String oldRoot = host.normalizeRootFolder(oldValue);
            if (Path.of(oldRoot).isAbsolute()) return null;
            Path oldAbsolute = Path.of(oldRoot).toAbsolutePath().normalize();
            Path newAbsolute = Path.of(host.normalizeRootFolder(newValue)).toAbsolutePath().normalize();
            if (oldAbsolute.equals(newAbsolute)) return null;
        } catch (RuntimeException ignored) {
            return null;
        }

        DesktopUiHost.GuiResponse response = host.guiGet("path-prefixes", 10_000);
        if (!response.reachable() || !response.is2xx() || response.body() == null) {
            LOG.warn(host.message("gui.config.log.symbolic-pin.status-unavailable"));
            return null;
        }
        try {
            DesktopUiHost.GuiValue body = response.body();
            if (!body.path("symbolicReferenced").asBoolean(false)) return null;
            for (DesktopUiHost.GuiValue prefix : body.path("prefixes")) {
                if (prefix.path("symbolic").asBoolean(false)) {
                    String path = prefix.path("path").asText("");
                    return path.isBlank() ? null : path;
                }
            }
        } catch (RuntimeException failure) {
            LOG.warn(host.message("gui.config.log.symbolic-pin.status-unavailable"), failure);
        }
        return null;
    }

    private void showSymbolicRootPinDialog(String path) {
        showDialog("config.symbolic-pin", "gui.config.symbolic-pin.title",
                DesktopUiDocument.DialogStyle.WARNING,
                (nextActions, dismissAction, dismiss) -> column("config.symbolic-pin.content",
                        new DesktopUiNode.Text("config.symbolic-pin.message",
                                appToken("gui.config.symbolic-pin.message", path), TextStyle.BODY, true, false),
                        row("config.symbolic-pin.actions",
                                button("config.symbolic-pin.confirm", "config.symbolic-pin.confirm",
                                        "desktop.ui.action.confirm", !busy, nextActions,
                                        () -> pinSymbolicRootAndSave(path)),
                                button("config.symbolic-pin.cancel", "config.symbolic-pin.cancel",
                                        "desktop.ui.action.cancel", !busy, nextActions, () -> {
                                            dialogState = null;
                                            setConfigNotice(host.message("gui.config.symbolic-pin.cancelled"));
                                            rebuild();
                                        }))), 620, 0);
    }

    private void pinSymbolicRootAndSave(String path) {
        dialogState = null;
        runBusy(() -> {
            try {
                DesktopUiHost.GuiResponse response = host.guiPostJson(
                        "path-prefixes/pin", Map.of("path", path), 15_000);
                if (response.reachable() && response.is2xx() && response.body() != null
                        && response.body().path("success").asBoolean(false)) {
                    saveConfiguration(true);
                    return;
                }
                String detail = response.reachable() ? Integer.toString(response.status()) : "unreachable";
                LOG.warn(host.message("gui.config.log.symbolic-pin.failed", detail));
            } catch (RuntimeException failure) {
                LOG.warn(host.message("gui.config.log.symbolic-pin.failed", safeMessage(failure)), failure);
            }
            showDialog("config.symbolic-pin.failed", "gui.dialog.error.title",
                    "gui.config.symbolic-pin.failed", DesktopUiDocument.DialogStyle.ERROR);
        });
    }

    private void showConfigurationRestartDialog(boolean processRestart) {
        String title = processRestart ? "gui.action.restart-application" : "gui.action.restart-service";
        String message = processRestart
                ? "gui.config.dialog.process-restart-required.message"
                : "gui.status.dialog.restart.confirm.message";
        showDialog("config.restart", title, DesktopUiDocument.DialogStyle.QUESTION,
                (nextActions, dismissAction, dismiss) -> column("config.restart.content",
                        text("config.restart.message", message, TextStyle.BODY),
                        row("config.restart.actions",
                                button("config.restart.confirm", "config.restart.confirm", title,
                                        true, nextActions, processRestart
                                                ? this::restartApplicationAfterConfigSave
                                                : this::restartBackendAfterConfigSave),
                                button("config.restart.later", dismissAction, "gui.action.restart-later",
                                        true, nextActions, dismiss))), 500, 0);
    }

    private void restartApplicationAfterConfigSave() {
        dialogState = null;
        runBusy(() -> setConfigNotice(host.message(host.restartApplication()
                ? "gui.config.notice.process-restarting" : "desktop.ui.action.failed")));
    }

    private void restartBackendAfterConfigSave() {
        dialogState = null;
        try {
            boolean accepted = host.restartBackend(this::rebuild);
            setConfigNotice(host.message(accepted
                    ? "gui.config.notice.restarting" : "gui.message.backend-busy"));
        } catch (RuntimeException failure) {
            LOG.warn(host.message("gui.status.log.restart-request.failed", safeMessage(failure)), failure);
            setConfigNotice(host.message("gui.message.backend-busy"));
        }
        rebuild();
    }

    private void persist(List<ConfigField> changed, boolean repositoriesChanged,
                         Map<String, String> interfaceValues) throws Exception {
        Map<DesktopUiHost.ConfigFile, Map<String, String>> normal = new LinkedHashMap<>();
        Map<String, Map<String, String>> secrets = new LinkedHashMap<>();
        for (ConfigField field : changed) {
            String value = host.requireSafeConfigValue(values.getOrDefault(field.key(), ""));
            if (field.spec().sensitive() && field.owner() != null) {
                secrets.computeIfAbsent(field.owner(), ignored -> new LinkedHashMap<>())
                        .put(host.requireSafeConfigKey(field.spec().key()), value);
            } else {
                DesktopUiHost.ConfigFile file = field.owner() == null
                        ? host.applicationConfig() : host.pluginConfig(field.owner());
                normal.computeIfAbsent(file, ignored -> new LinkedHashMap<>())
                        .put(host.requireSafeConfigKey(field.spec().key()), value);
            }
        }
        DesktopUiHost.ConfigFile applicationConfig = host.applicationConfig();
        if (!interfaceValues.isEmpty()) {
            Map<String, String> applicationValues = normal.computeIfAbsent(
                    applicationConfig, ignored -> new LinkedHashMap<>());
            for (Map.Entry<String, String> entry : interfaceValues.entrySet()) {
                applicationValues.put(host.requireSafeConfigKey(entry.getKey()),
                        host.requireSafeConfigValue(entry.getValue()));
            }
        }
        if (repositoriesChanged) {
            host.readPluginRepositories(applicationConfig);
            normal.computeIfAbsent(applicationConfig, ignored -> new LinkedHashMap<>());
        }
        Map<DesktopUiHost.ConfigFile, DesktopUiHost.ConfigSnapshot> snapshots = new LinkedHashMap<>();
        for (DesktopUiHost.ConfigFile file : normal.keySet()) snapshots.put(file, file.snapshot());
        Map<String, DesktopUiHost.CredentialSnapshot> credentialSnapshots = new LinkedHashMap<>();
        for (String owner : secrets.keySet()) credentialSnapshots.put(owner, host.snapshotCredentials(owner));
        try {
            host.withCredentialLocks(secrets.keySet(), () -> {
                for (Map.Entry<DesktopUiHost.ConfigFile, Map<String, String>> entry : normal.entrySet()) {
                    if (!entry.getValue().isEmpty()) entry.getKey().writeAll(entry.getValue());
                }
                if (repositoriesChanged) host.writePluginRepositories(applicationConfig, pluginRepositories);
                for (Map.Entry<String, Map<String, String>> entry : secrets.entrySet()) {
                    host.updateCredentials(entry.getKey(), entry.getValue());
                }
            });
        } catch (Exception failure) {
            Exception rollbackFailure = null;
            for (Map.Entry<DesktopUiHost.ConfigFile, DesktopUiHost.ConfigSnapshot> entry : snapshots.entrySet()) {
                try { entry.getKey().restore(entry.getValue()); }
                catch (Exception rollback) { rollbackFailure = rollback; }
            }
            for (Map.Entry<String, DesktopUiHost.CredentialSnapshot> entry : credentialSnapshots.entrySet()) {
                try { host.restoreCredentials(entry.getKey(), entry.getValue()); }
                catch (Exception rollback) { rollbackFailure = rollback; }
            }
            if (rollbackFailure != null) failure.addSuppressed(rollbackFailure);
            throw failure;
        }
    }

    private void validate(List<ConfigField> fields) throws Exception {
        for (ConfigField field : fields) {
            GuiConfigFieldContribution spec = field.spec();
            String value = values.getOrDefault(field.key(), "");
            host.requireSafeConfigKey(spec.key());
            host.requireSafeConfigValue(value);
            if (spec.type() == GuiConfigFieldType.PORT) {
                int port = Integer.parseInt(value);
                if (port < 1 || port > 65_535) throw new IllegalArgumentException(spec.key());
            }
            if (spec.type() == GuiConfigFieldType.INT) {
                int number = Integer.parseInt(value);
                if (spec.minValue() != null && number < spec.minValue()) throw new IllegalArgumentException(spec.key());
                if (spec.maxValue() != null && number > spec.maxValue()) throw new IllegalArgumentException(spec.key());
            }
            if (spec.type() == GuiConfigFieldType.ENUM && !spec.enumValues().contains(value)) {
                throw new IllegalArgumentException(spec.key());
            }
            if (spec.key().startsWith("maintenance.") && spec.key().endsWith(".time")
                    && !host.validMaintenanceTime(value)) {
                throw new IllegalArgumentException(spec.key());
            }
        }
    }

    private void clearCredential(ConfigField field) {
        if (field.owner() == null || !field.spec().sensitive()) return;
        runBusy(() -> {
            try {
                host.updateCredentials(field.owner(), Map.of(field.spec().key(), ""));
                values.put(field.key(), "");
                Set<FieldKey> stored = new LinkedHashSet<>(storedCredentialFields);
                stored.remove(field.key());
                storedCredentialFields = Set.copyOf(stored);
                setConfigNotice(host.message("desktop.ui.config.secret-cleared"));
            } catch (Exception failure) {
                setConfigNotice(host.message("gui.config.dialog.save-failed.message", safeMessage(failure)));
            }
        });
    }

    private void updateAutoStart(boolean enabled) {
        if (!autoStartSupported || enabled == autoStartEnabled) return;
        runBusy(() -> {
            try {
                host.setAutoStartEnabled(enabled);
                autoStartEnabled = enabled;
                setConfigNotice(host.message(enabled
                        ? "gui.config.autostart.notice.enabled"
                        : "gui.config.autostart.notice.disabled"));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                LOG.warn(host.message("gui.config.log.autostart.apply-failed",
                        enabled, safeMessage(interrupted)), interrupted);
                setConfigNotice(host.message("desktop.ui.tools.operation-failed"));
            } catch (Exception failure) {
                LOG.warn(host.message("gui.config.log.autostart.apply-failed",
                        enabled, safeMessage(failure)), failure);
                setConfigNotice(host.message("desktop.ui.tools.operation-failed"));
            }
        });
    }

    private void openConfigFile() {
        runBusy(() -> {
            try {
                host.openLocalPath(configPath);
            } catch (Exception failure) {
                LOG.warn(host.message("gui.config.log.open-file-failed", configPath, safeMessage(failure)), failure);
                showDialog("config.open-failed", "gui.dialog.error.title",
                        "desktop.ui.tools.operation-failed", DesktopUiDocument.DialogStyle.ERROR);
            }
        });
    }

    private void requestConfigurationReset() {
        showDialog("config.reset.dialog", "gui.config.dialog.reset-confirm.title",
                DesktopUiDocument.DialogStyle.QUESTION,
                (nextActions, dismissAction, dismiss) -> column("config.reset.content",
                        text("config.reset.message", "gui.config.dialog.reset-confirm.message", TextStyle.BODY),
                        row("config.reset.actions",
                                button("config.reset.confirm", "config.reset.confirm",
                                        "gui.button.reset-defaults", true, nextActions, () -> {
                                            dialogState = null;
                                            resetConfiguration();
                                        }),
                                button("config.reset.cancel", dismissAction, "desktop.ui.action.cancel",
                                        true, nextActions, dismiss))), 480, 0);
    }

    private void resetConfiguration() {
        for (ConfigField field : configFields) values.put(field.key(), field.spec().defaultValue());
        setConfigNotice("");
        rebuild();
    }

    private void reloadConfiguration() {
        loadConfiguration();
        setConfigNotice("");
        rebuild();
    }

    private Map<String, String> pendingInterfaceValues() {
        String language = form("interface.language", selected("app.language", "follow-system"));
        if (!"follow-system".equals(language) && host.matchLocale(language).isEmpty()) language = "follow-system";
        Set<String> availableProviders = currentSources().stream()
                .filter(source -> source.plugin() instanceof DesktopUiProvider)
                .map(DesktopUiPluginSource::id).collect(java.util.stream.Collectors.toSet());
        String provider = form("interface.provider", selected("app.gui-provider", "gui-swing"));
        if (!availableProviders.contains(provider)) provider = "gui-swing";
        Set<String> availableThemes = currentSources().stream().flatMap(source -> {
            try {
                return source.plugin().guiThemes().stream().filter(Objects::nonNull)
                        .map(theme -> theme.themeId());
            } catch (RuntimeException ignored) {
                return java.util.stream.Stream.empty();
            }
        }).collect(java.util.stream.Collectors.toSet());
        String theme = form("interface.theme", selected("app.theme", "system"));
        if (!availableThemes.contains(theme)) theme = "system";
        String expandAll = Boolean.toString(boolForm("interface.config-menu-expand-all",
                Boolean.parseBoolean(selected("app.config-menu-expand-all", "false"))));
        return Map.of(
                "app.language", language,
                "app.gui-provider", provider,
                "app.theme", theme,
                "app.config-menu-expand-all", expandAll);
    }

    private void applyLocale(String tag) {
        if (tag == null || tag.isBlank() || "follow-system".equals(tag)) host.detectSystemLocale();
        else host.matchLocale(tag).ifPresent(locale -> Locale.setDefault(locale.toLocale()));
        rebuild();
    }

    private void changePassword() {
        String current = form("security.current", "");
        String next = form("security.new", "");
        String confirm = form("security.confirm", "");
        if (current.isBlank()) {
            securityNotice = key("gui.security.validation.current-required");
            rebuild();
            return;
        }
        if (next.isBlank()) {
            securityNotice = key("gui.security.validation.new-required");
            rebuild();
            return;
        }
        if (next.length() < host.minimumPasswordLength()) {
            securityNotice = key("gui.security.validation.weak-password");
            rebuild();
            return;
        }
        if (!next.equals(confirm)) {
            securityNotice = key("gui.security.validation.mismatch");
            rebuild();
            return;
        }
        if (next.equals(current)) {
            securityNotice = key("gui.security.validation.same-password");
            rebuild();
            return;
        }
        securityNotice = key("gui.security.action.submitting");
        runBusy(() -> {
            DesktopUiHost.GuiResponse response = host.guiPostJson("change-password",
                    Map.of("oldPassword", current, "newPassword", next), 5_000);
            if (response.is2xx()) {
                LOG.info(host.message("gui.security.log.change-password.success"));
                clearSecurityForm();
                securityNotice = key("gui.security.status.success");
                showDialog("security.success", "gui.security.dialog.success.title",
                        "gui.security.dialog.success.message", DesktopUiDocument.DialogStyle.SUCCESS);
                return;
            }
            String error = response.body() == null ? "unexpected" : response.body().path("error").asText("unexpected");
            String messageKey = switch (error) {
                case "invalid-current" -> "gui.security.error.invalid-current";
                case "weak-password" -> "gui.security.error.weak-password";
                case "same-password" -> "gui.security.error.same-password";
                case "setup-incomplete" -> "gui.security.error.setup-incomplete";
                case "save-failed" -> "gui.security.error.save-failed";
                default -> response.reachable()
                        ? "gui.security.error.unexpected" : "gui.security.error.backend-unreachable";
            };
            securityNotice = key(messageKey);
            if (Set.of("setup-incomplete", "save-failed", "unexpected").contains(error)
                    || !response.reachable()) {
                LOG.error("Desktop password change failed: reachable={}, status={}, kind={}",
                        response.reachable(), response.status(), error);
                showDialog("security.error", "gui.dialog.error.title", messageKey,
                        DesktopUiDocument.DialogStyle.ERROR);
            } else {
                LOG.warn("Desktop password change rejected: status={}, kind={}", response.status(), error);
            }
        });
    }

    private void clearSecurityForm() {
        formValues.remove("security.current");
        formValues.remove("security.new");
        formValues.remove("security.confirm");
        securityFormRevision++;
    }

    private void runBackfill() {
        String db = form("tools.backfill.db", "").trim();
        if (db.isBlank()) {
            backfillNotice = host.message("gui.tools.validation.database-path.required");
            rebuild();
            return;
        }
        DesktopUiHost.BackfillOptions options = new DesktopUiHost.BackfillOptions(
                db, form("tools.backfill.proxy-host", host.defaultProxyHost()),
                intForm("tools.backfill.proxy-port", host.defaultProxyPort()),
                boolForm("tools.backfill.proxy", true), intForm("tools.backfill.delay", 1000),
                intForm("tools.backfill.limit", 0), boolForm("tools.backfill.dry", false));
        runExclusiveTool(host.message("gui.tools.card.backfill.title"), () -> {
            int count = host.countBackfillCandidates(options);
            backfillNotice = count == 0 ? host.message("gui.tools.backfill.status.none-found")
                    : host.message("gui.tools.backfill.status.pending-found", count);
            rebuild();
            try (DesktopUiHost.ToolLogSession log = host.openToolLog("artworks-backfill")) {
                log.openLatestInBrowser();
                DesktopUiHost.BackfillSummary summary = host.runBackfill(options);
                String result = host.message(summary.rateLimited()
                        ? "gui.tools.backfill.result.rate-limited" : "gui.tools.backfill.result.completed");
                backfillNotice = result;
                showDialog("tools.backfill.completed", "gui.tools.dialog.backfill.completed.title",
                        appToken("gui.tools.dialog.backfill.completed.message", result,
                                summary.processed(), summary.totalCandidates()),
                        DesktopUiDocument.DialogStyle.SUCCESS);
            }
        });
    }

    private void runMigration() {
        String db = form("tools.migration.db", "").trim();
        String root = form("tools.migration.root", "").trim();
        if (db.isBlank() || root.isBlank()) {
            migrationNotice = host.message(db.isBlank() ? "gui.tools.validation.database-path.required"
                    : "gui.tools.validation.root-folder.required");
            rebuild();
            return;
        }
        DesktopUiHost.MigrationOptions options = new DesktopUiHost.MigrationOptions(db, root);
        runExclusiveTool(host.message("gui.tools.card.migration.title"), () -> {
            int count = host.countMigrationCandidates(options);
            migrationNotice = count == 0 ? host.message("gui.tools.migration.status.none-found")
                    : host.message("gui.tools.migration.status.pending-found", count);
            rebuild();
            try (DesktopUiHost.ToolLogSession log = host.openToolLog("json-to-sqlite-migration")) {
                log.openLatestInBrowser();
                DesktopUiHost.MigrationSummary summary = host.runMigration(options, ignored -> { });
                if (summary.historyFileMissing()) {
                    migrationNotice = host.message("gui.tools.migration.status.history-missing");
                } else {
                    String result = host.message("gui.tools.migration.result.completed");
                    migrationNotice = result;
                    showDialog("tools.migration.completed", "gui.tools.dialog.migration.completed.title",
                            appToken("gui.tools.dialog.migration.completed.message", result,
                                    summary.migrated(), summary.skipped(), summary.totalCandidates()),
                            DesktopUiDocument.DialogStyle.SUCCESS);
                }
            }
        });
    }

    private void checkFolders() {
        String db = form("tools.folder.db", "").trim();
        if (db.isBlank()) {
            folderNotice = host.message("gui.tools.validation.database-path.required");
            rebuild();
            return;
        }
        runFolderAction(() -> {
            DesktopUiHost.FolderCheckResult result = host.checkArtworkFolders(Path.of(db));
            List<DesktopUiNode.TableRow> rows = new ArrayList<>();
            Map<String, DesktopUiHost.FolderArtwork> artworks = new LinkedHashMap<>();
            for (DesktopUiHost.FolderArtwork artwork : result.inaccessible()) {
                String id = "artwork." + artwork.artworkId();
                artworks.put(id, artwork);
                rows.add(new DesktopUiNode.TableRow(id, List.of(
                        Long.toString(artwork.artworkId()), nullToEmpty(artwork.title()),
                        host.message(artwork.moved() ? "gui.folder-checker.path-type.moved"
                                : "gui.folder-checker.path-type.original"),
                        artwork.path() == null ? host.message("gui.folder-checker.value.null-path") : artwork.path(),
                        host.message("gui.folder-checker.status.not-found"))));
            }
            folderRows = List.copyOf(rows);
            folderArtworks = Map.copyOf(artworks);
            selectedFolderRow = null;
            formValues.remove("tools.folder.new-path");
            folderNotice = host.message(rows.isEmpty() ? "gui.folder-checker.status.all-accessible"
                    : "gui.folder-checker.status.inaccessible-count", rows.isEmpty()
                    ? new Object[]{result.total()} : new Object[]{rows.size(), result.total()});
        });
    }

    private DesktopUiNode folderTable() {
        return new DesktopUiNode.Table("tools.folder.table", "folder.selected",
                List.of(
                        new DesktopUiNode.TableColumn("id", key("gui.folder-checker.column.artwork-id"), 90),
                        new DesktopUiNode.TableColumn("title", key("gui.folder-checker.column.title"), 180),
                        new DesktopUiNode.TableColumn("type", key("gui.folder-checker.column.path-type"), 100),
                        new DesktopUiNode.TableColumn("path", key("gui.folder-checker.column.path"), 340),
                        new DesktopUiNode.TableColumn("status", key("gui.folder-checker.column.status"), 90)),
                folderRows, SelectionMode.SINGLE,
                selectedFolderRow == null ? List.of() : List.of(selectedFolderRow), !busy);
    }

    private void requestFolderUpdate() {
        DesktopUiHost.FolderArtwork artwork = folderArtworks.get(selectedFolderRow);
        String newPath = form("tools.folder.new-path", "").trim();
        if (artwork == null) {
            folderNotice = host.message("gui.folder-checker.error.row-required");
            rebuild();
            return;
        }
        if (newPath.isBlank()) {
            folderNotice = host.message("gui.folder-checker.error.new-path.required");
            rebuild();
            return;
        }
        boolean directory;
        try { directory = host.isImageClassifierDirectory(Path.of(newPath)); }
        catch (RuntimeException invalid) { directory = false; }
        if (!directory) {
            showDialog("folder.path-not-found", "gui.folder-checker.dialog.path-not-found.title",
                    DesktopUiDocument.DialogStyle.QUESTION,
                    (nextActions, dismissAction, dismiss) -> column("folder.path-not-found.content",
                            new DesktopUiNode.Text("folder.path-not-found.message",
                                    appToken("gui.folder-checker.dialog.path-not-found.message", newPath),
                                    TextStyle.BODY, true, true),
                            row("folder.path-not-found.actions",
                                    button("folder.path-not-found.yes", "folder.path-not-found.yes",
                                            "desktop.ui.action.yes", true, nextActions,
                                            () -> applyFolderUpdate(artwork, newPath)),
                                    button("folder.path-not-found.no", dismissAction,
                                            "desktop.ui.action.cancel", true, nextActions, dismiss))), 560, 0);
            return;
        }
        applyFolderUpdate(artwork, newPath);
    }

    private void applyFolderUpdate(DesktopUiHost.FolderArtwork artwork, String newPath) {
        dialogState = null;
        runFolderAction(() -> {
            host.updateArtworkFolder(Path.of(form("tools.folder.db", "")),
                    artwork.artworkId(), artwork.moved(), newPath);
            folderNotice = host.message("gui.folder-checker.dialog.update-success.message",
                    artwork.artworkId(), host.message(artwork.moved()
                            ? "gui.folder-checker.column-name.move-folder"
                            : "gui.folder-checker.column-name.folder"), newPath);
            checkFoldersDirect();
        });
    }

    private void copySelectedFolderId() {
        DesktopUiHost.FolderArtwork artwork = folderArtworks.get(selectedFolderRow);
        if (artwork == null) return;
        try {
            host.copyText(Long.toString(artwork.artworkId()));
            showDialog("folder.copied", "gui.folder-checker.dialog.copied.title",
                    appToken("gui.folder-checker.dialog.copied.message", artwork.artworkId()),
                    DesktopUiDocument.DialogStyle.SUCCESS);
            rebuild();
        } catch (Exception failure) {
            LOG.warn("Unable to copy artwork id", failure);
            folderNotice = host.message("desktop.ui.action.failed");
            rebuild();
        }
    }

    private void runFolderAction(ThrowingRunnable action) {
        if (toolDialog != ToolDialog.FOLDER_CHECKER) {
            runExclusiveTool(host.message("gui.tools.card.folder-checker.title"), action);
            return;
        }
        if (busy) return;
        busy = true;
        rebuild();
        executeAsync(() -> {
            try {
                action.run();
            } catch (Exception failure) {
                LOG.error("Folder checker action failed", failure);
                folderNotice = host.message("desktop.ui.action.failed");
            } finally {
                busy = false;
                rebuild();
            }
        });
    }

    private void checkFoldersDirect() throws Exception {
        DesktopUiHost.FolderCheckResult result = host.checkArtworkFolders(Path.of(form("tools.folder.db", "")));
        folderRows = result.inaccessible().stream().map(artwork -> new DesktopUiNode.TableRow(
                "artwork." + artwork.artworkId(), List.of(Long.toString(artwork.artworkId()),
                nullToEmpty(artwork.title()),
                host.message(artwork.moved() ? "gui.folder-checker.path-type.moved"
                        : "gui.folder-checker.path-type.original"),
                artwork.path() == null ? host.message("gui.folder-checker.value.null-path") : artwork.path(),
                host.message("gui.folder-checker.status.not-found")))).toList();
        folderArtworks = result.inaccessible().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                artwork -> "artwork." + artwork.artworkId(), Function.identity()));
        selectedFolderRow = null;
    }

    private void showClassifierSettings() {
        showDialog("classifier.settings", "gui.image-classifier.button.settings",
                DesktopUiDocument.DialogStyle.INFO,
                (nextActions, dismissAction, dismiss) -> column("classifier.settings.content",
                        input("classifier.settings.folder", "classifier.default-folder",
                                "gui.image-classifier.label.default-folder", null, InputKind.DIRECTORY,
                                form("classifier.default-folder", ""), !busy),
                        input("classifier.settings.server", "classifier.server-url",
                                "gui.image-classifier.label.server-url", null, InputKind.TEXT,
                                form("classifier.server-url", "http://localhost:6999"), !busy),
                        toggle("classifier.settings.skip", "classifier.show-skip",
                                "gui.image-classifier.label.show-skip-button",
                                boolForm("classifier.show-skip", true), !busy),
                        input("classifier.settings.targets", "classifier.targets",
                                "desktop.ui.classifier.targets", null, InputKind.MULTILINE,
                                form("classifier.targets", ""), !busy),
                        row("classifier.settings.actions",
                                button("classifier.settings.save", "classifier.settings.save",
                                        "gui.button.save", !busy, nextActions, () -> {
                                            dialogState = null;
                                            saveClassifierSettings();
                                        }),
                                button("classifier.settings.cancel", dismissAction,
                                        "desktop.ui.action.cancel", !busy, nextActions, dismiss))), 660, 560);
    }

    private Optional<DesktopUiNode.ImageData> materializeImage(Path image) {
        DesktopUiNode.ImageData cached = classifierImageCache.get(image);
        if (cached != null) return Optional.of(cached);
        try {
            Path source = image;
            String name = image.getFileName().toString().toLowerCase(Locale.ROOT);
            if (name.endsWith(".webp")) {
                Path thumbnail = image.resolveSibling(image.getFileName().toString()
                        .substring(0, image.getFileName().toString().lastIndexOf('.')) + "_thumb.jpg");
                if (Files.isRegularFile(thumbnail)) source = thumbnail;
            }
            BufferedImage original = BoundedImageDecoder.read(source);
            if (original == null) return Optional.empty();
            double scale = Math.min(1d, Math.min(1600d / original.getWidth(), 1600d / original.getHeight()));
            int width = Math.max(1, (int) Math.round(original.getWidth() * scale));
            int height = Math.max(1, (int) Math.round(original.getHeight() * scale));
            BufferedImage rendered = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = rendered.createGraphics();
            try {
                graphics.setColor(Color.WHITE);
                graphics.fillRect(0, 0, width, height);
                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                        RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                graphics.drawImage(original, 0, 0, width, height, null);
            } finally {
                graphics.dispose();
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            if (!ImageIO.write(rendered, "jpg", output)) return Optional.empty();
            DesktopUiNode.ImageData data = new DesktopUiNode.ImageData("image/jpeg",
                    Base64.getEncoder().encodeToString(output.toByteArray()));
            classifierImageCache.put(image, data);
            return Optional.of(data);
        } catch (Exception failure) {
            LOG.warn("Unable to materialize classifier preview {}", image, failure);
            return Optional.empty();
        }
    }

    private void openClassifierImage(Path image) {
        runBusy(() -> {
            try { host.openLocalPath(image); }
            catch (Exception failure) {
                LOG.warn("Unable to open classifier image {}", image, failure);
                classifierNotice = host.message("desktop.ui.action.failed");
            }
        });
    }

    private void showClassifierImage(int index) {
        if (index < 0 || index >= classifierImages.size()) return;
        Path image = classifierImages.get(index);
        showDialog("classifier.viewer", "gui.image-classifier.dialog.image-viewer.title",
                DesktopUiDocument.DialogStyle.INFO,
                (nextActions, dismissAction, dismiss) -> {
                    List<DesktopUiNode> content = new ArrayList<>();
                    materializeImage(image).ifPresentOrElse(data -> content.add(new DesktopUiNode.Image(
                                    "classifier.viewer.image", data, TextToken.raw(image.getFileName().toString()),
                                    980, 700, DesktopUiNode.ScaleMode.FIT)),
                            () -> content.add(text("classifier.viewer.failed",
                                    "gui.image-classifier.thumbnail.viewer-load-failed-generic",
                                    TextStyle.ERROR)));
                    content.add(raw("classifier.viewer.page", host.message(
                            "gui.image-classifier.dialog.image-viewer.page-label",
                            index + 1, classifierImages.size()), TextStyle.CAPTION));
                    content.add(raw("classifier.viewer.name", image.getFileName().toString(), TextStyle.CODE));
                    content.add(row("classifier.viewer.actions",
                            button("classifier.viewer.previous", "classifier.viewer.previous",
                                    "gui.image-classifier.button.prev-image", index > 0,
                                    nextActions, () -> showClassifierImage(index - 1)),
                            button("classifier.viewer.next", "classifier.viewer.next",
                                    "gui.image-classifier.button.next-image",
                                    index + 1 < classifierImages.size(), nextActions,
                                    () -> showClassifierImage(index + 1)),
                            button("classifier.viewer.external", "classifier.viewer.external",
                                    "desktop.ui.action.open", true, nextActions,
                                    () -> openClassifierImage(image)),
                            button("classifier.viewer.close", dismissAction,
                                    "desktop.ui.action.close", true, nextActions, dismiss)));
                    return column("classifier.viewer.content", content);
                }, 1100, 860);
    }

    private int classifierFolderIndex() {
        Path selected = classifierPath(form("classifier.source", ""));
        return selected == null ? -1 : classifierFolders.indexOf(selected);
    }

    private void moveClassifierGroup(int offset) {
        int groups = classifierImages.isEmpty() ? 0 : (classifierImages.size() + 9) / 10;
        classifierGroupIndex = Math.max(0, Math.min(Math.max(0, groups - 1), classifierGroupIndex + offset));
        rebuild();
    }

    private void moveClassifierFolder(int offset) {
        int current = classifierFolderIndex();
        int next = current + offset;
        if (current < 0 || next < 0) return;
        if (offset > 0) {
            try { host.deleteImageClassifierFolderIfEmpty(classifierFolders.get(current)); }
            catch (Exception ignored) { }
        }
        if (next >= classifierFolders.size()) {
            formValues.remove("classifier.source");
            classifierImages = List.of();
            classifierArtwork = null;
            classifierNotice = host.message("gui.image-classifier.dialog.all-folders-complete.message");
            showDialog("classifier.complete", "gui.image-classifier.dialog.all-folders-complete.title",
                    "gui.image-classifier.dialog.all-folders-complete.message",
                    DesktopUiDocument.DialogStyle.SUCCESS);
            rebuild();
            return;
        }
        formValues.put("classifier.source", classifierId(classifierFolders.get(next)));
        refreshClassifierFolder();
    }

    private void refreshClassifierFolder() {
        if (busy || classifierPath(form("classifier.source", "")) == null) return;
        runBusy(() -> {
            try { refreshClassifierSelection(); }
            catch (Exception failure) {
                LOG.warn("Unable to refresh image classifier folder", failure);
                classifierNotice = safeMessage(failure);
            }
        });
    }

    private void refreshClassifierSelection() throws Exception {
        Path selected = classifierPath(form("classifier.source", ""));
        if (selected == null) return;
        classifierImages = List.copyOf(host.listImageClassifierImages(selected));
        classifierImageCache.clear();
        classifierGroupIndex = 0;
        classifierServer = host.checkImageClassifierServer(
                form("classifier.server-url", "http://localhost:6999"));
        classifierArtwork = host.resolveImageClassifierArtwork(selected, classifierServer).orElse(null);
        classifierNotice = "";
    }

    private void saveClassifierSettings() {
        runBusy(() -> {
            try {
                host.saveImageClassifierSettings(rootFolder, new DesktopUiHost.ImageClassifierSettings(
                        form("classifier.default-folder", ""), boolForm("classifier.show-skip", false),
                        form("classifier.server-url", "http://localhost:6999"),
                        parseTargets(form("classifier.targets", ""))));
                classifierNotice = host.message("gui.image-classifier.dialog.settings-saved.message");
                classifierServer = host.checkImageClassifierServer(
                        form("classifier.server-url", "http://localhost:6999"));
            } catch (Exception failure) {
                LOG.warn("Unable to save image classifier settings", failure);
                classifierNotice = host.message("desktop.ui.action.failed");
            }
        });
    }

    private void scanClassifierFolders() {
        String parent = form("classifier.default-folder", "").trim();
        if (parent.isBlank()) {
            classifierNotice = host.message("gui.image-classifier.validation.folder-path.required");
            rebuild();
            return;
        }
        runBusy(() -> {
            try {
                Path directory = Path.of(parent);
                if (!host.isImageClassifierDirectory(directory)) {
                    classifierNotice = host.message("gui.image-classifier.validation.folder-path.invalid");
                    return;
                }
                List<Path> folders = host.listImageClassifierFolders(directory);
                if (folders.isEmpty()) {
                    classifierNotice = host.message("gui.image-classifier.validation.no-subfolders");
                    return;
                }
                Map<String, Path> paths = new LinkedHashMap<>();
                for (int index = 0; index < folders.size(); index++) paths.put("folder." + index, folders.get(index));
                classifierFolders = List.copyOf(folders);
                classifierPaths = Map.copyOf(paths);
                formValues.put("classifier.source", "folder.0");
                refreshClassifierSelection();
            } catch (Exception failure) {
                LOG.warn("Unable to scan image classifier folders", failure);
                classifierNotice = host.message("desktop.ui.action.failed");
            }
        });
    }

    private void classifyFolder() {
        Path source = classifierPath(form("classifier.source", ""));
        int targetIndex = parseInt(form("classifier.target", "").replace("target.", ""), -1);
        List<DesktopUiHost.ImageClassifierTarget> targets = parseTargets(form("classifier.targets", ""));
        if (source == null || targetIndex < 0 || targetIndex >= targets.size()) return;
        runBusy(() -> {
            try {
                DesktopUiHost.ImageClassifierServer server = host.checkImageClassifierServer(
                        form("classifier.server-url", "http://localhost:6999"));
                if (!server.available()) {
                    classifierNotice = host.message("gui.image-classifier.server.connect-failed");
                    return;
                }
                DesktopUiHost.ImageClassifierArtwork artwork = host.resolveImageClassifierArtwork(source, server)
                        .orElseThrow(() -> new IllegalStateException("artwork metadata unavailable"));
                List<Path> images = List.copyOf(classifierImages);
                if (images.isEmpty()) {
                    classifierNotice = host.message("gui.image-classifier.dialog.no-images-to-classify.message");
                    return;
                }
                Path destination = Path.of(targets.get(targetIndex).folder());
                host.classifyImageFolder(source, images, artwork.artworkId(), destination, server,
                        (detail, remaining) -> false);
                host.deleteImageClassifierFolderIfEmpty(source);
                classifierFolders = classifierFolders.stream().filter(path -> !path.equals(source)).toList();
                Map<String, Path> paths = new LinkedHashMap<>();
                for (int index = 0; index < classifierFolders.size(); index++) {
                    paths.put("folder." + index, classifierFolders.get(index));
                }
                classifierPaths = Map.copyOf(paths);
                classifierImageCache.clear();
                classifierArtwork = null;
                classifierImages = List.of();
                if (classifierFolders.isEmpty()) {
                    formValues.remove("classifier.source");
                    classifierNotice = host.message("gui.image-classifier.dialog.all-folders-complete.message");
                    showDialog("classifier.complete", "gui.image-classifier.dialog.all-folders-complete.title",
                            "gui.image-classifier.dialog.all-folders-complete.message",
                            DesktopUiDocument.DialogStyle.SUCCESS);
                } else {
                    formValues.put("classifier.source", "folder.0");
                    refreshClassifierSelection();
                    classifierNotice = host.message("desktop.ui.classifier.completed", artwork.artworkId());
                }
            } catch (Exception failure) {
                LOG.warn("Unable to classify image folder {}", source, failure);
                classifierNotice = host.message("desktop.ui.action.failed");
            }
        });
    }

    private void runExclusiveTool(String toolName, ThrowingRunnable operation) {
        if (busy) return;
        if (backend.state() != DesktopUiHost.BackendState.RUNNING
                && backend.state() != DesktopUiHost.BackendState.STOPPED) {
            showDialog("tools.backend-busy", "gui.dialog.error.title", "gui.message.backend-busy",
                    DesktopUiDocument.DialogStyle.WARNING);
            rebuild();
            return;
        }
        exclusiveToolName = toolName;
        exclusiveToolStartedAt = System.currentTimeMillis();
        busy = true;
        rebuild();
        executeAsync(() -> {
            boolean restart = backend.state() == DesktopUiHost.BackendState.RUNNING;
            try {
                if (restart) {
                    java.util.concurrent.CountDownLatch stopped = new java.util.concurrent.CountDownLatch(1);
                    if (!host.stopBackend(stopped::countDown)
                            || !stopped.await(Duration.ofSeconds(30).toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)) {
                        throw new IllegalStateException(host.message("gui.message.backend-busy"));
                    }
                }
                operation.run();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                LOG.warn("Desktop tool was interrupted", interrupted);
                showDialog("tools.interrupted", "gui.dialog.error.title", "desktop.ui.tools.operation-failed",
                        DesktopUiDocument.DialogStyle.ERROR);
            } catch (Exception failure) {
                LOG.error("Desktop tool failed", failure);
                showDialog("tools.failed", "gui.dialog.error.title", "desktop.ui.tools.operation-failed",
                        DesktopUiDocument.DialogStyle.ERROR);
            } finally {
                if (restart) host.startBackend(() -> { });
                exclusiveToolName = "";
                exclusiveToolStartedAt = 0L;
                busy = false;
                rebuild();
            }
        });
    }

    private void runBusy(Runnable action) {
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

    private void executeAsync(Runnable action) {
        if (closed) return;
        try {
            worker.execute(action);
        } catch (RejectedExecutionException rejected) {
            if (!closed) throw rejected;
        }
    }

    @Override
    public synchronized void close() throws Exception {
        if (closed) return;
        closed = true;
        worker.shutdownNow();
        AutoCloseable subscription = backendSubscription;
        backendSubscription = null;
        if (subscription != null) subscription.close();
    }

    private void openWeb(String path) { openUri(webUri(path).toString()); }

    private void openUri(String value) {
        runBusy(() -> {
            try { host.openExternalUri(URI.create(value)); }
            catch (Exception failure) { statusNotice = safeMessage(failure); }
        });
    }

    private URI webUri(String path) {
        try {
            Map<String, String> config = host.applicationConfig().readAll(
                    List.of("server.port", "server.ssl.enabled", "ssl.domain"));
            int port = parseInt(config.get("server.port"), serverPort);
            boolean https = Boolean.parseBoolean(config.getOrDefault("server.ssl.enabled", "false"));
            String domain = config.getOrDefault("ssl.domain", "localhost").trim();
            if (domain.isBlank() || domain.contains("://") || domain.contains("/") || domain.contains("@")) {
                domain = "localhost";
            }
            return URI.create((https ? "https" : "http") + "://" + domain + ":" + port
                    + (path.startsWith("/") ? path : "/" + path));
        } catch (Exception failure) {
            return URI.create("http://localhost:" + serverPort + (path.startsWith("/") ? path : "/" + path));
        }
    }

    private synchronized void loadConfiguration() {
        List<ConfigField> fields = new ArrayList<>();
        Map<String, GuiConfigGroupContribution> groups = new LinkedHashMap<>();
        CORE_GROUPS.forEach(group -> groups.put(group.groupId(), group));
        for (GuiConfigFieldContribution spec : coreFields()) {
            fields.add(new ConfigField(new FieldKey(null, spec.key()), null, spec, groups.get(spec.groupId()), null,
                    hasConditions(spec)));
        }
        Set<FieldKey> accepted = new LinkedHashSet<>(fields.stream().map(ConfigField::key).toList());
        List<PluginConfig> plugins = new ArrayList<>();
        for (DesktopUiPluginSource source : currentSources()) {
            List<GuiConfigContribution> contributions;
            try {
                contributions = source.plugin().guiConfigContributions();
            } catch (RuntimeException ignored) {
                continue;
            }
            if (contributions == null) continue;
            List<GuiConfigContribution> safeContributions = contributions.stream()
                    .filter(Objects::nonNull).toList();
            List<WebRouteContribution> routes;
            try {
                List<WebRouteContribution> declared = source.plugin().routes();
                routes = declared == null ? List.of() : declared.stream().filter(Objects::nonNull).toList();
            } catch (RuntimeException ignored) {
                routes = List.of();
            }
            String namespace = pluginNamespace(source);
            plugins.add(new PluginConfig(source.id(), namespace, pluginDisplayNameKey(source),
                    safeContributions, routes));
            for (GuiConfigContribution contribution : safeContributions) {
                for (GuiConfigGroupContribution group : contribution.groups()) {
                    if (validGroup(group)) groups.putIfAbsent(group.groupId(), group);
                }
            }
        }
        for (PluginConfig plugin : plugins) {
            for (GuiConfigContribution contribution : plugin.contributions()) {
                for (GuiConfigFieldContribution spec : contribution.fields()) {
                    FieldKey key = spec == null ? null : new FieldKey(plugin.owner(), spec.key());
                    if (!validField(spec) || !accepted.add(key)) continue;
                    GuiConfigGroupContribution group = groups.get(spec.groupId());
                    if (group == null) {
                        group = new GuiConfigGroupContribution(
                                spec.groupId(), plugin.displayNameKey(), plugin.namespace(), 10_000, true);
                        groups.put(spec.groupId(), group);
                    }
                    String namespace = spec.i18nNamespace() == null
                            ? plugin.namespace() : spec.i18nNamespace();
                    fields.add(new ConfigField(key, plugin.owner(), spec, group, namespace, hasConditions(spec)));
                }
            }
        }
        Set<FieldKey> conditionSources = new LinkedHashSet<>();
        for (ConfigField field : fields) {
            java.util.stream.Stream.concat(field.spec().enabledWhen().stream(), field.spec().visibleWhen().stream())
                    .filter(Objects::nonNull)
                    .forEach(condition -> conditionSources.add(new FieldKey(field.owner(), condition.key())));
        }
        fields = fields.stream().map(field -> new ConfigField(field.key(), field.owner(), field.spec(),
                field.group(), field.namespace(), conditionSources.contains(field.key()))).toList();
        Map<FieldKey, ConfigField> trustedFields = new LinkedHashMap<>();
        fields.forEach(field -> trustedFields.put(field.key(), field));
        Map<String, ConfigSection> sections = new LinkedHashMap<>();
        Set<String> conflictedSections = new LinkedHashSet<>();
        for (PluginConfig plugin : plugins) {
            for (GuiConfigContribution contribution : plugin.contributions()) {
                for (GuiConfigSectionContribution declaration : contribution.sections()) {
                    ConfigSection incoming = configSection(plugin, declaration, groups, trustedFields);
                    if (incoming == null || conflictedSections.contains(incoming.id())) continue;
                    ConfigSection existing = sections.get(incoming.id());
                    if (existing == null) {
                        sections.put(incoming.id(), incoming);
                    } else if (existing.mergeable() && incoming.mergeable()
                            && existing.group().groupId().equals(incoming.group().groupId())
                            && existing.layout() == incoming.layout()) {
                        sections.put(incoming.id(), mergeSections(existing, incoming));
                    } else {
                        sections.remove(incoming.id());
                        conflictedSections.add(incoming.id());
                    }
                }
            }
        }
        Map<String, List<ConfigField>> byOwner = new LinkedHashMap<>();
        for (ConfigField field : fields) byOwner.computeIfAbsent(
                field.owner() == null ? APP_OWNER : field.owner(), ignored -> new ArrayList<>()).add(field);
        Map<FieldKey, String> loaded = new LinkedHashMap<>();
        Set<FieldKey> storedCredentials = new LinkedHashSet<>();
        for (Map.Entry<String, List<ConfigField>> entry : byOwner.entrySet()) {
            boolean app = APP_OWNER.equals(entry.getKey());
            if (!app) {
                loaded.putAll(loadPluginConfiguration(entry.getKey(), entry.getValue(), storedCredentials));
                continue;
            }
            DesktopUiHost.ConfigFile file = host.applicationConfig();
            List<String> keys = entry.getValue().stream().map(field -> field.spec().key()).toList();
            Map<String, String> stored;
            boolean readSucceeded;
            try {
                stored = file.readAll(keys);
                readSucceeded = true;
            } catch (Exception failure) {
                LOG.warn(host.message("gui.config.log.read-failed", safeMessage(failure)), failure);
                stored = Map.of();
                readSucceeded = false;
            }
            Map<String, String> missing = new LinkedHashMap<>();
            for (ConfigField field : entry.getValue()) {
                String value = field.spec().sensitive() ? ""
                        : stored.getOrDefault(field.spec().key(), field.spec().defaultValue());
                loaded.put(field.key(), value == null ? field.spec().defaultValue() : value);
                if (!stored.containsKey(field.spec().key())) {
                    missing.put(field.spec().key(), field.spec().defaultValue());
                }
            }
            if (readSucceeded && !missing.isEmpty()) {
                try {
                    file.writeAll(missing);
                    LOG.info(host.message("gui.config.log.missing-keys.completed",
                            missing.size(), String.join(", ", missing.keySet())));
                } catch (Exception failure) {
                    LOG.warn(host.message("gui.config.log.missing-keys.failed", safeMessage(failure)), failure);
                }
            }
        }
        try {
            Map<String, String> special = host.applicationConfig().readAll(
                    List.of("app.language", "app.gui-provider", "app.theme", "app.config-menu-expand-all"));
            special.forEach((key, value) -> savedValues.put(new FieldKey(null, key), value));
        } catch (Exception ignored) {
            // Defaults below remain active.
        }
        Set<FieldKey> loadedKeys = Set.copyOf(loaded.keySet());
        values.keySet().removeIf(key -> key.key().startsWith("app.") || loadedKeys.contains(key));
        values.putAll(loaded);
        savedValues.putAll(loaded);
        if (Boolean.parseBoolean(loaded.getOrDefault(new FieldKey(null, "debug.enabled"), "false"))) {
            debugUnlocked = true;
        }
        storedCredentialFields = Set.copyOf(storedCredentials);
        configFields = List.copyOf(fields);
        configSections = sections.values().stream()
                .sorted(Comparator.comparingInt((ConfigSection section) -> section.group().order())
                        .thenComparingInt(ConfigSection::order).thenComparing(ConfigSection::id))
                .toList();
        try {
            List<RepositoryConfigEntry> repositories = List.copyOf(
                    host.readPluginRepositories(host.applicationConfig()));
            pluginRepositories = repositories;
            savedPluginRepositories = repositories;
            pluginRepositoriesLoaded = true;
            pluginRepositoriesLoadFailure = "";
            if (repositoryRowIndex(selectedRepositoryRow) >= repositories.size()) selectedRepositoryRow = null;
        } catch (Exception failure) {
            pluginRepositories = List.of();
            savedPluginRepositories = List.of();
            pluginRepositoriesLoaded = false;
            pluginRepositoriesLoadFailure = safeMessage(failure);
            selectedRepositoryRow = null;
        }
        checkFieldDrift();
    }

    private void checkFieldDrift() {
        Map<String, List<ConfigField>> fieldsByOwner = new LinkedHashMap<>();
        for (ConfigField field : configFields) {
            if (field.owner() != null && field.spec().sensitive()) continue;
            fieldsByOwner.computeIfAbsent(field.owner() == null ? APP_OWNER : field.owner(),
                    ignored -> new ArrayList<>()).add(field);
        }
        for (Map.Entry<String, List<ConfigField>> entry : fieldsByOwner.entrySet()) {
            try {
                DesktopUiHost.ConfigFile file = APP_OWNER.equals(entry.getKey())
                        ? host.applicationConfig() : host.pluginConfig(entry.getKey());
                List<String> keys = entry.getValue().stream().map(field -> field.spec().key()).toList();
                Map<String, String> stored = file.readAll(keys);
                for (String key : keys) {
                    if (!stored.containsKey(key)) LOG.warn(host.message("gui.config.log.field-drift", key));
                }
            } catch (Exception failure) {
                LOG.warn(host.message("gui.config.log.field-drift-check.failed", safeMessage(failure)), failure);
            }
        }
    }

    private Map<FieldKey, String> loadPluginConfiguration(String owner, List<ConfigField> fields,
                                                           Set<FieldKey> storedCredentials) {
        DesktopUiHost.ConfigFile application = host.applicationConfig();
        DesktopUiHost.ConfigFile plugin = host.pluginConfig(owner);
        List<String> keys = fields.stream().map(field -> field.spec().key()).toList();
        Map<String, String> pluginValues;
        Map<String, String> legacyValues;
        Map<String, String> credentials;
        try {
            pluginValues = plugin.readAll(keys);
            legacyValues = application.readAll(keys);
            credentials = host.readCredentials(owner);
        } catch (Exception failure) {
            LOG.warn("Unable to read plugin-owned desktop configuration for {}", owner, failure);
            return defaultPluginValues(fields);
        }

        Map<FieldKey, String> loaded = new LinkedHashMap<>();
        Map<String, String> pluginWrites = new LinkedHashMap<>();
        Map<String, String> credentialWrites = new LinkedHashMap<>();
        Set<String> pluginRemovals = new LinkedHashSet<>();
        Set<String> legacyRemovals = new LinkedHashSet<>();
        Set<FieldKey> migratedCredentials = new LinkedHashSet<>();
        for (ConfigField field : fields) {
            String key = field.spec().key();
            if (field.spec().sensitive()) {
                String credential = credentials.getOrDefault(key, "");
                if (credential.isBlank()) {
                    credential = pluginValues.containsKey(key)
                            ? pluginValues.get(key) : legacyValues.getOrDefault(key, "");
                    if (!credential.isBlank()) credentialWrites.put(key, credential);
                }
                if (!credential.isBlank()) migratedCredentials.add(field.key());
                if (pluginValues.containsKey(key)) pluginRemovals.add(key);
                if (legacyValues.containsKey(key)) legacyRemovals.add(key);
                loaded.put(field.key(), "");
                continue;
            }
            String value;
            if (pluginValues.containsKey(key)) {
                value = pluginValues.get(key);
            } else if (legacyValues.containsKey(key)) {
                value = legacyValues.get(key);
                pluginWrites.put(key, value);
            } else {
                value = field.spec().defaultValue();
                pluginWrites.put(key, value);
            }
            if (legacyValues.containsKey(key)) legacyRemovals.add(key);
            loaded.put(field.key(), value == null ? field.spec().defaultValue() : value);
        }

        if (pluginWrites.isEmpty() && credentialWrites.isEmpty()
                && pluginRemovals.isEmpty() && legacyRemovals.isEmpty()) {
            storedCredentials.addAll(migratedCredentials);
            return loaded;
        }
        try {
            DesktopUiHost.ConfigSnapshot applicationSnapshot = application.snapshot();
            DesktopUiHost.ConfigSnapshot pluginSnapshot = plugin.snapshot();
            DesktopUiHost.CredentialSnapshot credentialSnapshot = host.snapshotCredentials(owner);
            try {
                host.withCredentialLocks(Set.of(owner), () -> {
                    if (!pluginWrites.isEmpty()) plugin.writeAll(pluginWrites);
                    if (!credentialWrites.isEmpty()) host.updateCredentials(owner, credentialWrites);
                    if (!pluginRemovals.isEmpty()) plugin.removeAll(pluginRemovals);
                    if (!legacyRemovals.isEmpty()) application.removeAll(legacyRemovals);
                });
            } catch (IOException failure) {
                try { application.restore(applicationSnapshot); }
                catch (IOException rollback) { failure.addSuppressed(rollback); }
                try { plugin.restore(pluginSnapshot); }
                catch (IOException rollback) { failure.addSuppressed(rollback); }
                try { host.restoreCredentials(owner, credentialSnapshot); }
                catch (IOException rollback) { failure.addSuppressed(rollback); }
                throw failure;
            }
            storedCredentials.addAll(migratedCredentials);
            return loaded;
        } catch (Exception failure) {
            LOG.warn("Unable to migrate plugin-owned desktop configuration for {}", owner, failure);
            Map<FieldKey, String> fallback = new LinkedHashMap<>();
            for (ConfigField field : fields) {
                String key = field.spec().key();
                if (field.spec().sensitive()) {
                    fallback.put(field.key(), "");
                    if (!credentials.getOrDefault(key, "").isBlank()) storedCredentials.add(field.key());
                } else {
                    fallback.put(field.key(), pluginValues.getOrDefault(key,
                            legacyValues.getOrDefault(key, field.spec().defaultValue())));
                }
            }
            return fallback;
        }
    }

    private static Map<FieldKey, String> defaultPluginValues(List<ConfigField> fields) {
        Map<FieldKey, String> defaults = new LinkedHashMap<>();
        for (ConfigField field : fields) {
            defaults.put(field.key(), field.spec().sensitive() ? "" : field.spec().defaultValue());
        }
        return defaults;
    }

    private ConfigSection configSection(PluginConfig plugin, GuiConfigSectionContribution spec,
                                        Map<String, GuiConfigGroupContribution> groups,
                                        Map<FieldKey, ConfigField> trustedFields) {
        if (spec == null || !validId(spec.sectionId())) return null;
        GuiConfigGroupContribution group = groups.get(spec.groupId());
        if (group == null || spec.layout() == null) return null;
        String namespace = spec.i18nNamespace() == null ? plugin.namespace() : spec.i18nNamespace();
        List<ConfigLayout> layouts = new ArrayList<>();
        Set<FieldKey> layoutFields = new LinkedHashSet<>();
        for (GuiConfigFieldLayoutContribution layout : spec.fieldLayouts()) {
            if (layout == null || !validOptionalId(layout.cardId())) continue;
            FieldKey key = new FieldKey(plugin.owner(), layout.fieldKey());
            if (!trustedFields.containsKey(key) || !layoutFields.add(key)) continue;
            String layoutNamespace = layout.i18nNamespace() == null
                    ? plugin.namespace() : layout.i18nNamespace();
            layouts.add(new ConfigLayout(key, layout.cardId(),
                    LocalizedText.optional(layoutNamespace, layout.cardLabelKey()), layout.order()));
        }
        layouts.sort(Comparator.comparingInt(ConfigLayout::order).thenComparing(layout -> layout.field().key()));

        Set<String> noticeCardIds = new LinkedHashSet<>();
        if (spec.layout() == GuiConfigSectionLayout.CARD_SWITCHER) {
            layouts.stream().map(ConfigLayout::cardId).filter(Objects::nonNull).forEach(noticeCardIds::add);
        }
        List<ConfigNotice> notices = withoutConflictedKeys(spec.notices().stream().filter(Objects::nonNull)
                .filter(notice -> validId(notice.noticeId()) && !notice.textKey().isBlank())
                .map(notice -> new ConfigNotice(notice.noticeId(),
                        LocalizedText.key(notice.i18nNamespace() == null
                                ? plugin.namespace() : notice.i18nNamespace(), notice.textKey()),
                        Set.copyOf(noticeCardIds), notice.order()))
                .toList(), ConfigNotice::id).stream()
                .sorted(Comparator.comparingInt(ConfigNotice::order).thenComparing(ConfigNotice::id)).toList();

        List<ConfigAction> configActions = withoutConflictedKeys(spec.actions().stream().filter(Objects::nonNull)
                .map(action -> configAction(plugin, action, trustedFields))
                .filter(Objects::nonNull)
                .toList(), action -> action.spec().actionId()).stream()
                .sorted(Comparator.comparingInt((ConfigAction action) -> action.spec().order())
                        .thenComparing(action -> action.spec().actionId())).toList();
        List<ConfigPreset> presets = withoutConflictedKeys(spec.presets().stream().filter(Objects::nonNull)
                .map(preset -> configPreset(plugin, preset, trustedFields))
                .filter(Objects::nonNull)
                .toList(), preset -> preset.spec().presetId()).stream()
                .sorted(Comparator.comparingInt((ConfigPreset preset) -> preset.spec().order())
                        .thenComparing(preset -> preset.spec().presetId())).toList();

        return new ConfigSection(spec.sectionId(), group, spec.layout(), spec.order(), spec.mergeable(),
                spec.contributesGroupVisibility(),
                LocalizedText.optional(namespace, spec.titleKey()),
                LocalizedText.optional(namespace, spec.helpKey()),
                LocalizedText.optional(namespace, spec.layoutLabelKey()),
                LocalizedText.optional(namespace, spec.layoutHelpKey()),
                LocalizedText.optional(namespace, spec.presetLabelKey()),
                LocalizedText.optional(namespace, spec.presetHelpKey()),
                List.copyOf(layouts), configActions, presets, notices);
    }

    private ConfigAction configAction(PluginConfig plugin, GuiConfigActionContribution action,
                                      Map<FieldKey, ConfigField> trustedFields) {
        if (!validId(action.actionId()) || action.labelKey() == null || action.labelKey().isBlank()
                || !validOptionalId(action.cardId()) || !validGuiEndpoint(action.endpoint())
                || !hasExactGuiPostRoute(plugin.routes(), action.endpoint())
                || !validActionPayload(plugin.owner(), action.payloadFields(), trustedFields)
                || !validActionResult(action.resultRules(), action.resultSummary())) return null;
        String namespace = action.i18nNamespace() == null ? plugin.namespace() : action.i18nNamespace();
        return new ConfigAction(plugin.owner(), namespace, action,
                LocalizedText.key(namespace, action.labelKey()),
                LocalizedText.optional(namespace, action.helpKey()),
                LocalizedText.optional(namespace, action.sendingNoticeKey()),
                action.readTimeoutMillis() <= 0 ? 30_000 : action.readTimeoutMillis());
    }

    private ConfigPreset configPreset(PluginConfig plugin, GuiConfigPresetContribution preset,
                                      Map<FieldKey, ConfigField> trustedFields) {
        if (!validId(preset.presetId()) || preset.labelKey() == null || preset.labelKey().isBlank()
                || !validOptionalId(preset.cardId())) return null;
        List<String> references = new ArrayList<>(preset.values().keySet());
        references.addAll(preset.lockedFieldKeys());
        if (preset.matchFieldKey() != null) references.add(preset.matchFieldKey());
        for (String key : references) {
            ConfigField field = trustedFields.get(new FieldKey(plugin.owner(), key));
            if (field == null || field.spec().sensitive() || field.spec().type() == GuiConfigFieldType.PASSWORD) {
                return null;
            }
        }
        for (Map.Entry<String, String> entry : preset.values().entrySet()) {
            ConfigField field = trustedFields.get(new FieldKey(plugin.owner(), entry.getKey()));
            if (!validPresetValue(field, entry.getValue())) return null;
        }
        String namespace = preset.i18nNamespace() == null ? plugin.namespace() : preset.i18nNamespace();
        return new ConfigPreset(plugin.owner(), namespace, preset,
                LocalizedText.key(namespace, preset.labelKey()),
                LocalizedText.optional(namespace, preset.helpKey()));
    }

    private boolean validPresetValue(ConfigField field, String value) {
        if (field == null) return false;
        try {
            host.requireSafeConfigValue(value);
            return switch (field.spec().type()) {
                case BOOL -> "true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value);
                case ENUM -> field.spec().enumValues().contains(value);
                case INT -> {
                    int number = Integer.parseInt(value);
                    yield (field.spec().minValue() == null || number >= field.spec().minValue())
                            && (field.spec().maxValue() == null || number <= field.spec().maxValue());
                }
                case PORT -> {
                    int port = Integer.parseInt(value);
                    yield port >= 1 && port <= 65_535;
                }
                case PATH_DIR, PATH_FILE, STRING, PASSWORD -> true;
            };
        } catch (Exception ignored) {
            return false;
        }
    }

    private static ConfigSection mergeSections(ConfigSection first, ConfigSection second) {
        List<ConfigLayout> layouts = new ArrayList<>(first.layouts());
        layouts.addAll(second.layouts());
        layouts.sort(Comparator.comparingInt(ConfigLayout::order).thenComparing(layout -> layout.field().key()));
        List<ConfigAction> mergedActions = new ArrayList<>(first.actions());
        mergedActions.addAll(second.actions());
        List<ConfigAction> actions = new ArrayList<>(withoutConflictedKeys(mergedActions,
                action -> List.of(action.owner(), action.spec().actionId())));
        actions.sort(Comparator.comparingInt((ConfigAction action) -> action.spec().order())
                .thenComparing(action -> action.spec().actionId()));
        List<ConfigPreset> mergedPresets = new ArrayList<>(first.presets());
        mergedPresets.addAll(second.presets());
        List<ConfigPreset> presets = new ArrayList<>(withoutConflictedKeys(mergedPresets,
                preset -> List.of(preset.owner(), preset.spec().presetId())));
        presets.sort(Comparator.comparingInt((ConfigPreset preset) -> preset.spec().order())
                .thenComparing(preset -> preset.spec().presetId()));
        Map<String, ConfigNotice> notices = new LinkedHashMap<>();
        java.util.stream.Stream.concat(first.notices().stream(), second.notices().stream())
                .sorted(Comparator.comparingInt(ConfigNotice::order).thenComparing(ConfigNotice::id))
                .forEach(notice -> notices.merge(notice.id(), notice, (existing, incoming) -> {
                    if (existing.cardIds().isEmpty() || incoming.cardIds().isEmpty()) {
                        return new ConfigNotice(existing.id(), existing.text(), Set.of(), existing.order());
                    }
                    Set<String> cardIds = new LinkedHashSet<>(existing.cardIds());
                    cardIds.addAll(incoming.cardIds());
                    return new ConfigNotice(existing.id(), existing.text(), Set.copyOf(cardIds), existing.order());
                }));
        return new ConfigSection(first.id(), first.group(), first.layout(), Math.min(first.order(), second.order()),
                true, first.contributesGroupVisibility() || second.contributesGroupVisibility(),
                first.title() == null ? second.title() : first.title(),
                first.help() == null ? second.help() : first.help(),
                first.layoutLabel() == null ? second.layoutLabel() : first.layoutLabel(),
                first.layoutHelp() == null ? second.layoutHelp() : first.layoutHelp(),
                first.presetLabel() == null ? second.presetLabel() : first.presetLabel(),
                first.presetHelp() == null ? second.presetHelp() : first.presetHelp(),
                List.copyOf(layouts), List.copyOf(actions), List.copyOf(presets), List.copyOf(notices.values()));
    }

    private static <T, K> List<T> withoutConflictedKeys(List<T> items, Function<T, K> key) {
        Map<K, T> accepted = new LinkedHashMap<>();
        Set<K> conflicted = new LinkedHashSet<>();
        for (T item : items) {
            K value = key.apply(item);
            if (conflicted.contains(value)) continue;
            if (accepted.putIfAbsent(value, item) != null) {
                accepted.remove(value);
                conflicted.add(value);
            }
        }
        return List.copyOf(accepted.values());
    }

    private static boolean validActionPayload(String owner, List<GuiConfigActionPayloadField> mappings,
                                              Map<FieldKey, ConfigField> trustedFields) {
        Set<String> paths = new LinkedHashSet<>();
        for (GuiConfigActionPayloadField mapping : mappings) {
            if (mapping == null || !safeJsonPath(mapping.payloadPath(), false, false)
                    || (mapping.fieldKey() == null && mapping.literalValue().isBlank())) return false;
            if (mapping.fieldKey() != null
                    && !trustedFields.containsKey(new FieldKey(owner, mapping.fieldKey()))) return false;
            for (String path : paths) {
                if (path.equals(mapping.payloadPath()) || path.startsWith(mapping.payloadPath() + ".")
                        || mapping.payloadPath().startsWith(path + ".")) return false;
            }
            paths.add(mapping.payloadPath());
        }
        return true;
    }

    private static boolean validActionResult(List<GuiConfigActionResultRule> rules,
                                             GuiConfigActionResultSummary summary) {
        boolean hasSummary = summary != null;
        if (summary != null && (!safeJsonPath(summary.arrayPath(), false, true)
                || !safeJsonPath(summary.labelPath(), false, true)
                || !safeJsonPath(summary.statusPath(), true, true)
                || !safeJsonPath(summary.detailPath(), true, true))) return false;
        for (GuiConfigActionResultRule rule : rules) {
            if (rule == null || rule.noticeKey() == null || rule.noticeKey().isBlank()) return false;
            for (GuiConfigActionResultCondition condition : rule.conditions()) {
                if (condition == null || !validResultReference(condition.source(), condition.path(), hasSummary)) {
                    return false;
                }
            }
            for (GuiConfigActionResultArgument argument : rule.arguments()) {
                if (argument == null || !validResultReference(argument.source(), argument.path(), hasSummary)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean validResultReference(GuiConfigActionResultSource source, String path,
                                                boolean hasSummary) {
        if (source == null) return false;
        return switch (source) {
            case JSON -> safeJsonPath(path, false, true);
            case SUMMARY -> hasSummary && nullToEmpty(path).isBlank();
            case REACHABLE, HTTP_2XX, HTTP_STATUS, HTTP_STATUS_TEXT -> nullToEmpty(path).isBlank();
        };
    }

    private static boolean validGuiEndpoint(String endpoint) {
        return endpoint != null && !endpoint.isBlank() && !endpoint.startsWith("/")
                && !endpoint.contains("://") && !endpoint.contains("?") && !endpoint.contains("#")
                && !endpoint.contains("\\") && java.util.Arrays.stream(endpoint.split("/"))
                .allMatch(part -> validId(part) && !".".equals(part) && !"..".equals(part));
    }

    private static boolean hasExactGuiPostRoute(List<WebRouteContribution> routes, String endpoint) {
        String path = "/api/gui/" + endpoint;
        return routes.stream().anyMatch(route -> path.equals(route.pathPattern())
                && route.accessPolicy() == AccessPolicy.GUI && route.acceptsMethod(HttpMethod.POST));
    }

    private static boolean validId(String value) {
        return value != null && value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,120}");
    }

    private static boolean validOptionalId(String value) {
        return value == null || validId(value);
    }

    private static String pluginNamespace(DesktopUiPluginSource source) {
        try {
            String namespace = source.plugin().displayNamespace();
            return namespace == null || namespace.isBlank() ? source.id() : namespace;
        } catch (RuntimeException ignored) {
            return source.id();
        }
    }

    private static String pluginDisplayNameKey(DesktopUiPluginSource source) {
        try {
            String key = source.plugin().displayName();
            return key == null || key.isBlank() ? source.id() : key;
        } catch (RuntimeException ignored) {
            return source.id();
        }
    }

    private List<GuiConfigFieldContribution> coreFields() {
        List<GuiConfigFieldContribution> fields = new ArrayList<>();
        int order = 0;
        fields.add(core("server.port", GuiConfigGroups.SERVER, GuiConfigFieldType.PORT, "6999",
                GuiConfigEffect.PROCESS_RESTART, 1, 65_535, order++));
        fields.add(core("database.maximum-pool-size", GuiConfigGroups.SERVER, GuiConfigFieldType.INT, "28",
                GuiConfigEffect.BACKEND_RESTART, 8, null, order++));
        fields.add(core("debug.enabled", GuiConfigGroups.SERVER, GuiConfigFieldType.BOOL, "false",
                GuiConfigEffect.BACKEND_RESTART, null, null, order++));
        fields.add(core("download.root-folder", GuiConfigGroups.DOWNLOAD, GuiConfigFieldType.PATH_DIR,
                "pixiv-download", GuiConfigEffect.PROCESS_RESTART, null, null, order++));
        fields.add(core("download.user-flat-folder", GuiConfigGroups.DOWNLOAD, GuiConfigFieldType.BOOL,
                "false", GuiConfigEffect.HOT_RELOAD, null, null, order++));
        fields.add(core("download.max-concurrent", GuiConfigGroups.DOWNLOAD, GuiConfigFieldType.INT,
                "10", GuiConfigEffect.BACKEND_RESTART, 1, null, order++));
        fields.add(core("plugin-catalog.enabled", GuiConfigGroups.PLUGINS, GuiConfigFieldType.BOOL,
                "true", GuiConfigEffect.BACKEND_RESTART, null, null, order++));
        fields.add(core("plugin-catalog.official-repository-enabled", GuiConfigGroups.PLUGINS,
                GuiConfigFieldType.BOOL, "true", GuiConfigEffect.BACKEND_RESTART, null, null, order++,
                List.of(GuiConfigCondition.isTrue("plugin-catalog.enabled")), List.of(), List.of()));
        fields.add(core("plugin-catalog.connect-timeout-ms", GuiConfigGroups.PLUGINS, GuiConfigFieldType.INT,
                "15000", GuiConfigEffect.BACKEND_RESTART, 1, null, order++));
        fields.add(core("plugin-catalog.read-timeout-ms", GuiConfigGroups.PLUGINS, GuiConfigFieldType.INT,
                "60000", GuiConfigEffect.BACKEND_RESTART, 1, null, order++));
        fields.add(core("plugin-catalog.max-manifest-bytes", GuiConfigGroups.PLUGINS, GuiConfigFieldType.INT,
                "1048576", GuiConfigEffect.BACKEND_RESTART, 1, null, order++));
        fields.add(core("plugin-catalog.max-package-bytes", GuiConfigGroups.PLUGINS, GuiConfigFieldType.INT,
                "104857600", GuiConfigEffect.BACKEND_RESTART, 1, null, order++));
        fields.add(core("proxy.enabled", GuiConfigGroups.PROXY, GuiConfigFieldType.BOOL, "true",
                GuiConfigEffect.HOT_RELOAD, null, null, order++));
        fields.add(core("proxy.host", GuiConfigGroups.PROXY, GuiConfigFieldType.STRING, host.defaultProxyHost(),
                GuiConfigEffect.HOT_RELOAD, null, null, order++, List.of(GuiConfigCondition.isTrue("proxy.enabled")),
                List.of(), List.of()));
        fields.add(core("proxy.port", GuiConfigGroups.PROXY, GuiConfigFieldType.PORT,
                Integer.toString(host.defaultProxyPort()), GuiConfigEffect.HOT_RELOAD, 1, 65_535, order++,
                List.of(GuiConfigCondition.isTrue("proxy.enabled")), List.of(), List.of()));
        fields.add(core("guest-invite.request-limit-minute", GuiConfigGroups.GUEST_INVITE, GuiConfigFieldType.INT,
                "300", GuiConfigEffect.HOT_RELOAD, 0, null, order++));
        fields.add(core("guest-invite.static-resource-request-limit-minute", GuiConfigGroups.GUEST_INVITE,
                GuiConfigFieldType.INT, "1200", GuiConfigEffect.HOT_RELOAD, 0, null, order++));
        fields.add(core("guest-invite.tts-request-limit-minute", GuiConfigGroups.GUEST_INVITE,
                GuiConfigFieldType.INT, "30", GuiConfigEffect.HOT_RELOAD, 0, null, order++));
        fields.add(core("setup.login-rate-limit-minute", GuiConfigGroups.SECURITY, GuiConfigFieldType.INT,
                "10", GuiConfigEffect.HOT_RELOAD, 0, null, order++));
        fields.add(core("maintenance.enabled", GuiConfigGroups.MAINTENANCE, GuiConfigFieldType.BOOL,
                "true", GuiConfigEffect.HOT_RELOAD, null, null, order++));
        for (String day : List.of("monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday")) {
            String enabledKey = "maintenance." + day + ".enabled";
            fields.add(core(enabledKey, GuiConfigGroups.MAINTENANCE, GuiConfigFieldType.BOOL,
                    day.equals("monday") ? "true" : "false", GuiConfigEffect.HOT_RELOAD, null, null, order++,
                    List.of(GuiConfigCondition.isTrue("maintenance.enabled")), List.of(), List.of(),
                    "gui.config.field." + enabledKey + ".label", "gui.config.field.maintenance.day.enabled.help"));
            String timeKey = "maintenance." + day + ".time";
            List<GuiConfigCondition> conditions = List.of(GuiConfigCondition.isTrue("maintenance.enabled"),
                    GuiConfigCondition.isTrue(enabledKey));
            fields.add(core(timeKey, GuiConfigGroups.MAINTENANCE, GuiConfigFieldType.STRING,
                    host.defaultMaintenanceTime(), GuiConfigEffect.HOT_RELOAD, null, null, order++, conditions,
                    conditions, List.of(), "gui.config.field." + timeKey + ".label",
                    "gui.config.field.maintenance.day.time.help"));
        }
        fields.add(core("ssl.domain", GuiConfigGroups.HTTPS, GuiConfigFieldType.STRING, "localhost",
                GuiConfigEffect.HOT_RELOAD, null, null, order++));
        fields.add(core("server.ssl.enabled", GuiConfigGroups.HTTPS, GuiConfigFieldType.BOOL, "false",
                GuiConfigEffect.BACKEND_RESTART, null, null, order++));
        fields.add(core("ssl.type", GuiConfigGroups.HTTPS, GuiConfigFieldType.ENUM, "pem",
                GuiConfigEffect.BACKEND_RESTART, null, null, order++,
                List.of(GuiConfigCondition.isTrue("server.ssl.enabled")), List.of(), List.of("pem", "jks")));
        fields.add(core("server.ssl.certificate", GuiConfigGroups.HTTPS, GuiConfigFieldType.PATH_FILE, "",
                GuiConfigEffect.BACKEND_RESTART, null, null, order++, sslPemConditions(),
                List.of(GuiConfigCondition.equalsTo("ssl.type", "pem")), List.of()));
        fields.add(core("server.ssl.certificate-private-key", GuiConfigGroups.HTTPS,
                GuiConfigFieldType.PATH_FILE, "", GuiConfigEffect.BACKEND_RESTART, null, null, order++,
                sslPemConditions(), List.of(GuiConfigCondition.equalsTo("ssl.type", "pem")), List.of()));
        fields.add(core("server.ssl.key-store-type", GuiConfigGroups.HTTPS, GuiConfigFieldType.ENUM, "JKS",
                GuiConfigEffect.BACKEND_RESTART, null, null, order++, sslJksConditions(),
                List.of(GuiConfigCondition.equalsTo("ssl.type", "jks")), List.of("JKS", "PKCS12")));
        fields.add(core("server.ssl.key-store", GuiConfigGroups.HTTPS, GuiConfigFieldType.PATH_FILE, "",
                GuiConfigEffect.BACKEND_RESTART, null, null, order++, sslJksConditions(),
                List.of(GuiConfigCondition.equalsTo("ssl.type", "jks")), List.of()));
        fields.add(core("server.ssl.key-store-password", GuiConfigGroups.HTTPS, GuiConfigFieldType.PASSWORD, "",
                GuiConfigEffect.BACKEND_RESTART, null, null, order++, sslJksConditions(),
                List.of(GuiConfigCondition.equalsTo("ssl.type", "jks")), List.of()));
        fields.add(core("server.trusted-proxy-cidrs", GuiConfigGroups.HTTPS, GuiConfigFieldType.STRING, "",
                GuiConfigEffect.BACKEND_RESTART, null, null, order++));
        fields.add(core("ssl.http-redirect", GuiConfigGroups.HTTPS, GuiConfigFieldType.BOOL, "false",
                GuiConfigEffect.BACKEND_RESTART, null, null, order++,
                List.of(GuiConfigCondition.isTrue("server.ssl.enabled")), List.of(), List.of()));
        fields.add(core("ssl.http-redirect-port", GuiConfigGroups.HTTPS, GuiConfigFieldType.PORT, "80",
                GuiConfigEffect.BACKEND_RESTART, 1, 65_535, order++,
                List.of(GuiConfigCondition.isTrue("server.ssl.enabled"), GuiConfigCondition.isTrue("ssl.http-redirect")),
                List.of(), List.of()));
        fields.add(core("update.enabled", GuiConfigGroups.UPDATE, GuiConfigFieldType.BOOL, "true",
                GuiConfigEffect.HOT_RELOAD, null, null, order++));
        fields.add(core("update.manifest-url", GuiConfigGroups.UPDATE, GuiConfigFieldType.STRING,
                host.defaultUpdateManifestUrl(), GuiConfigEffect.HOT_RELOAD, null, null, order++,
                List.of(GuiConfigCondition.isTrue("update.enabled")), List.of(), List.of()));
        fields.add(core("update.nightly-manifest-url", GuiConfigGroups.UPDATE, GuiConfigFieldType.STRING,
                host.defaultNightlyUpdateManifestUrl(), GuiConfigEffect.HOT_RELOAD, null, null, order++,
                List.of(GuiConfigCondition.isTrue("update.enabled"), GuiConfigCondition.isTrue("update.check-nightly")),
                List.of(), List.of()));
        fields.add(core("update.auto-check", GuiConfigGroups.UPDATE, GuiConfigFieldType.BOOL, "true",
                GuiConfigEffect.HOT_RELOAD, null, null, order++,
                List.of(GuiConfigCondition.isTrue("update.enabled")), List.of(), List.of()));
        fields.add(core("update.check-nightly", GuiConfigGroups.UPDATE, GuiConfigFieldType.BOOL,
                Boolean.toString(host.currentVersionNightly()), GuiConfigEffect.HOT_RELOAD, null, null, order++,
                List.of(GuiConfigCondition.isTrue("update.enabled")), List.of(), List.of()));
        fields.add(core("schedule.enabled", GuiConfigGroups.SCHEDULE, GuiConfigFieldType.BOOL, "true",
                GuiConfigEffect.HOT_RELOAD, null, null, order++));
        fields.add(core("schedule.tick-interval-ms", GuiConfigGroups.SCHEDULE, GuiConfigFieldType.INT, "60000",
                GuiConfigEffect.BACKEND_RESTART, 1000, null, order++, scheduleConditions(), List.of(), List.of()));
        fields.add(core("schedule.max-tasks", GuiConfigGroups.SCHEDULE, GuiConfigFieldType.INT, "100",
                GuiConfigEffect.HOT_RELOAD, 1, null, order++, scheduleConditions(), List.of(), List.of()));
        fields.add(core("schedule.inbox-check-every", GuiConfigGroups.SCHEDULE, GuiConfigFieldType.INT, "500",
                GuiConfigEffect.HOT_RELOAD, 1, null, order++, scheduleConditions(), List.of(), List.of()));
        fields.add(core("schedule.auth-failure-circuit-breaker", GuiConfigGroups.SCHEDULE, GuiConfigFieldType.INT, "5",
                GuiConfigEffect.HOT_RELOAD, 1, null, order++, scheduleConditions(), List.of(), List.of()));
        fields.add(core("schedule.pending-max-attempts", GuiConfigGroups.SCHEDULE, GuiConfigFieldType.INT, "5",
                GuiConfigEffect.HOT_RELOAD, 1, null, order++, scheduleConditions(), List.of(), List.of()));
        fields.add(core("schedule.overuse-defer-default-minutes", GuiConfigGroups.SCHEDULE, GuiConfigFieldType.INT,
                "60", GuiConfigEffect.HOT_RELOAD, 60, null, order++, scheduleConditions(), List.of(), List.of()));
        return List.copyOf(fields);
    }

    private void loadToolDefaults() {
        try {
            DesktopUiHost.BackfillOptions defaults = host.defaultBackfillOptions();
            formValues.putIfAbsent("tools.backfill.db", defaults.dbPath());
            formValues.putIfAbsent("tools.backfill.proxy-host", defaults.proxyHost());
            formValues.putIfAbsent("tools.backfill.proxy-port", Integer.toString(defaults.proxyPort()));
            formValues.putIfAbsent("tools.backfill.proxy", Boolean.toString(defaults.useProxy()));
            formValues.putIfAbsent("tools.backfill.delay", Long.toString(defaults.delayMs()));
            formValues.putIfAbsent("tools.backfill.limit", Integer.toString(defaults.limit()));
            formValues.putIfAbsent("tools.backfill.dry", Boolean.toString(defaults.dryRun()));
        } catch (RuntimeException ignored) {
            // Page defaults remain usable.
        }
        DesktopUiHost.ImageClassifierSettings settings = classifierSettings();
        formValues.putIfAbsent("classifier.default-folder", settings.defaultFolder());
        formValues.putIfAbsent("classifier.server-url", settings.serverUrl());
        formValues.putIfAbsent("classifier.show-skip", Boolean.toString(settings.showSkipButton()));
        formValues.putIfAbsent("classifier.targets", encodeTargets(settings.targets()));
    }

    private DesktopUiHost.ImageClassifierSettings classifierSettings() {
        try { return host.loadImageClassifierSettings(rootFolder); }
        catch (Exception ignored) { return new DesktopUiHost.ImageClassifierSettings("", false,
                "http://localhost:6999", List.of()); }
    }

    private DesktopUiHost.FfmpegProxy proxySettings() {
        try {
            Map<String, String> values = host.applicationConfig().readAll(
                    List.of("proxy.enabled", "proxy.host", "proxy.port"));
            boolean enabled = Boolean.parseBoolean(values.getOrDefault("proxy.enabled", "false"));
            String proxyHost = values.getOrDefault("proxy.host", host.defaultProxyHost());
            int proxyPort = parseInt(values.get("proxy.port"), host.defaultProxyPort());
            return new DesktopUiHost.FfmpegProxy(enabled && !proxyHost.isBlank() && proxyPort > 0,
                    proxyHost, proxyPort);
        } catch (Exception ignored) {
            return new DesktopUiHost.FfmpegProxy(false, "", 0);
        }
    }

    private List<DesktopUiHost.ImageClassifierTarget> parseTargets(String encoded) {
        List<DesktopUiHost.ImageClassifierTarget> targets = new ArrayList<>();
        for (String line : nullToEmpty(encoded).lines().toList()) {
            String value = line.trim();
            if (value.isBlank()) continue;
            int separator = value.indexOf('|');
            String folder = (separator < 0 ? value : value.substring(0, separator)).trim();
            String remark = separator < 0 ? "" : value.substring(separator + 1).trim();
            if (!folder.isBlank()) targets.add(new DesktopUiHost.ImageClassifierTarget(folder, remark));
        }
        return List.copyOf(targets);
    }

    private static String encodeTargets(List<DesktopUiHost.ImageClassifierTarget> targets) {
        return targets.stream().map(target -> target.folder() + (target.remark().isBlank()
                ? "" : "|" + target.remark())).collect(java.util.stream.Collectors.joining(System.lineSeparator()));
    }

    private List<Path> classifierFolders() { return classifierFolders; }
    private String classifierId(Path path) {
        return classifierPaths.entrySet().stream().filter(entry -> entry.getValue().equals(path))
                .map(Map.Entry::getKey).findFirst().orElse("folder.unknown");
    }
    private Path classifierPath(String id) { return classifierPaths.get(id); }

    private boolean visible(ConfigField field) {
        return (!"debug.enabled".equals(field.spec().key()) || debugUnlocked)
                && conditions(field, field.spec().visibleWhen());
    }
    private boolean enabled(ConfigField field) { return conditions(field, field.spec().enabledWhen()); }

    private boolean conditions(ConfigField field, List<GuiConfigCondition> conditions) {
        if (conditions == null) return true;
        for (GuiConfigCondition condition : conditions) {
            if (condition == null || condition.operator() == null) return false;
            String actual = values.getOrDefault(new FieldKey(field.owner(), condition.key()), "");
            String expected = condition.value() == null ? "" : condition.value();
            boolean matches = switch (condition.operator()) {
                case TRUE -> Boolean.parseBoolean(actual);
                case FALSE -> !Boolean.parseBoolean(actual);
                case EQUALS -> actual.equals(expected);
                case NOT_EQUALS -> !actual.equals(expected);
                case BLANK -> actual.isBlank();
                case NOT_BLANK -> !actual.isBlank();
            };
            if (!matches) return false;
        }
        return true;
    }

    private static boolean hasConditions(GuiConfigFieldContribution spec) {
        return !spec.enabledWhen().isEmpty() || !spec.visibleWhen().isEmpty();
    }

    private boolean validField(GuiConfigFieldContribution field) {
        if (field == null || field.type() == null || field.effect() == null
                || field.key() == null || field.key().isBlank() || field.labelKey() == null
                || field.labelKey().isBlank() || field.groupId() == null || field.groupId().isBlank()) return false;
        try {
            host.requireSafeConfigKey(field.key());
            host.requireSafeConfigValue(field.defaultValue());
            if (field.type() == GuiConfigFieldType.ENUM) {
                if (field.enumValues().isEmpty() || !field.enumValues().contains(field.defaultValue())
                        || field.enumValues().stream().anyMatch(Objects::isNull)
                        || new LinkedHashSet<>(field.enumValues()).size() != field.enumValues().size()) return false;
                for (String value : field.enumValues()) host.requireSafeConfigValue(value);
            }
            if (field.type() == GuiConfigFieldType.INT || field.type() == GuiConfigFieldType.PORT) {
                int minimum = field.type() == GuiConfigFieldType.PORT ? 1
                        : field.minValue() == null ? Integer.MIN_VALUE : field.minValue();
                int maximum = field.type() == GuiConfigFieldType.PORT ? 65_535
                        : field.maxValue() == null ? Integer.MAX_VALUE : field.maxValue();
                int value = Integer.parseInt(field.defaultValue());
                if (minimum > maximum || value < minimum || value > maximum) return false;
            }
            return bindingId(new FieldKey("third-party", field.key())).length() <= 128;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean validGroup(GuiConfigGroupContribution group) {
        return group != null && group.visibleInTabs() && group.groupId() != null
                && group.groupId().matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,80}")
                && group.labelKey() != null && !group.labelKey().isBlank();
    }

    private List<DesktopUiPluginSource> currentSources() {
        List<DesktopUiPluginSource> sources = rebuildSources;
        return sources == null ? loadCurrentSources() : sources;
    }

    private List<DesktopUiPluginSource> loadCurrentSources() {
        try {
            List<DesktopUiPluginSource> sources = pluginSources.get();
            return sources == null ? List.of() : List.copyOf(sources);
        } catch (RuntimeException ignored) {
            return List.of();
        }
    }

    private TextToken pluginToken(DesktopUiPluginSource source, String key, String fallback) {
        return token(source.plugin().displayNamespace(), key, fallback);
    }

    private TextToken enumToken(ConfigField field, String option) {
        String labelKey = field.spec().enumValueLabelKeys().get(option);
        return labelKey == null ? TextToken.raw(option) : token(field.namespace(), labelKey, option);
    }

    private static GuiConfigGroupContribution group(String id, String labelKey, int order) {
        return new GuiConfigGroupContribution(id, labelKey, null, order, true);
    }

    private static GuiConfigFieldContribution core(String key, String group, GuiConfigFieldType type,
                                                   String defaultValue, GuiConfigEffect effect,
                                                   Integer minimum, Integer maximum, int order) {
        return core(key, group, type, defaultValue, effect, minimum, maximum, order,
                List.of(), List.of(), type == GuiConfigFieldType.ENUM ? List.of(defaultValue) : List.of());
    }

    private static GuiConfigFieldContribution core(String key, String group, GuiConfigFieldType type,
                                                   String defaultValue, GuiConfigEffect effect,
                                                   Integer minimum, Integer maximum, int order,
                                                   List<GuiConfigCondition> enabled, List<GuiConfigCondition> visible,
                                                   List<String> enumValues) {
        return core(key, group, type, defaultValue, effect, minimum, maximum, order, enabled, visible,
                enumValues, "gui.config.field." + key + ".label", "gui.config.field." + key + ".help");
    }

    private static GuiConfigFieldContribution core(String key, String group, GuiConfigFieldType type,
                                                   String defaultValue, GuiConfigEffect effect,
                                                   Integer minimum, Integer maximum, int order,
                                                   List<GuiConfigCondition> enabled, List<GuiConfigCondition> visible,
                                                   List<String> enumValues, String labelKey, String helpKey) {
        return new GuiConfigFieldContribution(key, group, labelKey, helpKey, null, type, defaultValue, order,
                type == GuiConfigFieldType.PASSWORD, effect, enumValues, enabled, visible,
                minimum, maximum, true, Map.of());
    }

    private static List<GuiConfigCondition> sslPemConditions() {
        return List.of(GuiConfigCondition.isTrue("server.ssl.enabled"), GuiConfigCondition.equalsTo("ssl.type", "pem"));
    }
    private static List<GuiConfigCondition> sslJksConditions() {
        return List.of(GuiConfigCondition.isTrue("server.ssl.enabled"), GuiConfigCondition.equalsTo("ssl.type", "jks"));
    }
    private static List<GuiConfigCondition> scheduleConditions() {
        return List.of(GuiConfigCondition.isTrue("schedule.enabled"));
    }

    private static DesktopUiNode.Container column(String id, DesktopUiNode... children) {
        return column(id, List.of(children));
    }
    private static DesktopUiNode.Container column(String id, List<? extends DesktopUiNode> children) {
        return new DesktopUiNode.Container(id, ContainerLayout.COLUMN, 1, 10, Alignment.STRETCH,
                List.copyOf(children));
    }
    private static DesktopUiNode.Container row(String id, DesktopUiNode... children) {
        return row(id, List.of(children));
    }
    private static DesktopUiNode.Container row(String id, List<? extends DesktopUiNode> children) {
        return new DesktopUiNode.Container(id, ContainerLayout.FLOW, 1, 8, Alignment.START,
                List.copyOf(children));
    }
    private static DesktopUiNode.Container endRow(String id, DesktopUiNode... children) {
        return new DesktopUiNode.Container(id, ContainerLayout.FLOW, 1, 8, Alignment.END,
                List.of(children));
    }
    private static DesktopUiNode.FormRow formRow(String id, String labelKey, String helpKey,
                                                 DesktopUiNode content) {
        return new DesktopUiNode.FormRow(id, key(labelKey), helpKey == null ? null : key(helpKey),
                content, null);
    }
    private static DesktopUiNode.Scroll scroll(String id, DesktopUiNode child) {
        return new DesktopUiNode.Scroll(id, child);
    }
    private static DesktopUiNode.Group group(String id, String titleKey, DesktopUiNode child) {
        return new DesktopUiNode.Group(id, key(titleKey), child);
    }
    private static DesktopUiNode.Text text(String id, String messageKey, TextStyle style) {
        return new DesktopUiNode.Text(id, key(messageKey), style, true, style == TextStyle.CODE);
    }
    private static DesktopUiNode.Text secondary(String id, String messageKey) {
        return text(id, messageKey, TextStyle.SECONDARY);
    }
    private static DesktopUiNode.Text bullet(String id, String messageKey) {
        return text(id, messageKey, TextStyle.BULLET);
    }
    private static DesktopUiNode.Text raw(String id, String value, TextStyle style) {
        return new DesktopUiNode.Text(id, TextToken.raw(value == null || value.isBlank() ? "--" : value),
                style, true, style == TextStyle.CODE);
    }
    private static DesktopUiNode.Text alignedText(String id, String messageKey, TextStyle style,
                                                   DesktopUiNode.TextAlignment alignment) {
        return new DesktopUiNode.Text(id, key(messageKey), style, true, false, alignment);
    }
    private static DesktopUiNode.Text alignedRaw(String id, String value, TextStyle style,
                                                  DesktopUiNode.TextAlignment alignment) {
        return new DesktopUiNode.Text(id, TextToken.raw(value == null || value.isBlank() ? "--" : value),
                style, true, style == TextStyle.CODE, alignment);
    }
    private static DesktopUiNode.Text status(String id, String value) {
        return raw(id, value, TextStyle.CAPTION);
    }
    private static DesktopUiNode.TextInput input(String id, String binding, String label, String help,
                                                 InputKind kind, String value, boolean enabled) {
        return new DesktopUiNode.TextInput(id, binding, key(label), help == null ? null : key(help), kind,
                kind == InputKind.PASSWORD ? "" : nullToEmpty(value), 32,
                kind == InputKind.MULTILINE ? 5 : 1, enabled);
    }
    private DesktopUiNode.TextInput passwordInput(String id, String binding, String label, boolean enabled) {
        return new DesktopUiNode.TextInput(id, binding, key(label), null, InputKind.PASSWORD,
                "", 24, 1, enabled, securityFormRevision);
    }
    private static DesktopUiNode.Toggle toggle(String id, String binding, String label,
                                               boolean selected, boolean enabled) {
        return new DesktopUiNode.Toggle(id, binding, key(label), null, ToggleStyle.CHECKBOX, selected, enabled);
    }
    private static DesktopUiNode.NumberInput number(String id, String binding, String label, String help,
                                                    int value, int minimum, int maximum, boolean enabled) {
        return new DesktopUiNode.NumberInput(id, binding, key(label), help == null ? null : key(help),
                NumberStyle.SPINNER, Math.max(minimum, Math.min(maximum, value)), minimum, maximum, 1, enabled);
    }
    private static DesktopUiNode.Choice choice(String id, String binding, String label, String help,
                                               List<DesktopUiNode.Option> options, String selected,
                                               boolean enabled) {
        List<String> selectedIds = options.stream().anyMatch(option -> option.id().equals(selected))
                ? List.of(selected) : List.of();
        return new DesktopUiNode.Choice(id, binding, key(label), help == null ? null : key(help),
                ChoiceStyle.COMBO_BOX, SelectionMode.SINGLE, options, selectedIds, enabled);
    }
    private static DesktopUiNode.Button button(String id, String actionId, String label, boolean enabled,
                                               Map<String, Runnable> nextActions, Runnable action) {
        nextActions.put(actionId, action);
        return new DesktopUiNode.Button(id, actionId, key(label), null, ButtonStyle.NORMAL, enabled);
    }
    private static DesktopUiNode.Button rawButton(String id, String actionId, String label, boolean enabled,
                                                  Map<String, Runnable> nextActions, Runnable action) {
        nextActions.put(actionId, action);
        return new DesktopUiNode.Button(id, actionId, TextToken.raw(label), null,
                ButtonStyle.NORMAL, enabled);
    }

    private String backendMessage() {
        DesktopUiHost.MaintenanceSnapshot maintenance = host.maintenanceSnapshot();
        if (maintenance.active()) return maintenanceMessage(maintenance);
        long elapsed = elapsedSeconds(backendStateChangedAt);
        if (backend.state() == DesktopUiHost.BackendState.STARTING && elapsed >= 10L) {
            return host.message("gui.backend.state.starting.slow", elapsed);
        }
        if (!exclusiveToolName.isBlank()) {
            if (backend.state() == DesktopUiHost.BackendState.STOPPING) {
                return host.message("gui.status.state.stopping-for-tool", exclusiveToolName);
            }
            if (backend.state() == DesktopUiHost.BackendState.STOPPED) {
                long toolElapsed = elapsedSeconds(exclusiveToolStartedAt);
                return host.message(toolElapsed >= 30L ? "gui.status.state.stopped-by-tool.elapsed"
                        : "gui.status.state.stopped-by-tool", toolElapsed >= 30L
                        ? new Object[]{exclusiveToolName, toolElapsed} : new Object[]{exclusiveToolName});
            }
        }
        if (backend.state() == DesktopUiHost.BackendState.RUNNING && !statusConnected) {
            return host.message(elapsed < 3L ? "gui.status.state.connecting"
                    : "gui.status.state.connection-failed");
        }
        return host.message(switch (backend.state()) {
            case RUNNING -> "gui.backend.state.running";
            case STARTING -> "gui.backend.state.starting";
            case STOPPING -> "gui.backend.state.stopping";
            case STOPPED -> "gui.backend.state.stopped";
            case FAILED -> "gui.backend.state.failed";
        });
    }

    private TextStyle backendTextStyle() {
        if (host.maintenanceSnapshot().active()) return TextStyle.WARNING;
        return switch (backend.state()) {
            case RUNNING -> statusConnected ? TextStyle.SUCCESS : TextStyle.WARNING;
            case FAILED -> TextStyle.ERROR;
            default -> TextStyle.WARNING;
        };
    }

    private String maintenanceMessage(DesktopUiHost.MaintenanceSnapshot maintenance) {
        if (maintenance.index() <= 0 || nullToEmpty(maintenance.taskName()).isBlank()) {
            return host.message("gui.status.state.maintenance.preparing");
        }
        String task = host.message("gui.maintenance.task." + switch (maintenance.taskName()) {
            case "database-optimize", "guest-invite-cleanup" -> maintenance.taskName();
            default -> "other";
        }, "database-optimize".equals(maintenance.taskName())
                || "guest-invite-cleanup".equals(maintenance.taskName())
                ? new Object[]{} : new Object[]{maintenance.taskName()});
        String header = host.message("gui.status.state.maintenance", task,
                maintenance.index(), maintenance.total());
        if (maintenance.unitsTotal() > 0) {
            String progress;
            if (maintenance.unitsDone() > 0 && maintenance.unitsDone() < maintenance.unitsTotal()) {
                long elapsed = elapsedSeconds(maintenance.taskStartedAt());
                long eta = elapsed * ((long) maintenance.unitsTotal() - maintenance.unitsDone())
                        / maintenance.unitsDone();
                progress = host.message("gui.status.state.maintenance.progress", maintenance.unitsDone(),
                        maintenance.unitsTotal(), formatCompactDuration(eta));
            } else {
                progress = host.message("gui.status.state.maintenance.progress.eta-pending",
                        maintenance.unitsDone(), maintenance.unitsTotal());
            }
            return header + "\n" + progress;
        }
        long elapsed = elapsedSeconds(maintenance.taskStartedAt());
        return elapsed >= 30L ? host.message("gui.status.state.maintenance.elapsed", task,
                maintenance.index(), maintenance.total(), elapsed) : header;
    }

    private static long elapsedSeconds(long startedAt) {
        return startedAt <= 0 ? 0 : Math.max(0, (System.currentTimeMillis() - startedAt) / 1_000L);
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

    private String selected(String key, String fallback) {
        return form("interface." + switch (key) {
            case "app.language" -> "language";
            case "app.gui-provider" -> "provider";
            case "app.theme" -> "theme";
            case "app.config-menu-expand-all" -> "config-menu-expand-all";
            default -> key;
        }, savedValues.getOrDefault(new FieldKey(null, key), fallback));
    }
    private String form(String key, String fallback) { return formValues.getOrDefault(key, fallback); }
    private boolean boolForm(String key, boolean fallback) {
        return Boolean.parseBoolean(form(key, Boolean.toString(fallback)));
    }
    private int intForm(String key, int fallback) { return parseInt(form(key, null), fallback); }

    private static TextToken key(String key) { return TextToken.key(key); }
    private static TextToken token(String namespace, String key, String fallback) {
        if (key == null || key.isBlank()) return TextToken.raw(fallback == null ? "" : fallback);
        if (!validId(key) || (namespace != null && !validId(namespace))) {
            return TextToken.raw(fallback == null ? key : fallback);
        }
        return new TextToken(namespace, key, fallback == null ? key : fallback, List.of());
    }
    private static TextToken token(String namespace, String key, String fallback, List<String> arguments) {
        if (key == null || key.isBlank()) return TextToken.raw(fallback == null ? "" : fallback);
        if (!validId(key) || (namespace != null && !validId(namespace))) {
            return TextToken.raw(fallback == null ? key : fallback);
        }
        return new TextToken(namespace, key, fallback == null ? key : fallback, arguments);
    }
    private static TextToken appToken(String key, Object... arguments) {
        return new TextToken(null, key, key,
                java.util.Arrays.stream(arguments).map(String::valueOf).toList());
    }
    private static TextToken optionalToken(String namespace, String key) {
        return key == null || key.isBlank() ? null : token(namespace, key, key);
    }

    private static String bindingId(FieldKey key) {
        return "config." + safeId(key.owner() == null ? APP_OWNER : key.owner()) + "." + safeId(key.key());
    }
    private static String presetOptionId(ConfigPreset preset) {
        return safeId(preset.owner() + "." + preset.spec().presetId());
    }
    private static String safeId(String value) {
        String safe = nullToEmpty(value).trim().replaceAll("[^A-Za-z0-9._:-]", "-");
        if (safe.isBlank() || !Character.isLetterOrDigit(safe.charAt(0))) safe = "id-" + safe;
        return safe.length() <= 120 ? safe : safe.substring(0, 104) + "-" + Integer.toHexString(value.hashCode());
    }
    private static boolean safeHref(String href) {
        return href != null && href.startsWith("/") && !href.startsWith("//")
                && !href.contains("..") && href.indexOf('\r') < 0 && href.indexOf('\n') < 0;
    }
    private static int parseInt(String value, int fallback) {
        try { return value == null ? fallback : Integer.parseInt(value.trim()); }
        catch (NumberFormatException ignored) { return fallback; }
    }
    private static String trimTrailingSlashes(String value) {
        String trimmed = nullToEmpty(value).trim();
        while (trimmed.endsWith("/")) trimmed = trimmed.substring(0, trimmed.length() - 1);
        return trimmed;
    }
    static boolean safeJsonPath(String path, boolean allowBlank, boolean rejectSensitive) {
        if (path == null || path.isBlank()) return allowBlank;
        String normalized = path.trim();
        if (normalized.length() > 256) return false;
        String[] parts = normalized.split("\\.", -1);
        if (parts.length == 0 || parts.length > 8) return false;
        for (String part : parts) {
            if (!SAFE_JSON_SEGMENT.matcher(part).matches()) return false;
            if (rejectSensitive) {
                String folded = part.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
                if (SENSITIVE_RESULT_MARKERS.stream().anyMatch(folded::contains)) return false;
            }
        }
        return true;
    }
    static String sanitizeActionText(String value) {
        return sanitizeActionText(value, MAX_ACTION_TEXT_CODE_POINTS);
    }
    private static String sanitizeActionText(String value, int maximumCodePoints) {
        if (value == null || value.isBlank()) return "";
        StringBuilder safe = new StringBuilder(Math.min(value.length(), maximumCodePoints));
        boolean whitespace = false;
        int accepted = 0;
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (Character.isISOControl(codePoint) && !Character.isWhitespace(codePoint)) continue;
            if (Character.isWhitespace(codePoint)) {
                if (safe.length() > 0 && !whitespace) {
                    safe.append(' ');
                    accepted++;
                }
                whitespace = true;
                continue;
            }
            whitespace = false;
            if (accepted >= maximumCodePoints) {
                safe.append('…');
                break;
            }
            if (codePoint == '<') safe.append('‹');
            else if (codePoint == '>') safe.append('›');
            else safe.appendCodePoint(codePoint);
            accepted++;
        }
        return safe.toString().trim();
    }
    private static String responseDetail(DesktopUiHost.GuiResponse response) {
        if (!response.reachable()) return "unreachable";
        if (!response.rawBody().isBlank()) return response.status() + " " + response.rawBody();
        return Integer.toString(response.status());
    }
    private static String safeMessage(Throwable failure) {
        if (failure == null) return "unknown";
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }
    private static String formatSize(long bytes) {
        if (bytes <= 0L) return "--";
        double value = bytes;
        String[] units = {"B", "KiB", "MiB", "GiB"};
        int unit = 0;
        while (value >= 1024d && unit < units.length - 1) {
            value /= 1024d;
            unit++;
        }
        return unit == 0 ? Long.toString(bytes) + " " + units[unit]
                : String.format(Locale.ROOT, "%.1f %s", value, units[unit]);
    }
    private static String nullToEmpty(String value) { return value == null ? "" : value; }

    private static Optional<DesktopUiNode.ImageData> loadApplicationIcon() {
        try (var stream = AppDesktopUiModel.class.getResourceAsStream("/static/favicon.ico")) {
            if (stream == null) return Optional.empty();
            return Optional.of(new DesktopUiNode.ImageData("image/x-icon",
                    Base64.getEncoder().encodeToString(stream.readAllBytes())));
        } catch (Exception failure) {
            LOG.warn("Unable to load the desktop application icon", failure);
            return Optional.empty();
        }
    }

    private static String loadLicenseText() {
        try (var stream = AppDesktopUiModel.class.getResourceAsStream("/LICENSE")) {
            return stream == null ? "GNU AGPL v3.0"
                    : new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception failure) {
            LOG.warn("Unable to load the bundled license text", failure);
            return "GNU AGPL v3.0";
        }
    }

    private void setStatus(String value) {
        statusNotice = nullToEmpty(value);
        rebuild();
    }

    private void setConfigNotice(String value) {
        configNotice = nullToEmpty(value);
        configNoticeToken = null;
    }

    private record PluginConfig(String owner, String namespace, String displayNameKey,
                                 List<GuiConfigContribution> contributions,
                                List<WebRouteContribution> routes) {}
    private record PluginOnboardingStep(GuiOnboardingStepContribution step) {}

    @FunctionalInterface
    private interface DialogContent {
        DesktopUiNode build(Map<String, Runnable> actions, String dismissAction, Runnable dismiss);
    }

    private record DialogState(String id, TextToken title, TextToken message,
                               DesktopUiDocument.DialogStyle style, DialogContent content,
                               boolean dismissible, int width, int height) { }

    private enum ToolDialog { IMAGE_CLASSIFIER, FOLDER_CHECKER }

    private record PendingInstall(String assetUrl, long size, String releaseNotes,
                                  String releaseNotesUrl, String latestVersion) { }

    private record PathPrefixRow(long id, String path, boolean downloadRoot, boolean symbolic) { }
    private record MigrationChange(long id, String path) { }
    private record MigrationPlan(List<MigrationChange> changes, PathPrefixRow root) { }

    private record PluginStatusRow(String id, String name, String source, String statusCode,
                                   String phaseCode, boolean managed, boolean required, String version,
                                   String verificationStatus) { }

    private record LocalizedText(String namespace, String key, String fallback) {
        private TextToken token() { return AppDesktopUiModel.token(namespace, key, fallback); }
        private static LocalizedText key(String namespace, String key) {
            return new LocalizedText(namespace, key, key);
        }
        private static LocalizedText optional(String namespace, String key) {
            return key == null || key.isBlank() ? null : key(namespace, key);
        }
        private static LocalizedText app(String key) { return key(null, key); }
        private static LocalizedText raw(String text) { return new LocalizedText(null, "", text); }
    }

    private record ConfigLayout(FieldKey field, String cardId, LocalizedText cardLabel, int order) {}
    private record ConfigNotice(String id, LocalizedText text, Set<String> cardIds, int order) {}
    private record ConfigAction(String owner, String namespace, GuiConfigActionContribution spec,
                                LocalizedText label, LocalizedText help, LocalizedText sendingNotice,
                                int readTimeoutMillis) {
        private String cardId() { return spec.cardId(); }
    }
    private record ConfigPreset(String owner, String namespace, GuiConfigPresetContribution spec,
                                LocalizedText label, LocalizedText help) {
        private String cardId() { return spec.cardId(); }
    }
    private record ConfigSection(String id, GuiConfigGroupContribution group, GuiConfigSectionLayout layout,
                                 int order, boolean mergeable, boolean contributesGroupVisibility,
                                 LocalizedText title, LocalizedText help,
                                 LocalizedText layoutLabel, LocalizedText layoutHelp,
                                 LocalizedText presetLabel, LocalizedText presetHelp,
                                 List<ConfigLayout> layouts, List<ConfigAction> actions,
                                 List<ConfigPreset> presets, List<ConfigNotice> notices) {}

    private record ActionResult(boolean reachable, boolean http2xx, int status,
                                DesktopUiHost.GuiValue body, String summary) {
        private static ActionResult from(DesktopUiHost.GuiResponse response,
                                         GuiConfigActionResultSummary summarySpec) {
            DesktopUiHost.GuiValue parsed = response.bodyLimitExceeded() ? null : response.body();
            return new ActionResult(response.reachable(), response.is2xx(), response.status(), parsed,
                    buildSummary(parsed, summarySpec));
        }

        private String value(GuiConfigActionResultSource source, String path) {
            return switch (source) {
                case REACHABLE -> Boolean.toString(reachable);
                case HTTP_2XX -> Boolean.toString(http2xx);
                case HTTP_STATUS -> Integer.toString(status);
                case HTTP_STATUS_TEXT -> status <= 0 ? "" : "HTTP " + status;
                case JSON -> jsonText(path);
                case SUMMARY -> summary;
            };
        }

        private String jsonText(String path) {
            if (!safeJsonPath(path, false, true)) return "";
            DesktopUiHost.GuiValue node = nodeAt(body, path);
            if (node == null || node.isMissingNode() || node.isNull() || !node.isValueNode()) return "";
            return node.isBoolean() ? Boolean.toString(node.asBoolean()) : sanitizeActionText(node.asText(""));
        }

        private static String buildSummary(DesktopUiHost.GuiValue body, GuiConfigActionResultSummary spec) {
            if (body == null || spec == null || !safeJsonPath(spec.arrayPath(), false, true)
                    || !safeJsonPath(spec.labelPath(), false, true)
                    || !safeJsonPath(spec.statusPath(), true, true)
                    || !safeJsonPath(spec.detailPath(), true, true)) return "";
            DesktopUiHost.GuiValue array = nodeAt(body, spec.arrayPath());
            if (array == null || !array.isArray() || array.isEmpty()) return "";
            StringBuilder summary = new StringBuilder();
            int count = 0;
            for (DesktopUiHost.GuiValue item : array) {
                if (count >= MAX_ACTION_SUMMARY_ITEMS) break;
                String status = textAt(item, spec.statusPath());
                if (!spec.statusPath().isBlank() && status.equals(spec.successStatus())) continue;
                String label = textAt(item, spec.labelPath());
                String detail = textAt(item, spec.detailPath());
                if (label.isBlank() && status.isBlank() && detail.isBlank()) continue;
                if (!summary.isEmpty()) summary.append("; ");
                summary.append(label.isBlank() ? "-" : label);
                if (!spec.statusPath().isBlank()) {
                    summary.append(": ").append(status);
                    if (!detail.isBlank()) summary.append(" (").append(detail).append(')');
                } else if (!detail.isBlank()) {
                    summary.append(": ").append(detail);
                }
                count++;
                if (summary.codePointCount(0, summary.length()) >= MAX_ACTION_SUMMARY_CODE_POINTS) break;
            }
            return sanitizeActionText(summary.toString(), MAX_ACTION_SUMMARY_CODE_POINTS);
        }

        private static String textAt(DesktopUiHost.GuiValue value, String path) {
            if (!safeJsonPath(path, true, true)) return "";
            DesktopUiHost.GuiValue found = nodeAt(value, path);
            return found == null || found.isMissingNode() || found.isNull() || !found.isValueNode()
                    ? "" : sanitizeActionText(found.asText(""));
        }

        private static DesktopUiHost.GuiValue nodeAt(DesktopUiHost.GuiValue root, String path) {
            if (root == null) return null;
            DesktopUiHost.GuiValue current = root;
            if (path == null || path.isBlank()) return current;
            for (String part : path.split("\\.")) {
                current = current.path(part);
                if (current.isMissingNode() || current.isNull()) break;
            }
            return current;
        }
    }

    private record FieldKey(String owner, String key) {
        private FieldKey {
            key = key == null ? "" : key.trim();
            owner = owner == null || owner.isBlank() ? null : owner.trim();
        }
    }

    private record ConfigField(FieldKey key, String owner, GuiConfigFieldContribution spec,
                               GuiConfigGroupContribution group, String namespace,
                               boolean affectsConditions) {}

    @FunctionalInterface
    private interface ThrowingRunnable { void run() throws Exception; }
}
