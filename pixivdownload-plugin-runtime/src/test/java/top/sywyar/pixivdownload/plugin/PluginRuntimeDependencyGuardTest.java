package top.sywyar.pixivdownload.plugin;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * plugin-runtime 边界守卫：证明本模块是插件框架的 Spring 耦合启用运行时——只承载
 * {@link ConditionalOnPluginEnabled} / {@link OnPluginEnabledCondition} / {@link PluginToggleProperties}
 * 三件套，允许 Spring（条件 / 环境 / {@code @ConfigurationProperties} 绑定）+ JDK，但<b>零 plugin-api、
 * 零 app / 具体插件类反向依赖</b>，尤其<b>不得回指组合根 {@code BuiltInPlugins} / 运行时
 * {@code PluginRegistry} / {@code CorePlugin}</b>（它们与本模块共享拆分包
 * {@code top.sywyar.pixivdownload.plugin}，但留在 app、在全部插件模块之上）。
 *
 * <p>本守卫在 {@code pixivdownload-plugin-runtime} 模块内自包含运行：{@link ClassFileImporter} 只扫描本模块
 * main classpath 上的 {@code top.sywyar.pixivdownload..} 类（app / 插件类不在本模块 classpath 上），与主程序的
 * {@code PluginApiDependencyGuardTest}、core-api 的 {@code CoreApiDependencyGuardTest} 正交、各自从自己模块的
 * classpath 断言。
 */
class PluginRuntimeDependencyGuardTest {

    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importPackages("top.sywyar.pixivdownload");

    @Test
    @DisplayName("plugin-runtime 必须自包含：只依赖 JDK、Spring 与自身 plugin 精确包（不含 plugin.api）")
    void pluginRuntimeIsSelfContained() {
        classes()
                .that().resideInAPackage("top.sywyar.pixivdownload..")
                .should().onlyDependOnClassesThat()
                .resideInAnyPackage("top.sywyar.pixivdownload.plugin", "java..", "org.springframework..")
                .because("plugin-runtime 是插件框架的 Spring 耦合启用运行时：只能依赖 JDK、Spring 与自身精确包 "
                        + "top.sywyar.pixivdownload.plugin（三件套互引），不得依赖 plugin.api（精确包不含 .api 子包）、"
                        + "任何 app 业务包或具体插件实现包")
                .check(CLASSES);
    }

    @Test
    @DisplayName("plugin-runtime 不得回指 app 组合根 / 注册中心：BuiltInPlugins / PluginRegistry / CorePlugin")
    void pluginRuntimeDoesNotReverseReferenceAppCompositionRoot() {
        noClasses()
                .that().resideInAPackage("top.sywyar.pixivdownload..")
                .should().dependOnClassesThat()
                .haveFullyQualifiedName("top.sywyar.pixivdownload.plugin.BuiltInPlugins")
                .orShould().dependOnClassesThat()
                .haveFullyQualifiedName("top.sywyar.pixivdownload.plugin.PluginRegistry")
                .orShould().dependOnClassesThat()
                .haveFullyQualifiedName("top.sywyar.pixivdownload.plugin.CorePlugin")
                .because("关键解环手：plugin-runtime 在所有插件模块之下，绝不能回指 app 的组合根 BuiltInPlugins / "
                        + "运行时 PluginRegistry / CorePlugin（它们与本模块共享拆分包 top.sywyar.pixivdownload.plugin "
                        + "但留在 app）；OnPluginEnabledCondition 删掉 isRequired 短路分支后对 BuiltInPlugins 零引用，"
                        + "本守卫固化该解环（必选插件不可禁用由 app 侧 PluginRegistry 强制、必选插件无条件 Bean 由 "
                        + "app 侧 PluginApiDependencyGuardTest 守护）")
                .check(CLASSES);
    }

    @Test
    @DisplayName("plugin-runtime 不得依赖 plugin-api 或任何 app 业务 / 具体插件包")
    void pluginRuntimeDoesNotDependOnPluginApiOrBusinessPackages() {
        noClasses()
                .that().resideInAPackage("top.sywyar.pixivdownload..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                        "top.sywyar.pixivdownload.plugin.api..",
                        "top.sywyar.pixivdownload.download..",
                        "top.sywyar.pixivdownload.gallery..",
                        "top.sywyar.pixivdownload.novel..",
                        "top.sywyar.pixivdownload.stats..",
                        "top.sywyar.pixivdownload.duplicate..",
                        "top.sywyar.pixivdownload.schedule..",
                        "top.sywyar.pixivdownload.core..",
                        "top.sywyar.pixivdownload.gui..",
                        "top.sywyar.pixivdownload.author..",
                        "top.sywyar.pixivdownload.series..",
                        "top.sywyar.pixivdownload.setup..",
                        "top.sywyar.pixivdownload.quota..",
                        "top.sywyar.pixivdownload.push..",
                        "top.sywyar.pixivdownload.ai..",
                        "top.sywyar.pixivdownload.tts..",
                        "top.sywyar.pixivdownload.maintenance..",
                        "top.sywyar.pixivdownload.migration..",
                        "top.sywyar.pixivdownload.tools..",
                        "top.sywyar.pixivdownload.imageclassifier..",
                        "top.sywyar.pixivdownload.scripts..")
                .because("plugin-runtime 零 plugin-api 依赖（三件套是 Spring 运行时设施、不属跨插件契约）、"
                        + "零 app 业务 / 具体插件反向依赖；它在所有插件模块之下，任何这类反向依赖都会让 reactor 成环")
                .check(CLASSES);
    }

    @Test
    @DisplayName("plugin-runtime 模块应包含三件套（防守卫 vacuous 通过）")
    void pluginRuntimeContainsToggleRuntimeTypes() {
        assertThat(CLASSES.contain(ConditionalOnPluginEnabled.class.getName())).isTrue();
        assertThat(CLASSES.contain(OnPluginEnabledCondition.class.getName())).isTrue();
        assertThat(CLASSES.contain(PluginToggleProperties.class.getName())).isTrue();
    }
}
