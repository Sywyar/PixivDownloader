package top.sywyar.pixivdownload.plugin;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 插件分发打包边界守卫：把「主程序 boot jar 只含核心 + 内置插件、外置插件单独分发」这一发布形态不变量
 * 固化为可重复的自动化检查，供分发 / 打包流程依赖。三条互补验证：
 * <ol>
 *   <li><b>boot jar 不含外置插件类与资源</b>——经「运行期类路径不可加载」实证：app 模块测试运行期的类路径即 boot jar
 *       的 {@code BOOT-INF/classes} + {@code BOOT-INF/lib}（去掉 test 作用域），故外置插件 {@code stats} /
 *       {@code recovery-sentinel} 的类不可加载，即可证明它们不在 boot jar 内；同时正向断言内置下载工作台插件类、
 *       宿主 PF4J 运行时可加载、核心静态资源在位（非空泛断言）；外置插件的静态资源 / i18n 经核心壳 classloader 解析不到。</li>
 *   <li><b>{@code stats} 以 thin 外置插件形态打包</b>——其构建产物根部含 {@code plugin.properties} + 外置主类，
 *       且不泄漏共享契约 / 宿主类；若 Maven 已产出真实插件 jar，再断言 jar 内无 {@code BOOT-INF/}、无打入的
 *       {@code org/pf4j/}、{@code org/springframework/} 框架类（依赖均 provided）。</li>
 *   <li><b>{@code recovery-sentinel} 同样以 thin 外置插件形态打包</b>。</li>
 *   <li><b>{@code gui-theme} 以 PF4J 解压目录 ZIP 打包</b>——根 {@code plugin.properties}、
 *       {@code classes/} 与 {@code lib/*.jar} 在位，FlatLaf / IntelliJ Themes / JNA 仅在 ZIP 的 {@code lib/} 中，
 *       并通过独立 classloader 真实加载。</li>
 * </ol>
 *
 * <p>插件构建产物目录经 surefire 系统属性 {@code stats.plugin.classes} / {@code recovery-sentinel.plugin.classes}
 * 传入（指向各插件模块 {@code target/classes}，reactor 中先于 app 构建）；未就绪时（如 IDE 未触发 reactor 构建）
 * 对应用例 {@link Assumptions assume} 跳过。真实插件 jar（{@code target/<artifactId>-*.jar}）仅在 {@code package}
 * 阶段后存在——存在即追加更强的 thin 不变量断言，缺失时不跳过、仅不追加，故 {@code test} 阶段照常运行。
 */
@DisplayName("插件分发打包边界：boot jar 不含外置插件类、外置插件独立产物形态")
class DistributionPackagingBoundaryTest {

    private static final String STATS_CLASSES_PROPERTY = "stats.plugin.classes";
    private static final String SENTINEL_CLASSES_PROPERTY = "recovery-sentinel.plugin.classes";
    private static final String GUI_THEME_CLASSES_PROPERTY = "gui-theme.plugin.classes";
    private static final String GUI_THEME_ZIP_PROPERTY = "gui-theme.plugin.zip";

    @Test
    @DisplayName("boot jar 运行期类路径含内置下载工作台与宿主 PF4J，但不含外置插件 stats / recovery-sentinel 的类与资源")
    void bootJarExcludesExternalPluginClassesAndResources() {
        ClassLoader host = getClass().getClassLoader();

        // 正向（非空泛）：内置下载工作台插件随 boot jar 编译进来、宿主提供 PF4J 运行时、核心静态资源在位。
        assertThat(canLoad(host, "top.sywyar.pixivdownload.download.DownloadWorkbenchPlugin"))
                .as("内置下载工作台插件类应在 boot jar 内").isTrue();
        assertThat(canLoad(host, "org.pf4j.PluginManager"))
                .as("宿主应随 boot jar 提供 PF4J 运行时").isTrue();
        assertThat(host.getResource("static/favicon.ico"))
                .as("核心静态资源应在 boot jar 内").isNotNull();

        // 反向：外置插件的主类绝不在核心壳运行期类路径（= boot jar）内。
        assertThat(canLoad(host, "top.sywyar.pixivdownload.stats.StatsPf4jPlugin"))
                .as("外置 stats 插件类不应在 boot jar 内").isFalse();
        assertThat(canLoad(host, "top.sywyar.pixivdownload.stats.StatsPlugin"))
                .as("外置 stats 插件类不应在 boot jar 内").isFalse();
        assertThat(canLoad(host, "top.sywyar.pixivdownload.recoverysentinel.RecoverySentinelPf4jPlugin"))
                .as("外置 recovery-sentinel 插件类不应在 boot jar 内").isFalse();
        assertThat(canLoad(host, "top.sywyar.pixivdownload.guitheme.GuiThemePf4jPlugin"))
                .as("外置 gui-theme 插件类不应在 boot jar 内").isFalse();
        assertThat(canLoad(host, "com.formdev.flatlaf.FlatLaf"))
                .as("FlatLaf 不应在 app boot jar 运行期类路径内").isFalse();
        assertThat(canLoad(host, "com.sun.jna.Native"))
                .as("JNA 不应在 app boot jar 运行期类路径内").isFalse();

        // 外置插件的静态资源 / i18n 只随其自身 thin jar 携带，核心壳 classloader 解析不到。
        assertThat(host.getResource("static/pixiv-stats/pixiv-stats.css"))
                .as("stats 静态资源不应在 boot jar 内").isNull();
        assertThat(host.getResource("i18n/web/stats.properties"))
                .as("stats i18n 资源不应在 boot jar 内").isNull();
        assertThat(host.getResource("i18n/web/gui-theme.properties"))
                .as("gui-theme i18n 资源不应在 boot jar 内").isNull();
    }

    @Test
    @DisplayName("stats 以 thin 外置插件形态打包：根部 plugin.properties + 外置主类，无契约 / 宿主 / 框架类泄漏")
    void statsPackagesAsThinExternalPlugin() {
        assertThinExternalPlugin(STATS_CLASSES_PROPERTY, "pixivdownload-plugin-stats",
                "top/sywyar/pixivdownload/stats/StatsPf4jPlugin.class");
    }

    @Test
    @DisplayName("recovery-sentinel 以 thin 外置插件形态打包：根部 plugin.properties + 外置主类，无契约 / 宿主 / 框架类泄漏")
    void recoverySentinelPackagesAsThinExternalPlugin() {
        assertThinExternalPlugin(SENTINEL_CLASSES_PROPERTY, "pixivdownload-plugin-recovery-sentinel",
                "top/sywyar/pixivdownload/recoverysentinel/RecoverySentinelPf4jPlugin.class");
    }

    @Test
    @DisplayName("gui-theme 以 PF4J 解压目录 zip 形态打包：根 descriptor + classes/ + lib/*.jar")
    void guiThemePackagesAsExplodedDirectoryZip(@TempDir Path tempDir) {
        Path classesDir = locateConfiguredDir(GUI_THEME_CLASSES_PROPERTY);
        Assumptions.assumeTrue(classesDir != null && Files.isDirectory(classesDir),
                "插件构建产物未就绪（需 reactor 先构建 pixivdownload-plugin-gui-theme），跳过 zip 形态验证");

        assertThat(classesDir.resolve("plugin.properties"))
                .as("主题插件构建产物根部应含 plugin.properties").exists();
        assertThat(classesDir.resolve("top/sywyar/pixivdownload/guitheme/GuiThemePf4jPlugin.class"))
                .as("主题插件构建产物应含外置主类").exists();
        assertThat(classesDir.resolve("top/sywyar/pixivdownload/plugin/api"))
                .as("主题插件不得打入共享契约 plugin-api").doesNotExist();
        assertThat(classesDir.resolve("top/sywyar/pixivdownload/gui/theme/GuiThemeManager.class"))
                .as("主题插件不得打入 app 核心主题管理类").doesNotExist();

        Path zip = locateConfiguredZip(classesDir);
        if (zip == null) {
            return;
        }
        List<String> entries = jarEntryNames(zip);
        assertThat(entries).contains("plugin.properties");
        assertThat(entries).contains("classes/top/sywyar/pixivdownload/guitheme/GuiThemePf4jPlugin.class");
        assertThat(entries).as("解压目录 zip 不得包含根插件 jar").noneMatch(name -> name.matches("[^/]+\\.jar"));
        assertThat(entries).as("FlatLaf 必须只在 theme zip 的 lib/ 中")
                .anyMatch(name -> name.matches("lib/flatlaf-[^/]+\\.jar"));
        assertThat(entries).as("IntelliJ Themes 必须只在 theme zip 的 lib/ 中")
                .anyMatch(name -> name.matches("lib/flatlaf-intellij-themes-[^/]+\\.jar"));
        assertThat(entries).as("JNA 必须只在 theme zip 的 lib/ 中")
                .anyMatch(name -> name.matches("lib/jna-[^/]+\\.jar"));
        assertThat(entries).as("JNA Platform 必须只在 theme zip 的 lib/ 中")
                .anyMatch(name -> name.matches("lib/jna-platform-[^/]+\\.jar"));
        assertThat(entries).noneMatch(name -> name.startsWith("BOOT-INF/"));
        assertThat(entries).noneMatch(name -> name.startsWith("classes/top/sywyar/pixivdownload/plugin/api/"));

        assertGuiThemeZipLoadsWithPluginClassLoader(zip, tempDir);
    }

    // --- 验证 thin 外置插件形态：先据构建 classes 目录，jar 存在时再据真实 jar 追加更强断言 ---

    private void assertThinExternalPlugin(String classesProperty, String artifactId, String mainClassEntry) {
        Path classesDir = locateConfiguredDir(classesProperty);
        Assumptions.assumeTrue(classesDir != null && Files.isDirectory(classesDir),
                () -> "插件构建产物未就绪（需 reactor 先构建 " + artifactId + "），跳过 thin 形态验证");

        // 构建 classes 根部：描述符 + 外置主类在位；不泄漏共享契约（plugin-api）与宿主组合根类。
        assertThat(classesDir.resolve("plugin.properties"))
                .as("插件构建产物根部应含 plugin.properties").exists();
        assertThat(classesDir.resolve(mainClassEntry))
                .as("插件构建产物应含外置主类").exists();
        assertThat(classesDir.resolve("top/sywyar/pixivdownload/plugin/api"))
                .as("外置插件不得打入共享契约 plugin-api（同名异 loader 会令桥接 instanceof 失败）").doesNotExist();
        assertThat(classesDir.resolve("top/sywyar/pixivdownload/plugin/BuiltInPlugins.class"))
                .as("外置插件不得打入宿主组合根类").doesNotExist();

        // 真实插件 jar 仅在 package 阶段后存在：存在即追加 thin 不变量（根 plugin.properties、无 BOOT-INF、无打入框架类）。
        Path jar = locateModuleJar(classesDir, artifactId);
        if (jar == null) {
            return;
        }
        List<String> entries = jarEntryNames(jar);
        assertThat(entries).as("thin 插件 jar 根部应含 plugin.properties").contains("plugin.properties");
        assertThat(entries).as("thin 插件 jar 应含外置主类").contains(mainClassEntry);
        assertThat(entries).as("thin 插件 jar 不得是 Spring Boot 可执行 jar（无 BOOT-INF/）")
                .noneMatch(name -> name.startsWith("BOOT-INF/"));
        assertThat(entries).as("thin 插件 jar 不得打入 PF4J（provided，宿主提供）")
                .noneMatch(name -> name.startsWith("org/pf4j/"));
        assertThat(entries).as("thin 插件 jar 不得打入 Spring（provided，宿主提供）")
                .noneMatch(name -> name.startsWith("org/springframework/"));
        assertThat(entries).as("thin 插件 jar 不得打入共享契约 plugin-api")
                .noneMatch(name -> name.startsWith("top/sywyar/pixivdownload/plugin/api/"));
    }

    // --- helpers ---

    private static boolean canLoad(ClassLoader loader, String className) {
        try {
            Class.forName(className, false, loader);
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    private static Path locateConfiguredDir(String property) {
        String configured = System.getProperty(property);
        return (configured == null || configured.isBlank()) ? null : Path.of(configured);
    }

    private static Path locateConfiguredZip(Path classesDir) {
        String configured = System.getProperty(GUI_THEME_ZIP_PROPERTY);
        if (configured != null && !configured.isBlank() && Files.isRegularFile(Path.of(configured))) {
            return Path.of(configured);
        }
        Path targetDir = classesDir.getParent();
        if (targetDir == null || !Files.isDirectory(targetDir)) {
            return null;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(
                targetDir, "pixivdownload-plugin-gui-theme-*.zip")) {
            for (Path candidate : stream) {
                return candidate;
            }
        } catch (IOException ignored) {
            // package 阶段之前 zip 不存在，test 阶段只跳过更强断言。
        }
        return null;
    }

    /** 在插件模块 {@code target/} 下定位真实插件 jar（{@code <artifactId>-*.jar}，排除 sources / javadoc）；缺失返回 null。 */
    private static Path locateModuleJar(Path classesDir, String artifactId) {
        Path targetDir = classesDir.getParent();
        if (targetDir == null || !Files.isDirectory(targetDir)) {
            return null;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(targetDir, artifactId + "-*.jar")) {
            for (Path candidate : stream) {
                String name = candidate.getFileName().toString();
                if (name.endsWith("-sources.jar") || name.endsWith("-javadoc.jar")) {
                    continue;
                }
                return candidate;
            }
        } catch (IOException ignored) {
            // best-effort：定位失败按缺失处理（仅放弃更强的 jar 级断言，不致测试失败）
        }
        return null;
    }

    private static List<String> jarEntryNames(Path jar) {
        List<String> names = new ArrayList<>();
        try (JarFile jarFile = new JarFile(jar.toFile())) {
            var entries = jarFile.entries();
            while (entries.hasMoreElements()) {
                names.add(entries.nextElement().getName());
            }
        } catch (IOException e) {
            throw new IllegalStateException("无法读取插件 jar: " + jar, e);
        }
        return names;
    }

    private static void assertGuiThemeZipLoadsWithPluginClassLoader(Path zip, Path tempDir) {
        Path exploded = tempDir.resolve("gui-theme");
        extractZip(zip, exploded);

        List<URL> urls = new ArrayList<>();
        try {
            urls.add(exploded.resolve("classes").toUri().toURL());
            try (DirectoryStream<Path> libs = Files.newDirectoryStream(exploded.resolve("lib"), "*.jar")) {
                for (Path lib : libs) {
                    urls.add(lib.toUri().toURL());
                }
            }
            try (URLClassLoader loader = new URLClassLoader(urls.toArray(URL[]::new),
                    DistributionPackagingBoundaryTest.class.getClassLoader())) {
                Class<?> plugin = Class.forName(
                        "top.sywyar.pixivdownload.guitheme.GuiThemePf4jPlugin", false, loader);
                Class<?> flatLaf = Class.forName("com.formdev.flatlaf.FlatLaf", false, loader);
                Class<?> jna = Class.forName("com.sun.jna.Native", false, loader);

                assertThat(plugin.getClassLoader()).as("主题插件主类应由插件 classloader 加载").isSameAs(loader);
                assertThat(flatLaf.getClassLoader()).as("FlatLaf 应由主题插件 classloader 的 lib/ 加载").isSameAs(loader);
                assertThat(jna.getClassLoader()).as("JNA 应由主题插件 classloader 的 lib/ 加载").isSameAs(loader);
            }
        } catch (IOException | ReflectiveOperationException e) {
            throw new IllegalStateException("无法通过主题插件 ZIP classloader 加载类: " + zip, e);
        }
    }

    private static void extractZip(Path zip, Path targetDir) {
        try (JarFile jarFile = new JarFile(zip.toFile())) {
            var entries = jarFile.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                Path output = targetDir.resolve(entry.getName()).normalize();
                if (!output.startsWith(targetDir)) {
                    throw new IllegalStateException("插件 ZIP 含非法路径: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(output);
                    continue;
                }
                Files.createDirectories(output.getParent());
                try (InputStream in = jarFile.getInputStream(entry)) {
                    Files.copy(in, output);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("无法解压主题插件 ZIP: " + zip, e);
        }
    }
}
