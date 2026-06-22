package top.sywyar.pixivdownload.recoverysentinel;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * recovery-sentinel 插件模块边界守卫：证明本模块只承载最小外置插件本体（{@link RecoverySentinelPf4jPlugin} /
 * {@link RecoverySentinelPlugin}），<b>只依赖</b>跨插件契约 {@code plugin.api}、PF4J（外置插件主类）与 JDK，
 * <b>绝不依赖</b>主程序 {@code pixivdownload-app}、核心模块、Spring、core-api 或 plugin-runtime——它没有任何托管 Bean、
 * 不读核心数据、不用启用条件注解，故比 stats 更瘦。
 *
 * <p>本守卫在 {@code pixivdownload-plugin-recovery-sentinel} 模块内自包含运行：{@link ClassFileImporter} 只扫描本模块
 * classpath 上的类（app / 其它插件类不在本模块 classpath 上），与各模块各自的依赖守卫正交。
 */
class RecoverySentinelModuleDependencyGuardTest {

    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importPackages("top.sywyar.pixivdownload");

    @Test
    @DisplayName("recovery-sentinel 模块必须自包含：只依赖 plugin.api / PF4J / JDK，不依赖 app / 核心 / Spring")
    void recoverySentinelModuleIsSelfContained() {
        classes()
                .that().resideInAPackage("top.sywyar.pixivdownload.recoverysentinel..")
                .should().onlyDependOnClassesThat()
                .resideInAnyPackage(
                        "top.sywyar.pixivdownload.recoverysentinel..",
                        "top.sywyar.pixivdownload.plugin.api..",
                        "java..",
                        "org.pf4j..")
                .because("最小外置插件只能依赖跨插件契约 plugin.api（PixivFeaturePlugin / PixivPluginProvider / PluginKind）"
                        + "与 PF4J（外置插件主类 RecoverySentinelPf4jPlugin 继承 org.pf4j.Plugin）；它不贡献任何功能、"
                        + "没有托管 Bean、不读核心数据，故不依赖主程序 pixivdownload-app、核心、Spring、core-api 或 plugin-runtime")
                .check(CLASSES);
    }

    @Test
    @DisplayName("recovery-sentinel 模块应包含最小插件本体类与外置 PF4J 主类（防守卫 vacuous 通过）")
    void recoverySentinelModuleContainsPluginClasses() {
        assertThat(CLASSES.contain(RecoverySentinelPf4jPlugin.class.getName())).isTrue();
        assertThat(CLASSES.contain(RecoverySentinelPlugin.class.getName())).isTrue();
    }
}
