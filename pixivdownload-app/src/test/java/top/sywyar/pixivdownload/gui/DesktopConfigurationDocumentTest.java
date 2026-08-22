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

class DesktopConfigurationDocumentTest extends DesktopUiDocumentTestSupport {
    @Test
    @DisplayName("控制中心设置页从真实配置模型显示未保存、校验和重启状态")
    void controlCenterSettingsProjectsUnsavedValidationAndRestartState() throws Exception {
        Path config = tempDir.resolve("settings.yaml");
        Files.writeString(
                config,
                """
                        app.language: follow-system
                        app.gui-provider: gui-swing
                        app.theme: system
                        app.config-menu-expand-all: false
                        """,
                StandardCharsets.UTF_8
        );
        TestDesktopConfigFile configFile = new TestDesktopConfigFile(config);
        AppDesktopUiModel model = track(new AppDesktopUiModel(
                6999,
                tempDir.resolve("downloads").toString(),
                config,
                new AppDesktopUiHost(6999, configFile),
                List::of,
                rendererContract(DesktopUiExperienceProfile.CONTROL_CENTER)
        ));
        awaitButtonEnabled(model, "config.save");
        assertThat(settingsUnsavedCount(model.snapshot().document())).isEqualTo(0);

        dispatch(
                model,
                DesktopUiNode.EventType.SELECTION,
                "settings.categories",
                DesktopUiNode.Value.selection("download")
        );
        DesktopUiNode.TextInput root = configTextInput(
                model.snapshot().document(),
                "download.root-folder"
        );
        dispatch(
                model,
                DesktopUiNode.EventType.CHANGE,
                root.id(),
                DesktopUiNode.Value.text("\u0000")
        );
        assertThat(settingsUnsavedCount(model.snapshot().document())).isEqualTo(1);
        dispatch(
                model,
                DesktopUiNode.EventType.ACTIVATE,
                "config.save",
                DesktopUiNode.Value.empty()
        );

        await(() -> nodes(model.snapshot().document()).stream().anyMatch(node -> "config.notice".equals(
                node.id())));
        assertThat(settingsUnsavedCount(model.snapshot().document())).isEqualTo(1);

        dispatch(
                model,
                DesktopUiNode.EventType.CHANGE,
                root.id(),
                DesktopUiNode.Value.text("pixiv-download")
        );
        DesktopUiNode.TextInput concurrent = configTextInput(
                model.snapshot().document(),
                "download.max-concurrent"
        );
        dispatch(
                model,
                DesktopUiNode.EventType.CHANGE,
                concurrent.id(),
                DesktopUiNode.Value.text("11")
        );
        assertThat(settingsUnsavedCount(model.snapshot().document())).isEqualTo(1);
        awaitButtonEnabled(model, "config.save");
        dispatch(
                model,
                DesktopUiNode.EventType.ACTIVATE,
                "config.save",
                DesktopUiNode.Value.empty()
        );

        await(() -> model.snapshot().document().dialogs().stream().anyMatch(dialog -> "config.restart".equals(
                dialog.id())));
        assertThat(settingsUnsavedCount(model.snapshot().document())).isEqualTo(0);
        assertThat(read(configFile, "download.max-concurrent")).isEqualTo("11");
    }

    @Test
    @DisplayName("界面偏好只由配置页统一保存入口持久化")
    void interfacePreferencesUseTheUnifiedConfigurationSave() throws Exception {
        Path config = tempDir.resolve("interface.yaml");
        TestDesktopConfigFile configFile = new TestDesktopConfigFile(config);
        AppDesktopUiModel model = track(new AppDesktopUiModel(
                6999,
                tempDir.resolve("downloads").toString(),
                config,
                new AppDesktopUiHost(6999, configFile),
                List::of,
                rendererContract(DesktopUiExperienceProfile.CLASSIC)
        ));
        await(() -> nodes(model.snapshot().document()).stream().filter(DesktopUiNode.Button.class::isInstance).map(
                DesktopUiNode.Button.class::cast).anyMatch(button -> "config.save".equals(button.id()) && button.enabled()));

        assertThat(nodes(model.snapshot().document())).extracting(DesktopUiNode::id).doesNotContain(
                "interface.save");
        dispatch(
                model,
                DesktopUiNode.EventType.CHANGE,
                "interface.config-menu-expand-all.input",
                DesktopUiNode.Value.bool(true)
        );
        dispatch(
                model,
                DesktopUiNode.EventType.ACTIVATE,
                "config.save",
                DesktopUiNode.Value.empty()
        );

        await(() -> "true".equals(read(configFile, "app.config-menu-expand-all")));
        assertThat(configFile.readAll(List.of(
                "app.language",
                "app.gui-provider",
                "app.theme",
                "app.config-menu-expand-all"
        ))).containsEntry("app.language", "follow-system").containsEntry(
                "app.gui-provider",
                "gui-swing"
        ).containsEntry("app.theme", "system").containsEntry(
                "app.config-menu-expand-all",
                "true"
        );
    }

    @Test
    @DisplayName("配置重置先由声明式对话框确认再修改字段")
    void configurationResetRequiresDeclarativeConfirmation() throws Exception {
        AppDesktopUiModel model = model();
        awaitButtonEnabled(model, "config.reset");
        DesktopUiNode.TextInput root = configTextInput(
                model.snapshot().document(),
                "download.root-folder"
        );

        dispatch(
                model,
                DesktopUiNode.EventType.CHANGE,
                root.id(),
                DesktopUiNode.Value.text("changed-root")
        );
        dispatch(
                model,
                DesktopUiNode.EventType.ACTIVATE,
                "config.reset",
                DesktopUiNode.Value.empty()
        );

        assertThat(model.snapshot().document().dialogs()).extracting(DesktopUiDocument.Dialog::id).containsExactly(
                "config.reset.dialog");
        assertThat(configTextInput(
                model.snapshot().document(),
                "download.root-folder"
        ).value()).isEqualTo("changed-root");

        dispatch(
                model,
                DesktopUiNode.EventType.ACTIVATE,
                "config.reset.confirm",
                DesktopUiNode.Value.empty()
        );

        assertThat(model.snapshot().document().dialogs()).isEmpty();
        assertThat(configTextInput(
                model.snapshot().document(),
                "download.root-folder"
        ).value()).isEqualTo(root.value());
    }

    @Test
    @DisplayName("相对下载根目录经声明式确认固定旧记录后再保存")
    void symbolicDownloadRootIsPinnedBeforeConfigurationSave() throws Exception {
        Path config = tempDir.resolve("symbolic-root.yaml");
        Files.writeString(
                config,
                "download.root-folder: relative-download\n",
                StandardCharsets.UTF_8
        );
        TestDesktopConfigFile configFile = new TestDesktopConfigFile(config);
        AppDesktopUiHost delegate = new AppDesktopUiHost(6999, configFile);
        AtomicBoolean simulateSymbolicReference = new AtomicBoolean();
        AtomicBoolean pinned = new AtomicBoolean();
        DesktopUiHost host = (DesktopUiHost) Proxy.newProxyInstance(
                DesktopUiHost.class.getClassLoader(),
                new Class<?>[]{DesktopUiHost.class},
                (proxy, method, arguments) -> {
                    if ("guiGet".equals(method.getName()) && simulateSymbolicReference.get() && "path-prefixes".equals(
                            arguments[0])) {
                        return response(Map.of(
                                "symbolicReferenced",
                                true,
                                "prefixes",
                                List.of(Map.of(
                                        "path",
                                        Path.of("relative-download").toAbsolutePath().normalize().toString(),
                                        "symbolic",
                                        true
                                ))
                        ));
                    }
                    if ("guiPostJson".equals(method.getName()) && "path-prefixes/pin".equals(
                            arguments[0])) {
                        pinned.set(true);
                        return response(Map.of("success", true));
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
                List::of,
                rendererContract(DesktopUiExperienceProfile.CLASSIC)
        ));
        await(() -> nodes(model.snapshot().document()).stream().filter(DesktopUiNode.Button.class::isInstance).map(
                DesktopUiNode.Button.class::cast).anyMatch(button -> "config.save".equals(button.id()) && button.enabled()));
        DesktopUiNode.TextInput root = configTextInput(
                model.snapshot().document(),
                "download.root-folder"
        );
        String replacement = tempDir.resolve("new-download").toString();

        dispatch(
                model,
                DesktopUiNode.EventType.CHANGE,
                root.id(),
                DesktopUiNode.Value.text(replacement)
        );
        simulateSymbolicReference.set(true);
        awaitButtonEnabled(model, "config.save");
        dispatch(
                model,
                DesktopUiNode.EventType.ACTIVATE,
                "config.save",
                DesktopUiNode.Value.empty()
        );

        await(() -> model.snapshot().document().dialogs().stream().anyMatch(dialog -> "config.symbolic-pin".equals(
                dialog.id())));
        assertThat(configFile.read("download.root-folder")).isEqualTo("relative-download");

        awaitButtonEnabled(model, "config.symbolic-pin.confirm");
        dispatch(
                model,
                DesktopUiNode.EventType.ACTIVATE,
                "config.symbolic-pin.confirm",
                DesktopUiNode.Value.empty()
        );

        await(() -> pinned.get() && replacement.equals(read(
                configFile,
                "download.root-folder"
        )));
    }

    @Test
    @DisplayName("隐藏配置由文档级快捷键解锁且既有启用值自动解锁")
    void hiddenConfigurationUsesDocumentShortcutAndStoredState() throws Exception {
        AppDesktopUiModel model = model();
        assertThat(bindingIds(model.snapshot().document())).noneMatch(id -> id.endsWith(
                "debug.enabled"));

        DesktopUiDocument.KeyboardShortcut shortcut = model.snapshot().document().shortcuts().get(0);
        dispatch(
                model,
                DesktopUiNode.EventType.ACTIVATE,
                shortcut.id(),
                DesktopUiNode.Value.empty()
        );
        assertThat(bindingIds(model.snapshot().document())).anyMatch(id -> id.endsWith(
                "debug.enabled"));

        Path config = tempDir.resolve("enabled-debug.yaml");
        Files.writeString(config, "debug.enabled: true\n", StandardCharsets.UTF_8);
        AppDesktopUiModel stored = track(new AppDesktopUiModel(
                6999,
                tempDir.resolve("downloads").toString(),
                config,
                new AppDesktopUiHost(6999, new TestDesktopConfigFile(config)),
                List::of,
                rendererContract(DesktopUiExperienceProfile.CLASSIC)
        ));
        assertThat(bindingIds(stored.snapshot().document())).anyMatch(id -> id.endsWith(
                "debug.enabled"));
    }

    @Test
    @DisplayName("宿主文档中的全部宿主文本均可由每种可见语言解析")
    void allHostDocumentTokensResolveForEveryVisibleLocale() {
        DesktopUiDocument document = model().snapshot().document();
        List<DesktopUiNode.TextToken> tokens = new ArrayList<>();
        collectTokens(document, tokens);

        for (Locale locale : List.of(
                Locale.SIMPLIFIED_CHINESE,
                Locale.US,
                Locale.JAPAN,
                Locale.KOREA,
                Locale.TRADITIONAL_CHINESE
        )) {
            assertThat(tokens.stream().filter(token -> token.namespace() == null && !token.key().isBlank())).allSatisfy(
                    token -> assertThat(MessageBundles.get(
                            locale,
                            token.key(),
                            token.arguments().toArray()
                    )).isNotBlank().isNotEqualTo(token.key()));
        }
    }

    @Test
    @DisplayName("宿主 Schema 使用的全部静态文案键在每种可见语言中存在")
    void everyStaticSchemaKeyExistsInEveryVisibleLocale() throws Exception {
        String source = Files.readString(
                Path.of("src/main/java/top/sywyar/pixivdownload/gui/AppDesktopUiModel.java"),
                StandardCharsets.UTF_8
        );
        Matcher matcher = Pattern.compile("\"((?:gui|desktop\\.ui)\\.[A-Za-z0-9_.-]+)\"").matcher(
                source);
        Set<String> keys = new LinkedHashSet<>();
        Set<String> dynamicPrefixes = new LinkedHashSet<>();
        while (matcher.find()) {
            String key = matcher.group(1);
            if (key.endsWith(".")) dynamicPrefixes.add(key);
            else keys.add(key);
        }

        for (String name : List.of(
                "messages.properties",
                "messages_en.properties",
                "messages_ja.properties",
                "messages_ko.properties",
                "messages_zh-Hant.properties"
        )) {
            Properties bundle = new Properties();
            try (var reader = Files.newBufferedReader(
                    Path.of("src/main/resources/i18n", name),
                    StandardCharsets.UTF_8
            )) {
                bundle.load(reader);
            }
            assertThat(keys).as(name).allMatch(bundle::containsKey);
            assertThat(dynamicPrefixes).as(name + " dynamic families").allMatch(prefix -> bundle.stringPropertyNames().stream().anyMatch(
                    key -> key.startsWith(prefix)));
        }
    }

    @Test
    @DisplayName("插件丰富配置贡献由宿主转换为通用节点树")
    void richPluginConfigurationBecomesGenericDocumentNodes() throws Exception {
        PixivFeaturePlugin plugin = richConfigPlugin();
        DesktopUiPluginSource source = new DesktopUiPluginSource(
                "schema-test",
                false,
                plugin,
                plugin.getClass().getClassLoader()
        );
        AppDesktopUiModel model = model(List.of(source));
        DesktopUiDocument document = model.snapshot().document();
        List<String> ids = new ArrayList<>();
        List<DesktopUiNode.TextToken> tokens = new ArrayList<>();
        document.pages().forEach(page -> {
            collectIds(page.content(), ids);
            collectTokens(page.content(), tokens);
            page.floatingAction().ifPresent(node -> {
                collectIds(node, ids);
                collectTokens(node, tokens);
            });
        });

        assertThat(ids).contains(
                "config.section.schema-test.section.card.selector",
                "config.schema-test.schema-test.enabled.input",
                "config.category.plugins.settings.tabs",
                "config.section.schema-test.section.card.main.preset.form"
        );
        assertThat(tokens).extracting(DesktopUiNode.TextToken::key).contains(
                "section.title",
                "preset.label",
                "preset.global.label",
                "action.label",
                "action.global.label"
        );
        assertThat(nodes(document).stream().filter(DesktopUiNode.Choice.class::isInstance)).hasSizeGreaterThanOrEqualTo(
                2);

        DesktopUiNode.Choice mode = nodes(document).stream().filter(DesktopUiNode.Choice.class::isInstance).map(
                DesktopUiNode.Choice.class::cast).filter(choice -> choice.bindingId().endsWith(
                "schema-test.mode")).findFirst().orElseThrow();
        awaitChoiceEnabled(model, mode.id());
        dispatch(
                model,
                DesktopUiNode.EventType.SELECTION,
                mode.id(),
                DesktopUiNode.Value.selection(mode.options().get(1).id())
        );
        DesktopUiNode.Toggle enabledField = nodes(model.snapshot().document()).stream().filter(
                DesktopUiNode.Toggle.class::isInstance).map(DesktopUiNode.Toggle.class::cast).filter(
                toggle -> toggle.bindingId().endsWith("schema-test.enabled")).findFirst().orElseThrow();
        assertThat(enabledField.enabled()).isTrue();

        DesktopUiNode.Choice preset = nodes(model.snapshot().document()).stream().filter(
                DesktopUiNode.Choice.class::isInstance).map(DesktopUiNode.Choice.class::cast).filter(
                choice -> choice.bindingId().contains(".card.main.preset")).findFirst().orElseThrow();
        awaitChoiceEnabled(model, preset.id());
        dispatch(
                model,
                DesktopUiNode.EventType.SELECTION,
                preset.id(),
                DesktopUiNode.Value.selection(preset.options().get(0).id())
        );
        DesktopUiNode.Toggle field = nodes(model.snapshot().document()).stream().filter(
                DesktopUiNode.Toggle.class::isInstance).map(DesktopUiNode.Toggle.class::cast).filter(
                toggle -> toggle.bindingId().endsWith("schema-test.enabled")).findFirst().orElseThrow();
        assertThat(field.selected()).isTrue();
        assertThat(field.enabled()).isFalse();
    }

    @Test
    @DisplayName("合并的卡片 section 只显示当前卡片来源的提示")
    void mergedCardSectionShowsOnlyTheSelectedCardsNotices() throws Exception {
        PixivFeaturePlugin first = mergeableCardPlugin(
                "card-a-plugin",
                "card-a",
                "notice.card-a"
        );
        PixivFeaturePlugin second = mergeableCardPlugin(
                "card-b-plugin",
                "card-b",
                "notice.card-b"
        );
        AppDesktopUiModel model = model(List.of(
                new DesktopUiPluginSource(
                        first.id(),
                        false,
                        first,
                        first.getClass().getClassLoader()
                ),
                new DesktopUiPluginSource(
                        second.id(),
                        false,
                        second,
                        second.getClass().getClassLoader()
                )
        ));

        DesktopUiNode.Choice selector = nodes(model.snapshot().document()).stream().filter(
                DesktopUiNode.Choice.class::isInstance).map(DesktopUiNode.Choice.class::cast).filter(
                choice -> "config.section.merged.cards.card.selector".equals(choice.id())).findFirst().orElseThrow();
        List<DesktopUiNode.TextToken> tokens = new ArrayList<>();
        collectTokens(model.snapshot().document(), tokens);
        assertThat(tokens).extracting(DesktopUiNode.TextToken::key).contains("notice.card-a").doesNotContain(
                "notice.card-b");

        awaitChoiceEnabled(model, selector.id());
        dispatch(
                model,
                DesktopUiNode.EventType.SELECTION,
                selector.id(),
                DesktopUiNode.Value.selection("card-b")
        );
        tokens.clear();
        collectTokens(model.snapshot().document(), tokens);
        assertThat(tokens).extracting(DesktopUiNode.TextToken::key).contains("notice.card-b").doesNotContain(
                "notice.card-a");
    }

    @Test
    @DisplayName("插件未声明配置分组标题时使用其本地化名称")
    void pluginNameLabelsAnImplicitConfigurationGroup() {
        PixivFeaturePlugin plugin = new PixivFeaturePlugin() {
            @Override
            public String id() {
                return "implicit-group";
            }

            @Override
            public String displayName() {
                return "plugin.name";
            }

            @Override
            public String description() {
                return "plugin.summary";
            }

            @Override
            public String displayNamespace() {
                return "implicit-group";
            }

            @Override
            public PluginKind kind() {
                return PluginKind.FEATURE;
            }

            @Override
            public List<GuiConfigContribution> guiConfigContributions() {
                return List.of(new GuiConfigContribution(
                        List.of(),
                        List.of(new GuiConfigFieldContribution(
                                "implicit.value",
                                "implicit-settings",
                                "field.label",
                                GuiConfigFieldType.STRING,
                                "",
                                0
                        ))
                ));
            }
        };
        DesktopUiPluginSource source = new DesktopUiPluginSource(
                plugin.id(),
                false,
                plugin,
                plugin.getClass().getClassLoader()
        );
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
        Files.writeString(
                config,
                "fixture.value: legacy-value\nfixture.secret: legacy-secret\n",
                StandardCharsets.UTF_8
        );
        PixivFeaturePlugin plugin = pluginWithMigratedFields();
        DesktopUiPluginSource source = new DesktopUiPluginSource(
                "fixture",
                false,
                plugin,
                plugin.getClass().getClassLoader()
        );

        AppDesktopUiModel model = track(new AppDesktopUiModel(
                6999,
                tempDir.resolve("downloads").toString(),
                config,
                new AppDesktopUiHost(6999, new TestDesktopConfigFile(config)),
                () -> List.of(source),
                rendererContract(DesktopUiExperienceProfile.CLASSIC)
        ));

        Properties properties = new Properties();
        try (var reader = Files.newBufferedReader(
                RuntimeFiles.resolvePluginConfigPath(
                        "fixture",
                        "properties"
                ),
                StandardCharsets.UTF_8
        )) {
            properties.load(reader);
        }
        assertThat(properties).containsEntry(
                "fixture.value",
                "legacy-value"
        ).doesNotContainKey(
                "fixture.secret");
        assertThat(new PluginCredentialStore().readAll("fixture")).containsEntry(
                "fixture.secret",
                "legacy-secret"
        );
        assertThat(Files.readString(config, StandardCharsets.UTF_8)).doesNotContain(
                "fixture.value",
                "fixture.secret",
                "legacy-secret"
        );
        assertThat(nodes(model.snapshot().document()).stream().filter(DesktopUiNode.TextInput.class::isInstance).map(
                DesktopUiNode.TextInput.class::cast).filter(input -> input.bindingId().endsWith(
                "fixture.secret")).findFirst().orElseThrow().value()).isEmpty();
    }

}
