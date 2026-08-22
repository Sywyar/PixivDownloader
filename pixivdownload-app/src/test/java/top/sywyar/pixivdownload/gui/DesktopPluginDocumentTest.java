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
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiIcon;
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

class DesktopPluginDocumentTest extends DesktopUiDocumentTestSupport {
    @Test
    @DisplayName("活动插件页面遵守只读动作边界并随来源撤回")
    void pluginDesktopPagesJoinAndLeaveTheHostDocument() throws Exception {
        PixivFeaturePlugin plugin = new PixivFeaturePlugin() {
            @Override
            public String id() {
                return "page-fixture";
            }

            @Override
            public String displayName() {
                return "plugin.name";
            }

            @Override
            public String description() {
                return "plugin.description";
            }

            @Override
            public PluginKind kind() {
                return PluginKind.FEATURE;
            }

            @Override
            public List<WebRouteContribution> routes() {
                return List.of(new WebRouteContribution(
                        "/api/gui/page-fixture/run",
                        AccessPolicy.GUI,
                        Set.of(HttpMethod.POST),
                        false
                ));
            }

            @Override
            public List<DesktopUiPageContribution> desktopPages() {
                DesktopUiNode.Text first = new DesktopUiNode.Text(
                        "page-fixture.first.content",
                        DesktopUiNode.TextToken.raw("First"),
                        DesktopUiNode.TextStyle.BODY,
                        true,
                        false
                );
                DesktopUiNode.Button run = new DesktopUiNode.Button(
                        "page-fixture.second.run",
                        "page-fixture.second.run.action",
                        DesktopUiNode.TextToken.raw("Run"),
                        null,
                        DesktopUiNode.ButtonStyle.NORMAL,
                        true
                );
                DesktopUiDocument.Dialog dialog = new DesktopUiDocument.Dialog(
                        "page-fixture.second.dialog",
                        DesktopUiNode.TextToken.raw("Dialog"),
                        DesktopUiDocument.DialogStyle.INFO,
                        new DesktopUiNode.Text(
                                "page-fixture.second.dialog.content",
                                DesktopUiNode.TextToken.raw("Open"),
                                DesktopUiNode.TextStyle.BODY,
                                true,
                                false
                        ),
                        "page-fixture.second.dialog.dismiss",
                        true,
                        0,
                        0
                );
                DesktopUiNode.Button invalid = new DesktopUiNode.Button(
                        "page-fixture.invalid.run",
                        "page-fixture.invalid.run.action",
                        DesktopUiNode.TextToken.raw("Invalid"),
                        null,
                        DesktopUiNode.ButtonStyle.NORMAL,
                        true
                );
                DesktopUiNode.TextInput readOnly = new DesktopUiNode.TextInput(
                        "page-fixture.readonly.input",
                        "page-fixture.readonly.value",
                        DesktopUiNode.TextToken.raw("Read only"),
                        null,
                        DesktopUiNode.InputKind.TEXT,
                        "value",
                        20,
                        1,
                        false
                );
                DesktopUiNode.TextInput enabled = new DesktopUiNode.TextInput(
                        "page-fixture.enabled.input",
                        "page-fixture.enabled.value",
                        DesktopUiNode.TextToken.raw("Enabled"),
                        null,
                        DesktopUiNode.InputKind.TEXT,
                        "value",
                        20,
                        1,
                        true
                );
                DesktopUiNode.Image svg = new DesktopUiNode.Image(
                        "page-fixture.svg.image",
                        new DesktopUiNode.ImageData("image/svg+xml; charset=utf-8", "PHN2Zy8+"),
                        DesktopUiNode.TextToken.raw("SVG"),
                        16,
                        16,
                        DesktopUiNode.ScaleMode.FIT
                );
                DesktopUiNode.Button external = new DesktopUiNode.Button(
                        "page-fixture.external.run",
                        "page-fixture.external.run.action",
                        DesktopUiNode.TextToken.raw("External"),
                        null,
                        DesktopUiNode.ButtonStyle.NORMAL,
                        true
                );
                return List.of(
                        new DesktopUiPageContribution(
                                "page-fixture.second",
                                20,
                                DesktopUiNode.TextToken.raw("Second"),
                                run,
                                Map.of(
                                        "page-fixture.second.run.action",
                                        "page-fixture/run",
                                        "page-fixture.second.dialog.dismiss",
                                        "page-fixture/run"
                                ),
                                List.of(dialog)
                        ),
                        new DesktopUiPageContribution(
                                "page-fixture.first",
                                10,
                                DesktopUiNode.TextToken.raw("First"),
                                first
                        ),
                        new DesktopUiPageContribution(
                                "page-fixture.readonly",
                                15,
                                DesktopUiNode.TextToken.raw("Read only"),
                                readOnly
                        ),
                        new DesktopUiPageContribution(
                                "page-fixture.invalid",
                                30,
                                DesktopUiNode.TextToken.raw("Invalid"),
                                invalid,
                                Map.of("page-fixture.invalid.run.action", "page-fixture/missing"),
                                List.of()
                        ),
                        new DesktopUiPageContribution(
                                "page-fixture.enabled",
                                40,
                                DesktopUiNode.TextToken.raw("Enabled"),
                                enabled
                        ),
                        new DesktopUiPageContribution(
                                "page-fixture.svg",
                                40,
                                DesktopUiNode.TextToken.raw("SVG"),
                                svg
                        ),
                        new DesktopUiPageContribution(
                                "page-fixture.external",
                                50,
                                DesktopUiNode.TextToken.raw("External"),
                                external,
                                Map.of(
                                        "page-fixture.external.run.action",
                                        "https://example.invalid/api"
                                ),
                                List.of()
                        )
                );
            }
        };
        DesktopUiPluginSource source = new DesktopUiPluginSource(
                plugin.id(),
                false,
                plugin,
                plugin.getClass().getClassLoader()
        );
        AtomicReference<List<DesktopUiPluginSource>> sources = new AtomicReference<>(List.of(source));
        AtomicReference<String> actionPath = new AtomicReference<>();
        AtomicReference<Object> actionBody = new AtomicReference<>();
        AtomicReference<String> actionOwner = new AtomicReference<>();
        Path config = tempDir.resolve("plugin-page.yaml");
        AppDesktopUiHost delegate = new AppDesktopUiHost(
                6999,
                new TestDesktopConfigFile(config)
        );
        DesktopUiHost host = (DesktopUiHost) Proxy.newProxyInstance(
                DesktopUiHost.class.getClassLoader(),
                new Class<?>[]{DesktopUiHost.class},
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
                }
        );
        AppDesktopUiModel model = track(new AppDesktopUiModel(
                6999,
                tempDir.resolve("downloads").toString(),
                config,
                host,
                sources::get,
                rendererContract(DesktopUiExperienceProfile.CLASSIC)
        ));

        assertThat(model.snapshot().document().pages()).extracting(DesktopUiDocument.Page::id).endsWith(
                "page-fixture.first",
                "page-fixture.readonly",
                "page-fixture.second"
        ).doesNotContain(
                "page-fixture.invalid",
                "page-fixture.enabled",
                "page-fixture.svg",
                "page-fixture.external"
        );
        assertThat(model.snapshot().document().dialogs()).extracting(DesktopUiDocument.Dialog::id).contains(
                "page-fixture.second.dialog");
        awaitButtonEnabled(model, "page-fixture.second.run");
        await(() -> {
            if (actionBody.get() == null) {
                dispatch(
                        model,
                        DesktopUiNode.EventType.ACTIVATE,
                        "page-fixture.second.run",
                        DesktopUiNode.Value.empty()
                );
            }
            return actionBody.get() != null;
        });
        assertThat(actionPath).hasValue("page-fixture/run");
        assertThat(actionBody).hasValue(Map.of());
        assertThat(actionOwner).hasValue("page-fixture");

        long activeRevision = model.snapshot().revision();
        sources.set(List.of());
        dispatch(
                model,
                DesktopUiNode.EventType.ACTIVATE,
                "debug.unlock.shortcut",
                DesktopUiNode.Value.empty()
        );

        assertThat(model.snapshot().revision()).isGreaterThan(activeRevision);
        assertThat(model.snapshot().document().pages()).extracting(DesktopUiDocument.Page::id).doesNotContain(
                "page-fixture.first",
                "page-fixture.second"
        );
        assertThat(model.snapshot().document().dialogs()).extracting(DesktopUiDocument.Dialog::id).doesNotContain(
                "page-fixture.second.dialog");
    }

    @Test
    @DisplayName("宿主页面能力不兼容时拒绝首次快照")
    void incompatibleCoreDocumentFailsBeforePublication() {
        Path config = tempDir.resolve("incompatible-core.yaml");
        AppDesktopUiModel.RendererContract limited = new AppDesktopUiModel.RendererContract(
                "limited-provider",
                DesktopUiExperienceProfile.CLASSIC,
                Set.of(DesktopUiNode.Kind.TEXT),
                Set.of()
        );

        assertThatThrownBy(() -> new AppDesktopUiModel(
                6999,
                tempDir.resolve("downloads").toString(),
                config,
                new AppDesktopUiHost(6999, new TestDesktopConfigFile(config)),
                List::of,
                limited
        )).isInstanceOf(IllegalStateException.class).hasMessageContaining("limited-provider");
    }

    @Test
    @DisplayName("插件页面与对话框按当前 owner publication 隔离能力缺口")
    void incompatiblePluginTreesAreIsolatedAndWithdrawnByPublication() {
        PixivFeaturePlugin plugin = new PixivFeaturePlugin() {
            @Override
            public String id() {
                return "compat-fixture";
            }

            @Override
            public String displayName() {
                return "plugin.name";
            }

            @Override
            public String description() {
                return "plugin.description";
            }

            @Override
            public PluginKind kind() {
                return PluginKind.FEATURE;
            }

            @Override
            public List<DesktopUiPageContribution> desktopPages() {
                DesktopUiNode.Tree pageTree = tree("compat-fixture.tree.content");
                DesktopUiDocument.Dialog dialog = new DesktopUiDocument.Dialog(
                        "compat-fixture.readable.dialog",
                        DesktopUiNode.TextToken.raw("Dialog"),
                        DesktopUiDocument.DialogStyle.INFO,
                        tree("compat-fixture.readable.dialog.content"),
                        "compat-fixture.readable.dialog.dismiss",
                        false,
                        0,
                        0
                );
                return List.of(
                        new DesktopUiPageContribution(
                                "compat-fixture.readable",
                                10,
                                DesktopUiNode.TextToken.raw("Readable"),
                                new DesktopUiNode.Text(
                                        "compat-fixture.readable.content",
                                        DesktopUiNode.TextToken.raw("Content"),
                                        DesktopUiNode.TextStyle.BODY,
                                        true,
                                        false
                                ),
                                Map.of(),
                                List.of(dialog)
                        ),
                        new DesktopUiPageContribution(
                                "compat-fixture.tree",
                                20,
                                DesktopUiNode.TextToken.raw("Tree"),
                                pageTree
                        )
                );
            }

            private DesktopUiNode.Tree tree(String id) {
                return new DesktopUiNode.Tree(
                        id,
                        id + ".value",
                        List.of(new DesktopUiNode.TreeItem(
                                id + ".item",
                                DesktopUiNode.TextToken.raw("Item"),
                                List.of()
                        )),
                        DesktopUiNode.SelectionMode.SINGLE,
                        List.of(),
                        false
                );
            }
        };
        AtomicReference<List<DesktopUiPluginSource>> sources = new AtomicReference<>(List.of(new DesktopUiPluginSource(
                plugin.id(),
                false,
                plugin,
                plugin.getClass().getClassLoader(),
                "compat-fixture",
                1L
        )));
        Set<DesktopUiCapability> capabilities = new LinkedHashSet<>(Set.of(DesktopUiCapability.values()));
        capabilities.remove(DesktopUiCapability.TREE_EXPAND_COLLAPSE);
        AppDesktopUiModel.RendererContract limited = new AppDesktopUiModel.RendererContract(
                "limited-provider",
                DesktopUiExperienceProfile.CLASSIC,
                Set.of(DesktopUiNode.Kind.values()),
                capabilities
        );
        Path config = tempDir.resolve("incompatible-plugin.yaml");
        AppDesktopUiModel model = track(new AppDesktopUiModel(
                6999,
                tempDir.resolve("downloads").toString(),
                config,
                new AppDesktopUiHost(6999, new TestDesktopConfigFile(config)),
                sources::get,
                limited
        ));

        DesktopUiDocument.Page readable = model.snapshot().document().pages().stream().filter(page -> page.id().equals(
                "compat-fixture.readable")).findFirst().orElseThrow();
        assertThat(readable.content()).isInstanceOf(DesktopUiNode.Text.class);
        DesktopUiDocument.Page isolated = model.snapshot().document().pages().stream().filter(page -> page.id().equals(
                "compat-fixture.tree")).findFirst().orElseThrow();
        List<DesktopUiNode> isolatedNodes = new ArrayList<>();
        collectNodes(isolated.content(), isolatedNodes);
        assertThat(isolatedNodes).noneMatch(DesktopUiNode.Tree.class::isInstance);
        assertThat(isolatedNodes.stream().filter(DesktopUiNode.Text.class::isInstance).map(
                DesktopUiNode.Text.class::cast).map(text -> text.text().key()).toList()).contains(
                "desktop.ui.compatibility.page-unavailable");
        assertThat(model.snapshot().document().dialogs()).extracting(DesktopUiDocument.Dialog::id).doesNotContain(
                "compat-fixture.readable.dialog");
        DesktopUiDocument.Dialog notice = model.snapshot().document().dialogs().stream().filter(
                dialog -> dialog.id().startsWith("desktop.compatibility.compat-fixture.")).findFirst().orElseThrow();
        List<DesktopUiNode.TextToken> noticeTokens = new ArrayList<>();
        collectTokens(notice.content(), noticeTokens);
        assertThat(noticeTokens).extracting(DesktopUiNode.TextToken::key).contains(
                "desktop.ui.compatibility.dialog-unavailable");
        assertThat(model.snapshot().document().requiredCapabilities()).doesNotContain(
                DesktopUiCapability.TREE_EXPAND_COLLAPSE);

        dispatch(
                model,
                DesktopUiNode.EventType.ACTIVATE,
                notice.id(),
                DesktopUiNode.Value.empty()
        );
        assertThat(model.snapshot().document().dialogs()).extracting(DesktopUiDocument.Dialog::id).noneMatch(
                id -> id.startsWith("desktop.compatibility.compat-fixture."));

        sources.set(List.of(new DesktopUiPluginSource(
                plugin.id(),
                false,
                plugin,
                plugin.getClass().getClassLoader(),
                "compat-fixture",
                2L
        )));
        dispatch(
                model,
                DesktopUiNode.EventType.ACTIVATE,
                "debug.unlock.shortcut",
                DesktopUiNode.Value.empty()
        );
        DesktopUiDocument.Dialog replacementNotice = model.snapshot().document().dialogs().stream().filter(
                dialog -> dialog.id().startsWith("desktop.compatibility.compat-fixture.2.")).findFirst().orElseThrow();
        assertThat(model.snapshot().document().dialogs()).extracting(DesktopUiDocument.Dialog::id).anyMatch(
                id -> id.startsWith("desktop.compatibility.compat-fixture.2."));

        sources.set(List.of());
        dispatch(
                model,
                DesktopUiNode.EventType.ACTIVATE,
                replacementNotice.id(),
                DesktopUiNode.Value.empty()
        );
        assertThat(model.snapshot().document().pages()).extracting(DesktopUiDocument.Page::id).doesNotContain(
                "compat-fixture.readable",
                "compat-fixture.tree"
        );
        assertThat(model.snapshot().document().dialogs()).extracting(DesktopUiDocument.Dialog::id).noneMatch(
                id -> id.startsWith("desktop.compatibility.compat-fixture."));
    }

    @Test
    @DisplayName("主题选项只包含当前桌面提供者支持的专属主题")
    void themeOptionsFollowTheSelectedDesktopProvider() {
        ThemeProviderPlugin swing = new ThemeProviderPlugin(
                "gui-swing",
                List.of(new GuiThemeContribution(
                        "moonlight",
                        locale -> "Moonlight",
                        GuiThemeAppearance.DARK,
                        () -> {
        }
                ))
        );
        ThemeProviderPlugin compose = new ThemeProviderPlugin("gui-compose", List.of());
        AppDesktopUiModel model = model(List.of(source(swing), source(compose)));

        DesktopUiNode.Choice themes = choice(
                model.snapshot().document(),
                "interface.theme.input"
        );
        assertThat(themes.options()).extracting(DesktopUiNode.Option::id).containsExactly(
                "system",
                "light",
                "dark",
                "moonlight"
        );

        dispatch(
                model,
                DesktopUiNode.EventType.SELECTION,
                "interface.provider.input",
                DesktopUiNode.Value.selection("gui-compose")
        );
        themes = choice(model.snapshot().document(), "interface.theme.input");
        assertThat(themes.options()).extracting(DesktopUiNode.Option::id).containsExactly(
                "system",
                "light",
                "dark"
        );
        assertThat(themes.selectedIds()).containsExactly("system");
    }

}
