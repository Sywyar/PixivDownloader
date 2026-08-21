package top.sywyar.pixivdownload.gui;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.config.credential.PluginCredentialStore;
import top.sywyar.pixivdownload.gui.config.TestDesktopConfigFile;
import top.sywyar.pixivdownload.i18n.MessageBundles;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiCapability;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiContext;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiExperienceProfile;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiPageContribution;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiProvider;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiSession;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiSnapshot;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigCondition;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigActionContribution;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigContribution;
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
import top.sywyar.pixivdownload.plugin.api.gui.GuiThemeAppearance;
import top.sywyar.pixivdownload.plugin.api.gui.GuiThemeContribution;
import top.sywyar.pixivdownload.plugin.api.gui.document.DesktopUiDocument;
import top.sywyar.pixivdownload.plugin.api.gui.document.DesktopUiNode;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginKind;
import top.sywyar.pixivdownload.plugin.api.web.AccessPolicy;
import top.sywyar.pixivdownload.plugin.api.web.HttpMethod;
import top.sywyar.pixivdownload.plugin.api.web.NavigationContribution;
import top.sywyar.pixivdownload.plugin.api.web.NavigationPlacements;
import top.sywyar.pixivdownload.plugin.api.web.WebRouteContribution;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AppDesktopUiHostDocumentTest {

    private static final DesktopUiHost.OnboardingSnapshot COMPLETE_ONBOARDING =
            new DesktopUiHost.OnboardingSnapshot(true, true, 4, true, true);

    @TempDir
    Path tempDir;
    private final List<AppDesktopUiModel> openModels = new ArrayList<>();

    @BeforeEach
    void isolateRuntimeState() {
        System.setProperty(RuntimeFiles.STATE_DIR_PROPERTY, tempDir.resolve("state").toString());
    }

    @AfterEach
    void clearRuntimeConfigOverride() throws Exception {
        for (AppDesktopUiModel model : openModels) model.close();
        System.clearProperty(RuntimeFiles.CONFIG_DIR_PROPERTY);
        System.clearProperty(RuntimeFiles.STATE_DIR_PROPERTY);
    }

    @Test
    @DisplayName("CLASSIC 档位保留全部既有桌面根页面")
    void hostModelProvidesCompleteDesktopDocument() {
        DesktopUiDocument document = model(DesktopUiExperienceProfile.CLASSIC).snapshot().document();

        assertThat(document.pages()).extracting(DesktopUiDocument.Page::id)
                .containsExactly("welcome", "status", "config", "plugins", "tools", "security", "about");
        assertThat(document.requiredNodeKinds()).contains(
                DesktopUiNode.Kind.CONTAINER, DesktopUiNode.Kind.GROUP, DesktopUiNode.Kind.TABS,
                DesktopUiNode.Kind.SCROLL, DesktopUiNode.Kind.TEXT, DesktopUiNode.Kind.TEXT_INPUT,
                DesktopUiNode.Kind.TOGGLE, DesktopUiNode.Kind.CHOICE, DesktopUiNode.Kind.NUMBER_INPUT,
                DesktopUiNode.Kind.TABLE, DesktopUiNode.Kind.BUTTON);
        assertThat(nodes(document)).extracting(DesktopUiNode::id)
                .contains("config.market.repositories");
        DesktopUiDocument.Tray tray = document.tray().orElseThrow();
        assertThat(tray.items()).extracting(DesktopUiDocument.TrayItem::role)
                .containsExactly(DesktopUiDocument.TrayItemRole.ACTIVATE_WINDOW,
                        DesktopUiDocument.TrayItemRole.SEPARATOR,
                        DesktopUiDocument.TrayItemRole.DISPATCH,
                        DesktopUiDocument.TrayItemRole.DISPATCH,
                        DesktopUiDocument.TrayItemRole.SEPARATOR,
                        DesktopUiDocument.TrayItemRole.DISPATCH);
        assertThat(tray.items()).extracting(DesktopUiDocument.TrayItem::actionId)
                .contains("tray.batch.open", "tray.download-folder.open", "tray.exit");
    }

    @Test
    @DisplayName("CONTROL_CENTER 档位通过独立入口生成完整文档")
    void controlCenterProfileHasACompleteDocumentEntry() {
        DesktopUiDocument controlCenter = modelWithOnboarding(List.of(),
                DesktopUiExperienceProfile.CONTROL_CENTER, COMPLETE_ONBOARDING).snapshot().document();

        assertThat(controlCenter.pages()).extracting(DesktopUiDocument.Page::id)
                .containsExactly("home", "automation", "plugins", "tools", "security", "settings", "about");
        assertThat(nodes(controlCenter)).extracting(DesktopUiNode::id)
                .contains("home.greeting", "home.hero", "home.system", "home.metrics",
                        "home.quick-start", "home.running", "home.storage",
                        "plugins.layout", "plugins.grid", "tools.layout", "tools.quick.grid",
                        "tools.maintenance.grid", "settings.layout", "settings.categories",
                        "settings.content", "settings.summary",
                        "about.name", "about.docs", "about.update.check");
    }

    @Test
    @DisplayName("控制中心插件、工具与设置页采用原型分区且 CLASSIC 结构不变")
    void controlCenterReplicatesPrototypePagePartitionsWithoutChangingClassic() {
        DesktopUiDocument controlCenter = modelWithOnboarding(List.of(),
                DesktopUiExperienceProfile.CONTROL_CENTER, COMPLETE_ONBOARDING).snapshot().document();
        DesktopUiDocument classic = model(DesktopUiExperienceProfile.CLASSIC).snapshot().document();

        assertPageContent(controlCenter, "plugins", DesktopUiNode.Scroll.class);
        assertPageContent(controlCenter, "tools", DesktopUiNode.Scroll.class);
        assertPageContent(controlCenter, "settings", DesktopUiNode.Scroll.class);
        assertThat(nodes(controlCenter).stream().filter(node -> "plugins.grid".equals(node.id()))
                .findFirst().orElseThrow()).isInstanceOf(DesktopUiNode.AdaptiveGrid.class);
        assertThat(nodes(controlCenter).stream().filter(node -> "settings.categories".equals(node.id()))
                .findFirst().orElseThrow()).isInstanceOf(DesktopUiNode.Tree.class);
        DesktopUiNode.AdaptiveGrid settings = (DesktopUiNode.AdaptiveGrid) nodes(controlCenter).stream()
                .filter(node -> "settings.layout".equals(node.id())).findFirst().orElseThrow();
        assertThat(settings.children()).extracting(DesktopUiNode::id)
                .containsExactly("settings.sidebar", "settings.content");
        assertThat(((DesktopUiNode.Container) settings.children().get(0)).children())
                .extracting(DesktopUiNode::id)
                .containsExactly("settings.categories.surface", "settings.summary");
        assertThat(nodes(controlCenter).stream().filter(node -> "config.save".equals(node.id()))).hasSize(1);
        assertThat(nodes(classic)).extracting(DesktopUiNode::id)
                .doesNotContain("plugins.layout", "tools.layout", "settings.layout", "settings.categories");
        assertPageContent(classic, "config", DesktopUiNode.Dock.class);
    }

    @Test
    @DisplayName("控制中心引导只保留宿主设置步骤且 CLASSIC 继续显示下载入口")
    void controlCenterOnboardingKeepsOnlyHostSetupFacts() {
        DesktopUiHost.OnboardingSnapshot readyForCompletion =
                new DesktopUiHost.OnboardingSnapshot(false, true, 4, false, true);

        DesktopUiDocument controlCenter = modelWithOnboarding(List.of(),
                DesktopUiExperienceProfile.CONTROL_CENTER, readyForCompletion).snapshot().document();
        DesktopUiDocument classic = modelWithOnboarding(List.of(),
                DesktopUiExperienceProfile.CLASSIC, readyForCompletion).snapshot().document();

        assertThat(nodes(controlCenter)).extracting(DesktopUiNode::id)
                .contains("welcome.done.finish")
                .doesNotContain("welcome.start.open", "welcome.ffmpeg.title", "welcome.scripts.title");
        assertThat(nodes(controlCenter)).extracting(DesktopUiNode::id)
                .noneMatch(id -> id.startsWith("welcome.plugin."));
        assertThat(textNode(controlCenter, "welcome.done.body").text().key())
                .isEqualTo("desktop.ui.onboarding.done.body");
        assertThat(nodes(classic)).extracting(DesktopUiNode::id).contains("welcome.start.open");
    }

    @Test
    @DisplayName("控制中心首页读取真实快照并用最近现存目录计算存储")
    void controlCenterHomeUsesMaterializedFactsAndFileStore() throws Exception {
        Path config = tempDir.resolve("control-center.yaml");
        AppDesktopUiHost delegate = new AppDesktopUiHost(6999, new TestDesktopConfigFile(config));
        DesktopUiHost host = (DesktopUiHost) Proxy.newProxyInstance(
                DesktopUiHost.class.getClassLoader(), new Class<?>[]{DesktopUiHost.class},
                (proxy, method, arguments) -> {
                    if ("onboardingState".equals(method.getName())) return COMPLETE_ONBOARDING;
                    if ("controlCenterSnapshot".equals(method.getName())) {
                        return response(Map.of(
                                "cards", List.of(Map.of(
                                        "owner", Map.of("pluginId", "fixture"),
                                        "card", Map.of(
                                                "cardId", "completed", "order", 10,
                                                "title", token("Completed"),
                                                "primaryValue", token("12"),
                                                "supportingText", token("Observed"),
                                                "tone", "SUCCESS", "icon", "DOWNLOAD",
                                                "availability", "AVAILABLE"))),
                                "runningTasks", List.of(Map.of(
                                        "owner", Map.of("pluginId", "fixture"),
                                        "task", Map.of(
                                                "taskId", "running", "order", 10,
                                                "title", token("Running task"),
                                                "supportingText", token("Downloading"),
                                                "status", "RUNNING", "progress", 0.5,
                                                "availability", "AVAILABLE"))),
                                "automations", List.of()));
                    }
                    try {
                        return method.invoke(delegate, arguments);
                    } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                });
        AppDesktopUiModel model = track(new AppDesktopUiModel(6999,
                tempDir.resolve("missing").resolve("downloads").toString(), config,
                host, List::of, rendererContract(DesktopUiExperienceProfile.CONTROL_CENTER)));

        await(() -> nodes(model.snapshot().document()).stream()
                .anyMatch(node -> "home.metrics.fixture.completed".equals(node.id())));
        assertThat(nodes(model.snapshot().document())).extracting(DesktopUiNode::id)
                .contains("home.metrics.fixture.completed", "home.hero.fixture.running",
                        "home.running.fixture.running", "home.storage");
        assertThat(nodes(model.snapshot().document()).stream()
                .filter(DesktopUiNode.Text.class::isInstance)
                .map(DesktopUiNode.Text.class::cast)
                .filter(node -> "home.storage.primary".equals(node.id()))
                .findFirst().orElseThrow().text().arguments()).doesNotContain("--");
    }

    @Test
    @DisplayName("控制中心自动化与插件页面只读展示宿主快照")
    void controlCenterAutomationAndPluginsAreReadOnlyFacts() throws Exception {
        Path config = tempDir.resolve("control-center-read-only.yaml");
        AppDesktopUiHost delegate = new AppDesktopUiHost(6999, new TestDesktopConfigFile(config));
        DesktopUiHost host = (DesktopUiHost) Proxy.newProxyInstance(
                DesktopUiHost.class.getClassLoader(), new Class<?>[]{DesktopUiHost.class},
                (proxy, method, arguments) -> {
                    if ("controlCenterSnapshot".equals(method.getName())) {
                        return response(Map.of(
                                "cards", List.of(),
                                "runningTasks", List.of(),
                                "automations", List.of(Map.of(
                                        "owner", Map.of("pluginId", "fixture"),
                                        "snapshot", Map.of(
                                                "availability", "AVAILABLE",
                                                "observedAt", "2026-08-21T00:00:00Z",
                                                "tasks", List.of(Map.of(
                                                        "taskId", "nightly", "order", 10,
                                                        "title", token("Nightly task"),
                                                        "triggerSummary", token("Cron at midnight"),
                                                        "status", "SUSPENDED", "lastResult", "ERROR",
                                                        "nextRuns", List.of(
                                                                "2026-08-21T02:00:00Z",
                                                                "2026-08-21T01:00:00Z"),
                                                        "observedAt", "2026-08-21T00:00:00Z")))))));
                    }
                    if ("guiGet".equals(method.getName()) && "plugins/status".equals(arguments[0])) {
                        return response(Map.of(
                                "recoveryMode", false,
                                "observedAt", "2026-08-21T00:00:00Z",
                                "plugins", List.of(Map.of(
                                        "id", "fixture", "name", "Fixture", "source", "external",
                                        "status", "FAILED", "runtimePhase", "STOPPED",
                                        "managed", true, "required", false, "version", "1.0.0",
                                        "verification", Map.of(
                                                "status", "INVALID_SIGNATURE",
                                                "diagnosticCode", "SIGNATURE_MISMATCH",
                                                "lastVerifiedAt", "2026-08-20T23:59:00Z")))));
                    }
                    try {
                        return method.invoke(delegate, arguments);
                    } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                });
        AppDesktopUiModel model = track(new AppDesktopUiModel(6999,
                tempDir.resolve("downloads").toString(), config,
                host, List::of, rendererContract(DesktopUiExperienceProfile.CONTROL_CENTER)));

        await(() -> nodes(model.snapshot().document()).stream()
                .anyMatch(node -> "automation.task.fixture.nightly".equals(node.id())));
        await(() -> nodes(model.snapshot().document()).stream()
                .anyMatch(node -> "plugins.card.fixture".equals(node.id())));

        DesktopUiNode automationPage = model.snapshot().document().pages().stream()
                .filter(page -> "automation".equals(page.id())).findFirst().orElseThrow().content();
        List<DesktopUiNode> automationNodes = new ArrayList<>();
        collectNodes(automationPage, automationNodes);
        assertThat(automationNodes).extracting(DesktopUiNode::id)
                .contains("automation.source.fixture", "automation.task.fixture.nightly")
                .anyMatch(id -> id.startsWith("automation.timeline.fixture.nightly."));
        assertThat(automationNodes).noneMatch(DesktopUiNode.Button.class::isInstance)
                .noneMatch(DesktopUiNode.Link.class::isInstance);

        DesktopUiNode pluginsPage = model.snapshot().document().pages().stream()
                .filter(page -> "plugins".equals(page.id())).findFirst().orElseThrow().content();
        List<DesktopUiNode> pluginNodes = new ArrayList<>();
        collectNodes(pluginsPage, pluginNodes);
        assertThat(pluginNodes).noneMatch(DesktopUiNode.Button.class::isInstance)
                .noneMatch(DesktopUiNode.Link.class::isInstance);
        assertThat(pluginNodes.stream()
                .filter(DesktopUiNode.Text.class::isInstance)
                .map(DesktopUiNode.Text.class::cast)
                .map(DesktopUiNode.Text::text)
                .flatMap(token -> java.util.stream.Stream.concat(
                        java.util.stream.Stream.of(token.fallback()), token.arguments().stream())))
                .anyMatch(value -> value.contains("SIGNATURE_MISMATCH"));
    }

    @Test
    @DisplayName("快速开始只接纳同 owner 精确 GET 路由且不放宽访问策略")
    void quickStartRequiresAnExactOwnerRouteWithNoBroaderAccess() {
        DesktopUiPluginSource validZ = quickStartSource(
                "z-valid", "/z-valid.html", AccessPolicy.ADMIN, AccessPolicy.ADMIN, true);
        DesktopUiPluginSource validA = quickStartSource(
                "a-valid", "/a-valid.html", AccessPolicy.ADMIN, AccessPolicy.ADMIN, true);
        DesktopUiPluginSource crossOwner = quickStartSource(
                "cross-owner", "/shared.html", AccessPolicy.ADMIN, AccessPolicy.ADMIN, false);
        DesktopUiPluginSource routeOwner = quickStartSource(
                "route-owner", "/unused.html", AccessPolicy.ADMIN, AccessPolicy.ADMIN, false,
                List.of(WebRouteContribution.admin("/shared.html")));
        DesktopUiPluginSource broader = quickStartSource(
                "broader", "/broader.html", AccessPolicy.PUBLIC, AccessPolicy.ADMIN, true);
        DesktopUiPluginSource wildcard = quickStartSource(
                "wildcard", "/wildcard/page.html", AccessPolicy.ADMIN, AccessPolicy.ADMIN, false,
                List.of(WebRouteContribution.admin("/wildcard/**")));
        DesktopUiPluginSource postOnly = quickStartSource(
                "post-only", "/post-only.html", AccessPolicy.ADMIN, AccessPolicy.ADMIN, false,
                List.of(new WebRouteContribution("/post-only.html", AccessPolicy.ADMIN,
                        Set.of(HttpMethod.POST), false)));

        DesktopUiDocument document = modelWithOnboarding(List.of(validZ, crossOwner, routeOwner, broader,
                        wildcard, postOnly, validA), DesktopUiExperienceProfile.CONTROL_CENTER,
                COMPLETE_ONBOARDING).snapshot().document();

        assertThat(nodes(document).stream()
                .filter(DesktopUiNode.Button.class::isInstance)
                .map(DesktopUiNode.Button.class::cast)
                .filter(button -> button.id().startsWith("home.quick-start.")))
                .extracting(DesktopUiNode.Button::label)
                .extracting(DesktopUiNode.TextToken::fallback)
                .containsExactly("a-valid", "z-valid");
        DesktopUiNode.Container quickStart = (DesktopUiNode.Container) nodes(document).stream()
                .filter(node -> "home.quick-start.grid".equals(node.id())).findFirst().orElseThrow();
        assertThat(quickStart.layout()).isEqualTo(DesktopUiNode.ContainerLayout.GRID);
        assertThat(quickStart.columns()).isEqualTo(2);
    }

    @Test
    @DisplayName("宿主按文档顺序交付三类多选值")
    void selectionBindingsReceiveEverySelectedIdInDocumentOrder() {
        DesktopUiNode.TextToken label = DesktopUiNode.TextToken.raw("Label");
        DesktopUiNode.Choice choice = new DesktopUiNode.Choice(
                "choice", "choice.value", label, null,
                DesktopUiNode.ChoiceStyle.CHECK_BOXES, DesktopUiNode.SelectionMode.MULTIPLE,
                List.of(new DesktopUiNode.Option("first", label, true),
                        new DesktopUiNode.Option("second", label, true),
                        new DesktopUiNode.Option("third", label, true)), List.of(), true);
        DesktopUiNode.Table table = new DesktopUiNode.Table(
                "table", "table.value", List.of(new DesktopUiNode.TableColumn("value", label, 0)),
                List.of(new DesktopUiNode.TableRow("first", List.of("First")),
                        new DesktopUiNode.TableRow("second", List.of("Second")),
                        new DesktopUiNode.TableRow("third", List.of("Third"))),
                DesktopUiNode.SelectionMode.MULTIPLE, List.of(), true);
        DesktopUiNode.Tree tree = new DesktopUiNode.Tree(
                "tree", "tree.value", List.of(
                new DesktopUiNode.TreeItem("first", label, List.of(
                        new DesktopUiNode.TreeItem("second", label, List.of()))),
                new DesktopUiNode.TreeItem("third", label, List.of())),
                DesktopUiNode.SelectionMode.MULTIPLE, List.of(), true);
        AtomicReference<List<String>> selected = new AtomicReference<>();

        for (DesktopUiNode node : List.of(choice, table, tree)) {
            AppDesktopUiModel.acceptSelection(selected::set, node,
                    DesktopUiNode.Value.selections(List.of("third", "first", "second")));
            assertThat(selected).hasValue(List.of("first", "second", "third"));
            assertThatThrownBy(() -> selected.get().add("forged"))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
        assertThatThrownBy(() -> DesktopUiNode.Value.selections(List.of("first", "first")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate selection value");
    }

    @Test
    @DisplayName("插件托盘入口进入宿主完整 Schema")
    void pluginTrayNavigationJoinsTheHostDocument() {
        PixivFeaturePlugin plugin = new PixivFeaturePlugin() {
            @Override public String id() { return "tray-fixture"; }
            @Override public String displayName() { return "plugin.name"; }
            @Override public String description() { return "plugin.description"; }
            @Override public PluginKind kind() { return PluginKind.FEATURE; }
            @Override public List<NavigationContribution> navigation() {
                return List.of(new NavigationContribution("fixture-entry",
                        NavigationPlacements.GUI_TRAY_ACTIONS, "fixture", "navigation.label",
                        "/fixture.html", "link", AccessPolicy.PUBLIC, 10));
            }
        };
        DesktopUiPluginSource source = new DesktopUiPluginSource(
                plugin.id(), false, plugin, plugin.getClass().getClassLoader());

        DesktopUiDocument.TrayItem item = model(List.of(source)).snapshot().document()
                .tray().orElseThrow().items().stream()
                .filter(candidate -> candidate.id().startsWith("tray.web."))
                .findFirst().orElseThrow();

        assertThat(item.label().namespace()).isEqualTo("fixture");
        assertThat(item.label().key()).isEqualTo("navigation.label");
        assertThat(item.actionId()).isEqualTo(item.id() + ".open");
    }

    @Test
    @DisplayName("活动插件页面遵守只读动作边界并随来源撤回")
    void pluginDesktopPagesJoinAndLeaveTheHostDocument() throws Exception {
        PixivFeaturePlugin plugin = new PixivFeaturePlugin() {
            @Override public String id() { return "page-fixture"; }
            @Override public String displayName() { return "plugin.name"; }
            @Override public String description() { return "plugin.description"; }
            @Override public PluginKind kind() { return PluginKind.FEATURE; }
            @Override public List<WebRouteContribution> routes() {
                return List.of(new WebRouteContribution("/api/gui/page-fixture/run",
                        AccessPolicy.GUI, Set.of(HttpMethod.POST), false));
            }
            @Override public List<DesktopUiPageContribution> desktopPages() {
                DesktopUiNode.Text first = new DesktopUiNode.Text(
                        "page-fixture.first.content", DesktopUiNode.TextToken.raw("First"),
                        DesktopUiNode.TextStyle.BODY, true, false);
                DesktopUiNode.Button run = new DesktopUiNode.Button(
                        "page-fixture.second.run", "page-fixture.second.run.action",
                        DesktopUiNode.TextToken.raw("Run"), null, DesktopUiNode.ButtonStyle.NORMAL, true);
                DesktopUiDocument.Dialog dialog = new DesktopUiDocument.Dialog(
                        "page-fixture.second.dialog", DesktopUiNode.TextToken.raw("Dialog"),
                        DesktopUiDocument.DialogStyle.INFO,
                        new DesktopUiNode.Text("page-fixture.second.dialog.content",
                                DesktopUiNode.TextToken.raw("Open"), DesktopUiNode.TextStyle.BODY, true, false),
                        "page-fixture.second.dialog.dismiss", true, 0, 0);
                DesktopUiNode.Button invalid = new DesktopUiNode.Button(
                        "page-fixture.invalid.run", "page-fixture.invalid.run.action",
                        DesktopUiNode.TextToken.raw("Invalid"), null, DesktopUiNode.ButtonStyle.NORMAL, true);
                DesktopUiNode.TextInput readOnly = new DesktopUiNode.TextInput(
                        "page-fixture.readonly.input", "page-fixture.readonly.value",
                        DesktopUiNode.TextToken.raw("Read only"), null, DesktopUiNode.InputKind.TEXT,
                        "value", 20, 1, false);
                DesktopUiNode.TextInput enabled = new DesktopUiNode.TextInput(
                        "page-fixture.enabled.input", "page-fixture.enabled.value",
                        DesktopUiNode.TextToken.raw("Enabled"), null, DesktopUiNode.InputKind.TEXT,
                        "value", 20, 1, true);
                DesktopUiNode.Image svg = new DesktopUiNode.Image(
                        "page-fixture.svg.image",
                        new DesktopUiNode.ImageData("image/svg+xml; charset=utf-8", "PHN2Zy8+"),
                        DesktopUiNode.TextToken.raw("SVG"), 16, 16, DesktopUiNode.ScaleMode.FIT);
                DesktopUiNode.Button external = new DesktopUiNode.Button(
                        "page-fixture.external.run", "page-fixture.external.run.action",
                        DesktopUiNode.TextToken.raw("External"), null, DesktopUiNode.ButtonStyle.NORMAL, true);
                return List.of(
                        new DesktopUiPageContribution("page-fixture.second", 20,
                                DesktopUiNode.TextToken.raw("Second"), run,
                                Map.of("page-fixture.second.run.action", "page-fixture/run",
                                        "page-fixture.second.dialog.dismiss", "page-fixture/run"),
                                List.of(dialog)),
                        new DesktopUiPageContribution("page-fixture.first", 10,
                                DesktopUiNode.TextToken.raw("First"), first),
                        new DesktopUiPageContribution("page-fixture.readonly", 15,
                                DesktopUiNode.TextToken.raw("Read only"), readOnly),
                        new DesktopUiPageContribution("page-fixture.invalid", 30,
                                DesktopUiNode.TextToken.raw("Invalid"), invalid,
                                Map.of("page-fixture.invalid.run.action", "page-fixture/missing"), List.of()),
                        new DesktopUiPageContribution("page-fixture.enabled", 40,
                                DesktopUiNode.TextToken.raw("Enabled"), enabled),
                        new DesktopUiPageContribution("page-fixture.svg", 40,
                                DesktopUiNode.TextToken.raw("SVG"), svg),
                        new DesktopUiPageContribution("page-fixture.external", 50,
                                DesktopUiNode.TextToken.raw("External"), external,
                                Map.of("page-fixture.external.run.action", "https://example.invalid/api"),
                                List.of()));
            }
        };
        DesktopUiPluginSource source = new DesktopUiPluginSource(
                plugin.id(), false, plugin, plugin.getClass().getClassLoader());
        AtomicReference<List<DesktopUiPluginSource>> sources = new AtomicReference<>(List.of(source));
        AtomicReference<String> actionPath = new AtomicReference<>();
        AtomicReference<Object> actionBody = new AtomicReference<>();
        AtomicReference<String> actionOwner = new AtomicReference<>();
        Path config = tempDir.resolve("plugin-page.yaml");
        AppDesktopUiHost delegate = new AppDesktopUiHost(6999, new TestDesktopConfigFile(config));
        DesktopUiHost host = (DesktopUiHost) Proxy.newProxyInstance(
                DesktopUiHost.class.getClassLoader(), new Class<?>[]{DesktopUiHost.class},
                (proxy, method, arguments) -> {
                    if ("guiPostJson".equals(method.getName())) {
                        actionPath.set((String) arguments[0]);
                        actionBody.set(arguments[1]);
                        if (arguments.length == 4) actionOwner.set((String) arguments[3]);
                        return response(Map.of("private", "ignored"));
                    }
                    try {
                        return method.invoke(delegate, arguments);
                    } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                });
        AppDesktopUiModel model = track(new AppDesktopUiModel(6999, tempDir.resolve("downloads").toString(),
                config, host, sources::get, rendererContract(DesktopUiExperienceProfile.CLASSIC)));

        assertThat(model.snapshot().document().pages()).extracting(DesktopUiDocument.Page::id)
                .endsWith("page-fixture.first", "page-fixture.readonly", "page-fixture.second")
                .doesNotContain("page-fixture.invalid", "page-fixture.enabled",
                        "page-fixture.svg", "page-fixture.external");
        assertThat(model.snapshot().document().dialogs()).extracting(DesktopUiDocument.Dialog::id)
                .contains("page-fixture.second.dialog");
        awaitButtonEnabled(model, "page-fixture.second.run");
        await(() -> {
            if (actionBody.get() == null) {
                dispatch(model, DesktopUiNode.EventType.ACTIVATE,
                        "page-fixture.second.run", DesktopUiNode.Value.empty());
            }
            return actionBody.get() != null;
        });
        assertThat(actionPath).hasValue("page-fixture/run");
        assertThat(actionBody).hasValue(Map.of());
        assertThat(actionOwner).hasValue("page-fixture");

        long activeRevision = model.snapshot().revision();
        sources.set(List.of());
        dispatch(model, DesktopUiNode.EventType.ACTIVATE,
                "debug.unlock.shortcut", DesktopUiNode.Value.empty());

        assertThat(model.snapshot().revision()).isGreaterThan(activeRevision);
        assertThat(model.snapshot().document().pages()).extracting(DesktopUiDocument.Page::id)
                .doesNotContain("page-fixture.first", "page-fixture.second");
        assertThat(model.snapshot().document().dialogs()).extracting(DesktopUiDocument.Dialog::id)
                .doesNotContain("page-fixture.second.dialog");
    }

    @Test
    @DisplayName("宿主页面能力不兼容时拒绝首次快照")
    void incompatibleCoreDocumentFailsBeforePublication() {
        Path config = tempDir.resolve("incompatible-core.yaml");
        AppDesktopUiModel.RendererContract limited = new AppDesktopUiModel.RendererContract(
                "limited-provider", DesktopUiExperienceProfile.CLASSIC,
                Set.of(DesktopUiNode.Kind.TEXT), Set.of());

        assertThatThrownBy(() -> new AppDesktopUiModel(
                6999, tempDir.resolve("downloads").toString(), config,
                new AppDesktopUiHost(6999, new TestDesktopConfigFile(config)), List::of, limited))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("limited-provider");
    }

    @Test
    @DisplayName("插件页面与对话框按当前 owner publication 隔离能力缺口")
    void incompatiblePluginTreesAreIsolatedAndWithdrawnByPublication() {
        PixivFeaturePlugin plugin = new PixivFeaturePlugin() {
            @Override public String id() { return "compat-fixture"; }
            @Override public String displayName() { return "plugin.name"; }
            @Override public String description() { return "plugin.description"; }
            @Override public PluginKind kind() { return PluginKind.FEATURE; }
            @Override public List<DesktopUiPageContribution> desktopPages() {
                DesktopUiNode.Tree pageTree = tree("compat-fixture.tree.content");
                DesktopUiDocument.Dialog dialog = new DesktopUiDocument.Dialog(
                        "compat-fixture.readable.dialog", DesktopUiNode.TextToken.raw("Dialog"),
                        DesktopUiDocument.DialogStyle.INFO,
                        tree("compat-fixture.readable.dialog.content"),
                        "compat-fixture.readable.dialog.dismiss", false, 0, 0);
                return List.of(
                        new DesktopUiPageContribution(
                                "compat-fixture.readable", 10, DesktopUiNode.TextToken.raw("Readable"),
                                new DesktopUiNode.Text(
                                        "compat-fixture.readable.content", DesktopUiNode.TextToken.raw("Content"),
                                        DesktopUiNode.TextStyle.BODY, true, false),
                                Map.of(), List.of(dialog)),
                        new DesktopUiPageContribution(
                                "compat-fixture.tree", 20, DesktopUiNode.TextToken.raw("Tree"), pageTree));
            }

            private DesktopUiNode.Tree tree(String id) {
                return new DesktopUiNode.Tree(
                        id, id + ".value",
                        List.of(new DesktopUiNode.TreeItem(
                                id + ".item", DesktopUiNode.TextToken.raw("Item"), List.of())),
                        DesktopUiNode.SelectionMode.SINGLE, List.of(), false);
            }
        };
        AtomicReference<List<DesktopUiPluginSource>> sources = new AtomicReference<>(List.of(
                new DesktopUiPluginSource(plugin.id(), false, plugin, plugin.getClass().getClassLoader(),
                        "compat-fixture", 1L)));
        Set<DesktopUiCapability> capabilities = new LinkedHashSet<>(Set.of(DesktopUiCapability.values()));
        capabilities.remove(DesktopUiCapability.TREE_EXPAND_COLLAPSE);
        AppDesktopUiModel.RendererContract limited = new AppDesktopUiModel.RendererContract(
                "limited-provider", DesktopUiExperienceProfile.CLASSIC,
                Set.of(DesktopUiNode.Kind.values()), capabilities);
        Path config = tempDir.resolve("incompatible-plugin.yaml");
        AppDesktopUiModel model = track(new AppDesktopUiModel(
                6999, tempDir.resolve("downloads").toString(), config,
                new AppDesktopUiHost(6999, new TestDesktopConfigFile(config)), sources::get, limited));

        DesktopUiDocument.Page readable = model.snapshot().document().pages().stream()
                .filter(page -> page.id().equals("compat-fixture.readable")).findFirst().orElseThrow();
        assertThat(readable.content()).isInstanceOf(DesktopUiNode.Text.class);
        DesktopUiDocument.Page isolated = model.snapshot().document().pages().stream()
                .filter(page -> page.id().equals("compat-fixture.tree")).findFirst().orElseThrow();
        List<DesktopUiNode> isolatedNodes = new ArrayList<>();
        collectNodes(isolated.content(), isolatedNodes);
        assertThat(isolatedNodes).noneMatch(DesktopUiNode.Tree.class::isInstance);
        assertThat(isolatedNodes.stream()
                .filter(DesktopUiNode.Text.class::isInstance)
                .map(DesktopUiNode.Text.class::cast)
                .map(text -> text.text().key()).toList())
                .contains("desktop.ui.compatibility.page-unavailable");
        assertThat(model.snapshot().document().dialogs()).extracting(DesktopUiDocument.Dialog::id)
                .doesNotContain("compat-fixture.readable.dialog");
        DesktopUiDocument.Dialog notice = model.snapshot().document().dialogs().stream()
                .filter(dialog -> dialog.id().startsWith("desktop.compatibility.compat-fixture."))
                .findFirst().orElseThrow();
        List<DesktopUiNode.TextToken> noticeTokens = new ArrayList<>();
        collectTokens(notice.content(), noticeTokens);
        assertThat(noticeTokens).extracting(DesktopUiNode.TextToken::key)
                .contains("desktop.ui.compatibility.dialog-unavailable");
        assertThat(model.snapshot().document().requiredCapabilities())
                .doesNotContain(DesktopUiCapability.TREE_EXPAND_COLLAPSE);

        dispatch(model, DesktopUiNode.EventType.ACTIVATE, notice.id(), DesktopUiNode.Value.empty());
        assertThat(model.snapshot().document().dialogs()).extracting(DesktopUiDocument.Dialog::id)
                .noneMatch(id -> id.startsWith("desktop.compatibility.compat-fixture."));

        sources.set(List.of(new DesktopUiPluginSource(
                plugin.id(), false, plugin, plugin.getClass().getClassLoader(), "compat-fixture", 2L)));
        dispatch(model, DesktopUiNode.EventType.ACTIVATE,
                "debug.unlock.shortcut", DesktopUiNode.Value.empty());
        DesktopUiDocument.Dialog replacementNotice = model.snapshot().document().dialogs().stream()
                .filter(dialog -> dialog.id().startsWith("desktop.compatibility.compat-fixture.2."))
                .findFirst().orElseThrow();
        assertThat(model.snapshot().document().dialogs()).extracting(DesktopUiDocument.Dialog::id)
                .anyMatch(id -> id.startsWith("desktop.compatibility.compat-fixture.2."));

        sources.set(List.of());
        dispatch(model, DesktopUiNode.EventType.ACTIVATE, replacementNotice.id(), DesktopUiNode.Value.empty());
        assertThat(model.snapshot().document().pages()).extracting(DesktopUiDocument.Page::id)
                .doesNotContain("compat-fixture.readable", "compat-fixture.tree");
        assertThat(model.snapshot().document().dialogs()).extracting(DesktopUiDocument.Dialog::id)
                .noneMatch(id -> id.startsWith("desktop.compatibility.compat-fixture."));
    }

    @Test
    @DisplayName("主题选项只包含当前桌面提供者支持的专属主题")
    void themeOptionsFollowTheSelectedDesktopProvider() {
        ThemeProviderPlugin swing = new ThemeProviderPlugin("gui-swing", List.of(
                new GuiThemeContribution("moonlight", locale -> "Moonlight",
                        GuiThemeAppearance.DARK, () -> { })));
        ThemeProviderPlugin compose = new ThemeProviderPlugin("gui-compose", List.of());
        AppDesktopUiModel model = model(List.of(source(swing), source(compose)));

        DesktopUiNode.Choice themes = choice(model.snapshot().document(), "interface.theme.input");
        assertThat(themes.options()).extracting(DesktopUiNode.Option::id)
                .containsExactly("system", "light", "dark", "moonlight");

        dispatch(model, DesktopUiNode.EventType.SELECTION, "interface.provider.input",
                DesktopUiNode.Value.selection("gui-compose"));
        themes = choice(model.snapshot().document(), "interface.theme.input");
        assertThat(themes.options()).extracting(DesktopUiNode.Option::id)
                .containsExactly("system", "light", "dark");
        assertThat(themes.selectedIds()).containsExactly("system");
    }

    @Test
    @DisplayName("基准页面结构保留固定头尾、滚动内容与对齐表单")
    void baselinePageStructureUsesStableLayoutSemantics() {
        DesktopUiDocument document = model().snapshot().document();

        DesktopUiNode.Dock welcome = assertPageContent(document, "welcome", DesktopUiNode.Dock.class);
        assertThat(welcome.top()).isInstanceOf(DesktopUiNode.Container.class);
        assertThat(welcome.center()).isInstanceOf(DesktopUiNode.Scroll.class);
        assertThat(welcome.bottom()).isInstanceOf(DesktopUiNode.Container.class);

        DesktopUiNode.Dock status = assertPageContent(document, "status", DesktopUiNode.Dock.class);
        assertThat(status.center()).isInstanceOf(DesktopUiNode.Scroll.class);
        assertThat(status.bottom()).isInstanceOf(DesktopUiNode.Container.class);

        DesktopUiNode.Dock config = assertPageContent(document, "config", DesktopUiNode.Dock.class);
        assertThat(config.center()).isInstanceOf(DesktopUiNode.Tabs.class);
        assertThat(config.bottom()).isInstanceOf(DesktopUiNode.Surface.class);
        assertThat(nodes(document)).anyMatch(DesktopUiNode.Form.class::isInstance);
        assertThat(nodes(document)).extracting(DesktopUiNode::id)
                .contains("config.autostart.input", "config.open", "config.reset",
                        "config.category.plugins.scopes", "config.category.plugins.settings.empty");
    }

    @Test
    @DisplayName("工具提示与关于页保留基准排版语义")
    void toolsAndAboutKeepBaselineLayoutSemantics() {
        DesktopUiDocument document = model().snapshot().document();
        List<DesktopUiNode> nodes = nodes(document);
        DesktopUiNode.Text limitHint = nodes.stream()
                .filter(DesktopUiNode.Text.class::isInstance).map(DesktopUiNode.Text.class::cast)
                .filter(text -> "tools.backfill.limit-hint".equals(text.id())).findFirst().orElseThrow();
        DesktopUiNode.Text license = nodes.stream()
                .filter(DesktopUiNode.Text.class::isInstance).map(DesktopUiNode.Text.class::cast)
                .filter(text -> "about.license.badge".equals(text.id())).findFirst().orElseThrow();
        DesktopUiNode.Text technology = nodes.stream()
                .filter(DesktopUiNode.Text.class::isInstance).map(DesktopUiNode.Text.class::cast)
                .filter(text -> "about.tech".equals(text.id())).findFirst().orElseThrow();
        DesktopUiNode.Dock proxyControls = nodes.stream()
                .filter(DesktopUiNode.Dock.class::isInstance).map(DesktopUiNode.Dock.class::cast)
                .filter(dock -> "tools.backfill.proxy.controls".equals(dock.id())).findFirst().orElseThrow();
        DesktopUiNode.Dock about = assertPageContent(document, "about", DesktopUiNode.Dock.class);

        assertThat(limitHint.wrap()).isFalse();
        assertThat(proxyControls.center()).isInstanceOf(DesktopUiNode.TextInput.class);
        assertThat(proxyControls.end()).isInstanceOf(DesktopUiNode.Container.class);
        assertThat(about.top()).isInstanceOf(DesktopUiNode.Container.class);
        assertThat(about.center()).isInstanceOf(DesktopUiNode.Scroll.class);
        assertThat(license.textAlignment()).isEqualTo(DesktopUiNode.TextAlignment.CENTER);
        assertThat(technology.text().key()).isEqualTo("gui.about.tech");
        assertThat(technology.text().arguments()).hasSize(1);
    }

    @Test
    @DisplayName("控制中心显示持久化工具历史且 CLASSIC 页面结构不变")
    void controlCenterShowsToolHistoryAndRecordsClassifierClose() throws Exception {
        DesktopToolHistory history = new DesktopToolHistory(RuntimeFiles.guiStateDirectory());
        history.record(DesktopToolHistory.ToolId.JSON_TO_SQLITE_MIGRATION,
                DesktopToolHistory.Outcome.SUCCEEDED, 1L, 9, 7, null,
                Path.of("log", "html", "json-to-sqlite-migration_2026-08-21_120000.html"));

        AppDesktopUiModel controlCenter = model(DesktopUiExperienceProfile.CONTROL_CENTER);
        assertThat(nodes(controlCenter.snapshot().document())).extracting(DesktopUiNode::id)
                .contains("tools.layout", "tools.quick.grid", "tools.maintenance.grid");
        DesktopUiNode.Table table = nodes(controlCenter.snapshot().document()).stream()
                .filter(DesktopUiNode.Table.class::isInstance).map(DesktopUiNode.Table.class::cast)
                .filter(node -> "tools.history.table".equals(node.id())).findFirst().orElseThrow();
        assertThat(table.rows()).singleElement().satisfies(row -> assertThat(row.cells())
                .contains("9", "7", "json-to-sqlite-migration_2026-08-21_120000.html"));
        assertThat(nodes(model(DesktopUiExperienceProfile.CLASSIC).snapshot().document()))
                .extracting(DesktopUiNode::id).doesNotContain("tools.history", "tools.history.table");

        awaitButtonEnabled(controlCenter, "tools.image-classifier.open");
        dispatch(controlCenter, DesktopUiNode.EventType.ACTIVATE,
                "tools.image-classifier.open", DesktopUiNode.Value.empty());
        awaitButtonEnabled(controlCenter, "classifier.dialog.close");
        dispatch(controlCenter, DesktopUiNode.EventType.ACTIVATE,
                "classifier.dialog.close", DesktopUiNode.Value.empty());

        assertThat(new DesktopToolHistory(RuntimeFiles.guiStateDirectory()).entries()).first().satisfies(entry -> {
            assertThat(entry.toolId()).isEqualTo(DesktopToolHistory.ToolId.IMAGE_CLASSIFIER);
            assertThat(entry.outcome()).isEqualTo(DesktopToolHistory.Outcome.CLOSED);
        });
    }

    @Test
    @DisplayName("控制中心安全页在可恢复错误后保留密码并允许显式清空")
    void controlCenterSecurityKeepsRecoverableInputUntilExplicitClear() throws Exception {
        Path config = tempDir.resolve("security.yaml");
        AppDesktopUiHost delegate = new AppDesktopUiHost(6999, new TestDesktopConfigFile(config));
        AtomicInteger requests = new AtomicInteger();
        DesktopUiHost host = (DesktopUiHost) Proxy.newProxyInstance(
                DesktopUiHost.class.getClassLoader(), new Class<?>[]{DesktopUiHost.class},
                (proxy, method, arguments) -> {
                    if ("guiPostJson".equals(method.getName())
                            && "change-password".equals(arguments[0])) {
                        requests.incrementAndGet();
                        return new DesktopUiHost.GuiResponse(true, 401,
                                DesktopUiHost.GuiValue.of(Map.of("error", "invalid-current")), "", false);
                    }
                    try {
                        return method.invoke(delegate, arguments);
                    } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                });
        AppDesktopUiModel model = track(new AppDesktopUiModel(6999, tempDir.resolve("downloads").toString(),
                config, host, List::of, rendererContract(DesktopUiExperienceProfile.CONTROL_CENTER)));
        awaitButtonEnabled(model, "security.submit");
        long formRevision = textInput(model.snapshot().document(), "security.current.input").stateRevision();

        dispatch(model, DesktopUiNode.EventType.CHANGE,
                "security.current.input", DesktopUiNode.Value.text("wrong-password"));
        dispatch(model, DesktopUiNode.EventType.CHANGE,
                "security.new.input", DesktopUiNode.Value.text("new-password-123"));
        dispatch(model, DesktopUiNode.EventType.CHANGE,
                "security.confirm.input", DesktopUiNode.Value.text("new-password-123"));
        dispatch(model, DesktopUiNode.EventType.ACTIVATE,
                "security.submit", DesktopUiNode.Value.empty());

        await(() -> requests.get() == 1
                && "gui.security.error.invalid-current".equals(
                textNode(model.snapshot().document(), "security.notice").text().key()));
        assertThat(textInput(model.snapshot().document(), "security.current.input").stateRevision())
                .isEqualTo(formRevision);
        awaitButtonEnabled(model, "security.submit");
        dispatch(model, DesktopUiNode.EventType.ACTIVATE,
                "security.submit", DesktopUiNode.Value.empty());
        await(() -> requests.get() == 2);

        awaitButtonEnabled(model, "security.clear");
        dispatch(model, DesktopUiNode.EventType.ACTIVATE,
                "security.clear", DesktopUiNode.Value.empty());

        assertThat(textInput(model.snapshot().document(), "security.current.input").stateRevision())
                .isEqualTo(formRevision + 1);
        assertThat(textNode(model.snapshot().document(), "security.notice").text().key())
                .isEqualTo("gui.security.status.idle");
        dispatch(model, DesktopUiNode.EventType.ACTIVATE,
                "security.submit", DesktopUiNode.Value.empty());
        assertThat(textNode(model.snapshot().document(), "security.notice").text().key())
                .isEqualTo("gui.security.validation.current-required");
        assertThat(requests).hasValue(2);
    }

    @Test
    @DisplayName("控制中心设置页从真实配置模型显示未保存、校验和重启状态")
    void controlCenterSettingsProjectsUnsavedValidationAndRestartState() throws Exception {
        Path config = tempDir.resolve("settings.yaml");
        Files.writeString(config, """
                app.language: follow-system
                app.gui-provider: gui-swing
                app.theme: system
                app.config-menu-expand-all: false
                """, StandardCharsets.UTF_8);
        TestDesktopConfigFile configFile = new TestDesktopConfigFile(config);
        AppDesktopUiModel model = track(new AppDesktopUiModel(6999, tempDir.resolve("downloads").toString(),
                config, new AppDesktopUiHost(6999, configFile), List::of,
                rendererContract(DesktopUiExperienceProfile.CONTROL_CENTER)));
        awaitButtonEnabled(model, "config.save");
        assertThat(settingsUnsavedCount(model.snapshot().document())).isEqualTo(0);

        dispatch(model, DesktopUiNode.EventType.SELECTION,
                "settings.categories", DesktopUiNode.Value.selection("download"));
        DesktopUiNode.TextInput root = configTextInput(model.snapshot().document(), "download.root-folder");
        dispatch(model, DesktopUiNode.EventType.CHANGE, root.id(), DesktopUiNode.Value.text("\u0000"));
        assertThat(settingsUnsavedCount(model.snapshot().document())).isEqualTo(1);
        dispatch(model, DesktopUiNode.EventType.ACTIVATE, "config.save", DesktopUiNode.Value.empty());

        await(() -> nodes(model.snapshot().document()).stream()
                .anyMatch(node -> "config.notice".equals(node.id())));
        assertThat(settingsUnsavedCount(model.snapshot().document())).isEqualTo(1);

        dispatch(model, DesktopUiNode.EventType.CHANGE, root.id(), DesktopUiNode.Value.text("pixiv-download"));
        DesktopUiNode.TextInput concurrent = configTextInput(
                model.snapshot().document(), "download.max-concurrent");
        dispatch(model, DesktopUiNode.EventType.CHANGE, concurrent.id(), DesktopUiNode.Value.text("11"));
        assertThat(settingsUnsavedCount(model.snapshot().document())).isEqualTo(1);
        awaitButtonEnabled(model, "config.save");
        dispatch(model, DesktopUiNode.EventType.ACTIVATE, "config.save", DesktopUiNode.Value.empty());

        await(() -> model.snapshot().document().dialogs().stream()
                .anyMatch(dialog -> "config.restart".equals(dialog.id())));
        assertThat(settingsUnsavedCount(model.snapshot().document())).isEqualTo(0);
        assertThat(read(configFile, "download.max-concurrent")).isEqualTo("11");
    }

    @Test
    @DisplayName("界面偏好只由配置页统一保存入口持久化")
    void interfacePreferencesUseTheUnifiedConfigurationSave() throws Exception {
        Path config = tempDir.resolve("interface.yaml");
        TestDesktopConfigFile configFile = new TestDesktopConfigFile(config);
        AppDesktopUiModel model = track(new AppDesktopUiModel(6999, tempDir.resolve("downloads").toString(),
                config, new AppDesktopUiHost(6999, configFile), List::of,
                rendererContract(DesktopUiExperienceProfile.CLASSIC)));
        await(() -> nodes(model.snapshot().document()).stream()
                .filter(DesktopUiNode.Button.class::isInstance)
                .map(DesktopUiNode.Button.class::cast)
                .anyMatch(button -> "config.save".equals(button.id()) && button.enabled()));

        assertThat(nodes(model.snapshot().document())).extracting(DesktopUiNode::id)
                .doesNotContain("interface.save");
        dispatch(model, DesktopUiNode.EventType.CHANGE,
                "interface.config-menu-expand-all.input", DesktopUiNode.Value.bool(true));
        dispatch(model, DesktopUiNode.EventType.ACTIVATE,
                "config.save", DesktopUiNode.Value.empty());

        await(() -> "true".equals(read(configFile, "app.config-menu-expand-all")));
        assertThat(configFile.readAll(List.of("app.language", "app.gui-provider", "app.theme",
                        "app.config-menu-expand-all")))
                .containsEntry("app.language", "follow-system")
                .containsEntry("app.gui-provider", "gui-swing")
                .containsEntry("app.theme", "system")
                .containsEntry("app.config-menu-expand-all", "true");
    }

    @Test
    @DisplayName("配置重置先由声明式对话框确认再修改字段")
    void configurationResetRequiresDeclarativeConfirmation() throws Exception {
        AppDesktopUiModel model = model();
        awaitButtonEnabled(model, "config.reset");
        DesktopUiNode.TextInput root = configTextInput(model.snapshot().document(), "download.root-folder");

        dispatch(model, DesktopUiNode.EventType.CHANGE,
                root.id(), DesktopUiNode.Value.text("changed-root"));
        dispatch(model, DesktopUiNode.EventType.ACTIVATE,
                "config.reset", DesktopUiNode.Value.empty());

        assertThat(model.snapshot().document().dialogs()).extracting(DesktopUiDocument.Dialog::id)
                .containsExactly("config.reset.dialog");
        assertThat(configTextInput(model.snapshot().document(), "download.root-folder").value())
                .isEqualTo("changed-root");

        dispatch(model, DesktopUiNode.EventType.ACTIVATE,
                "config.reset.confirm", DesktopUiNode.Value.empty());

        assertThat(model.snapshot().document().dialogs()).isEmpty();
        assertThat(configTextInput(model.snapshot().document(), "download.root-folder").value())
                .isEqualTo(root.value());
    }

    @Test
    @DisplayName("业务输入立即发布并由重新加载的宿主值覆盖")
    void businessInputIsControlledByPublishedDocument() throws Exception {
        AppDesktopUiModel model = model();
        awaitButtonEnabled(model, "config.reload");
        DesktopUiNode.TextInput root = configTextInput(model.snapshot().document(), "download.root-folder");
        long initialRevision = model.snapshot().revision();

        dispatch(model, DesktopUiNode.EventType.CHANGE,
                root.id(), DesktopUiNode.Value.text("changed-root"));

        assertThat(model.snapshot().revision()).isGreaterThan(initialRevision);
        assertThat(configTextInput(model.snapshot().document(), "download.root-folder").value())
                .isEqualTo("changed-root");
        long editedRevision = model.snapshot().revision();

        dispatch(model, DesktopUiNode.EventType.ACTIVATE,
                "config.reload", DesktopUiNode.Value.empty());

        assertThat(model.snapshot().revision()).isGreaterThan(editedRevision);
        assertThat(configTextInput(model.snapshot().document(), "download.root-folder").value())
                .isEqualTo(root.value());
    }

    @Test
    @DisplayName("连续输入沿用交互修订号且拒绝伪造代际")
    void continuousInputUsesInteractionRevisionInsteadOfDocumentRevision() {
        AppDesktopUiModel model = model();
        DesktopUiSnapshot observed = model.snapshot();
        DesktopUiNode.TextInput root = configTextInput(observed.document(), "download.root-folder");
        long interactionRevision = observed.interactionRevisions().get(root.id());

        model.dispatch(new DesktopUiNode.Event(observed.revision(), interactionRevision,
                DesktopUiNode.EventType.CHANGE, root.id(), DesktopUiNode.Value.text("first-root")));
        DesktopUiSnapshot afterFirst = model.snapshot();
        assertThat(afterFirst.revision()).isGreaterThan(observed.revision());
        assertThat(afterFirst.interactionRevisions().get(root.id())).isEqualTo(interactionRevision);

        model.dispatch(new DesktopUiNode.Event(observed.revision(), interactionRevision,
                DesktopUiNode.EventType.CHANGE, root.id(), DesktopUiNode.Value.text("second-root")));
        assertThat(configTextInput(model.snapshot().document(), "download.root-folder").value())
                .isEqualTo("second-root");

        model.dispatch(new DesktopUiNode.Event(model.snapshot().revision(), interactionRevision + 1L,
                DesktopUiNode.EventType.CHANGE, root.id(), DesktopUiNode.Value.text("forged-root")));
        assertThat(configTextInput(model.snapshot().document(), "download.root-folder").value())
                .isEqualTo("second-root");
    }

    @Test
    @DisplayName("插件代际变化提升其值控件的交互修订号")
    void pluginGenerationChangesInteractionRevision() {
        PixivFeaturePlugin plugin = richConfigPlugin();
        AtomicReference<List<DesktopUiPluginSource>> sources = new AtomicReference<>(List.of(
                new DesktopUiPluginSource(plugin.id(), false, plugin,
                        plugin.getClass().getClassLoader(), "schema-test-package", 1L)));
        Path config = tempDir.resolve("generation-config.yaml");
        AppDesktopUiModel model = track(new AppDesktopUiModel(6999, tempDir.resolve("downloads").toString(),
                config, new AppDesktopUiHost(6999, new TestDesktopConfigFile(config)), sources::get,
                rendererContract(DesktopUiExperienceProfile.CLASSIC)));
        DesktopUiSnapshot observed = model.snapshot();
        DesktopUiNode.Choice mode = nodes(observed.document()).stream()
                .filter(DesktopUiNode.Choice.class::isInstance).map(DesktopUiNode.Choice.class::cast)
                .filter(choice -> choice.bindingId().endsWith("schema-test.mode")).findFirst().orElseThrow();

        sources.set(List.of(new DesktopUiPluginSource(plugin.id(), false, plugin,
                plugin.getClass().getClassLoader(), "schema-test-package", 2L)));
        dispatch(model, DesktopUiNode.EventType.ACTIVATE,
                "debug.unlock.shortcut", DesktopUiNode.Value.empty());

        assertThat(model.snapshot().interactionRevisions().get(mode.id()))
                .isGreaterThan(observed.interactionRevisions().get(mode.id()));
    }

    @Test
    @DisplayName("过期事件和已关闭对话框事件不会执行")
    void staleAndClosedDialogEventsAreIgnored() throws Exception {
        AppDesktopUiModel model = model();
        awaitButtonEnabled(model, "config.reset");
        long pageRevision = model.snapshot().revision();
        model.dispatch(new DesktopUiNode.Event(pageRevision, -1L, DesktopUiNode.EventType.ACTIVATE,
                "config.reset", DesktopUiNode.Value.empty()));
        long dialogRevision = model.snapshot().revision();
        DesktopUiNode.Event confirm = new DesktopUiNode.Event(
                dialogRevision, -1L, DesktopUiNode.EventType.ACTIVATE,
                "config.reset.confirm", DesktopUiNode.Value.empty());

        model.dispatch(new DesktopUiNode.Event(pageRevision, -1L, DesktopUiNode.EventType.ACTIVATE,
                "config.reset.confirm", DesktopUiNode.Value.empty()));
        assertThat(model.snapshot().document().dialogs()).extracting(DesktopUiDocument.Dialog::id)
                .containsExactly("config.reset.dialog");

        model.dispatch(confirm);
        assertThat(model.snapshot().document().dialogs()).isEmpty();
        long closedRevision = model.snapshot().revision();
        model.dispatch(confirm);
        assertThat(model.snapshot().document().dialogs()).isEmpty();
        assertThat(model.snapshot().revision()).isEqualTo(closedRevision);
    }

    @Test
    @DisplayName("禁用节点的重复事件不会执行业务动作")
    void disabledNodeEventsAreRejectedBeforeDispatch() {
        AppDesktopUiModel model = model();
        DesktopUiNode.Button disabled = nodes(model.snapshot().document()).stream()
                .filter(DesktopUiNode.Button.class::isInstance)
                .map(DesktopUiNode.Button.class::cast)
                .filter(button -> !button.enabled())
                .findFirst().orElseThrow();
        DesktopUiDocument before = model.snapshot().document();
        long revision = model.snapshot().revision();
        DesktopUiNode.Event click = new DesktopUiNode.Event(
                revision, -1L, DesktopUiNode.EventType.ACTIVATE, disabled.id(), DesktopUiNode.Value.empty());

        model.dispatch(click);
        assertThat(model.snapshot().document()).isEqualTo(before);
        assertThat(model.snapshot().revision()).isEqualTo(revision);

        model.dispatch(click);
        assertThat(model.snapshot().document()).isEqualTo(before);
        assertThat(model.snapshot().revision()).isEqualTo(revision);
    }

    @Test
    @DisplayName("当前文档拒绝伪造选项和值类型")
    void currentDocumentRejectsForgedOptionsAndValueKinds() {
        AppDesktopUiModel model = model();
        DesktopUiNode.Choice language = nodes(model.snapshot().document()).stream()
                .filter(DesktopUiNode.Choice.class::isInstance)
                .map(DesktopUiNode.Choice.class::cast)
                .filter(choice -> "interface.language.input".equals(choice.id()))
                .findFirst().orElseThrow();
        List<String> selected = language.selectedIds();
        long optionRevision = model.snapshot().revision();

        model.dispatch(new DesktopUiNode.Event(optionRevision,
                model.snapshot().interactionRevisions().get(language.id()), DesktopUiNode.EventType.SELECTION,
                language.id(), DesktopUiNode.Value.selection("forged-option")));
        DesktopUiNode.Choice afterForgedOption = nodes(model.snapshot().document()).stream()
                .filter(DesktopUiNode.Choice.class::isInstance)
                .map(DesktopUiNode.Choice.class::cast)
                .filter(choice -> language.id().equals(choice.id()))
                .findFirst().orElseThrow();
        assertThat(afterForgedOption.selectedIds()).isEqualTo(selected);

        DesktopUiNode.TextInput root = configTextInput(model.snapshot().document(), "download.root-folder");
        long typeRevision = model.snapshot().revision();
        model.dispatch(new DesktopUiNode.Event(typeRevision,
                model.snapshot().interactionRevisions().get(root.id()), DesktopUiNode.EventType.SELECTION,
                root.id(), DesktopUiNode.Value.selection("forged-value")));
        assertThat(configTextInput(model.snapshot().document(), "download.root-folder").value()).isEqualTo(root.value());
        assertThat(model.snapshot().revision()).isEqualTo(typeRevision);
    }

    @Test
    @DisplayName("当前文档索引拒绝禁用选项、伪造行和树项以及越界数字")
    void currentDocumentIndexRejectsForgedSelectionsAndInvalidNumbers() {
        DesktopUiNode.TextToken label = DesktopUiNode.TextToken.raw("Label");
        DesktopUiNode.Choice choice = new DesktopUiNode.Choice(
                "choice", "choice.value", label, null,
                DesktopUiNode.ChoiceStyle.COMBO_BOX, DesktopUiNode.SelectionMode.SINGLE,
                List.of(new DesktopUiNode.Option("disabled-option", label, false)), List.of(), true);
        DesktopUiNode.Table table = new DesktopUiNode.Table(
                "table", "table.value", List.of(new DesktopUiNode.TableColumn("value", label, 0)),
                List.of(new DesktopUiNode.TableRow("row", List.of("Row"))),
                DesktopUiNode.SelectionMode.SINGLE, List.of(), true);
        DesktopUiNode.Tree tree = new DesktopUiNode.Tree(
                "tree", "tree.value", List.of(new DesktopUiNode.TreeItem("item", label, List.of())),
                DesktopUiNode.SelectionMode.SINGLE, List.of(), true);
        DesktopUiNode.NumberInput number = new DesktopUiNode.NumberInput(
                "number", "number.value", label, null, DesktopUiNode.NumberStyle.SPINNER,
                1, 1, 9, 2, true);
        DesktopUiDocument document = new DesktopUiDocument(List.of(new DesktopUiDocument.Page(
                "page", label, new DesktopUiNode.Container(
                "root", DesktopUiNode.ContainerLayout.COLUMN, 1, 0,
                DesktopUiNode.Alignment.STRETCH, List.of(choice, table, tree, number)))));
        Map<String, AppDesktopUiModel.EventEndpoint> endpoints =
                AppDesktopUiModel.indexEventEndpoints(document);

        assertThat(AppDesktopUiModel.validateEvent(endpoints.get(choice.id()), new DesktopUiNode.Event(
                0, 0, DesktopUiNode.EventType.SELECTION, choice.id(),
                DesktopUiNode.Value.selection("disabled-option")))).isEqualTo("choice option is disabled");
        assertThat(AppDesktopUiModel.validateEvent(endpoints.get(table.id()), new DesktopUiNode.Event(
                0, 0, DesktopUiNode.EventType.SELECTION, table.id(),
                DesktopUiNode.Value.selection("forged-row")))).isEqualTo("unknown table row");
        assertThat(AppDesktopUiModel.validateEvent(endpoints.get(tree.id()), new DesktopUiNode.Event(
                0, 0, DesktopUiNode.EventType.SELECTION, tree.id(),
                DesktopUiNode.Value.selection("forged-item")))).isEqualTo("unknown tree item");
        assertThat(AppDesktopUiModel.validateEvent(endpoints.get(number.id()), new DesktopUiNode.Event(
                0, 0, DesktopUiNode.EventType.CHANGE, number.id(),
                DesktopUiNode.Value.number(10)))).isEqualTo("number is outside bounds");
        assertThat(AppDesktopUiModel.validateEvent(endpoints.get(number.id()), new DesktopUiNode.Event(
                0, 0, DesktopUiNode.EventType.CHANGE, number.id(),
                DesktopUiNode.Value.number(4)))).isEqualTo("number does not align with step");
    }

    @Test
    @DisplayName("相对下载根目录经声明式确认固定旧记录后再保存")
    void symbolicDownloadRootIsPinnedBeforeConfigurationSave() throws Exception {
        Path config = tempDir.resolve("symbolic-root.yaml");
        Files.writeString(config, "download.root-folder: relative-download\n", StandardCharsets.UTF_8);
        TestDesktopConfigFile configFile = new TestDesktopConfigFile(config);
        AppDesktopUiHost delegate = new AppDesktopUiHost(6999, configFile);
        AtomicBoolean simulateSymbolicReference = new AtomicBoolean();
        AtomicBoolean pinned = new AtomicBoolean();
        DesktopUiHost host = (DesktopUiHost) Proxy.newProxyInstance(
                DesktopUiHost.class.getClassLoader(), new Class<?>[]{DesktopUiHost.class},
                (proxy, method, arguments) -> {
                    if ("guiGet".equals(method.getName()) && simulateSymbolicReference.get()
                            && "path-prefixes".equals(arguments[0])) {
                        return response(Map.of("symbolicReferenced", true, "prefixes", List.of(
                                Map.of("path", Path.of("relative-download").toAbsolutePath().normalize().toString(),
                                        "symbolic", true))));
                    }
                    if ("guiPostJson".equals(method.getName())
                            && "path-prefixes/pin".equals(arguments[0])) {
                        pinned.set(true);
                        return response(Map.of("success", true));
                    }
                    try {
                        return method.invoke(delegate, arguments);
                    } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                });
        AppDesktopUiModel model = track(new AppDesktopUiModel(6999, tempDir.resolve("downloads").toString(),
                config, host, List::of, rendererContract(DesktopUiExperienceProfile.CLASSIC)));
        await(() -> nodes(model.snapshot().document()).stream()
                .filter(DesktopUiNode.Button.class::isInstance)
                .map(DesktopUiNode.Button.class::cast)
                .anyMatch(button -> "config.save".equals(button.id()) && button.enabled()));
        DesktopUiNode.TextInput root = configTextInput(model.snapshot().document(), "download.root-folder");
        String replacement = tempDir.resolve("new-download").toString();

        dispatch(model, DesktopUiNode.EventType.CHANGE,
                root.id(), DesktopUiNode.Value.text(replacement));
        simulateSymbolicReference.set(true);
        awaitButtonEnabled(model, "config.save");
        dispatch(model, DesktopUiNode.EventType.ACTIVATE,
                "config.save", DesktopUiNode.Value.empty());

        await(() -> model.snapshot().document().dialogs().stream()
                .anyMatch(dialog -> "config.symbolic-pin".equals(dialog.id())));
        assertThat(configFile.read("download.root-folder")).isEqualTo("relative-download");

        awaitButtonEnabled(model, "config.symbolic-pin.confirm");
        dispatch(model, DesktopUiNode.EventType.ACTIVATE,
                "config.symbolic-pin.confirm", DesktopUiNode.Value.empty());

        await(() -> pinned.get() && replacement.equals(read(configFile, "download.root-folder")));
    }

    @Test
    @DisplayName("后端进入运行态后自动刷新插件状态")
    void backendConnectionRefreshesPluginStatusWithoutManualAction() throws Exception {
        Path config = tempDir.resolve("backend-connect.yaml");
        AppDesktopUiHost delegate = new AppDesktopUiHost(6999, new TestDesktopConfigFile(config));
        AtomicBoolean connected = new AtomicBoolean();
        AtomicReference<Consumer<DesktopUiHost.BackendSnapshot>> subscriber = new AtomicReference<>();
        DesktopUiHost host = (DesktopUiHost) Proxy.newProxyInstance(
                DesktopUiHost.class.getClassLoader(), new Class<?>[]{DesktopUiHost.class},
                (proxy, method, arguments) -> {
                    if ("backendSnapshot".equals(method.getName())) {
                        return new DesktopUiHost.BackendSnapshot(DesktopUiHost.BackendState.STOPPED, null);
                    }
                    if ("subscribeBackend".equals(method.getName())) {
                        @SuppressWarnings("unchecked")
                        Consumer<DesktopUiHost.BackendSnapshot> listener =
                                (Consumer<DesktopUiHost.BackendSnapshot>) arguments[0];
                        subscriber.set(listener);
                        return (AutoCloseable) () -> { };
                    }
                    if ("guiGet".equals(method.getName())) {
                        if (!connected.get()) return new DesktopUiHost.GuiResponse(false, 0, null, "", false);
                        return switch ((String) arguments[0]) {
                            case "status" -> response(Map.of("port", "6999", "mode", "solo",
                                    "startTime", "now", "httpsEnabled", false));
                            case "onboarding" -> response(Map.of("batchVisited", false,
                                    "completedSteps", List.of()));
                            case "plugins/status" -> response(Map.of("recoveryMode", false, "plugins", List.of(
                                    Map.of("id", "connected-plugin", "name", "Connected plugin",
                                            "source", "external", "status", "STARTED",
                                            "runtimePhase", "STARTED", "managed", true,
                                            "required", false, "version", "1.0.0"))));
                            default -> new DesktopUiHost.GuiResponse(false, 0, null, "", false);
                        };
                    }
                    try {
                        return method.invoke(delegate, arguments);
                    } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                });
        AppDesktopUiModel model = track(new AppDesktopUiModel(6999, tempDir.resolve("downloads").toString(),
                config, host, List::of, rendererContract(DesktopUiExperienceProfile.CLASSIC)));
        await(() -> subscriber.get() != null);

        connected.set(true);
        subscriber.get().accept(new DesktopUiHost.BackendSnapshot(DesktopUiHost.BackendState.RUNNING, null));

        await(() -> nodes(model.snapshot().document()).stream()
                .anyMatch(node -> "plugins.card.connected-plugin".equals(node.id())));
    }

    @Test
    @DisplayName("隐藏配置由文档级快捷键解锁且既有启用值自动解锁")
    void hiddenConfigurationUsesDocumentShortcutAndStoredState() throws Exception {
        AppDesktopUiModel model = model();
        assertThat(bindingIds(model.snapshot().document())).noneMatch(id -> id.endsWith("debug.enabled"));

        DesktopUiDocument.KeyboardShortcut shortcut = model.snapshot().document().shortcuts().get(0);
        dispatch(model, DesktopUiNode.EventType.ACTIVATE,
                shortcut.id(), DesktopUiNode.Value.empty());
        assertThat(bindingIds(model.snapshot().document())).anyMatch(id -> id.endsWith("debug.enabled"));

        Path config = tempDir.resolve("enabled-debug.yaml");
        Files.writeString(config, "debug.enabled: true\n", StandardCharsets.UTF_8);
        AppDesktopUiModel stored = track(new AppDesktopUiModel(6999, tempDir.resolve("downloads").toString(),
                config,
                new AppDesktopUiHost(6999, new TestDesktopConfigFile(config)), List::of,
                rendererContract(DesktopUiExperienceProfile.CLASSIC)));
        assertThat(bindingIds(stored.snapshot().document())).anyMatch(id -> id.endsWith("debug.enabled"));
    }

    @Test
    @DisplayName("宿主文档中的全部宿主文本均可由每种可见语言解析")
    void allHostDocumentTokensResolveForEveryVisibleLocale() {
        DesktopUiDocument document = model().snapshot().document();
        List<DesktopUiNode.TextToken> tokens = new ArrayList<>();
        collectTokens(document, tokens);

        for (Locale locale : List.of(Locale.SIMPLIFIED_CHINESE, Locale.US, Locale.JAPAN,
                Locale.KOREA, Locale.TRADITIONAL_CHINESE)) {
            assertThat(tokens.stream().filter(token -> token.namespace() == null && !token.key().isBlank()))
                    .allSatisfy(token -> assertThat(MessageBundles.get(locale, token.key(), token.arguments().toArray()))
                            .isNotBlank()
                            .isNotEqualTo(token.key()));
        }
    }

    @Test
    @DisplayName("宿主 Schema 使用的全部静态文案键在每种可见语言中存在")
    void everyStaticSchemaKeyExistsInEveryVisibleLocale() throws Exception {
        String source = Files.readString(Path.of("src/main/java/top/sywyar/pixivdownload/gui/AppDesktopUiModel.java"),
                StandardCharsets.UTF_8);
        Matcher matcher = Pattern.compile("\"((?:gui|desktop\\.ui)\\.[A-Za-z0-9_.-]+)\"").matcher(source);
        Set<String> keys = new LinkedHashSet<>();
        Set<String> dynamicPrefixes = new LinkedHashSet<>();
        while (matcher.find()) {
            String key = matcher.group(1);
            if (key.endsWith(".")) dynamicPrefixes.add(key);
            else keys.add(key);
        }

        for (String name : List.of("messages.properties", "messages_en.properties", "messages_ja.properties",
                "messages_ko.properties", "messages_zh-Hant.properties")) {
            Properties bundle = new Properties();
            try (var reader = Files.newBufferedReader(Path.of("src/main/resources/i18n", name),
                    StandardCharsets.UTF_8)) {
                bundle.load(reader);
            }
            assertThat(keys).as(name).allMatch(bundle::containsKey);
            assertThat(dynamicPrefixes).as(name + " dynamic families").allMatch(prefix ->
                    bundle.stringPropertyNames().stream().anyMatch(key -> key.startsWith(prefix)));
        }
    }

    @Test
    @DisplayName("插件丰富配置贡献由宿主转换为通用节点树")
    void richPluginConfigurationBecomesGenericDocumentNodes() throws Exception {
        PixivFeaturePlugin plugin = richConfigPlugin();
        DesktopUiPluginSource source = new DesktopUiPluginSource(
                "schema-test", false, plugin, plugin.getClass().getClassLoader());
        AppDesktopUiModel model = model(List.of(source));
        DesktopUiDocument document = model.snapshot().document();
        List<String> ids = new ArrayList<>();
        List<DesktopUiNode.TextToken> tokens = new ArrayList<>();
        document.pages().forEach(page -> {
            collectIds(page.content(), ids);
            collectTokens(page.content(), tokens);
        });

        assertThat(ids).contains(
                "config.section.schema-test.section.card.selector",
                "config.schema-test.schema-test.enabled.input",
                "config.category.plugins.settings.tabs",
                "config.section.schema-test.section.card.main.preset.form");
        assertThat(tokens).extracting(DesktopUiNode.TextToken::key)
                .contains("section.title", "preset.label", "preset.global.label",
                        "action.label", "action.global.label");
        assertThat(nodes(document).stream().filter(DesktopUiNode.Choice.class::isInstance)).hasSizeGreaterThanOrEqualTo(2);

        DesktopUiNode.Choice mode = nodes(document).stream()
                .filter(DesktopUiNode.Choice.class::isInstance).map(DesktopUiNode.Choice.class::cast)
                .filter(choice -> choice.bindingId().endsWith("schema-test.mode")).findFirst().orElseThrow();
        awaitChoiceEnabled(model, mode.id());
        dispatch(model, DesktopUiNode.EventType.SELECTION, mode.id(),
                DesktopUiNode.Value.selection(mode.options().get(1).id()));
        DesktopUiNode.Toggle enabledField = nodes(model.snapshot().document()).stream()
                .filter(DesktopUiNode.Toggle.class::isInstance).map(DesktopUiNode.Toggle.class::cast)
                .filter(toggle -> toggle.bindingId().endsWith("schema-test.enabled")).findFirst().orElseThrow();
        assertThat(enabledField.enabled()).isTrue();

        DesktopUiNode.Choice preset = nodes(model.snapshot().document()).stream()
                .filter(DesktopUiNode.Choice.class::isInstance).map(DesktopUiNode.Choice.class::cast)
                .filter(choice -> choice.bindingId().contains(".card.main.preset")).findFirst().orElseThrow();
        awaitChoiceEnabled(model, preset.id());
        dispatch(model, DesktopUiNode.EventType.SELECTION, preset.id(),
                DesktopUiNode.Value.selection(preset.options().get(0).id()));
        DesktopUiNode.Toggle field = nodes(model.snapshot().document()).stream()
                .filter(DesktopUiNode.Toggle.class::isInstance).map(DesktopUiNode.Toggle.class::cast)
                .filter(toggle -> toggle.bindingId().endsWith("schema-test.enabled")).findFirst().orElseThrow();
        assertThat(field.selected()).isTrue();
        assertThat(field.enabled()).isFalse();
    }

    @Test
    @DisplayName("合并的卡片 section 只显示当前卡片来源的提示")
    void mergedCardSectionShowsOnlyTheSelectedCardsNotices() throws Exception {
        PixivFeaturePlugin first = mergeableCardPlugin("card-a-plugin", "card-a", "notice.card-a");
        PixivFeaturePlugin second = mergeableCardPlugin("card-b-plugin", "card-b", "notice.card-b");
        AppDesktopUiModel model = model(List.of(
                new DesktopUiPluginSource(first.id(), false, first, first.getClass().getClassLoader()),
                new DesktopUiPluginSource(second.id(), false, second, second.getClass().getClassLoader())));

        DesktopUiNode.Choice selector = nodes(model.snapshot().document()).stream()
                .filter(DesktopUiNode.Choice.class::isInstance).map(DesktopUiNode.Choice.class::cast)
                .filter(choice -> "config.section.merged.cards.card.selector".equals(choice.id()))
                .findFirst().orElseThrow();
        List<DesktopUiNode.TextToken> tokens = new ArrayList<>();
        collectTokens(model.snapshot().document(), tokens);
        assertThat(tokens).extracting(DesktopUiNode.TextToken::key)
                .contains("notice.card-a").doesNotContain("notice.card-b");

        awaitChoiceEnabled(model, selector.id());
        dispatch(model, DesktopUiNode.EventType.SELECTION, selector.id(),
                DesktopUiNode.Value.selection("card-b"));
        tokens.clear();
        collectTokens(model.snapshot().document(), tokens);
        assertThat(tokens).extracting(DesktopUiNode.TextToken::key)
                .contains("notice.card-b").doesNotContain("notice.card-a");
    }

    @Test
    @DisplayName("插件未声明配置分组标题时使用其本地化名称")
    void pluginNameLabelsAnImplicitConfigurationGroup() {
        PixivFeaturePlugin plugin = new PixivFeaturePlugin() {
            @Override public String id() { return "implicit-group"; }
            @Override public String displayName() { return "plugin.name"; }
            @Override public String description() { return "plugin.summary"; }
            @Override public String displayNamespace() { return "implicit-group"; }
            @Override public PluginKind kind() { return PluginKind.FEATURE; }
            @Override public List<GuiConfigContribution> guiConfigContributions() {
                return List.of(new GuiConfigContribution(List.of(), List.of(
                        new GuiConfigFieldContribution("implicit.value", "implicit-settings",
                                "field.label", GuiConfigFieldType.STRING, "", 0))));
            }
        };
        DesktopUiPluginSource source = new DesktopUiPluginSource(
                plugin.id(), false, plugin, plugin.getClass().getClassLoader());
        List<DesktopUiNode.TextToken> tokens = new ArrayList<>();

        collectTokens(model(List.of(source)).snapshot().document(), tokens);

        assertThat(tokens).anySatisfy(token -> {
            assertThat(token.namespace()).isEqualTo("implicit-group");
            assertThat(token.key()).isEqualTo("plugin.name");
        });
    }

    @Test
    @DisplayName("插件字段首次加载时从旧 YAML 迁入 owner 配置与凭据存储")
    void pluginConfigurationMigratesToOwnerStores() throws Exception {
        Path runtimeConfig = tempDir.resolve("runtime-config");
        System.setProperty(RuntimeFiles.CONFIG_DIR_PROPERTY, runtimeConfig.toString());
        Path config = tempDir.resolve("config.yaml");
        Files.writeString(config, "fixture.value: legacy-value\nfixture.secret: legacy-secret\n",
                StandardCharsets.UTF_8);
        PixivFeaturePlugin plugin = pluginWithMigratedFields();
        DesktopUiPluginSource source = new DesktopUiPluginSource(
                "fixture", false, plugin, plugin.getClass().getClassLoader());

        AppDesktopUiModel model = track(new AppDesktopUiModel(6999, tempDir.resolve("downloads").toString(),
                config,
                new AppDesktopUiHost(6999, new TestDesktopConfigFile(config)), () -> List.of(source),
                rendererContract(DesktopUiExperienceProfile.CLASSIC)));

        Properties properties = new Properties();
        try (var reader = Files.newBufferedReader(
                RuntimeFiles.resolvePluginConfigPath("fixture", "properties"), StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        assertThat(properties).containsEntry("fixture.value", "legacy-value")
                .doesNotContainKey("fixture.secret");
        assertThat(new PluginCredentialStore().readAll("fixture"))
                .containsEntry("fixture.secret", "legacy-secret");
        assertThat(Files.readString(config, StandardCharsets.UTF_8))
                .doesNotContain("fixture.value", "fixture.secret", "legacy-secret");
        assertThat(nodes(model.snapshot().document()).stream()
                .filter(DesktopUiNode.TextInput.class::isInstance)
                .map(DesktopUiNode.TextInput.class::cast)
                .filter(input -> input.bindingId().endsWith("fixture.secret"))
                .findFirst().orElseThrow().value()).isEmpty();
    }

    private AppDesktopUiModel model() {
        return model(List.of());
    }

    private AppDesktopUiModel model(DesktopUiExperienceProfile experienceProfile) {
        return model(List.of(), experienceProfile);
    }

    private AppDesktopUiModel model(List<DesktopUiPluginSource> sources) {
        return model(sources, DesktopUiExperienceProfile.CLASSIC);
    }

    private AppDesktopUiModel model(List<DesktopUiPluginSource> sources,
                                    DesktopUiExperienceProfile experienceProfile) {
        Path config = tempDir.resolve("config.yaml");
        return track(new AppDesktopUiModel(6999, tempDir.resolve("downloads").toString(),
                config,
                new AppDesktopUiHost(6999, new TestDesktopConfigFile(config)), () -> sources,
                rendererContract(experienceProfile)));
    }

    private AppDesktopUiModel modelWithOnboarding(List<DesktopUiPluginSource> sources,
                                                   DesktopUiExperienceProfile experienceProfile,
                                                   DesktopUiHost.OnboardingSnapshot onboarding) {
        Path config = tempDir.resolve("config.yaml");
        AppDesktopUiHost delegate = new AppDesktopUiHost(6999, new TestDesktopConfigFile(config));
        DesktopUiHost host = (DesktopUiHost) Proxy.newProxyInstance(
                DesktopUiHost.class.getClassLoader(), new Class<?>[]{DesktopUiHost.class},
                (proxy, method, arguments) -> {
                    if ("onboardingState".equals(method.getName())) return onboarding;
                    if ("backendSnapshot".equals(method.getName())) {
                        return new DesktopUiHost.BackendSnapshot(DesktopUiHost.BackendState.RUNNING, null);
                    }
                    if ("subscribeBackend".equals(method.getName())) return (AutoCloseable) () -> { };
                    if (method.getName().startsWith("markOnboarding")
                            || "saveOnboardingProgress".equals(method.getName())) return true;
                    try {
                        return method.invoke(delegate, arguments);
                    } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                });
        return track(new AppDesktopUiModel(6999, tempDir.resolve("downloads").toString(),
                config, host, () -> sources, rendererContract(experienceProfile)));
    }

    private static Map<String, Object> token(String value) {
        return Map.of("key", "", "fallback", value, "arguments", List.of());
    }

    private static DesktopUiPluginSource quickStartSource(
            String id, String href, AccessPolicy navigationPolicy,
            AccessPolicy routePolicy, boolean ownsTarget) {
        return quickStartSource(id, href, navigationPolicy, routePolicy, ownsTarget, List.of());
    }

    private static DesktopUiPluginSource quickStartSource(
            String id, String href, AccessPolicy navigationPolicy,
            AccessPolicy routePolicy, boolean ownsTarget,
            List<WebRouteContribution> additionalRoutes) {
        PixivFeaturePlugin plugin = new PixivFeaturePlugin() {
            @Override public String id() { return id; }
            @Override public String displayName() { return id; }
            @Override public String description() { return id; }
            @Override public PluginKind kind() { return PluginKind.FEATURE; }
            @Override public List<WebRouteContribution> routes() {
                List<WebRouteContribution> routes = new ArrayList<>(additionalRoutes);
                if (ownsTarget) {
                    routes.add(new WebRouteContribution(href, routePolicy, Set.of(HttpMethod.GET), false));
                }
                return List.copyOf(routes);
            }
            @Override public List<NavigationContribution> navigation() {
                return List.of(new NavigationContribution(id,
                        NavigationPlacements.DESKTOP_QUICK_START, id, "label", href,
                        "open", navigationPolicy, 10));
            }
        };
        return new DesktopUiPluginSource(id, false, plugin, plugin.getClass().getClassLoader());
    }

    private static AppDesktopUiModel.RendererContract rendererContract(
            DesktopUiExperienceProfile experienceProfile) {
        return new AppDesktopUiModel.RendererContract(
                "test", experienceProfile,
                Set.of(DesktopUiNode.Kind.values()), Set.of(DesktopUiCapability.values()));
    }

    private static DesktopUiPluginSource source(ThemeProviderPlugin plugin) {
        return new DesktopUiPluginSource(plugin.id(), false, plugin, plugin.getClass().getClassLoader());
    }

    private static DesktopUiNode.Choice choice(DesktopUiDocument document, String id) {
        return nodes(document).stream()
                .filter(DesktopUiNode.Choice.class::isInstance)
                .map(DesktopUiNode.Choice.class::cast)
                .filter(choice -> id.equals(choice.id()))
                .findFirst().orElseThrow();
    }

    private static DesktopUiNode.TextInput textInput(DesktopUiDocument document, String id) {
        return nodes(document).stream()
                .filter(DesktopUiNode.TextInput.class::isInstance)
                .map(DesktopUiNode.TextInput.class::cast)
                .filter(input -> id.equals(input.id()))
                .findFirst().orElseThrow();
    }

    private static DesktopUiNode.Text textNode(DesktopUiDocument document, String id) {
        return nodes(document).stream()
                .filter(DesktopUiNode.Text.class::isInstance)
                .map(DesktopUiNode.Text.class::cast)
                .filter(text -> id.equals(text.id()))
                .findFirst().orElseThrow();
    }

    private static int settingsUnsavedCount(DesktopUiDocument document) {
        return Integer.parseInt(textNode(document, "settings.unsaved-count").text().arguments().get(0));
    }

    private record ThemeProviderPlugin(String id, List<GuiThemeContribution> themes)
            implements PixivFeaturePlugin, DesktopUiProvider {
        @Override public String displayName() { return id; }
        @Override public String description() { return id; }
        @Override public PluginKind kind() { return PluginKind.FEATURE; }
        @Override public List<GuiThemeContribution> guiThemes() { return themes; }
        @Override public Set<DesktopUiNode.Kind> supportedNodeKinds() { return Set.of(DesktopUiNode.Kind.TEXT); }
        @Override public Set<DesktopUiCapability> supportedCapabilities() { return Set.of(); }
        @Override public DesktopUiSession launch(DesktopUiContext context) {
            throw new UnsupportedOperationException();
        }
    }

    private AppDesktopUiModel track(AppDesktopUiModel model) {
        openModels.add(model);
        return model;
    }

    private static PixivFeaturePlugin richConfigPlugin() {
        return new PixivFeaturePlugin() {
            @Override public String id() { return "schema-test"; }
            @Override public String displayName() { return "plugin.name"; }
            @Override public String description() { return "plugin.summary"; }
            @Override public String displayNamespace() { return "schema-test"; }
            @Override public PluginKind kind() { return PluginKind.FEATURE; }
            @Override public List<WebRouteContribution> routes() {
                return List.of(new WebRouteContribution("/api/gui/schema-test-action",
                        AccessPolicy.GUI, Set.of(HttpMethod.POST), false));
            }
            @Override public List<GuiConfigContribution> guiConfigContributions() {
                GuiConfigFieldContribution field = new GuiConfigFieldContribution(
                        "schema-test.enabled", "schema-test", "field.label", "field.help", null,
                        GuiConfigFieldType.BOOL, "false", 0, false, GuiConfigEffect.HOT_RELOAD,
                        List.of(), List.of(GuiConfigCondition.equalsTo("schema-test.mode", "fast/mode")),
                        List.of(), null, null);
                GuiConfigFieldContribution mode = new GuiConfigFieldContribution(
                        "schema-test.mode", "schema-test", "field.label", "field.help", null,
                        GuiConfigFieldType.ENUM, "safe mode", 1, false, GuiConfigEffect.HOT_RELOAD,
                        List.of("safe mode", "fast/mode"), List.of(), List.of(), null, null);
                GuiConfigSectionContribution section = new GuiConfigSectionContribution(
                        "schema-test.section", "schema-test", "section.title", "section.help", null,
                        GuiConfigSectionLayout.CARD_SWITCHER, 0,
                        List.of(
                                new GuiConfigFieldLayoutContribution(field.key(), "main", "card.label", 0),
                                new GuiConfigFieldLayoutContribution(mode.key(), "main", "card.label", 1)),
                        List.of(
                                new GuiConfigActionContribution(
                                        "schema-test.global-action", "action.global.label", "action.help", null,
                                        null, "schema-test-action", 1_000, 0, List.of(), "", List.of(), null),
                                new GuiConfigActionContribution(
                                        "schema-test.action", "action.label", "action.help", null, "main",
                                        "schema-test-action", 1_000, 1, List.of(), "", List.of(), null)),
                        List.of(
                                new GuiConfigPresetContribution(
                                        "schema-test.global-preset", "preset.global.label", "preset.help", null,
                                        null, 0, field.key(), "false", Map.of(field.key(), "false"), List.of()),
                                new GuiConfigPresetContribution(
                                        "schema-test.preset", "preset.label", "preset.help", null, "main", 1,
                                        field.key(), "true", Map.of(field.key(), "true"))));
                return List.of(new GuiConfigContribution(
                        List.of(new GuiConfigGroupContribution(
                                "schema-test", "group.label", null, 2_000, true)),
                        List.of(field, mode), List.of(section)));
            }
        };
    }

    private static PixivFeaturePlugin mergeableCardPlugin(String id, String cardId, String noticeKey) {
        return new PixivFeaturePlugin() {
            @Override public String id() { return id; }
            @Override public String displayName() { return "plugin.name"; }
            @Override public String description() { return "plugin.summary"; }
            @Override public String displayNamespace() { return id; }
            @Override public PluginKind kind() { return PluginKind.FEATURE; }
            @Override public List<GuiConfigContribution> guiConfigContributions() {
                String fieldKey = id + ".value";
                GuiConfigFieldContribution field = new GuiConfigFieldContribution(
                        fieldKey, GuiConfigGroups.PLUGINS, "field.label",
                        GuiConfigFieldType.STRING, "", 0);
                GuiConfigSectionContribution section = new GuiConfigSectionContribution(
                        "merged.cards", GuiConfigGroups.PLUGINS, "", "", id,
                        "card.selector", "", "", "",
                        List.of(new GuiConfigSectionNoticeContribution(id + ".notice", noticeKey, 0)),
                        GuiConfigSectionLayout.CARD_SWITCHER, 0,
                        List.of(new GuiConfigFieldLayoutContribution(fieldKey, cardId, "card.label", 0)),
                        List.of(), List.of(), true, true);
                return List.of(new GuiConfigContribution(List.of(), List.of(field), List.of(section)));
            }
        };
    }

    private static PixivFeaturePlugin pluginWithMigratedFields() {
        return new PixivFeaturePlugin() {
            @Override public String id() { return "fixture"; }
            @Override public String displayName() { return "plugin.name"; }
            @Override public String description() { return "plugin.summary"; }
            @Override public PluginKind kind() { return PluginKind.FEATURE; }
            @Override public List<GuiConfigContribution> guiConfigContributions() {
                return List.of(new GuiConfigContribution(List.of(), List.of(
                        new GuiConfigFieldContribution("fixture.value", GuiConfigGroups.PLUGINS,
                                "fixture.value.label", GuiConfigFieldType.STRING, "default", 0),
                        new GuiConfigFieldContribution("fixture.secret", GuiConfigGroups.PLUGINS,
                                "fixture.secret.label", "", GuiConfigFieldType.PASSWORD, "", 1,
                                true, GuiConfigEffect.BACKEND_RESTART))));
            }
        };
    }

    private static void collectIds(DesktopUiNode node, List<String> ids) {
        ids.add(node.id());
        node.childNodes().forEach(child -> collectIds(child, ids));
    }

    private static List<DesktopUiNode> nodes(DesktopUiDocument document) {
        List<DesktopUiNode> nodes = new ArrayList<>();
        document.pages().forEach(page -> collectNodes(page.content(), nodes));
        document.dialogs().forEach(dialog -> collectNodes(dialog.content(), nodes));
        return nodes;
    }

    private static Set<String> bindingIds(DesktopUiDocument document) {
        return nodes(document).stream().map(node -> {
            if (node instanceof DesktopUiNode.TextInput input) return input.bindingId();
            if (node instanceof DesktopUiNode.Toggle toggle) return toggle.bindingId();
            if (node instanceof DesktopUiNode.Choice choice) return choice.bindingId();
            if (node instanceof DesktopUiNode.NumberInput input) return input.bindingId();
            return "";
        }).filter(id -> !id.isBlank()).collect(java.util.stream.Collectors.toSet());
    }

    private static DesktopUiNode.TextInput configTextInput(DesktopUiDocument document, String key) {
        return nodes(document).stream()
                .filter(DesktopUiNode.TextInput.class::isInstance)
                .map(DesktopUiNode.TextInput.class::cast)
                .filter(input -> input.bindingId().endsWith(key))
                .findFirst().orElseThrow();
    }

    private static DesktopUiHost.GuiResponse response(Map<String, Object> body) {
        return new DesktopUiHost.GuiResponse(true, 200, DesktopUiHost.GuiValue.of(body), "", false);
    }

    private static void dispatch(AppDesktopUiModel model, DesktopUiNode.EventType type,
                                 String nodeId, DesktopUiNode.Value value) {
        synchronized (model) {
            DesktopUiSnapshot snapshot = model.snapshot();
            long interactionRevision = type == DesktopUiNode.EventType.ACTIVATE ? -1L
                    : snapshot.interactionRevisions().get(nodeId);
            model.dispatch(new DesktopUiNode.Event(
                    snapshot.revision(), interactionRevision, type, nodeId, value));
        }
    }

    private static String read(DesktopUiHost.ConfigFile config, String key) {
        try {
            return config.read(key);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(10L);
        assertThat(condition.getAsBoolean()).isTrue();
    }

    private static void awaitButtonEnabled(AppDesktopUiModel model, String id) throws InterruptedException {
        await(() -> nodes(model.snapshot().document()).stream()
                .filter(DesktopUiNode.Button.class::isInstance)
                .map(DesktopUiNode.Button.class::cast)
                .anyMatch(button -> id.equals(button.id()) && button.enabled()));
    }

    private static void awaitChoiceEnabled(AppDesktopUiModel model, String id) throws InterruptedException {
        await(() -> nodes(model.snapshot().document()).stream()
                .filter(DesktopUiNode.Choice.class::isInstance)
                .map(DesktopUiNode.Choice.class::cast)
                .anyMatch(choice -> id.equals(choice.id()) && choice.enabled()));
    }

    private static <T extends DesktopUiNode> T assertPageContent(
            DesktopUiDocument document, String pageId, Class<T> type) {
        DesktopUiNode content = document.pages().stream().filter(page -> page.id().equals(pageId))
                .findFirst().orElseThrow().content();
        assertThat(content).isInstanceOf(DesktopUiNode.Surface.class);
        DesktopUiNode nested = ((DesktopUiNode.Surface) content).content();
        assertThat(nested).isInstanceOf(type);
        return type.cast(nested);
    }

    private static void collectNodes(DesktopUiNode node, List<DesktopUiNode> nodes) {
        nodes.add(node);
        node.childNodes().forEach(child -> collectNodes(child, nodes));
    }

    private static void collectTokens(DesktopUiDocument document, List<DesktopUiNode.TextToken> tokens) {
        document.pages().forEach(page -> {
            tokens.add(page.title());
            collectTokens(page.content(), tokens);
        });
        document.dialogs().forEach(dialog -> {
            tokens.add(dialog.title());
            collectTokens(dialog.content(), tokens);
        });
    }

    private static void collectTokens(DesktopUiNode node, List<DesktopUiNode.TextToken> tokens) {
        if (node instanceof DesktopUiNode.Group group) tokens.add(group.title());
        else if (node instanceof DesktopUiNode.Form form) {
            add(tokens, form.labelSuffix());
            form.rows().forEach(row -> {
                tokens.add(row.label());
                add(tokens, row.help());
            });
        }
        else if (node instanceof DesktopUiNode.Tabs tabs) tabs.tabs().forEach(tab -> tokens.add(tab.title()));
        else if (node instanceof DesktopUiNode.Text text) tokens.add(text.text());
        else if (node instanceof DesktopUiNode.Image image) tokens.add(image.altText());
        else if (node instanceof DesktopUiNode.Progress progress) add(tokens, progress.text());
        else if (node instanceof DesktopUiNode.TextInput input) {
            tokens.add(input.label()); add(tokens, input.help());
        } else if (node instanceof DesktopUiNode.Toggle toggle) {
            tokens.add(toggle.label()); add(tokens, toggle.help());
        } else if (node instanceof DesktopUiNode.Choice choice) {
            tokens.add(choice.label()); add(tokens, choice.help());
            choice.options().forEach(option -> tokens.add(option.label()));
        } else if (node instanceof DesktopUiNode.NumberInput input) {
            tokens.add(input.label()); add(tokens, input.help());
        } else if (node instanceof DesktopUiNode.Table table) {
            table.columns().forEach(column -> tokens.add(column.label()));
        } else if (node instanceof DesktopUiNode.Tree tree) {
            tree.items().forEach(item -> collectTokens(item, tokens));
        } else if (node instanceof DesktopUiNode.Button button) {
            tokens.add(button.label()); add(tokens, button.help());
        } else if (node instanceof DesktopUiNode.Link link) {
            tokens.add(link.label()); add(tokens, link.help());
        }
        node.childNodes().forEach(child -> collectTokens(child, tokens));
    }

    private static void collectTokens(DesktopUiNode.TreeItem item, List<DesktopUiNode.TextToken> tokens) {
        tokens.add(item.label());
        item.children().forEach(child -> collectTokens(child, tokens));
    }

    private static void add(List<DesktopUiNode.TextToken> tokens, DesktopUiNode.TextToken token) {
        if (token != null) tokens.add(token);
    }
}
