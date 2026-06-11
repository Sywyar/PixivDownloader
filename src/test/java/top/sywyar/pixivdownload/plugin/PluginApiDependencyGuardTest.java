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
