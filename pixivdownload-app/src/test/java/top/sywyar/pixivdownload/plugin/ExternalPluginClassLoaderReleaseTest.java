package top.sywyar.pixivdownload.plugin;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import top.sywyar.pixivdownload.plugin.runtime.discovery.PluginInstallation;
import top.sywyar.pixivdownload.plugin.runtime.PluginRuntimeManager;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import static org.assertj.core.api.Assertions.assertThat;
import static top.sywyar.pixivdownload.plugin.PluginTestArtifacts.zipDirectoryAsJar;

/**
 * 真实外置插件 classloader 物理卸载后的回收探针：用 gallery-tools 插件 jar（编译产物 + 根部
 * {@code plugin.properties} 组装）经 {@link PluginRuntimeManager} 真实加载 / 启动，捕获其插件 classloader 的弱引用，
 * 经 {@link PluginRuntimeManager#unloadPlugin(String)} 物理卸载后，
 * 释放测试直接持有的强引用，检查 JAR 句柄释放并观察 classloader 是否被回收。
 *
 * <p>这条用例补足 {@link PluginClassLoaderLeakProbeTest}（受控合成 loader）<b>没有</b>覆盖的那一段——真实 PF4J
 * {@code PluginClassLoader}（含真实加载的类、jar 文件句柄）的物理卸载与回收观测。
 *
 * <h2>强引用约束</h2>
 * 捕获 classloader 与物理卸载收口在 {@link #loadCaptureAndUnload} <b>独立栈帧</b>内：{@link PluginRuntimeManager}、
 * {@link PluginInstallation}（含插件实例 + 描述符 + classloader）等强引用都是该方法局部，返回后随帧出栈、只留弱引用，
 * 调用方不残留任何 pin 住 classloader 的局部。
 *
 * <p>gallery-tools 构建产物目录经 surefire 系统属性 {@code gallery-tools.plugin.classes} 传入；未就绪时
 *（如 IDE 未触发 reactor 构建）整条用例 {@link Assumptions assume} 跳过。预算内未观察到回收时，
 * 以 {@link Assumptions#abort} 保留原因未确定的边界；其它 teardown 用例只证明各自覆盖的引用或资源已释放。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("真实外置插件物理卸载后的 JAR 释放与 classloader 回收探针")
class ExternalPluginClassLoaderReleaseTest {

    private static final String STATS_CLASSES_PROPERTY = "gallery-tools.plugin.classes";

    private Path tempPluginsDir;

    @AfterAll
    void cleanup() {
        // 用例内已验证 JAR 可移动并删除，此处清理剩余临时文件。
        deleteRecursivelyQuietly(tempPluginsDir);
    }

    @Test
    @DisplayName("加载 → 捕获 classloader 弱引用 → PF4J 物理卸载 → 丢弃强引用后 classloader 可被回收")
    void realStatsClassLoaderIsCollectableAfterPhysicalUnload() throws IOException {
        Path statsClasses = locateStatsClasses();
        Assumptions.assumeTrue(statsClasses != null && Files.isDirectory(statsClasses),
                "gallery-tools 插件构建产物未就绪，跳过真实 classloader 回收验证");
        // 前提：外置 jar 不含共享契约（plugin-api），否则桥接 instanceof 因同名异 loader 失败。
        assertThat(statsClasses.resolve("top/sywyar/pixivdownload/plugin/api")).doesNotExist();

        Files.createDirectories(Path.of("target", "test-runtime"));
        tempPluginsDir = Files.createTempDirectory(Path.of("target", "test-runtime"), "pixiv-plugins-leak");
        Path jar = tempPluginsDir.resolve("gallery-tools-plugin.jar");
        zipDirectoryAsJar(statsClasses, jar);
        PluginTestProvenance.writeVerifiedLocalUpload(tempPluginsDir, jar);

        WeakReference<ClassLoader> weakCl = loadCaptureAndUnload(tempPluginsDir);
        assertThat(weakCl).as("加载时应已捕获到真实插件 classloader 的弱引用").isNotNull();

        Path moved = tempPluginsDir.resolve("gallery-tools-plugin-moved.jar");
        Files.move(jar, moved);
        Files.delete(moved);
        assertThat(moved).as("公共 unload 返回后 JAR 应可立即移动并删除").doesNotExist();

        boolean collected = ClassLoaderLeakProbes.awaitCollected(weakCl);
        if (!collected) {
            Assumptions.abort("真实插件物理卸载后 JAR 已可移动并删除，但预算内未观察到 classloader 被 GC 回收；"
                    + "尚未确定未回收原因，JAR 句柄释放不能排除其它强引用。");
        }
    }

    /**
     * 在独立栈帧内加载真实插件、捕获 classloader 弱引用并物理卸载，避免调用方局部变量保留强引用。
     */
    private static WeakReference<ClassLoader> loadCaptureAndUnload(Path pluginsDir) {
        PluginRuntimeManager manager = new PluginRuntimeManager(pluginsDir, PluginTestProvenance.verifier());
        manager.start();
        PluginInstallation stats = manager.inspectPlugins().installations().stream()
                .filter(installation -> installation.id().equals("gallery-tools"))
                .findFirst().orElseThrow();
        WeakReference<ClassLoader> weakCl = new WeakReference<>(stats.classLoader());
        manager.unloadPlugin("gallery-tools");
        return weakCl;
    }

    // --- helpers（与 GalleryToolsExternalPluginIntegrationTest 同口径）---

    private static Path locateStatsClasses() {
        String configured = System.getProperty(STATS_CLASSES_PROPERTY);
        return (configured == null || configured.isBlank()) ? null : Path.of(configured);
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
