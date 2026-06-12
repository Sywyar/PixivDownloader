package top.sywyar.pixivdownload.plugin;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * 包依赖守卫。后续每个解耦步骤完成后，把对应的「禁止依赖」追加固化到这里，防止回潮。
 */
class PluginApiDependencyGuardTest {

    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importPackages("top.sywyar.pixivdownload");

    @Test
    @DisplayName("plugin.api 必须自包含：只依赖 JDK 与 plugin.api 自身")
    void pluginApiIsSelfContained() {
        classes()
                .that().resideInAPackage("top.sywyar.pixivdownload.plugin.api..")
                .should().onlyDependOnClassesThat()
                .resideInAnyPackage("top.sywyar.pixivdownload.plugin.api..", "java..")
                .because("plugin.api 是跨插件边界共享的契约包，不得依赖任何业务包或框架")
                .check(CLASSES);
    }

    @Test
    @DisplayName("核心不得反向依赖 stats 插件包（组合根 BuiltInPlugins 除外）")
    void coreDoesNotDependOnStatsPlugin() {
        noClasses()
                .that().resideOutsideOfPackage("top.sywyar.pixivdownload.stats..")
                .and().doNotHaveFullyQualifiedName(BuiltInPlugins.class.getName())
                .should().dependOnClassesThat()
                .resideInAPackage("top.sywyar.pixivdownload.stats..")
                .because("stats 是功能插件，核心只能经 PluginRegistry 间接使用其 contribution；"
                        + "BuiltInPlugins 是既定的组合根例外")
                .check(CLASSES);
    }

    @Test
    @DisplayName("核心不得反向依赖 duplicate 插件包（组合根 BuiltInPlugins 与下载即时算 Hash 链路除外）")
    void coreDoesNotDependOnDuplicatePlugin() {
        noClasses()
                .that().resideOutsideOfPackage("top.sywyar.pixivdownload.duplicate..")
                .and().doNotHaveFullyQualifiedName(BuiltInPlugins.class.getName())
                .and().doNotHaveFullyQualifiedName("top.sywyar.pixivdownload.download.DownloadService")
                .should().dependOnClassesThat()
                .resideInAPackage("top.sywyar.pixivdownload.duplicate..")
                .because("duplicate 是功能插件，核心只能经 PluginRegistry 间接使用其 contribution；"
                        + "BuiltInPlugins 是既定的组合根例外，DownloadService→ImageHashService "
                        + "是『下载后即时算 Hash』的既定核心链路例外（不随插件禁用）")
                .check(CLASSES);
    }

    @Test
    @DisplayName("下载即时算 Hash 链路例外仅限 ImageHashService 一个类")
    void downloadServiceOnlyTouchesImageHashService() {
        noClasses()
                .that().haveFullyQualifiedName("top.sywyar.pixivdownload.download.DownloadService")
                .should().dependOnClassesThat(
                        JavaClass.Predicates.resideInAPackage("top.sywyar.pixivdownload.duplicate..")
                                .and(DescribedPredicate.not(JavaClass.Predicates.type(
                                        top.sywyar.pixivdownload.duplicate.ImageHashService.class))))
                .because("核心链路例外的口径收窄到 Hash 计算入口本身，防止经例外类扩散依赖")
                .check(CLASSES);
    }

    @Test
    @DisplayName("common 不得依赖业务包：项目内仅允许 common/config/i18n")
    void commonDependsOnlyOnInfrastructure() {
        noClasses()
                .that().resideInAPackage("top.sywyar.pixivdownload.common..")
                .should().dependOnClassesThat(
                        JavaClass.Predicates.resideInAPackage("top.sywyar.pixivdownload..")
                                .and(DescribedPredicate.not(JavaClass.Predicates.resideInAnyPackage(
                                        "top.sywyar.pixivdownload.common..",
                                        "top.sywyar.pixivdownload.config..",
                                        "top.sywyar.pixivdownload.i18n.."))))
                .because("common 是叶子工具包，只允许依赖 config / i18n 两个基础设施包")
                .check(CLASSES);
    }
}
