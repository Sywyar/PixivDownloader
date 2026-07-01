package top.sywyar.pixivdownload.plugin;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.runtime.discovery.PluginDirectoryState;
import top.sywyar.pixivdownload.plugin.runtime.discovery.PluginInventory;
import top.sywyar.pixivdownload.plugin.runtime.PluginRuntimeManager;
import top.sywyar.pixivdownload.plugin.runtime.PluginRuntimeStatus;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginApiRequirement;
import top.sywyar.pixivdownload.plugin.runtime.status.PluginStatus;
import top.sywyar.pixivdownload.plugin.runtime.status.RequiredPluginPolicy;
import top.sywyar.pixivdownload.plugin.runtime.status.RequiredPluginPolicy.RequiredPlugin;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import top.sywyar.pixivdownload.plugin.management.PluginStatusService;
import top.sywyar.pixivdownload.plugin.recovery.RecoveryModeService;
import top.sywyar.pixivdownload.plugin.registry.PluginRegistry;
import top.sywyar.pixivdownload.plugin.registry.PluginSource;

/**
 * 恢复模式的端到端集成验证：用<b>真实</b> recovery-sentinel 插件 jar（由 reactor 兄弟模块
 * {@code pixivdownload-plugin-recovery-sentinel} 的编译产物 + 根部 {@code plugin.properties} 组装）放进临时
 * {@code plugins/} 目录，经 {@link PluginRuntimeManager} 真实加载 / 启动 / 发现，再走真实的
 * {@link PluginRegistry} → {@link PluginStatusService} → 恢复模式评估器 → {@link RecoveryModeService} 决策链，验证：
 * <ol>
 *   <li>把 recovery-sentinel 声明为必选、且该外置插件<b>已安装且启用</b>（STARTED）时，核心<b>不</b>进入恢复模式；</li>
 *   <li>把 recovery-sentinel 声明为必选、但用 {@code plugins.recovery-sentinel.enabled=false} <b>禁用</b>它（仍被 PF4J
 *       加载、但不进入活动快照）时，状态评估为 {@link PluginStatus#DISABLED}、核心<b>进入</b>恢复模式；</li>
 *   <li>外置 recovery-sentinel 以 {@link PluginSource#EXTERNAL} 接入，且它<b>不自称必选</b>
 *       （{@link PixivFeaturePlugin#required()} 为 {@code false}）——必选性只来自核心策略。</li>
 * </ol>
 *
 * <p>「必选但缺失（未安装）」一案不需要真实 jar（缺失即清点为空），由 {@link RecoverySentinelRecoveryGateTest} 用真实
 * {@link RecoveryModeService} 覆盖；此处用真实 jar 专门覆盖「已安装且启用」与「已安装但禁用」两案，正是没有真实外置
 * 插件时无法复现的两种状态。
 *
 * <p>本测试用的必选策略<b>只含 recovery-sentinel</b>（不含内置 download-workbench），以隔离被验证的变量——组件级注册
 * 中心此处不装配内置插件，若策略含 download-workbench 会因其缺失干扰判定。
 *
 * <p>recovery-sentinel 构建产物目录经 surefire 系统属性 {@code recovery-sentinel.plugin.classes} 传入（reactor 中先于
 * app 构建）；未就绪时（如 IDE 未触发 reactor 构建）整类 {@link Assumptions assume} 跳过。Windows 下 PF4J 加载 jar 会
 * 持有文件锁，故 {@link #unloadAndCleanup()} 先停止 / 卸载插件释放 classloader 再删除临时目录。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("recovery-sentinel 外置 PF4J 插件：真实加载下「已装启用→正常」「已装禁用→恢复」+ EXTERNAL 接入不自称必选")
class RecoverySentinelExternalPluginIntegrationTest {

    private static final String SENTINEL_CLASSES_PROPERTY = "recovery-sentinel.plugin.classes";
    private static final RequiredPluginPolicy POLICY = RequiredPluginPolicy.of(List.of(
            new RequiredPlugin("recovery-sentinel", PluginApiRequirement.unspecified(), false, "plugin.recovery.blocked")));

    private Path tempPluginsDir;
    private PluginRuntimeManager manager;
    private PluginRuntimeStatus status;

    @BeforeAll
    void loadExternalSentinelJar() throws IOException {
        Path sentinelClasses = locateSentinelClasses();
        Assumptions.assumeTrue(sentinelClasses != null && Files.isDirectory(sentinelClasses),
                "recovery-sentinel 插件构建产物未就绪（需 reactor 先构建 pixivdownload-plugin-recovery-sentinel），跳过真实 jar 验证");
        // 关键前提：外置 jar 里绝不能含共享契约（plugin-api 等），否则桥接 instanceof 会因同名异 loader 而失败。
        assertThat(sentinelClasses.resolve("top/sywyar/pixivdownload/plugin/api")).doesNotExist();

        tempPluginsDir = Files.createTempDirectory("pixiv-recovery-sentinel-it");
        Path jar = tempPluginsDir.resolve("recovery-sentinel-plugin-0.0.1.jar");
        zipDirectoryAsJar(sentinelClasses, jar);
        PluginTestProvenance.writeLocalUpload(tempPluginsDir, jar, "recovery-sentinel", "1.0.0");

        manager = new PluginRuntimeManager(tempPluginsDir);
        status = manager.start();
    }

    @AfterAll
    void unloadAndCleanup() {
        if (manager != null) {
            // 先停止 / 卸载，释放 PF4J 插件 classloader 对 jar 的文件锁（Windows 下否则删不掉临时目录）。
            manager.pluginManager().ifPresent(pm -> {
                try {
                    pm.stopPlugins();
                } catch (Exception ignored) {
                    // best-effort
                }
                try {
                    pm.unloadPlugins();
                } catch (Exception ignored) {
                    // best-effort
                }
            });
        }
        deleteRecursivelyQuietly(tempPluginsDir);
    }

    @Test
    @DisplayName("运行时真实加载并启动外置 recovery-sentinel（POPULATED、started 含 recovery-sentinel、无失败）")
    void runtimeManagerLoadsAndStartsSentinel() {
        assertThat(status.state()).isEqualTo(PluginDirectoryState.POPULATED);
        assertThat(status.startedPluginIds()).contains("recovery-sentinel");
        assertThat(status.failures()).isEmpty();
    }

    @Test
    @DisplayName("已安装且启用：状态 STARTED、来源 EXTERNAL、不自称必选，核心不进入恢复模式")
    void enabledSentinelKeepsOperational() {
        PluginToggleProperties enabled = new PluginToggleProperties();   // 默认全部启用
        PluginRegistry registry = registryFor(enabled);

        // 外置接入 + 不自称必选（必选性只来自核心策略）。
        assertThat(registry.source("recovery-sentinel")).contains(PluginSource.EXTERNAL);
        assertThat(registry.find("recovery-sentinel").orElseThrow().required()).isFalse();

        PluginStatusService statusService = new PluginStatusService(registry, manager.inspectPlugins(), POLICY);
        assertThat(statusService.report().byId("recovery-sentinel").orElseThrow().status())
                .isEqualTo(PluginStatus.STARTED);

        RecoveryModeService recovery = new RecoveryModeService(statusService, POLICY);
        assertThat(recovery.isActive()).isFalse();
        assertThat(recovery.decision().reasons()).isEmpty();
    }

    @Test
    @DisplayName("已安装但 plugins.recovery-sentinel.enabled=false：状态 DISABLED，核心进入恢复模式")
    void disabledSentinelEntersRecovery() {
        PluginRegistry registry = registryFor(togglesWith("recovery-sentinel", false));

        // 被禁用：仍安装（PF4J 已加载），但不在活动快照内。
        assertThat(registry.find("recovery-sentinel")).isEmpty();
        assertThat(registry.allPlugins()).extracting(PixivFeaturePlugin::id).contains("recovery-sentinel");

        PluginStatusService statusService = new PluginStatusService(registry, manager.inspectPlugins(), POLICY);
        assertThat(statusService.report().byId("recovery-sentinel").orElseThrow().status())
                .isEqualTo(PluginStatus.DISABLED);

        RecoveryModeService recovery = new RecoveryModeService(statusService, POLICY);
        assertThat(recovery.isActive()).isTrue();
        assertThat(recovery.decision().firstReason().orElseThrow().pluginId()).isEqualTo("recovery-sentinel");
        assertThat(recovery.decision().firstReason().orElseThrow().status()).isEqualTo(PluginStatus.DISABLED);
    }

    // --- helpers ---

    private PluginRegistry registryFor(PluginToggleProperties toggles) {
        return new PluginRegistry(List.of(), toggles, manager.discoverFeaturePlugins());
    }

    private static PluginToggleProperties togglesWith(String pluginId, boolean enabled) {
        PluginToggleProperties toggles = new PluginToggleProperties();
        PluginToggleProperties.PluginToggle toggle = new PluginToggleProperties.PluginToggle();
        toggle.setEnabled(enabled);
        toggles.put(pluginId, toggle);
        return toggles;
    }

    private static Path locateSentinelClasses() {
        String configured = System.getProperty(SENTINEL_CLASSES_PROPERTY);
        return (configured == null || configured.isBlank()) ? null : Path.of(configured);
    }

    /** 把一个目录（recovery-sentinel 的 {@code target/classes}，根部已含 {@code plugin.properties}）打成 PF4J 可加载的 jar。 */
    private static void zipDirectoryAsJar(Path sourceDir, Path jarPath) throws IOException {
        try (OutputStream out = Files.newOutputStream(jarPath);
             ZipOutputStream zos = new ZipOutputStream(out)) {
            List<Path> files;
            try (var walk = Files.walk(sourceDir)) {
                files = walk.filter(Files::isRegularFile).sorted().toList();
            }
            for (Path file : files) {
                String entryName = sourceDir.relativize(file).toString().replace('\\', '/');
                zos.putNextEntry(new ZipEntry(entryName));
                Files.copy(file, zos);
                zos.closeEntry();
            }
        }
    }

    private static void deleteRecursivelyQuietly(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var walk = Files.walk(root)) {
            walk.sorted(Comparator.comparingInt(Path::getNameCount).reversed()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // best-effort：临时目录，残留由 OS 清理
                }
            });
        } catch (IOException ignored) {
            // best-effort
        }
    }
}
