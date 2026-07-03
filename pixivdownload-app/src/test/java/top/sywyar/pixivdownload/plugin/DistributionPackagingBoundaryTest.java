package top.sywyar.pixivdownload.plugin;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.sywyar.pixivdownload.plugin.api.gui.GuiThemeContribution;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;

import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JRootPane;
import javax.swing.LookAndFeel;
import javax.swing.SwingUtilities;
import javax.swing.UIDefaults;
import javax.swing.UIManager;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 插件分发打包边界守卫：把「主程序 boot jar 只含核心 + 内置插件、外置插件单独分发」这一发布形态不变量
 * 固化为可重复的自动化检查，供分发 / 打包流程依赖。三条互补验证：
 * <ol>
 *   <li><b>boot jar 不含外置插件类与资源</b>——经「运行期类路径不可加载」实证：app 模块测试运行期的类路径即 boot jar
 *       的 {@code BOOT-INF/classes} + {@code BOOT-INF/lib}（去掉 test 作用域），故外置插件 {@code download-workbench} /
 *       {@code stats} / {@code duplicate} / {@code recovery-sentinel} 的类不可加载，即可证明它们不在 boot jar 内；同时正向断言
 *       宿主 PF4J 运行时可加载、核心静态资源在位（非空泛断言）；外置插件的静态资源 / i18n 经核心壳 classloader 解析不到。</li>
 *   <li><b>{@code stats} 以 thin 外置插件形态打包</b>——其构建产物根部含 {@code plugin.properties} + 外置主类，
 *       且不泄漏共享契约 / 宿主类；若 Maven 已产出真实插件 jar，再断言 jar 内无 {@code BOOT-INF/}、无打入的
 *       {@code org/pf4j/}、{@code org/springframework/} 框架类（依赖均 provided）。</li>
 *   <li><b>{@code recovery-sentinel} 同样以 thin 外置插件形态打包</b>。</li>
 *   <li><b>{@code gui-theme} 以 PF4J JAR-with-lib 打包</b>——根 {@code plugin.properties}、
 *       标准 JAR 类路径与 {@code lib/*.jar} 在位，FlatLaf / IntelliJ Themes / JNA 仅在 JAR 的 {@code lib/} 中，
 *       并通过模拟 runtime materialization 后的独立 classloader 真实加载。</li>
 * </ol>
 *
 * <p>插件构建产物目录经 surefire 系统属性 {@code stats.plugin.classes} /
 * {@code duplicate.plugin.classes} / {@code recovery-sentinel.plugin.classes} / {@code gui-theme.plugin.classes} 传入（指向各插件模块
 * {@code target/classes}，reactor 中先于 app 构建）；未就绪时（如 IDE 未触发 reactor 构建）对应用例
 * {@link Assumptions assume} 跳过。真实插件 jar（{@code target/<artifactId>-*.jar}，gui-theme 可由
 * {@code gui-theme.plugin.jar} 指定）仅在 {@code package} 阶段后存在——存在即追加更强的 artifact 不变量断言，
 * 缺失时不跳过、仅不追加，故 {@code test} 阶段照常运行。
 */
@DisplayName("插件分发打包边界：boot jar 不含外置插件类、外置插件独立产物形态")
class DistributionPackagingBoundaryTest {

    private static final String DOWNLOAD_WORKBENCH_CLASSES_PROPERTY = "download-workbench.plugin.classes";
    private static final String STATS_CLASSES_PROPERTY = "stats.plugin.classes";
    private static final String DUPLICATE_CLASSES_PROPERTY = "duplicate.plugin.classes";
    private static final String NOTIFICATION_CLASSES_PROPERTY = "notification.plugin.classes";
    private static final String PUSH_CLASSES_PROPERTY = "push.plugin.classes";
    private static final String MAIL_CLASSES_PROPERTY = "mail.plugin.classes";
    private static final String TTS_CLASSES_PROPERTY = "tts.plugin.classes";
    private static final String AI_CLASSES_PROPERTY = "ai.plugin.classes";
    private static final String SENTINEL_CLASSES_PROPERTY = "recovery-sentinel.plugin.classes";
    private static final String GUI_THEME_CLASSES_PROPERTY = "gui-theme.plugin.classes";
    private static final String GUI_THEME_JAR_PROPERTY = "gui-theme.plugin.jar";

    @Test
    @DisplayName("boot jar 运行期类路径含宿主 PF4J，但不含外置 download-workbench / stats / duplicate / recovery-sentinel 的类与资源")
    void bootJarExcludesExternalPluginClassesAndResources() {
        ClassLoader host = getClass().getClassLoader();

        // 正向（非空泛）：宿主提供 PF4J 运行时、核心静态资源在位。
        assertThat(canLoad(host, "org.pf4j.PluginManager"))
                .as("宿主应随 boot jar 提供 PF4J 运行时").isTrue();
        assertThat(host.getResource("static/favicon.ico"))
                .as("核心静态资源应在 boot jar 内").isNotNull();

        // 反向：外置插件的主类绝不在核心壳运行期类路径（= boot jar）内。
        assertThat(canLoad(host, "top.sywyar.pixivdownload.download.DownloadWorkbenchPf4jPlugin"))
                .as("外置 download-workbench 插件主类不应在 boot jar 内").isFalse();
        assertThat(canLoad(host, "top.sywyar.pixivdownload.download.DownloadWorkbenchPlugin"))
                .as("外置 download-workbench 插件类不应在 boot jar 内").isFalse();
        assertThat(canLoad(host, "top.sywyar.pixivdownload.stats.StatsPf4jPlugin"))
                .as("外置 stats 插件类不应在 boot jar 内").isFalse();
        assertThat(canLoad(host, "top.sywyar.pixivdownload.stats.StatsPlugin"))
                .as("外置 stats 插件类不应在 boot jar 内").isFalse();
        assertThat(canLoad(host, "top.sywyar.pixivdownload.duplicate.DuplicatePf4jPlugin"))
                .as("外置 duplicate 插件主类不应在 boot jar 内").isFalse();
        assertThat(canLoad(host, "top.sywyar.pixivdownload.duplicate.DuplicatePlugin"))
                .as("外置 duplicate 插件类不应在 boot jar 内").isFalse();
        assertThat(canLoad(host, "top.sywyar.pixivdownload.recoverysentinel.RecoverySentinelPf4jPlugin"))
                .as("外置 recovery-sentinel 插件类不应在 boot jar 内").isFalse();
        assertThat(canLoad(host, "top.sywyar.pixivdownload.guitheme.GuiThemePf4jPlugin"))
                .as("外置 gui-theme 插件类不应在 boot jar 内").isFalse();
        assertThat(canLoad(host, "top.sywyar.pixivdownload.notificationbase.NotificationPf4jPlugin"))
                .as("外置 notification 插件类不应在 boot jar 内").isFalse();
        assertThat(canLoad(host, "top.sywyar.pixivdownload.push.PushPf4jPlugin"))
                .as("外置 push 插件类不应在 boot jar 内").isFalse();
        assertThat(canLoad(host, "top.sywyar.pixivdownload.mail.MailPf4jPlugin"))
                .as("外置 mail 插件类不应在 boot jar 内").isFalse();
        assertThat(canLoad(host, "top.sywyar.pixivdownload.tts.TtsPf4jPlugin"))
                .as("外置 tts 插件类不应在 boot jar 内").isFalse();
        assertThat(canLoad(host, "top.sywyar.pixivdownload.ai.AiPf4jPlugin"))
                .as("外置 ai 插件类不应在 boot jar 内").isFalse();
        assertThat(canLoad(host, "com.formdev.flatlaf.FlatLaf"))
                .as("FlatLaf 不应在 app boot jar 运行期类路径内").isFalse();
        assertThat(canLoad(host, "com.sun.jna.Native"))
                .as("JNA 不应在 app boot jar 运行期类路径内").isFalse();
        assertThat(canLoad(host, "jakarta.mail.Session"))
                .as("Jakarta Mail 不应在 app boot jar 运行期类路径内").isFalse();
        assertThat(canLoad(host, "org.springframework.mail.javamail.JavaMailSenderImpl"))
                .as("spring-context-support 邮件实现不应在 app boot jar 运行期类路径内").isFalse();

        // 外置插件的静态资源 / i18n 只随其自身 thin jar 携带，核心壳 classloader 解析不到。
        assertThat(host.getResource("static/pixiv-batch.html"))
                .as("download-workbench 下载页不应在 boot jar 内").isNull();
        assertThat(host.getResource("static/pixiv-batch/pixiv-batch.css"))
                .as("download-workbench 下载页静态资源不应在 boot jar 内").isNull();
        assertThat(host.getResource("static/userscripts/Pixiv 页面批量下载器(Page Scrape).user.js"))
                .as("download-workbench userscript 不应在 boot jar 内").isNull();
        assertThat(host.getResource("i18n/web/batch.properties"))
                .as("download-workbench i18n 不应在 boot jar 内").isNull();
        assertThat(host.getResource("i18n/web/userscript.properties"))
                .as("download-workbench userscript i18n 不应在 boot jar 内").isNull();
        assertThat(host.getResource("static/pixiv-stats/pixiv-stats.css"))
                .as("stats 静态资源不应在 boot jar 内").isNull();
        assertThat(host.getResource("i18n/web/stats.properties"))
                .as("stats i18n 资源不应在 boot jar 内").isNull();
        assertThat(host.getResource("static/pixiv-duplicates.html"))
                .as("duplicate 页面不应在 boot jar 内").isNull();
        assertThat(host.getResource("static/pixiv-duplicates/pixiv-duplicates.css"))
                .as("duplicate 静态资源不应在 boot jar 内").isNull();
        assertThat(host.getResource("i18n/web/duplicates.properties"))
                .as("duplicate i18n 资源不应在 boot jar 内").isNull();
        assertThat(host.getResource("i18n/web/gui-theme.properties"))
                .as("gui-theme i18n 资源不应在 boot jar 内").isNull();
        assertThat(host.getResource("i18n/web/notification.properties"))
                .as("notification i18n 资源不应在 boot jar 内").isNull();
        assertThat(host.getResource("static/pixiv-tts/pixiv-tts.css"))
                .as("tts 静态资源不应在 boot jar 内").isNull();
        assertThat(host.getResource("static/pixiv-ai/pixiv-translate.js"))
                .as("ai 静态资源不应在 boot jar 内").isNull();
        assertThat(host.getResource("i18n/web/tts.properties"))
                .as("tts i18n 资源不应在 boot jar 内").isNull();
        assertThat(host.getResource("i18n/web/ai.properties"))
                .as("ai i18n 资源不应在 boot jar 内").isNull();
        assertThat(host.getResource("i18n/web/translate.properties"))
                .as("translate i18n 资源不应在 boot jar 内").isNull();
        assertThat(host.getResource("i18n/mail/messages.properties"))
                .as("mail i18n 资源不应在 boot jar 内").isNull();
        assertThat(host.getResource("i18n/push/messages.properties"))
                .as("push i18n 资源不应在 boot jar 内").isNull();
        assertThat(host.getResource("mail/templates/run-summary.html"))
                .as("mail 模板资源不应在 boot jar 内").isNull();
    }

    @Test
    @DisplayName("boot jar 条目黑名单：不含外置插件实现包、静态资源、i18n 与私有依赖")
    void bootJarEntriesExcludeExternalPluginPayloads() {
        Path bootJar = locateBootJar();
        Assumptions.assumeTrue(bootJar != null,
                "boot jar 尚未生成（需 package 阶段），跳过 jar 条目级边界验证");

        List<String> entries = jarEntryNames(bootJar);
        List<String> forbiddenPrefixes = List.of(
                "BOOT-INF/classes/top/sywyar/pixivdownload/ai/",
                "BOOT-INF/classes/top/sywyar/pixivdownload/notification/",
                "BOOT-INF/classes/top/sywyar/pixivdownload/notificationbase/",
                "BOOT-INF/classes/top/sywyar/pixivdownload/push/",
                "BOOT-INF/classes/top/sywyar/pixivdownload/tts/",
                "BOOT-INF/classes/top/sywyar/pixivdownload/mail/",
                "BOOT-INF/classes/top/sywyar/pixivdownload/stats/",
                "BOOT-INF/classes/top/sywyar/pixivdownload/duplicate/",
                "BOOT-INF/classes/top/sywyar/pixivdownload/guitheme/",
                "BOOT-INF/classes/top/sywyar/pixivdownload/download/",
                "BOOT-INF/classes/top/sywyar/pixivdownload/schedule/",
                "BOOT-INF/classes/top/sywyar/pixivdownload/recoverysentinel/",
                "BOOT-INF/classes/static/pixiv-batch",
                "BOOT-INF/classes/static/userscripts/",
                "BOOT-INF/classes/static/pixiv-stats/",
                "BOOT-INF/classes/static/pixiv-duplicates",
                "BOOT-INF/classes/static/pixiv-tts/",
                "BOOT-INF/classes/static/pixiv-ai/",
                "BOOT-INF/classes/i18n/web/batch",
                "BOOT-INF/classes/i18n/web/userscript",
                "BOOT-INF/classes/i18n/web/stats",
                "BOOT-INF/classes/i18n/web/duplicates",
                "BOOT-INF/classes/i18n/web/gui-theme",
                "BOOT-INF/classes/i18n/web/notification",
                "BOOT-INF/classes/i18n/web/push",
                "BOOT-INF/classes/i18n/web/mail",
                "BOOT-INF/classes/i18n/web/tts",
                "BOOT-INF/classes/i18n/web/ai",
                "BOOT-INF/classes/i18n/web/translate",
                "BOOT-INF/classes/i18n/mail/",
                "BOOT-INF/classes/i18n/push/",
                "BOOT-INF/classes/i18n/tts/",
                "BOOT-INF/classes/i18n/ai/",
                "BOOT-INF/classes/mail/templates/");
        for (String prefix : forbiddenPrefixes) {
            assertThat(entries)
                    .as("boot jar must not contain external plugin payload prefix " + prefix)
                    .noneMatch(name -> name.startsWith(prefix));
        }

        List<String> forbiddenLibPatterns = List.of(
                "BOOT-INF/lib/flatlaf-[^/]+\\.jar",
                "BOOT-INF/lib/flatlaf-intellij-themes-[^/]+\\.jar",
                "BOOT-INF/lib/jna-[^/]+\\.jar",
                "BOOT-INF/lib/jna-platform-[^/]+\\.jar",
                "BOOT-INF/lib/jakarta\\.mail-[^/]+\\.jar",
                "BOOT-INF/lib/jakarta\\.activation-api-[^/]+\\.jar",
                "BOOT-INF/lib/angus-[^/]+\\.jar",
                "BOOT-INF/lib/spring-context-support-[^/]+\\.jar");
        for (String pattern : forbiddenLibPatterns) {
            assertThat(entries)
                    .as("boot jar must not contain external plugin private dependency " + pattern)
                    .noneMatch(name -> name.matches(pattern));
        }
    }

    @Test
    @DisplayName("download-workbench 以 thin 外置插件形态打包：根部 plugin.properties + 外置主类，无契约 / 宿主 / 框架类泄漏")
    void downloadWorkbenchPackagesAsThinExternalPlugin() {
        assertThinExternalPlugin(DOWNLOAD_WORKBENCH_CLASSES_PROPERTY, "pixivdownload-plugin-download-workbench",
                "top/sywyar/pixivdownload/download/DownloadWorkbenchPf4jPlugin.class");
    }

    @Test
    @DisplayName("stats 以 thin 外置插件形态打包：根部 plugin.properties + 外置主类，无契约 / 宿主 / 框架类泄漏")
    void statsPackagesAsThinExternalPlugin() {
        assertThinExternalPlugin(STATS_CLASSES_PROPERTY, "pixivdownload-plugin-stats",
                "top/sywyar/pixivdownload/stats/StatsPf4jPlugin.class");
    }

    @Test
    @DisplayName("duplicate 以 thin 外置插件形态打包：根部 plugin.properties + 外置主类，无契约 / 宿主 / 框架类泄漏")
    void duplicatePackagesAsThinExternalPlugin() {
        assertThinExternalPlugin(DUPLICATE_CLASSES_PROPERTY, "pixivdownload-plugin-duplicate",
                "top/sywyar/pixivdownload/duplicate/DuplicatePf4jPlugin.class");
    }

    @Test
    @DisplayName("notification 以 thin 外置插件形态打包：根部 plugin.properties + 外置主类，无契约 / 宿主 / 框架类泄漏")
    void notificationPackagesAsThinExternalPlugin() {
        assertThinExternalPlugin(NOTIFICATION_CLASSES_PROPERTY, "pixivdownload-plugin-notification",
                "top/sywyar/pixivdownload/notificationbase/NotificationPf4jPlugin.class");
    }

    @Test
    @DisplayName("push 以 thin 外置插件形态打包：根部 plugin.properties + 外置主类，无契约 / 宿主 / 框架类泄漏")
    void pushPackagesAsThinExternalPlugin() {
        assertThinExternalPlugin(PUSH_CLASSES_PROPERTY, "pixivdownload-plugin-push",
                "top/sywyar/pixivdownload/push/PushPf4jPlugin.class");
    }

    @Test
    @DisplayName("mail 以 JAR-with-lib 形态打包：根 descriptor + 插件类 + mail 私有依赖")
    void mailPackagesAsJarWithPrivateLibraries() {
        assertJarWithPrivateLibraries(MAIL_CLASSES_PROPERTY, "pixivdownload-plugin-mail",
                "top/sywyar/pixivdownload/mail/MailPf4jPlugin.class",
                List.of("spring-context-support-[0-9].*\\.jar",
                        "jakarta\\.mail-[0-9].*\\.jar",
                        "jakarta\\.activation-api-[0-9].*\\.jar",
                        "angus-activation-[0-9].*\\.jar"));
    }

    @Test
    @DisplayName("tts 以 thin 外置插件形态打包：根部 plugin.properties + 外置主类，无契约 / 宿主 / 框架类泄漏")
    void ttsPackagesAsThinExternalPlugin() {
        assertThinExternalPlugin(TTS_CLASSES_PROPERTY, "pixivdownload-plugin-tts",
                "top/sywyar/pixivdownload/tts/TtsPf4jPlugin.class");
    }

    @Test
    @DisplayName("ai 以 thin 外置插件形态打包：根部 plugin.properties + 外置主类，无契约 / 宿主 / 框架类泄漏")
    void aiPackagesAsThinExternalPlugin() {
        assertThinExternalPlugin(AI_CLASSES_PROPERTY, "pixivdownload-plugin-ai",
                "top/sywyar/pixivdownload/ai/AiPf4jPlugin.class");
    }

    @Test
    @DisplayName("recovery-sentinel 以 thin 外置插件形态打包：根部 plugin.properties + 外置主类，无契约 / 宿主 / 框架类泄漏")
    void recoverySentinelPackagesAsThinExternalPlugin() {
        assertThinExternalPlugin(SENTINEL_CLASSES_PROPERTY, "pixivdownload-plugin-recovery-sentinel",
                "top/sywyar/pixivdownload/recoverysentinel/RecoverySentinelPf4jPlugin.class");
    }

    @Test
    @DisplayName("gui-theme 以 JAR-with-lib 形态打包：根 descriptor + 插件类 + lib/*.jar")
    void guiThemePackagesAsJarWithPrivateLibraries(@TempDir Path tempDir) {
        Path classesDir = locateConfiguredDir(GUI_THEME_CLASSES_PROPERTY);
        Assumptions.assumeTrue(classesDir != null && Files.isDirectory(classesDir),
                "插件构建产物未就绪（需 reactor 先构建 pixivdownload-plugin-gui-theme），跳过 JAR-with-lib 形态验证");

        assertThat(classesDir.resolve("plugin.properties"))
                .as("主题插件构建产物根部应含 plugin.properties").exists();
        assertThat(classesDir.resolve("top/sywyar/pixivdownload/guitheme/GuiThemePf4jPlugin.class"))
                .as("主题插件构建产物应含外置主类").exists();
        assertThat(classesDir.resolve("top/sywyar/pixivdownload/plugin/api"))
                .as("主题插件不得打入共享契约 plugin-api").doesNotExist();
        assertThat(classesDir.resolve("top/sywyar/pixivdownload/gui/theme/GuiThemeManager.class"))
                .as("主题插件不得打入 app 核心主题管理类").doesNotExist();

        Path jar = locateConfiguredGuiThemeJar(classesDir);
        if (jar == null) {
            return;
        }
        List<String> entries = jarEntryNames(jar);
        assertThat(entries).contains("plugin.properties");
        assertThat(entries).contains("top/sywyar/pixivdownload/guitheme/GuiThemePf4jPlugin.class");
        assertThat(entries).as("主题插件 JAR 内不得嵌套根插件 jar").noneMatch(name -> name.matches("[^/]+\\.jar"));
        assertThat(entries).as("FlatLaf 必须只在 theme JAR 的 lib/ 中")
                .anyMatch(name -> name.matches("lib/flatlaf-[0-9][^/]*\\.jar"));
        assertThat(entries).as("IntelliJ Themes 必须只在 theme JAR 的 lib/ 中")
                .anyMatch(name -> name.matches("lib/flatlaf-intellij-themes-[0-9][^/]*\\.jar"));
        assertThat(entries).as("JNA 必须只在 theme JAR 的 lib/ 中")
                .anyMatch(name -> name.matches("lib/jna-[0-9][^/]*\\.jar"));
        assertThat(entries).as("JNA Platform 必须只在 theme JAR 的 lib/ 中")
                .anyMatch(name -> name.matches("lib/jna-platform-[0-9][^/]*\\.jar"));
        assertThat(entries).noneMatch(name -> name.startsWith("BOOT-INF/"));
        assertThat(entries).noneMatch(name -> name.startsWith("top/sywyar/pixivdownload/plugin/api/"));

        assertGuiThemeJarLoadsWithPluginClassLoader(jar, tempDir);
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
        assertThat(entries).as("thin 插件 jar 不得携带私有 lib/*.jar")
                .noneMatch(name -> name.matches("lib/[^/]+\\.jar"));
    }

    private void assertJarWithPrivateLibraries(String classesProperty, String artifactId, String mainClassEntry,
                                               List<String> requiredLibPatterns) {
        Path classesDir = locateConfiguredDir(classesProperty);
        Assumptions.assumeTrue(classesDir != null && Files.isDirectory(classesDir),
                () -> "插件构建产物未就绪（需 reactor 先构建 " + artifactId + "），跳过 JAR-with-lib 形态验证");

        assertThat(classesDir.resolve("plugin.properties"))
                .as("插件构建产物根部应含 plugin.properties").exists();
        assertThat(classesDir.resolve(mainClassEntry))
                .as("插件构建产物应含外置主类").exists();
        assertThat(classesDir.resolve("top/sywyar/pixivdownload/plugin/api"))
                .as("外置插件不得打入共享契约 plugin-api").doesNotExist();
        assertThat(classesDir.resolve("lib"))
                .as("JAR-with-lib 插件构建产物应携带 lib/ 私有依赖").isDirectory();

        Path jar = locateModuleJar(classesDir, artifactId);
        if (jar == null) {
            return;
        }
        List<String> entries = jarEntryNames(jar);
        assertThat(entries).contains("plugin.properties");
        assertThat(entries).contains(mainClassEntry);
        assertThat(entries).noneMatch(name -> name.startsWith("BOOT-INF/"));
        assertThat(entries).noneMatch(name -> name.startsWith("top/sywyar/pixivdownload/plugin/api/"));
        assertThat(entries).noneMatch(name -> name.startsWith("org/pf4j/"));
        assertThat(entries).noneMatch(name -> name.startsWith("org/springframework/"));
        for (String required : requiredLibPatterns) {
            assertThat(entries).as("插件 JAR 应在 lib/ 中携带私有依赖 " + required)
                    .anyMatch(name -> name.matches("lib/" + required));
        }
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

    private static Path locateBootJar() {
        Path repoRoot = locateRepoRoot();
        Path targetDir = repoRoot.resolve("pixivdownload-app").resolve("target");
        if (!Files.isDirectory(targetDir)) {
            return null;
        }
        try (Stream<Path> stream = Files.list(targetDir)) {
            return stream.filter(candidate -> Files.isRegularFile(candidate)
                            && candidate.getFileName().toString().matches("PixivDownload-.*-boot\\.jar"))
                    .sorted(Comparator.comparing(DistributionPackagingBoundaryTest::lastModified).reversed())
                    .filter(candidate -> isFreshBootJar(candidate, repoRoot))
                    .findFirst()
                    .orElse(null);
        } catch (IOException ignored) {
            // jar may not exist in test phase.
        }
        return null;
    }

    private static boolean isFreshBootJar(Path candidate, Path repoRoot) {
        try {
            FileTime jarTime = Files.getLastModifiedTime(candidate);
            return !hasNewerFile(repoRoot.resolve("pixivdownload-app").resolve("src").resolve("main").resolve("java"),
                    jarTime)
                    && !hasNewerFile(repoRoot.resolve("pixivdownload-app").resolve("src").resolve("main")
                    .resolve("resources"), jarTime);
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean hasNewerFile(Path root, FileTime jarTime) throws IOException {
        if (!Files.isDirectory(root)) {
            return false;
        }
        try (Stream<Path> stream = Files.walk(root)) {
            return stream.filter(Files::isRegularFile)
                    .anyMatch(path -> lastModified(path).compareTo(jarTime) > 0);
        }
    }

    private static FileTime lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path);
        } catch (IOException e) {
            return FileTime.fromMillis(0);
        }
    }

    private static Path locateRepoRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            if (Files.isDirectory(current.resolve("pixivdownload-app"))
                    && Files.isDirectory(current.resolve("scripts"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("无法定位仓库根目录");
    }

    private static Path locateConfiguredGuiThemeJar(Path classesDir) {
        String configured = System.getProperty(GUI_THEME_JAR_PROPERTY);
        if (configured != null && !configured.isBlank()) {
            Path configuredPath = Path.of(configured);
            if (Files.isRegularFile(configuredPath) && isFreshArtifact(configuredPath, classesDir)) {
                return configuredPath;
            }
        }
        Path targetDir = classesDir.getParent();
        if (targetDir == null || !Files.isDirectory(targetDir)) {
            return null;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(
                targetDir, "pixivdownload-plugin-gui-theme-*.jar")) {
            for (Path candidate : stream) {
                String name = candidate.getFileName().toString();
                if (name.endsWith("-sources.jar") || name.endsWith("-javadoc.jar")) {
                    continue;
                }
                if (isFreshArtifact(candidate, classesDir)) {
                    return candidate;
                }
            }
        } catch (IOException ignored) {
            // package 阶段之前 jar 不存在，test 阶段只跳过更强断言。
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
                if (isFreshArtifact(candidate, classesDir)) {
                    return candidate;
                }
            }
        } catch (IOException ignored) {
            // best-effort：定位失败按缺失处理（仅放弃更强的 jar 级断言，不致测试失败）
        }
        return null;
    }

    private static boolean isFreshArtifact(Path artifact, Path classesDir) {
        try {
            return Files.getLastModifiedTime(artifact).compareTo(Files.getLastModifiedTime(classesDir)) >= 0;
        } catch (IOException ignored) {
            return true;
        }
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

    private static void assertGuiThemeJarLoadsWithPluginClassLoader(Path jar, Path tempDir) {
        Path materialized = tempDir.resolve("gui-theme-materialized");
        materializeJarWithPrivateLibs(jar, materialized);

        List<URL> urls = new ArrayList<>();
        try {
            urls.add(materialized.resolve("classes").toUri().toURL());
            try (DirectoryStream<Path> libs = Files.newDirectoryStream(materialized.resolve("lib"), "*.jar")) {
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

                assertGuiThemeLookAndFeelCanCreateSwingDelegates(loader);
            }
        } catch (IOException | ReflectiveOperationException e) {
            throw new IllegalStateException("无法通过主题插件 JAR-with-lib classloader 加载类: " + jar, e);
        }
    }

    private static void assertGuiThemeLookAndFeelCanCreateSwingDelegates(URLClassLoader loader)
            throws ReflectiveOperationException {
        LookAndFeel previousLookAndFeel = UIManager.getLookAndFeel();
        Object previousDefaultsClassLoader = UIManager.getDefaults().get("ClassLoader");
        Object previousLookAndFeelDefaultsClassLoader = UIManager.getLookAndFeelDefaults().get("ClassLoader");
        try {
            Class<?> featureClass = Class.forName("top.sywyar.pixivdownload.guitheme.GuiThemePlugin", true, loader);
            PixivFeaturePlugin feature = (PixivFeaturePlugin) featureClass.getDeclaredConstructor().newInstance();
            GuiThemeContribution light = feature.guiThemes().stream()
                    .filter(theme -> "light".equals(theme.themeId()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("主题插件缺少 light contribution"));

            runOnEdtAndWait(() -> {
                try {
                    light.applyOnEventDispatchThread();
                } catch (Exception e) {
                    throw new IllegalStateException("无法应用 gui-theme light 主题", e);
                }
            });

            assertThat(UIManager.getLookAndFeelDefaults().get("ClassLoader"))
                    .as("FlatLaf UI delegate 必须通过主题插件 classloader 解析")
                    .isSameAs(loader);
            runOnEdtAndWait(() -> {
                assertThat(new JPanel().getUI()).as("JPanel UI delegate 应可创建").isNotNull();
                assertThat(new JButton("probe").getUI()).as("JButton UI delegate 应可创建").isNotNull();
                assertThat(new JRootPane().getUI()).as("JRootPane UI delegate 应可创建").isNotNull();
            });
        } finally {
            if (previousLookAndFeel != null) {
                runOnEdtAndWait(() -> {
                    try {
                        UIManager.setLookAndFeel(previousLookAndFeel);
                    } catch (Exception e) {
                        throw new IllegalStateException("无法恢复测试前 LookAndFeel", e);
                    }
                });
            }
            restoreDefaultValue(UIManager.getDefaults(), "ClassLoader", previousDefaultsClassLoader);
            restoreDefaultValue(UIManager.getLookAndFeelDefaults(), "ClassLoader", previousLookAndFeelDefaultsClassLoader);
        }
    }

    private static void restoreDefaultValue(UIDefaults defaults, Object key, Object previousValue) {
        if (previousValue == null) {
            defaults.remove(key);
            return;
        }
        defaults.put(key, previousValue);
    }

    private static void runOnEdtAndWait(Runnable task) {
        if (SwingUtilities.isEventDispatchThread()) {
            task.run();
            return;
        }
        try {
            SwingUtilities.invokeAndWait(task);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待 EDT 执行被中断", e);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("EDT 执行失败", cause);
        }
    }

    private static void materializeJarWithPrivateLibs(Path jar, Path targetDir) {
        Path classesDir = targetDir.resolve("classes");
        Path libDir = targetDir.resolve("lib");
        try (JarFile jarFile = new JarFile(jar.toFile())) {
            var entries = jarFile.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }
                String name = entry.getName();
                if ("plugin.properties".equals(name)) {
                    continue;
                }
                Path output = name.matches("lib/[^/]+\\.jar")
                        ? libDir.resolve(Path.of(name).getFileName().toString()).normalize()
                        : classesDir.resolve(name).normalize();
                if (!output.startsWith(targetDir.normalize())) {
                    throw new IllegalStateException("插件 JAR 含非法路径: " + name);
                }
                Files.createDirectories(output.getParent());
                try (InputStream in = jarFile.getInputStream(entry)) {
                    Files.copy(in, output, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("无法物化主题插件 JAR: " + jar, e);
        }
    }
}
