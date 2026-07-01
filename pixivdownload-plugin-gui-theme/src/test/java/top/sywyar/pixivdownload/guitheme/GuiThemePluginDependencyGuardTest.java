package top.sywyar.pixivdownload.guitheme;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

class GuiThemePluginDependencyGuardTest {

    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importPackages("top.sywyar.pixivdownload.guitheme");

    @Test
    void guiThemePluginDoesNotDependOnAppImplementationPackages() {
        noClasses()
                .that().resideInAPackage("top.sywyar.pixivdownload.guitheme..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                        "top.sywyar.pixivdownload.gui..",
                        "top.sywyar.pixivdownload.config..",
                        "top.sywyar.pixivdownload.i18n..",
                        "top.sywyar.pixivdownload.plugin.runtime..")
                .because("the theme plugin crosses the host boundary only through plugin-api and PF4J")
                .check(CLASSES);
    }
}
