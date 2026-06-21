package top.sywyar.pixivdownload.plugin;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import top.sywyar.pixivdownload.plugin.runtime.DiscoveredFeaturePlugin;
import top.sywyar.pixivdownload.plugin.runtime.PixivPluginDiscoveryBridge;
import top.sywyar.pixivdownload.plugin.runtime.PluginDiscoveryResult;
import top.sywyar.pixivdownload.plugin.runtime.PluginRuntimeManager;

/**
 * plugin-runtime 边界守卫：证明本模块是插件框架的 Spring 耦合启用运行时 + PF4J 外置插件运行时骨架 / 发现桥接——
 * 承载 {@link ConditionalOnPluginEnabled} / {@link OnPluginEnabledCondition} / {@link PluginToggleProperties}
 * 三件套与 {@code plugin.runtime} 子包（{@link PluginRuntimeManager} 目录定位 / 加载 / 启动 / 诊断，
 * {@link PixivPluginDiscoveryBridge} 把外置插件的 PixivFeaturePlugin 暴露给核心）。允许 Spring（条件 / 环境 /
 * {@code @ConfigurationProperties} 绑定）、PF4J、slf4j、<b>plugin-api</b>（发现桥接产出 PixivFeaturePlugin 需跨边界
 * 共享契约）与 JDK，但<b>零 app / 具体插件类反向依赖</b>，尤其<b>不得回指组合根 {@code BuiltInPlugins} /
 * 运行时 {@code PluginRegistry} / {@code CorePlugin}</b>（它们与本模块共享拆分包
 * {@code top.sywyar.pixivdownload.plugin}，但留在 app、在全部插件模块之上）。
 *
 * <p>本守卫在 {@code pixivdownload-plugin-runtime} 模块内自包含运行：{@link ClassFileImporter} 扫描本模块 main
 * classpath 上的 {@code top.sywyar.pixivdownload..} 类。本模块编译期依赖 plugin-api 后，plugin-api 的契约类也会落到
 * classpath、被一并导入；故各规则的<b>主语集合显式排除 {@code plugin.api..}</b>（只约束本模块自身的
 * {@code plugin} / {@code plugin.runtime} 类，不把 plugin-api 自己的依赖面误算进本模块）。app 的
 * {@code PluginApiDependencyGuardTest}、core-api 的 {@code CoreApiDependencyGuardTest} 各自从自己模块的 classpath
 * 断言，与本守卫正交。
 */
class PluginRuntimeDependencyGuardTest {

    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importPackages("top.sywyar.pixivdownload");

    @Test
    @DisplayName("plugin-runtime 必须自包含：只依赖 JDK、Spring、PF4J、slf4j、plugin-api 与自身 plugin / plugin.runtime 包")
    void pluginRuntimeIsSelfContained() {
        classes()
                .that().resideInAPackage("top.sywyar.pixivdownload..")
                .and().resideOutsideOfPackage("top.sywyar.pixivdownload.plugin.api..")
                .should().onlyDependOnClassesThat()
                .resideInAnyPackage(
                        "top.sywyar.pixivdownload.plugin",
                        "top.sywyar.pixivdownload.plugin.runtime",
                        "top.sywyar.pixivdownload.plugin.api..",
                        "java..", "org.springframework..", "org.pf4j..", "org.slf4j..")
                .because("plugin-runtime 是插件框架的 Spring 耦合启用运行时 + PF4J 外置插件运行时骨架 / 发现桥接：只能"
                        + "依赖 JDK、Spring（条件 / 绑定）、PF4J（PluginManager 等）、slf4j、plugin-api（跨插件契约，"
                        + "发现桥接产出 PixivFeaturePlugin 需要它）与自身精确包 top.sywyar.pixivdownload.plugin（三件套）/ "
                        + "top.sywyar.pixivdownload.plugin.runtime（PF4J 封装 + 发现桥接），不得依赖任何 app 业务包或具体"
                        + "插件实现包（本规则主语已排除 plugin.api 自身，只约束本模块的 plugin / plugin.runtime 类）")
                .check(CLASSES);
    }

    @Test
    @DisplayName("plugin-runtime 不得回指 app 组合根 / 注册中心：BuiltInPlugins / PluginRegistry / CorePlugin")
    void pluginRuntimeDoesNotReverseReferenceAppCompositionRoot() {
        noClasses()
                .that().resideInAPackage("top.sywyar.pixivdownload..")
                .and().resideOutsideOfPackage("top.sywyar.pixivdownload.plugin.api..")
                .should().dependOnClassesThat()
                .haveFullyQualifiedName("top.sywyar.pixivdownload.plugin.BuiltInPlugins")
                .orShould().dependOnClassesThat()
                .haveFullyQualifiedName("top.sywyar.pixivdownload.plugin.PluginRegistry")
                .orShould().dependOnClassesThat()
                .haveFullyQualifiedName("top.sywyar.pixivdownload.plugin.CorePlugin")
                .because("关键解环手：plugin-runtime 在所有插件模块之下，绝不能回指 app 的组合根 BuiltInPlugins / "
                        + "运行时 PluginRegistry / CorePlugin（它们与本模块共享拆分包 top.sywyar.pixivdownload.plugin "
                        + "但留在 app）；OnPluginEnabledCondition 删掉 isRequired 短路分支后对 BuiltInPlugins 零引用，"
                        + "发现桥接也只产出 plugin-api 契约类型、不回指 app 注册中心，本守卫固化该解环（必选插件不可禁用由 "
                        + "app 侧 PluginRegistry 强制、必选插件无条件 Bean 由 app 侧 PluginApiDependencyGuardTest 守护）")
                .check(CLASSES);
    }

    @Test
    @DisplayName("plugin-runtime 不得依赖任何 app 业务 / 具体插件包（plugin-api 跨插件契约允许）")
    void pluginRuntimeDoesNotDependOnBusinessPackages() {
        noClasses()
                .that().resideInAPackage("top.sywyar.pixivdownload..")
                .and().resideOutsideOfPackage("top.sywyar.pixivdownload.plugin.api..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(
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
                .because("plugin-runtime 零 app 业务 / 具体插件反向依赖；它在所有插件模块之下，任何这类反向依赖都会让 "
                        + "reactor 成环。它对 plugin-api 的依赖是合法跨插件契约（发现桥接产出 PixivFeaturePlugin），不在禁用面内")
                .check(CLASSES);
    }

    @Test
    @DisplayName("plugin-runtime 模块应包含三件套（防守卫 vacuous 通过）")
    void pluginRuntimeContainsToggleRuntimeTypes() {
        assertThat(CLASSES.contain(ConditionalOnPluginEnabled.class.getName())).isTrue();
        assertThat(CLASSES.contain(OnPluginEnabledCondition.class.getName())).isTrue();
        assertThat(CLASSES.contain(PluginToggleProperties.class.getName())).isTrue();
    }

    @Test
    @DisplayName("plugin-runtime 模块应包含 PF4J 运行时骨架与发现桥接（防守卫 vacuous 通过）")
    void pluginRuntimeContainsPf4jRuntimeSkeletonAndDiscoveryBridge() {
        assertThat(CLASSES.contain(PluginRuntimeManager.class.getName())).isTrue();
        assertThat(CLASSES.contain(
                top.sywyar.pixivdownload.plugin.runtime.PluginRuntimeStatus.class.getName())).isTrue();
        assertThat(CLASSES.contain(
                top.sywyar.pixivdownload.plugin.runtime.PluginDirectoryState.class.getName())).isTrue();
        assertThat(CLASSES.contain(PixivPluginDiscoveryBridge.class.getName())).isTrue();
        assertThat(CLASSES.contain(DiscoveredFeaturePlugin.class.getName())).isTrue();
        assertThat(CLASSES.contain(PluginDiscoveryResult.class.getName())).isTrue();
    }
}
