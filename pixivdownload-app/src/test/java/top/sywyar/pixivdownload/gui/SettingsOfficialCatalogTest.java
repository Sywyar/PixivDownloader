package top.sywyar.pixivdownload.gui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import top.sywyar.pixivdownload.core.db.DatabaseTarget;
import top.sywyar.pixivdownload.gui.config.DatabaseSettingsService;
import top.sywyar.pixivdownload.plugin.CorePlugin;
import top.sywyar.pixivdownload.plugin.PluginToggleProperties;
import top.sywyar.pixivdownload.plugin.registry.PluginRegistry;
import top.sywyar.pixivdownload.plugin.runtime.bootstrap.PluginBootstrapSession;
import top.sywyar.pixivdownload.plugin.runtime.bootstrap.PluginEnabledSnapshot;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@DisplayName("当前官方插件通过真实发现链进入 HTTP 设置目录")
@EnabledIfSystemProperty(named = "http.api.official-integration", matches = "true")
class SettingsOfficialCatalogTest {
    @TempDir Path root;

    @Test
    @DisplayName("每个已发现插件的全部声明字段可读，敏感字段不回显且目录读取不写配置")
    void exposesAllDeclaredFields() throws Exception {
        Path project = Path.of(System.getProperty("http.api.official-root")).toAbsolutePath();
        assertThat(project.resolve("target/pixivdownload-plugin-dev-classpath.txt")).isRegularFile();
        var previous = new LinkedHashMap<String, String>();
        var properties = new LinkedHashMap<String, String>();
        properties.put("pixivdownload.plugin-dev.enabled", "true");
        properties.put("pixivdownload.plugin-dev.root", project.toString());
        for (String directory : List.of("config", "data", "state", "instance", "plugins"))
            properties.put("pixivdownload." + directory + "-dir", root.resolve(directory).toString());
        properties.forEach((key, value) -> { previous.put(key, System.getProperty(key)); System.setProperty(key, value); });
        try (var session = PluginBootstrapSession.createProcess(root.resolve("plugins"), PluginEnabledSnapshot.empty()).start()) {
            var discovery = session.startupDiscovery();
            assertThat(discovery.failures()).isEmpty();
            assertThat(discovery.discovered()).isNotEmpty();
            var registry = new PluginRegistry(List.of(new CorePlugin()), new PluginToggleProperties(), discovery);
            var snapshots = GuiLauncher.buildDesktopUiPluginSnapshots(GuiLauncher.buildDesktopUiPluginSources(registry));
            var host = new AppDesktopUiHost(0, new String[0], session, mock(DatabaseTarget.class));
            var service = new SettingsApiService(host, () -> snapshots, mock(DatabaseSettingsService.class, RETURNS_DEEP_STUBS));
            var expected = new HashSet<String>();
            snapshots.forEach(plugin -> plugin.configContributions().forEach(contribution -> contribution.fields()
                    .forEach(field -> expected.add(plugin.id() + "/" + field.key()))));
            var fields = service.fields();
            assertThat(fields.stream().filter(field -> field.owner() != null)
                    .map(field -> field.owner() + "/" + field.key()).toList()).containsExactlyInAnyOrderElementsOf(expected);
            assertThat(fields.stream().filter(SettingsApiService.Field::sensitive)).allMatch(field -> field.value() == null);
            if (Files.exists(root.resolve("config"))) {
                try (var files = Files.walk(root.resolve("config"))) {
                    assertThat(files.filter(Files::isRegularFile).toList()).isEmpty();
                }
            }
        } finally {
            previous.forEach((key, value) -> { if (value == null) System.clearProperty(key); else System.setProperty(key, value); });
        }
    }
}
