package top.sywyar.pixivdownload.gui;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.guitheme.GuiSwingPlugin;
import top.sywyar.pixivdownload.plugin.PluginToggleProperties;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopEnvironmentTransfer.FieldKey;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiHost;
import top.sywyar.pixivdownload.plugin.registry.PluginRegistry;
import top.sywyar.pixivdownload.plugin.runtime.discovery.*;
import top.sywyar.pixivdownload.transfer.EnvironmentTransferService;
import top.sywyar.pixivdownload.transfer.archive.TransferArchive;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("生产迁移快照中的 GUI 与主题声明")
class GuiTransferPreferencesTest {
    @TempDir Path root;
    private final Map<String, String> previous = new HashMap<>();

    @BeforeEach void isolate() throws Exception {
        for (String property : List.of(RuntimeFiles.CONFIG_DIR_PROPERTY, RuntimeFiles.DATA_DIR_PROPERTY)) {
            previous.put(property, System.getProperty(property));
            System.setProperty(property, Files.createDirectory(root.resolve(property)).toString());
        }
    }
    @AfterEach void restore() {
        previous.forEach((key, value) -> { if (value == null) System.clearProperty(key); else System.setProperty(key, value); });
    }

    @ParameterizedTest
    @CsvSource({
            "app.gui-provider, gui-swing, true, false, true",
            "app.theme, moonlight, true, false, true",
            "app.gui-provider, missing-ui, true, false, false",
            "app.theme, missing-theme, true, false, false",
            "app.theme, moonlight, false, false, true",
            "app.gui-provider, gui-swing, false, false, false",
            "app.gui-provider, gui-swing, false, true, true"
    })
    @DisplayName("安装声明参与真实导入校验，禁用 GUI 须同时启用，未知偏好拒绝")
    void importsDeclaredPreferencesWithoutStartingPlugins(String key, String value, boolean enabled,
                                                         boolean enableSelected, boolean accepted) throws Exception {
        var plugin = spy(new GuiSwingPlugin());
        var toggles = new PluginToggleProperties();
        toggles.setEnabled(plugin.id(), enabled);
        var discovery = new PluginDiscoveryResult(List.of(new DiscoveredFeaturePlugin(
                plugin.id(), plugin.id(), plugin, getClass().getClassLoader())), List.of());
        var registry = new PluginRegistry(List.of(), toggles, discovery);
        var snapshots = GuiLauncher.buildTransferPluginSnapshots(registry);
        assertThat(snapshots).hasSize(1);
        assertThat(snapshots.get(0).desktopUiProvider()).isTrue();
        assertThat(snapshots.get(0).themes()).extracting(theme -> theme.themeId()).contains("moonlight");
        assertThat(snapshots.get(0).navigation()).isEmpty();
        verify(plugin, never()).launch(any());
        verify(plugin, never()).navigation();
        verify(plugin, never()).routes();
        verify(plugin, never()).guiOnboardingSteps();
        String toggle = "plugins." + plugin.id() + ".enabled";
        Files.writeString(RuntimeFiles.peekConfigYamlPath(),
                "app.gui-provider: other-ui\napp.theme: light\n" + toggle + ": " + enabled + "\n");
        var settings = new LinkedHashMap<String, String>();
        settings.put(key, value);
        if (enableSelected) settings.put(toggle, "true");
        byte[] payload = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(settings);
        Path bundle = root.resolve("preferences.pdbackup");
        new TransferArchive().write(bundle, null, "fixture", List.of(new TransferArchive.Source(
                new TransferArchive.Descriptor("host", TransferArchive.Category.CONFIG, "settings.json", 1),
                () -> new ByteArrayInputStream(payload))));
        var host = mock(DesktopUiHost.class);
        var transfer = new EnvironmentTransferService(host, () -> GuiLauncher.buildTransferPluginSnapshots(registry), () -> true);
        var preview = transfer.previewConfiguration(bundle, null);
        var selected = new HashSet<FieldKey>();
        settings.keySet().forEach(name -> selected.add(new FieldKey("host", name)));
        if (accepted) {
            transfer.importConfiguration(preview.token(), selected, Map.of(), null, false, Set.of(), Map.of());
            assertThat(transfer.restoreState().status()).isEqualTo(top.sywyar.pixivdownload.plugin.api.gui.DesktopEnvironmentTransfer.Status.PENDING);
            try (var staged = Files.walk(RuntimeFiles.environmentTransferDirectory())) {
                Path after = staged.filter(path -> path.getFileName().toString().equals("0.after")).findFirst().orElseThrow();
                assertThat(Files.readString(after, StandardCharsets.UTF_8)).contains(key + ": " + value);
            }
        } else {
            assertThatThrownBy(() -> transfer.importConfiguration(preview.token(), selected, Map.of(), null, false, Set.of(), Map.of()))
                    .isInstanceOf(java.io.IOException.class).hasMessage("transfer.config-value-invalid");
            assertThat(transfer.restoreState().status()).isEqualTo(top.sywyar.pixivdownload.plugin.api.gui.DesktopEnvironmentTransfer.Status.NONE);
        }
        assertThat(Files.readString(RuntimeFiles.peekConfigYamlPath())).contains("app.theme: light", "app.gui-provider: other-ui");
        verify(plugin, never()).launch(any());
        verify(plugin, never()).navigation();
    }

    @Test @DisplayName("禁用插件仍经生产快照导出并合并声明的配置")
    void transfersConfigurationOfDisabledPlugin() throws Exception {
        var plugin = spy(new GuiSwingPlugin());
        String key = "gui-swing.fixture";
        when(plugin.guiConfigContributions()).thenReturn(List.of(new top.sywyar.pixivdownload.plugin.api.gui.GuiConfigContribution(
                List.of(new top.sywyar.pixivdownload.plugin.api.gui.GuiConfigFieldContribution(
                        key, "fixture", "fixture", top.sywyar.pixivdownload.plugin.api.gui.GuiConfigFieldType.STRING, "", 1)))));
        var toggles = new PluginToggleProperties();
        toggles.setEnabled(plugin.id(), false);
        var registry = new PluginRegistry(List.of(), toggles, new PluginDiscoveryResult(List.of(
                new DiscoveredFeaturePlugin(plugin.id(), plugin.id(), plugin, getClass().getClassLoader())), List.of()));
        Files.writeString(RuntimeFiles.peekConfigYamlPath(), "plugins.gui-swing.enabled: false\n");
        Path config = RuntimeFiles.peekPluginConfigPath(plugin.id());
        Files.createDirectories(config.getParent());
        Files.writeString(config, key + "=source\n");
        var host = mock(DesktopUiHost.class);
        when(host.applicationVersion()).thenReturn("fixture");
        var transfer = new EnvironmentTransferService(host,
                () -> GuiLauncher.buildTransferPluginSnapshots(registry), () -> true);
        Path bundle = root.resolve("disabled.pdbackup");
        transfer.exportConfiguration(bundle, null, false, false, false, false);
        Files.writeString(config, key + "=target\nunselected=keep\n");
        var preview = transfer.previewConfiguration(bundle, null);
        transfer.importConfiguration(preview.token(), Set.of(new FieldKey(plugin.id(), key)),
                Map.of(), null, false, Set.of(), Map.of());
        try (var staged = Files.walk(RuntimeFiles.environmentTransferDirectory())) {
            Path after = staged.filter(path -> path.getFileName().toString().equals("0.after")).findFirst().orElseThrow();
            assertThat(Files.readString(after)).contains(key + "=source", "unselected=keep");
        }
        verify(plugin, never()).launch(any());
        verify(plugin, never()).routes();
        verify(plugin, never()).navigation();
        verify(plugin, never()).guiOnboardingSteps();
    }
}
