package top.sywyar.pixivdownload.gui.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.sywyar.pixivdownload.gui.panel.ConfigPanel;
import top.sywyar.pixivdownload.plugin.PluginToggleProperties;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigCondition;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigContribution;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigFieldContribution;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigFieldType;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigGroupContribution;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigGroups;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginKind;
import top.sywyar.pixivdownload.plugin.api.web.I18nContribution;
import top.sywyar.pixivdownload.plugin.registry.PluginRegistry;
import top.sywyar.pixivdownload.plugin.registry.PluginSource;
import top.sywyar.pixivdownload.plugin.runtime.discovery.PluginDiscoveryResult;
import top.sywyar.pixivdownload.plugin.runtime.discovery.PluginLoadFailure;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("GUI 配置字段 contribution 聚合")
class GuiConfigContributionAggregatorTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("默认无插件贡献时字段与分组保持核心清单")
    void noPluginContributionsKeepCoreFieldsUnchanged() {
        ConfigFieldSnapshot snapshot = ConfigFieldRegistry.snapshot(
                GuiConfigContributionAggregator.from(new PluginRegistry(List.of())));

        assertThat(snapshot.groups()).containsExactlyElementsOf(ConfigFieldRegistry.groups());
        assertThat(snapshot.fields()).extracting(ConfigFieldSpec::key)
                .containsExactlyElementsOf(ConfigFieldRegistry.allFields().stream()
                        .map(ConfigFieldSpec::key)
                        .toList());
        assertThat(snapshot.diagnostics()).isEmpty();
    }

    @Test
    @DisplayName("插件启用后贡献字段进入配置页快照")
    void enabledPluginFieldsAppearInSnapshot() {
        PixivFeaturePlugin plugin = plugin("fixture", () -> List.of(new GuiConfigContribution(
                List.of(new GuiConfigGroupContribution("fixture-group", "fixture.group.label", 50)),
                List.of(
                        field("fixture.mode", "fixture-group", "fixture.mode.label", GuiConfigFieldType.STRING),
                        new GuiConfigFieldContribution(
                                "fixture.secret",
                                GuiConfigGroups.NOTIFICATION,
                                "fixture.secret.label",
                                "fixture.secret.help",
                                null,
                                GuiConfigFieldType.STRING,
                                "",
                                20,
                                true,
                                false,
                                List.of(),
                                List.of(GuiConfigCondition.isTrue("fixture.enabled")),
                                List.of(),
                                null,
                                null)))));

        ConfigFieldSnapshot snapshot = ConfigFieldRegistry.snapshot(
                GuiConfigContributionAggregator.from(new PluginRegistry(List.of(plugin))));
        ConfigFieldSpec mode = field(snapshot, "fixture.mode");
        ConfigFieldSpec secret = field(snapshot, "fixture.secret");

        assertThat(snapshot.groups()).contains("fixture.group.label");
        assertThat(mode.label()).isEqualTo("fixture.mode.label");
        assertThat(mode.group()).isEqualTo("fixture.group.label");
        assertThat(secret.type()).isEqualTo(FieldType.PASSWORD);
        assertThat(secret.requiresRestart()).isFalse();
        assertThat(secret.enabledWhen().test(new ConfigSnapshot(Map.of("fixture.enabled", "true")))).isTrue();
        assertThat(secret.enabledWhen().test(new ConfigSnapshot(Map.of("fixture.enabled", "false")))).isFalse();
        assertThat(snapshot.diagnostics()).isEmpty();
    }

    @Test
    @DisplayName("禁用、未安装、损坏和抛异常的插件不暴露字段")
    void inactiveOrBrokenPluginsDoNotExposeFields() {
        PixivFeaturePlugin plugin = plugin("fixture", () -> List.of(new GuiConfigContribution(
                List.of(field("fixture.visible", GuiConfigGroups.PLUGINS,
                        "fixture.visible.label", GuiConfigFieldType.BOOL)))));

        ConfigFieldSnapshot disabled = ConfigFieldRegistry.snapshot(GuiConfigContributionAggregator.from(
                new PluginRegistry(List.of(plugin), toggles("fixture", false))));
        ConfigFieldSnapshot uninstalled = ConfigFieldRegistry.snapshot(GuiConfigContributionAggregator.from(
                new PluginRegistry(List.of())));
        ConfigFieldSnapshot damaged = ConfigFieldRegistry.snapshot(GuiConfigContributionAggregator.from(
                new PluginRegistry(List.of(), new PluginToggleProperties(),
                        new PluginDiscoveryResult(List.of(),
                                List.of(new PluginLoadFailure("broken.jar", "load failed"))))));
        ConfigFieldSnapshot throwing = ConfigFieldRegistry.snapshot(GuiConfigContributionAggregator.from(
                new PluginRegistry(List.of(plugin("throwing", () -> {
                    throw new IllegalStateException("boom");
                })))));

        assertThat(disabled.fields()).extracting(ConfigFieldSpec::key).doesNotContain("fixture.visible");
        assertThat(uninstalled.fields()).extracting(ConfigFieldSpec::key).doesNotContain("fixture.visible");
        assertThat(damaged.fields()).extracting(ConfigFieldSpec::key).doesNotContain("fixture.visible");
        assertThat(throwing.fields()).extracting(ConfigFieldSpec::key).doesNotContain("fixture.visible");
        assertThat(throwing.diagnostics()).anySatisfy(diagnostic -> {
            assertThat(diagnostic.pluginId()).isEqualTo("throwing");
            assertThat(diagnostic.message()).contains("boom");
        });
    }

    @Test
    @DisplayName("插件 displayNamespace 抛异常时只隔离该插件字段")
    void displayNamespaceFailureIsIsolatedPerPlugin() {
        PixivFeaturePlugin broken = plugin("broken-display", List::of,
                () -> {
                    throw new IllegalStateException("namespace boom");
                },
                () -> List.of(new GuiConfigContribution(List.of(field("broken.display",
                        GuiConfigGroups.PLUGINS, "broken.display.label", GuiConfigFieldType.STRING)))));
        PixivFeaturePlugin healthy = plugin("healthy-display", () -> List.of(new GuiConfigContribution(
                List.of(field("healthy.display", GuiConfigGroups.PLUGINS,
                        "healthy.display.label", GuiConfigFieldType.STRING)))));

        ConfigFieldSnapshot snapshot = ConfigFieldRegistry.snapshot(GuiConfigContributionAggregator.from(
                new PluginRegistry(List.of(broken, healthy))));

        assertThat(snapshot.fields()).extracting(ConfigFieldSpec::key)
                .contains("healthy.display")
                .doesNotContain("broken.display");
        assertThat(snapshot.diagnostics()).anySatisfy(diagnostic -> {
            assertThat(diagnostic.pluginId()).isEqualTo("broken-display");
            assertThat(diagnostic.key()).isEqualTo("broken.display");
            assertThat(diagnostic.message()).contains("namespace boom");
        });
    }

    @Test
    @DisplayName("插件 i18n 抛异常时只隔离该插件字段")
    void i18nFailureIsIsolatedPerPlugin() {
        PixivFeaturePlugin broken = plugin("broken-i18n",
                () -> {
                    throw new IllegalStateException("i18n boom");
                },
                null,
                () -> List.of(new GuiConfigContribution(List.of(field("broken.i18n",
                        GuiConfigGroups.PLUGINS, "broken.i18n.label", GuiConfigFieldType.STRING)))));
        PixivFeaturePlugin healthy = plugin("healthy-i18n", () -> List.of(new GuiConfigContribution(
                List.of(field("healthy.i18n", GuiConfigGroups.PLUGINS,
                        "healthy.i18n.label", GuiConfigFieldType.STRING)))));

        ConfigFieldSnapshot snapshot = ConfigFieldRegistry.snapshot(GuiConfigContributionAggregator.from(
                new PluginRegistry(List.of(broken, healthy))));

        assertThat(snapshot.fields()).extracting(ConfigFieldSpec::key)
                .contains("healthy.i18n")
                .doesNotContain("broken.i18n");
        assertThat(snapshot.diagnostics()).anySatisfy(diagnostic -> {
            assertThat(diagnostic.pluginId()).isEqualTo("broken-i18n");
            assertThat(diagnostic.message()).contains("i18n boom");
        });
    }

    @Test
    @DisplayName("插件 i18n 返回 null 时按无 bundle 处理并回退 key")
    void nullI18nFallsBackToKeys() {
        PixivFeaturePlugin plugin = plugin("null-i18n", () -> null, null,
                () -> List.of(new GuiConfigContribution(List.of(new GuiConfigFieldContribution(
                        "null.i18n",
                        GuiConfigGroups.PLUGINS,
                        "null.i18n.label",
                        "null.i18n.help",
                        null,
                        GuiConfigFieldType.STRING,
                        "",
                        10,
                        false,
                        true,
                        List.of(),
                        List.of(),
                        List.of(),
                        null,
                        null)))));

        ConfigFieldSnapshot snapshot = ConfigFieldRegistry.snapshot(GuiConfigContributionAggregator.from(
                new PluginRegistry(List.of(plugin))));
        ConfigFieldSpec field = field(snapshot, "null.i18n");

        assertThat(field.label()).isEqualTo("null.i18n.label");
        assertThat(field.helpText()).isEqualTo("null.i18n.help");
        assertThat(snapshot.diagnostics()).anySatisfy(diagnostic -> {
            assertThat(diagnostic.pluginId()).isEqualTo("null-i18n");
            assertThat(diagnostic.message()).contains("i18n contribution list is null");
        });
    }

    @Test
    @DisplayName("插件 label/help 解析失败时不会清空其它插件字段")
    void textResolutionFailureDoesNotEmptyWholeSnapshot() {
        PixivFeaturePlugin broken = plugin("broken-text",
                List.of(new I18nContribution("broken-text", null)),
                () -> List.of(new GuiConfigContribution(
                        List.of(new GuiConfigGroupContribution("broken-text-group",
                                "broken.group.label", "broken-text", 10, true)),
                        List.of(
                                field("broken.text", GuiConfigGroups.PLUGINS,
                                        "broken.field.label", GuiConfigFieldType.STRING),
                                field("broken.text.group", "broken-text-group",
                                        "broken.field.group.label", GuiConfigFieldType.STRING)))));
        PixivFeaturePlugin healthy = plugin("healthy-text", () -> List.of(new GuiConfigContribution(
                List.of(field("healthy.text", GuiConfigGroups.PLUGINS,
                        "healthy.text.label", GuiConfigFieldType.STRING)))));

        ConfigFieldSnapshot snapshot = ConfigFieldRegistry.snapshot(
                GuiConfigContributionAggregator.fromRegisteredPlugins(List.of(
                        new PluginRegistry.RegisteredPlugin(broken, PluginSource.EXTERNAL,
                                broken.getClass().getClassLoader()),
                        new PluginRegistry.RegisteredPlugin(healthy, PluginSource.EXTERNAL,
                                healthy.getClass().getClassLoader()))));

        assertThat(snapshot.fields()).extracting(ConfigFieldSpec::key)
                .contains("healthy.text")
                .doesNotContain("broken.text", "broken.text.group");
        assertThat(snapshot.groups()).doesNotContain("broken.group.label");
        assertThat(snapshot.diagnostics()).anySatisfy(diagnostic -> {
            assertThat(diagnostic.pluginId()).isEqualTo("broken-text");
            assertThat(diagnostic.key()).isEqualTo("broken-text-group");
            assertThat(diagnostic.message()).contains("text resolution failed");
        });
        assertThat(snapshot.diagnostics()).anySatisfy(diagnostic -> {
            assertThat(diagnostic.pluginId()).isEqualTo("broken-text");
            assertThat(diagnostic.key()).isEqualTo("broken.text");
            assertThat(diagnostic.message()).contains("text resolution failed");
        });
    }

    @Test
    @DisplayName("重复 key 只产生明确诊断，不任选一个插件字段继续")
    void duplicateKeysAreDiagnosedWithoutSelectingAPluginField() {
        PixivFeaturePlugin coreConflict = plugin("core-conflict", () -> List.of(new GuiConfigContribution(
                List.of(field("server.port", GuiConfigGroups.SERVER,
                        "plugin.server.port.label", GuiConfigFieldType.STRING)))));
        PixivFeaturePlugin first = plugin("dup-first", () -> List.of(new GuiConfigContribution(
                List.of(field("fixture.duplicate", GuiConfigGroups.PLUGINS,
                        "first.label", GuiConfigFieldType.STRING)))));
        PixivFeaturePlugin second = plugin("dup-second", () -> List.of(new GuiConfigContribution(
                List.of(field("fixture.duplicate", GuiConfigGroups.PLUGINS,
                        "second.label", GuiConfigFieldType.STRING)))));

        ConfigFieldSnapshot coreSnapshot = ConfigFieldRegistry.snapshot(GuiConfigContributionAggregator.from(
                new PluginRegistry(List.of(coreConflict))));
        ConfigFieldSnapshot pluginSnapshot = ConfigFieldRegistry.snapshot(GuiConfigContributionAggregator.from(
                new PluginRegistry(List.of(first, second))));

        assertThat(coreSnapshot.fields()).filteredOn(spec -> spec.key().equals("server.port")).hasSize(1);
        assertThat(coreSnapshot.diagnostics()).anySatisfy(diagnostic -> {
            assertThat(diagnostic.pluginId()).isEqualTo("core-conflict");
            assertThat(diagnostic.key()).isEqualTo("server.port");
            assertThat(diagnostic.message()).contains("core");
        });
        assertThat(pluginSnapshot.fields()).extracting(ConfigFieldSpec::key)
                .doesNotContain("fixture.duplicate");
        assertThat(pluginSnapshot.diagnostics()).anySatisfy(diagnostic -> {
            assertThat(diagnostic.pluginId()).isEqualTo("dup-second");
            assertThat(diagnostic.key()).isEqualTo("fixture.duplicate");
            assertThat(diagnostic.message()).contains("dup-first", "dup-second");
        });
    }

    @Test
    @DisplayName("null contribution 与 null 列表安全降级为诊断")
    void nullContributionsAreHandledSafely() {
        ArrayList<GuiConfigContribution> listWithNull = new ArrayList<>();
        listWithNull.add(null);
        PixivFeaturePlugin nullList = plugin("null-list", () -> null);
        PixivFeaturePlugin nullContribution = plugin("null-contribution", () -> listWithNull);
        PixivFeaturePlugin emptyContribution = plugin("empty-contribution", () -> List.of(
                new GuiConfigContribution(null, null)));

        ConfigFieldSnapshot snapshot = ConfigFieldRegistry.snapshot(GuiConfigContributionAggregator.from(
                new PluginRegistry(List.of(nullList, nullContribution, emptyContribution))));

        assertThat(snapshot.fields()).extracting(ConfigFieldSpec::key)
                .doesNotContain("null-list.field", "null-contribution.field", "empty-contribution.field");
        assertThat(snapshot.diagnostics()).anySatisfy(diagnostic ->
                assertThat(diagnostic.pluginId()).isEqualTo("null-list"));
        assertThat(snapshot.diagnostics()).anySatisfy(diagnostic ->
                assertThat(diagnostic.pluginId()).isEqualTo("null-contribution"));
    }

    @Test
    @DisplayName("i18n bundle 经注册插件的 classloader 解析")
    void pluginI18nUsesRegisteredClassLoader() throws Exception {
        Path resourceRoot = tempDir.resolve("resources");
        Path bundleDir = resourceRoot.resolve("i18n/web");
        Files.createDirectories(bundleDir);
        Files.writeString(bundleDir.resolve("fixture.properties"), String.join("\n",
                "fixture.group.label=External Group",
                "fixture.field.label=External Label",
                "fixture.field.help=External Help"), StandardCharsets.UTF_8);
        URL[] urls = {resourceRoot.toUri().toURL()};

        PixivFeaturePlugin plugin = plugin("fixture", List.of(new I18nContribution("fixture", "i18n.web.fixture")),
                () -> List.of(new GuiConfigContribution(
                        List.of(new GuiConfigGroupContribution("fixture-i18n", "fixture.group.label", 10)),
                        List.of(new GuiConfigFieldContribution(
                                "fixture.i18n",
                                "fixture-i18n",
                                "fixture.field.label",
                                "fixture.field.help",
                                "fixture",
                                GuiConfigFieldType.STRING,
                                "",
                                10,
                                false,
                                true,
                                List.of(),
                                List.of(),
                                List.of(),
                                null,
                                null)))));

        try (URLClassLoader loader = new URLClassLoader(urls, null)) {
            ConfigFieldSnapshot snapshot = ConfigFieldRegistry.snapshot(
                    GuiConfigContributionAggregator.fromRegisteredPlugins(List.of(
                            new PluginRegistry.RegisteredPlugin(plugin, PluginSource.EXTERNAL, loader))));

            ConfigFieldSpec spec = field(snapshot, "fixture.i18n");
            assertThat(snapshot.groups()).contains("External Group");
            assertThat(spec.label()).isEqualTo("External Label");
            assertThat(spec.helpText()).isEqualTo("External Help");
        }
    }

    @Test
    @DisplayName("ConfigPanel 消费宿主合并后的字段快照")
    void configPanelConsumesAggregatedSnapshot() {
        PixivFeaturePlugin plugin = plugin("fixture", () -> List.of(new GuiConfigContribution(
                List.of(field("fixture.panel", GuiConfigGroups.PLUGINS,
                        "fixture.panel.label", GuiConfigFieldType.BOOL)))));
        ConfigFieldSnapshot snapshot = ConfigFieldRegistry.snapshot(
                GuiConfigContributionAggregator.from(new PluginRegistry(List.of(plugin))));

        ConfigPanel panel = new ConfigPanel(tempDir.resolve("config.yaml"), 6999,
                path -> "http://localhost:6999" + path, snapshot);

        assertThat(panel.allFields()).extracting(ConfigFieldSpec::key).contains("fixture.panel");
        assertThat(panel.findSpec("fixture.panel")).isNotNull();
    }

    private static GuiConfigFieldContribution field(String key, String groupId,
                                                    String labelKey, GuiConfigFieldType type) {
        return new GuiConfigFieldContribution(key, groupId, labelKey, type, "", 10);
    }

    private static ConfigFieldSpec field(ConfigFieldSnapshot snapshot, String key) {
        return snapshot.fields().stream()
                .filter(spec -> spec.key().equals(key))
                .findFirst()
                .orElseThrow();
    }

    private static PluginToggleProperties toggles(String pluginId, boolean enabled) {
        PluginToggleProperties toggles = new PluginToggleProperties();
        PluginToggleProperties.PluginToggle toggle = new PluginToggleProperties.PluginToggle();
        toggle.setEnabled(enabled);
        toggles.put(pluginId, toggle);
        return toggles;
    }

    private static PixivFeaturePlugin plugin(String id, Supplier<List<GuiConfigContribution>> contributions) {
        return plugin(id, List.of(), contributions);
    }

    private static PixivFeaturePlugin plugin(String id, List<I18nContribution> i18n,
                                             Supplier<List<GuiConfigContribution>> contributions) {
        return plugin(id, () -> i18n, null, contributions);
    }

    private static PixivFeaturePlugin plugin(String id, Supplier<List<I18nContribution>> i18n,
                                             Supplier<String> displayNamespace,
                                             Supplier<List<GuiConfigContribution>> contributions) {
        return new TestPlugin(id, i18n, displayNamespace, contributions);
    }

    private static final class TestPlugin implements PixivFeaturePlugin {
        private final String id;
        private final Supplier<List<I18nContribution>> i18n;
        private final Supplier<String> displayNamespace;
        private final Supplier<List<GuiConfigContribution>> contributions;

        private TestPlugin(String id,
                           Supplier<List<I18nContribution>> i18n,
                           Supplier<String> displayNamespace,
                           Supplier<List<GuiConfigContribution>> contributions) {
            this.id = id;
            this.i18n = i18n;
            this.displayNamespace = displayNamespace;
            this.contributions = contributions;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public String displayName() {
            return "plugin.label";
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
        public String displayNamespace() {
            return displayNamespace == null ? PixivFeaturePlugin.super.displayNamespace() : displayNamespace.get();
        }

        @Override
        public List<I18nContribution> i18n() {
            return i18n.get();
        }

        @Override
        public List<GuiConfigContribution> guiConfigContributions() {
            return contributions.get();
        }
    }
}
