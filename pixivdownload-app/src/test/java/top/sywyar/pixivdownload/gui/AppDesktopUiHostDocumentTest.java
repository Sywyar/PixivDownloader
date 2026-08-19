package top.sywyar.pixivdownload.gui;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.config.credential.PluginCredentialStore;
import top.sywyar.pixivdownload.gui.config.TestDesktopConfigFile;
import top.sywyar.pixivdownload.i18n.MessageBundles;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiCapability;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiContext;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiProvider;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiSession;
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
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class AppDesktopUiHostDocumentTest {

    @TempDir
    Path tempDir;
    private final List<AppDesktopUiModel> openModels = new ArrayList<>();

    @AfterEach
    void clearRuntimeConfigOverride() throws Exception {
        for (AppDesktopUiModel model : openModels) model.close();
        System.clearProperty(RuntimeFiles.CONFIG_DIR_PROPERTY);
    }

    @Test
    @DisplayName("宿主模型提供全部桌面根页面")
    void hostModelProvidesCompleteDesktopDocument() {
        DesktopUiDocument document = model().document();

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
    @DisplayName("宿主选择绑定保留全部多选值")
    void selectionBindingsReceiveEverySelectedId() {
        AtomicReference<List<String>> selected = new AtomicReference<>();

        AppDesktopUiModel.acceptSelection(selected::set,
                DesktopUiNode.Value.selections(List.of("first", "second")));

        assertThat(selected).hasValue(List.of("first", "second"));
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

        DesktopUiDocument.TrayItem item = model(List.of(source)).document().tray().orElseThrow().items().stream()
                .filter(candidate -> candidate.id().startsWith("tray.web."))
                .findFirst().orElseThrow();

        assertThat(item.label().namespace()).isEqualTo("fixture");
        assertThat(item.label().key()).isEqualTo("navigation.label");
        assertThat(item.actionId()).isEqualTo(item.id() + ".open");
    }

    @Test
    @DisplayName("主题选项只包含当前桌面提供者支持的专属主题")
    void themeOptionsFollowTheSelectedDesktopProvider() {
        ThemeProviderPlugin swing = new ThemeProviderPlugin("gui-swing", List.of(
                new GuiThemeContribution("moonlight", locale -> "Moonlight",
                        GuiThemeAppearance.DARK, () -> { })));
        ThemeProviderPlugin compose = new ThemeProviderPlugin("gui-compose", List.of());
        AppDesktopUiModel model = model(List.of(source(swing), source(compose)));

        DesktopUiNode.Choice themes = choice(model.document(), "interface.theme.input");
        assertThat(themes.options()).extracting(DesktopUiNode.Option::id)
                .containsExactly("system", "light", "dark", "moonlight");

        dispatch(model, DesktopUiNode.EventType.SELECTION, "interface.provider.input",
                DesktopUiNode.Value.selection("gui-compose"));
        themes = choice(model.document(), "interface.theme.input");
        assertThat(themes.options()).extracting(DesktopUiNode.Option::id)
                .containsExactly("system", "light", "dark");
        assertThat(themes.selectedIds()).containsExactly("system");
    }

    @Test
    @DisplayName("基准页面结构保留固定头尾、滚动内容与对齐表单")
    void baselinePageStructureUsesStableLayoutSemantics() {
        DesktopUiDocument document = model().document();

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
        DesktopUiDocument document = model().document();
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
    @DisplayName("界面偏好只由配置页统一保存入口持久化")
    void interfacePreferencesUseTheUnifiedConfigurationSave() throws Exception {
        Path config = tempDir.resolve("interface.yaml");
        TestDesktopConfigFile configFile = new TestDesktopConfigFile(config);
        AppDesktopUiModel model = track(new AppDesktopUiModel(6999, tempDir.resolve("downloads").toString(),
                config, new AppDesktopUiHost(6999, configFile), List::of));
        await(() -> nodes(model.document()).stream()
                .filter(DesktopUiNode.Button.class::isInstance)
                .map(DesktopUiNode.Button.class::cast)
                .anyMatch(button -> "config.save".equals(button.id()) && button.enabled()));

        assertThat(nodes(model.document())).extracting(DesktopUiNode::id)
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
        DesktopUiNode.TextInput root = configTextInput(model.document(), "download.root-folder");

        dispatch(model, DesktopUiNode.EventType.CHANGE,
                root.id(), DesktopUiNode.Value.text("changed-root"));
        dispatch(model, DesktopUiNode.EventType.ACTIVATE,
                "config.reset", DesktopUiNode.Value.empty());

        assertThat(model.document().dialogs()).extracting(DesktopUiDocument.Dialog::id)
                .containsExactly("config.reset.dialog");
        assertThat(configTextInput(model.document(), "download.root-folder").value())
                .isEqualTo("changed-root");

        dispatch(model, DesktopUiNode.EventType.ACTIVATE,
                "config.reset.confirm", DesktopUiNode.Value.empty());

        assertThat(model.document().dialogs()).isEmpty();
        assertThat(configTextInput(model.document(), "download.root-folder").value())
                .isEqualTo(root.value());
    }

    @Test
    @DisplayName("业务输入立即发布并由重新加载的宿主值覆盖")
    void businessInputIsControlledByPublishedDocument() throws Exception {
        AppDesktopUiModel model = model();
        awaitButtonEnabled(model, "config.reload");
        DesktopUiNode.TextInput root = configTextInput(model.document(), "download.root-folder");
        long initialRevision = model.revision();

        dispatch(model, DesktopUiNode.EventType.CHANGE,
                root.id(), DesktopUiNode.Value.text("changed-root"));

        assertThat(model.revision()).isGreaterThan(initialRevision);
        assertThat(configTextInput(model.document(), "download.root-folder").value())
                .isEqualTo("changed-root");
        long editedRevision = model.revision();

        dispatch(model, DesktopUiNode.EventType.ACTIVATE,
                "config.reload", DesktopUiNode.Value.empty());

        assertThat(model.revision()).isGreaterThan(editedRevision);
        assertThat(configTextInput(model.document(), "download.root-folder").value())
                .isEqualTo(root.value());
    }

    @Test
    @DisplayName("过期事件和已关闭对话框事件不会执行")
    void staleAndClosedDialogEventsAreIgnored() throws Exception {
        AppDesktopUiModel model = model();
        awaitButtonEnabled(model, "config.reset");
        long pageRevision = model.revision();
        model.dispatch(new DesktopUiNode.Event(pageRevision, DesktopUiNode.EventType.ACTIVATE,
                "config.reset", DesktopUiNode.Value.empty()));
        long dialogRevision = model.revision();
        DesktopUiNode.Event confirm = new DesktopUiNode.Event(
                dialogRevision, DesktopUiNode.EventType.ACTIVATE,
                "config.reset.confirm", DesktopUiNode.Value.empty());

        model.dispatch(new DesktopUiNode.Event(pageRevision, DesktopUiNode.EventType.ACTIVATE,
                "config.reset.confirm", DesktopUiNode.Value.empty()));
        assertThat(model.document().dialogs()).extracting(DesktopUiDocument.Dialog::id)
                .containsExactly("config.reset.dialog");

        model.dispatch(confirm);
        assertThat(model.document().dialogs()).isEmpty();
        long closedRevision = model.revision();
        model.dispatch(confirm);
        assertThat(model.document().dialogs()).isEmpty();
        assertThat(model.revision()).isEqualTo(closedRevision);
    }

    @Test
    @DisplayName("禁用节点的重复事件不会执行业务动作")
    void disabledNodeEventsAreRejectedBeforeDispatch() {
        AppDesktopUiModel model = model();
        DesktopUiNode.Button disabled = nodes(model.document()).stream()
                .filter(DesktopUiNode.Button.class::isInstance)
                .map(DesktopUiNode.Button.class::cast)
                .filter(button -> !button.enabled())
                .findFirst().orElseThrow();
        DesktopUiDocument before = model.document();
        long revision = model.revision();
        DesktopUiNode.Event click = new DesktopUiNode.Event(
                revision, DesktopUiNode.EventType.ACTIVATE, disabled.id(), DesktopUiNode.Value.empty());

        model.dispatch(click);
        assertThat(model.document()).isEqualTo(before);
        assertThat(model.revision()).isEqualTo(revision);

        model.dispatch(click);
        assertThat(model.document()).isEqualTo(before);
        assertThat(model.revision()).isEqualTo(revision);
    }

    @Test
    @DisplayName("当前文档拒绝伪造选项和值类型")
    void currentDocumentRejectsForgedOptionsAndValueKinds() {
        AppDesktopUiModel model = model();
        DesktopUiNode.Choice language = nodes(model.document()).stream()
                .filter(DesktopUiNode.Choice.class::isInstance)
                .map(DesktopUiNode.Choice.class::cast)
                .filter(choice -> "interface.language.input".equals(choice.id()))
                .findFirst().orElseThrow();
        List<String> selected = language.selectedIds();
        long optionRevision = model.revision();

        model.dispatch(new DesktopUiNode.Event(optionRevision, DesktopUiNode.EventType.SELECTION,
                language.id(), DesktopUiNode.Value.selection("forged-option")));
        DesktopUiNode.Choice afterForgedOption = nodes(model.document()).stream()
                .filter(DesktopUiNode.Choice.class::isInstance)
                .map(DesktopUiNode.Choice.class::cast)
                .filter(choice -> language.id().equals(choice.id()))
                .findFirst().orElseThrow();
        assertThat(afterForgedOption.selectedIds()).isEqualTo(selected);

        DesktopUiNode.TextInput root = configTextInput(model.document(), "download.root-folder");
        long typeRevision = model.revision();
        model.dispatch(new DesktopUiNode.Event(typeRevision, DesktopUiNode.EventType.SELECTION,
                root.id(), DesktopUiNode.Value.selection("forged-value")));
        assertThat(configTextInput(model.document(), "download.root-folder").value()).isEqualTo(root.value());
        assertThat(model.revision()).isEqualTo(typeRevision);
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
                0, DesktopUiNode.EventType.SELECTION, choice.id(),
                DesktopUiNode.Value.selection("disabled-option")))).isEqualTo("choice option is disabled");
        assertThat(AppDesktopUiModel.validateEvent(endpoints.get(table.id()), new DesktopUiNode.Event(
                0, DesktopUiNode.EventType.SELECTION, table.id(),
                DesktopUiNode.Value.selection("forged-row")))).isEqualTo("unknown table row");
        assertThat(AppDesktopUiModel.validateEvent(endpoints.get(tree.id()), new DesktopUiNode.Event(
                0, DesktopUiNode.EventType.SELECTION, tree.id(),
                DesktopUiNode.Value.selection("forged-item")))).isEqualTo("unknown tree item");
        assertThat(AppDesktopUiModel.validateEvent(endpoints.get(number.id()), new DesktopUiNode.Event(
                0, DesktopUiNode.EventType.CHANGE, number.id(),
                DesktopUiNode.Value.number(10)))).isEqualTo("number is outside bounds");
        assertThat(AppDesktopUiModel.validateEvent(endpoints.get(number.id()), new DesktopUiNode.Event(
                0, DesktopUiNode.EventType.CHANGE, number.id(),
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
                config, host, List::of));
        await(() -> nodes(model.document()).stream()
                .filter(DesktopUiNode.Button.class::isInstance)
                .map(DesktopUiNode.Button.class::cast)
                .anyMatch(button -> "config.save".equals(button.id()) && button.enabled()));
        DesktopUiNode.TextInput root = configTextInput(model.document(), "download.root-folder");
        String replacement = tempDir.resolve("new-download").toString();

        dispatch(model, DesktopUiNode.EventType.CHANGE,
                root.id(), DesktopUiNode.Value.text(replacement));
        simulateSymbolicReference.set(true);
        awaitButtonEnabled(model, "config.save");
        dispatch(model, DesktopUiNode.EventType.ACTIVATE,
                "config.save", DesktopUiNode.Value.empty());

        await(() -> model.document().dialogs().stream()
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
                config, host, List::of));
        await(() -> subscriber.get() != null);

        connected.set(true);
        subscriber.get().accept(new DesktopUiHost.BackendSnapshot(DesktopUiHost.BackendState.RUNNING, null));

        await(() -> nodes(model.document()).stream()
                .anyMatch(node -> "plugins.card.connected-plugin".equals(node.id())));
    }

    @Test
    @DisplayName("隐藏配置由文档级快捷键解锁且既有启用值自动解锁")
    void hiddenConfigurationUsesDocumentShortcutAndStoredState() throws Exception {
        AppDesktopUiModel model = model();
        assertThat(bindingIds(model.document())).noneMatch(id -> id.endsWith("debug.enabled"));

        DesktopUiDocument.KeyboardShortcut shortcut = model.document().shortcuts().get(0);
        dispatch(model, DesktopUiNode.EventType.ACTIVATE,
                shortcut.id(), DesktopUiNode.Value.empty());
        assertThat(bindingIds(model.document())).anyMatch(id -> id.endsWith("debug.enabled"));

        Path config = tempDir.resolve("enabled-debug.yaml");
        Files.writeString(config, "debug.enabled: true\n", StandardCharsets.UTF_8);
        AppDesktopUiModel stored = track(new AppDesktopUiModel(6999, tempDir.resolve("downloads").toString(),
                config,
                new AppDesktopUiHost(6999, new TestDesktopConfigFile(config)), List::of));
        assertThat(bindingIds(stored.document())).anyMatch(id -> id.endsWith("debug.enabled"));
    }

    @Test
    @DisplayName("宿主文档中的全部宿主文本均可由每种可见语言解析")
    void allHostDocumentTokensResolveForEveryVisibleLocale() {
        DesktopUiDocument document = model().document();
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
        DesktopUiDocument document = model.document();
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
        DesktopUiNode.Toggle enabledField = nodes(model.document()).stream()
                .filter(DesktopUiNode.Toggle.class::isInstance).map(DesktopUiNode.Toggle.class::cast)
                .filter(toggle -> toggle.bindingId().endsWith("schema-test.enabled")).findFirst().orElseThrow();
        assertThat(enabledField.enabled()).isTrue();

        DesktopUiNode.Choice preset = nodes(model.document()).stream()
                .filter(DesktopUiNode.Choice.class::isInstance).map(DesktopUiNode.Choice.class::cast)
                .filter(choice -> choice.bindingId().contains(".card.main.preset")).findFirst().orElseThrow();
        awaitChoiceEnabled(model, preset.id());
        dispatch(model, DesktopUiNode.EventType.SELECTION, preset.id(),
                DesktopUiNode.Value.selection(preset.options().get(0).id()));
        DesktopUiNode.Toggle field = nodes(model.document()).stream()
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

        DesktopUiNode.Choice selector = nodes(model.document()).stream()
                .filter(DesktopUiNode.Choice.class::isInstance).map(DesktopUiNode.Choice.class::cast)
                .filter(choice -> "config.section.merged.cards.card.selector".equals(choice.id()))
                .findFirst().orElseThrow();
        List<DesktopUiNode.TextToken> tokens = new ArrayList<>();
        collectTokens(model.document(), tokens);
        assertThat(tokens).extracting(DesktopUiNode.TextToken::key)
                .contains("notice.card-a").doesNotContain("notice.card-b");

        awaitChoiceEnabled(model, selector.id());
        dispatch(model, DesktopUiNode.EventType.SELECTION, selector.id(),
                DesktopUiNode.Value.selection("card-b"));
        tokens.clear();
        collectTokens(model.document(), tokens);
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

        collectTokens(model(List.of(source)).document(), tokens);

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
                new AppDesktopUiHost(6999, new TestDesktopConfigFile(config)), () -> List.of(source)));

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
        assertThat(nodes(model.document()).stream()
                .filter(DesktopUiNode.TextInput.class::isInstance)
                .map(DesktopUiNode.TextInput.class::cast)
                .filter(input -> input.bindingId().endsWith("fixture.secret"))
                .findFirst().orElseThrow().value()).isEmpty();
    }

    private AppDesktopUiModel model() {
        return model(List.of());
    }

    private AppDesktopUiModel model(List<DesktopUiPluginSource> sources) {
        Path config = tempDir.resolve("config.yaml");
        return track(new AppDesktopUiModel(6999, tempDir.resolve("downloads").toString(),
                config,
                new AppDesktopUiHost(6999, new TestDesktopConfigFile(config)), () -> sources));
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
            model.dispatch(new DesktopUiNode.Event(model.revision(), type, nodeId, value));
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
        await(() -> nodes(model.document()).stream()
                .filter(DesktopUiNode.Button.class::isInstance)
                .map(DesktopUiNode.Button.class::cast)
                .anyMatch(button -> id.equals(button.id()) && button.enabled()));
    }

    private static void awaitChoiceEnabled(AppDesktopUiModel model, String id) throws InterruptedException {
        await(() -> nodes(model.document()).stream()
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
