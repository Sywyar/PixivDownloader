package top.sywyar.pixivdownload.plugin;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.runtime.discovery.DiscoveredFeaturePlugin;
import top.sywyar.pixivdownload.plugin.runtime.discovery.PluginDirectoryState;
import top.sywyar.pixivdownload.plugin.runtime.discovery.PluginDiscoveryResult;
import top.sywyar.pixivdownload.plugin.runtime.discovery.PluginInstallation;
import top.sywyar.pixivdownload.plugin.runtime.discovery.PluginInventory;
import top.sywyar.pixivdownload.plugin.runtime.PluginRuntimeManager;
import top.sywyar.pixivdownload.plugin.runtime.PluginRuntimeStatus;
import top.sywyar.pixivdownload.plugin.runtime.status.PluginStatus;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 中文 + 空格路径下的外置插件加载回归：Windows 安装器 / portable 会把官方可选插件预置进工作目录 {@code plugins/}，
 * 而用户常把软件装在「C:\Users\某 用户\下载 目录\」之类含中文与空格的路径下。本用例用<b>真实</b> stats 插件 jar
 *（由本模块编译产物 + 根部 {@code plugin.properties} 组装）放进一个<b>目录名刻意含中文字符与空格</b>的 {@code plugins/}，
 * 经 {@link PluginRuntimeManager} 真实加载 / 启动 / 清点，验证：
 * <ol>
 *   <li>{@link PluginRuntimeManager#start()} 在中文 + 空格路径下仍返回 {@code POPULATED}、loaded / started 含
 *       {@code stats}、无失败诊断（PF4J 以 {@link java.nio.file.Path} 加载、路径不被破坏）；</li>
 *   <li>清点出的安装条目状态 {@code STARTED}、其解析用 classloader 在该路径下仍能解析插件内静态资源
 *       （classloader 的 jar URL 编解码不被路径里的空格 / 非 ASCII 字符破坏）；</li>
 *   <li>发现桥接把 {@code stats} 识别为可接入的功能插件。</li>
 * </ol>
 *
 * <p>这是对 {@link StatsExternalPluginIntegrationTest}（ASCII 临时目录）的补充——后者证明加载链路本身，本用例证明
 * 同一链路在中文 + 空格路径下不退化。stats 构建产物目录经 surefire 系统属性 {@code stats.plugin.classes} 传入
 *（reactor 中先于 app 构建）；未就绪时整类 {@link Assumptions assume} 跳过。Windows 下 PF4J 加载 jar 会持有文件锁，
 * 故 {@link #unloadAndCleanup()} 先停止 / 卸载插件释放 classloader 再删除目录。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("外置插件目录含中文与空格时 PF4J 仍能加载 / 启动 / 解析资源")
class PluginRuntimeChinesePathLoadTest {

    private static final String STATS_CLASSES_PROPERTY = "stats.plugin.classes";
    /** 目录名刻意含中文字符与空格，复现真实安装路径（相对 app 模块工作目录，mvn clean 时随 target 清理）。 */
    private static final Path PLUGINS_DIR = Path.of("target/test-runtime/插件 加载 中文 test/plugins");

    private PluginRuntimeManager manager;
    private PluginRuntimeStatus status;

    @BeforeAll
    void loadExternalStatsJarFromChineseSpacePath() throws IOException {
        Path statsClasses = locateStatsClasses();
        Assumptions.assumeTrue(statsClasses != null && Files.isDirectory(statsClasses),
                "stats 插件构建产物未就绪（需 reactor 先构建 pixivdownload-plugin-stats），跳过中文路径加载验证");

        // 前提自检：测试路径必须真含空格与非 ASCII（中文），否则本回归形同虚设。
        String absolutePath = PLUGINS_DIR.toAbsolutePath().toString();
        assertThat(absolutePath).contains(" ");
        assertThat(absolutePath.codePoints().anyMatch(codePoint -> codePoint > 127))
                .as("插件目录绝对路径应含非 ASCII 字符：%s", absolutePath)
                .isTrue();

        deleteRecursivelyQuietly(PLUGINS_DIR);
        Files.createDirectories(PLUGINS_DIR);
        Path jar = PLUGINS_DIR.resolve("pixivdownload-plugin-stats.jar");
        zipDirectoryAsJar(statsClasses, jar);
        PluginTestProvenance.writeLocalUpload(PLUGINS_DIR, jar, "stats", "1.0.0");

        manager = new PluginRuntimeManager(PLUGINS_DIR);
        status = manager.start();
    }

    @AfterAll
    void unloadAndCleanup() {
        if (manager != null) {
            // 先停止 / 卸载，释放 PF4J 插件 classloader 对 jar 的文件锁（Windows 下否则删不掉目录）。
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
        // 删整棵「中文 + 空格」目录（plugins 的父目录）。
        deleteRecursivelyQuietly(PLUGINS_DIR.getParent());
    }

    @Test
    @DisplayName("中文 + 空格路径：start() 返回 POPULATED、loaded / started 含 stats、无失败")
    void loadsAndStartsStatsFromChineseSpacePath() {
        assertThat(status.state()).isEqualTo(PluginDirectoryState.POPULATED);
        assertThat(status.loadedPluginIds()).contains("stats");
        assertThat(status.startedPluginIds()).contains("stats");
        assertThat(status.failures()).isEmpty();
    }

    @Test
    @DisplayName("中文 + 空格路径：清点条目 STARTED，其外置 classloader 仍能解析插件内静态资源")
    void inventoryClassLoaderResolvesResourcesFromChineseSpacePath() {
        PluginInventory inventory = manager.inspectPlugins();
        assertThat(inventory.failures()).isEmpty();

        PluginInstallation stats = inventory.installations().stream()
                .filter(installation -> installation.id().equals("stats"))
                .findFirst().orElseThrow();
        assertThat(stats.status()).isEqualTo(PluginStatus.STARTED);

        ClassLoader externalCl = stats.classLoader();
        assertThat(externalCl).isNotSameAs(getClass().getClassLoader());
        // classloader 的 jar URL 编解码在含空格 / 非 ASCII 的路径下仍正确：能解析插件内静态资源与 i18n。
        assertThat(externalCl.getResource("static/pixiv-stats/pixiv-stats.css")).isNotNull();
        assertThat(externalCl.getResource("i18n/web/stats.properties")).isNotNull();
    }

    @Test
    @DisplayName("中文 + 空格路径：发现桥接把 stats 识别为可接入功能插件")
    void discoversStatsFromChineseSpacePath() {
        PluginDiscoveryResult discovery = manager.discoverFeaturePlugins();
        assertThat(discovery.hasFailures()).isFalse();
        assertThat(discovery.discovered())
                .extracting(DiscoveredFeaturePlugin::featurePluginId).contains("stats");
        PixivFeaturePlugin stats = discovery.discovered().stream()
                .filter(d -> d.featurePluginId().equals("stats"))
                .findFirst().orElseThrow().plugin();
        assertThat(stats.id()).isEqualTo("stats");
    }

    // --- helpers ---

    private static Path locateStatsClasses() {
        String configured = System.getProperty(STATS_CLASSES_PROPERTY);
        return (configured == null || configured.isBlank()) ? null : Path.of(configured);
    }

    /** 把一个目录（stats 的 {@code target/classes}，根部已含 {@code plugin.properties}）打成 PF4J 可加载的 jar。 */
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
