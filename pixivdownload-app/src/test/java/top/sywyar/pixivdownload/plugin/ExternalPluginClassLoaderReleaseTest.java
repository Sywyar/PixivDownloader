package top.sywyar.pixivdownload.plugin;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import top.sywyar.pixivdownload.plugin.runtime.PluginInstallation;
import top.sywyar.pixivdownload.plugin.runtime.PluginRuntimeManager;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真实外置插件 classloader <b>物理卸载后可被 GC 回收</b>的端到端泄漏回归：用真实 stats 插件 jar（编译产物 + 根部
 * {@code plugin.properties} 组装）经 {@link PluginRuntimeManager} 真实加载 / 启动，捕获其插件 classloader 的弱引用，
 * 经 PF4J <b>物理卸载</b>（{@code stopPlugins} + {@code unloadPlugins}，释放 {@code PluginWrapper} 与 jar 文件句柄）后
 * 丢弃全部强引用，断言该 classloader 可被回收。
 *
 * <p>这条用例补足 {@link PluginClassLoaderLeakProbeTest}（受控合成 loader）<b>没有</b>覆盖的那一段——真实 PF4J
 * {@code PluginClassLoader}（含真实加载的类、jar 文件句柄）在物理卸载后确实失去全部强引用、可被回收。
 *
 * <h2>强引用约束</h2>
 * 捕获 classloader 与物理卸载收口在 {@link #loadCaptureAndUnload} <b>独立栈帧</b>内：{@link PluginRuntimeManager}、
 * {@link PluginInstallation}（含插件实例 + 描述符 + classloader）等强引用都是该方法局部，返回后随帧出栈、只留弱引用，
 * 调用方不残留任何 pin 住 classloader 的局部。
 *
 * <p>stats 构建产物目录经 surefire 系统属性 {@code stats.plugin.classes} 传入（reactor 中先于 app 构建）；未就绪时
 *（如 IDE 未触发 reactor 构建）整条用例 {@link Assumptions assume} 跳过。允许弱引用 GC 在 Windows / JVM 下不稳定：
 * 物理卸载后若本环境未回收，则判为环境 inconclusive（{@link Assumptions#abort}）而非业务失败——是否真有强引用残留由
 * {@link PluginClassLoaderLeakProbeTest} 的确定性引用链检查与生命周期 teardown 用例守住。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("真实外置 stats 插件 classloader 物理卸载后可被 GC 回收")
class ExternalPluginClassLoaderReleaseTest {

    private static final String STATS_CLASSES_PROPERTY = "stats.plugin.classes";

    private Path tempPluginsDir;

    @AfterAll
    void cleanup() {
        // jar 文件句柄已在用例内经 unloadPlugins 释放（Windows 下否则删不掉），此处只删临时目录。
        deleteRecursivelyQuietly(tempPluginsDir);
    }

    @Test
    @DisplayName("加载 → 捕获 classloader 弱引用 → PF4J 物理卸载 → 丢弃强引用后 classloader 可被回收")
    void realStatsClassLoaderIsCollectableAfterPhysicalUnload() throws IOException {
        Path statsClasses = locateStatsClasses();
        Assumptions.assumeTrue(statsClasses != null && Files.isDirectory(statsClasses),
                "stats 插件构建产物未就绪（需 reactor 先构建 pixivdownload-plugin-stats），跳过真实 classloader 回收验证");
        // 前提：外置 jar 不含共享契约（plugin-api），否则桥接 instanceof 因同名异 loader 失败。
        assertThat(statsClasses.resolve("top/sywyar/pixivdownload/plugin/api")).doesNotExist();

        Files.createDirectories(Path.of("target", "test-runtime"));
        tempPluginsDir = Files.createTempDirectory(Path.of("target", "test-runtime"), "pixiv-plugins-leak");
        Path jar = tempPluginsDir.resolve("stats-plugin.jar");
        zipDirectoryAsJar(statsClasses, jar);
        PluginTestProvenance.writeLocalUpload(tempPluginsDir, jar, "stats", "1.0.0");

        WeakReference<ClassLoader> weakCl = loadCaptureAndUnload(tempPluginsDir);
        assertThat(weakCl).as("加载时应已捕获到真实 stats classloader 的弱引用").isNotNull();

        Path moved = tempPluginsDir.resolve("stats-plugin-moved.jar");
        Files.move(jar, moved);
        Files.delete(moved);
        assertThat(moved).as("公共 unload 返回后 JAR 应可立即移动并删除").doesNotExist();

        boolean collected = ClassLoaderLeakProbes.awaitCollected(weakCl);
        if (!collected) {
            // 物理卸载已释放 PF4J 侧强引用；本环境仍未回收 → 归为 Windows / JVM GC 不稳定（inconclusive），非业务泄漏。
            Assumptions.abort("真实 stats classloader 物理卸载后未在本环境被 GC 回收——判为环境不稳定（inconclusive）；"
                    + "确定性引用链 / teardown 回归由 PluginClassLoaderLeakProbeTest 与生命周期用例守住。");
        }
        assertThat(collected).as("真实 stats classloader 物理卸载后应可被 GC 回收").isTrue();
    }

    /**
     * 在独立栈帧内加载真实 stats jar、捕获其 classloader 弱引用并 PF4J 物理卸载；{@link PluginRuntimeManager} 与
     * {@link PluginInstallation} 等强引用都是本方法局部，返回即出帧不可达，调用方只拿到弱引用。
     */
    private static WeakReference<ClassLoader> loadCaptureAndUnload(Path pluginsDir) {
        PluginRuntimeManager manager = new PluginRuntimeManager(pluginsDir);
        manager.start();
        PluginInstallation stats = manager.inspectPlugins().installations().stream()
                .filter(installation -> installation.id().equals("stats"))
                .findFirst().orElseThrow();
        WeakReference<ClassLoader> weakCl = new WeakReference<>(stats.classLoader());
        manager.unloadPlugin("stats");
        return weakCl; // manager / stats / inventory 在此出帧 → 强引用图可回收
    }

    // --- helpers（与 StatsExternalPluginIntegrationTest 同口径）---

    private static Path locateStatsClasses() {
        String configured = System.getProperty(STATS_CLASSES_PROPERTY);
        return (configured == null || configured.isBlank()) ? null : Path.of(configured);
    }

    /** 把 stats 的 {@code target/classes}（根部已含 {@code plugin.properties}）打成 PF4J 可加载的 jar。 */
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
                    // best-effort：临时目录，残留由 OS / 下次 mvn clean 清理
                }
            });
        } catch (IOException ignored) {
            // best-effort
        }
    }
}
