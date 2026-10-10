package top.sywyar.pixivdownload.gui;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.mock.env.MockEnvironment;
import top.sywyar.pixivdownload.plugin.BuiltInPlugins;
import top.sywyar.pixivdownload.plugin.PluginRuntimeConfiguration;
import top.sywyar.pixivdownload.plugin.PluginToggleProperties;
import top.sywyar.pixivdownload.plugin.management.PluginStatusService;
import top.sywyar.pixivdownload.plugin.recovery.RecoveryModeService;
import top.sywyar.pixivdownload.plugin.registry.PluginRegistry;
import top.sywyar.pixivdownload.plugin.runtime.PluginRuntimeManager;
import top.sywyar.pixivdownload.plugin.runtime.artifact.PluginDevelopmentArtifacts;
import top.sywyar.pixivdownload.plugin.runtime.status.PluginStatus;

import java.nio.file.Path;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPathFactory;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("缺失必需插件的共享启动配置")
class RecoveryLaunchConfigurationTest {
    private final Path root = Path.of(System.getProperty("maven.multiModuleProjectDirectory", "..")).toAbsolutePath().normalize();

    @Test
    @DisplayName("三种 IDE 先构建恢复场景，再以开发模式和必需探针参数启动")
    void sharedLaunchersPrepareRecoveryRuntime() throws Exception {
        String idea = ".run/Missing Required Plugin.run.xml";
        assertThat(xml(idea, "//option[@name='VM_PARAMETERS']/@value"))
                .contains("-Dpixivdownload.plugin-dev.enabled=true", "-Dpixivdownload.plugin-dev.root=");
        assertThat(xml(idea, "//option[@name='PROGRAM_PARAMETERS']/@value"))
                .contains("--pixivdownload.recovery-sentinel.required=true");
        assertThat(xml(idea, "//option[@name='Maven.BeforeRunTask']/@goal"))
                .contains("pixivdownload-app,pixivdownload-official-plugins", "-Precovery-mode", "process-classes");
        var mapper = new ObjectMapper();
        var launches = mapper.readTree(root.resolve(".vscode/launch.json").toFile()).path("configurations");
        var launch = java.util.stream.StreamSupport.stream(launches.spliterator(), false)
                .filter(node -> node.path("name").asText().equals("Missing Required Plugin")).findFirst().orElseThrow();
        assertThat(launch.path("vmArgs").toString()).contains("-Dpixivdownload.plugin-dev.enabled=true");
        assertThat(launch.path("args").toString()).contains("--pixivdownload.recovery-sentinel.required=true");
        var tasks = mapper.readTree(root.resolve(".vscode/tasks.json").toFile()).path("tasks");
        var task = java.util.stream.StreamSupport.stream(tasks.spliterator(), false)
                .filter(node -> node.path("label").equals(launch.path("preLaunchTask"))).findFirst().orElseThrow();
        assertThat(task.path("args").toString())
                .contains("pixivdownload-app,pixivdownload-official-plugins", "-Precovery-mode", "process-classes");
        String eclipseCompile = xml("eclipse/Missing Required Plugin.launch",
                "//stringAttribute[@key='org.eclipse.debug.core.launchGroup.0.name']/@value");
        assertThat(xml("eclipse/" + eclipseCompile + ".launch", "//stringAttribute[@key='M2_PROFILES']/@value"))
                .isEqualTo("recovery-mode");
        assertThat(xml("eclipse/" + eclipseCompile + ".launch", "//stringAttribute[@key='M2_GOALS']/@value"))
                .contains("pixivdownload-app,pixivdownload-official-plugins", "process-classes");
        assertThat(xml("eclipse/Missing Required Plugin Application.launch",
                "//stringAttribute[@key='org.eclipse.jdt.launching.VM_ARGUMENTS']/@value"))
                .contains("-Dpixivdownload.plugin-dev.enabled=true");
        assertThat(xml("eclipse/Missing Required Plugin Application.launch",
                "//stringAttribute[@key='org.eclipse.jdt.launching.PROGRAM_ARGUMENTS']/@value"))
                .contains("--pixivdownload.recovery-sentinel.required=true");
    }

    @Test
    @EnabledIfSystemProperty(named = "pixivdownload.test.recoveryRuntime", matches = "true")
    @ResourceLock("java.lang.System.properties")
    @DisplayName("实际恢复构建保留可选择的 GUI，唯一缺失的必需项为测试探针")
    void compiledRuntimeHasGuiAndMissingSentinel(@TempDir Path plugins) {
        String enabled = System.getProperty(PluginDevelopmentArtifacts.ENABLED_PROPERTY);
        String previousRoot = System.getProperty(PluginDevelopmentArtifacts.ROOT_PROPERTY);
        var runtime = new PluginRuntimeManager(plugins);
        try {
            System.setProperty(PluginDevelopmentArtifacts.ENABLED_PROPERTY, "true");
            System.setProperty(PluginDevelopmentArtifacts.ROOT_PROPERTY, root.toString());
            assertThat(runtime.start().failures()).isEmpty();
            var registry = new PluginRegistry(BuiltInPlugins.createAll(), new PluginToggleProperties(),
                    runtime.discoverFeaturePlugins());
            var sources = GuiLauncher.buildDesktopUiPluginSources(registry);
            var providers = sources.stream().map(DesktopUiPluginSource::plugin)
                    .filter(top.sywyar.pixivdownload.plugin.api.gui.DesktopUiProvider.class::isInstance)
                    .map(top.sywyar.pixivdownload.plugin.api.gui.DesktopUiProvider.class::cast).toList();
            assertThat(DesktopUiSelector.select("", providers).provider()).isNotNull();
            var policy = new PluginRuntimeConfiguration().requiredPluginPolicy(new MockEnvironment()
                    .withProperty("pixivdownload.recovery-sentinel.required", "true"));
            var statuses = new PluginStatusService(registry, runtime.inspectPlugins(), policy);
            var decision = new RecoveryModeService(statuses, policy).decision();
            assertThat(decision.reasons()).singleElement().satisfies(reason -> {
                assertThat(reason.pluginId()).isEqualTo("recovery-sentinel");
                assertThat(reason.status()).isEqualTo(PluginStatus.MISSING_REQUIRED);
            });
        } finally {
            runtime.shutdown();
            if (enabled == null) System.clearProperty(PluginDevelopmentArtifacts.ENABLED_PROPERTY);
            else System.setProperty(PluginDevelopmentArtifacts.ENABLED_PROPERTY, enabled);
            if (previousRoot == null) System.clearProperty(PluginDevelopmentArtifacts.ROOT_PROPERTY);
            else System.setProperty(PluginDevelopmentArtifacts.ROOT_PROPERTY, previousRoot);
        }
    }

    private String xml(String file, String expression) throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        var document = factory.newDocumentBuilder().parse(root.resolve(file).toFile());
        return XPathFactory.newInstance().newXPath().evaluate(expression, document);
    }
}
