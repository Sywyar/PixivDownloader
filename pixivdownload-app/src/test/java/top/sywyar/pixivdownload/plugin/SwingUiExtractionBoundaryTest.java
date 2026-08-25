package top.sywyar.pixivdownload.plugin;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

class SwingUiExtractionBoundaryTest {
    @Test
    void appProductionClassesDoNotDependOnSwingToolkit() {
        var classes = new ClassFileImporter().importPath(locateModule().resolve("target/classes"));

        noClasses().should().dependOnClassesThat().resideInAnyPackage(
                        "javax.swing..",
                        "com.formdev.flatlaf..",
                        "com.sun.jna..")
                .because("Swing, FlatLaf and JNA implementations belong to pixivdownload-plugin-gui-swing")
                .check(classes);
    }

    private static Path locateModule() {
        Path current = Path.of("").toAbsolutePath();
        for (int i = 0; i < 8 && current != null; i++, current = current.getParent()) {
            if (Files.isRegularFile(current.resolve("pom.xml"))
                    && "pixivdownload-app".equals(current.getFileName().toString())) return current;
            Path nested = current.resolve("pixivdownload-app");
            if (Files.isRegularFile(nested.resolve("pom.xml"))) return nested;
        }
        throw new IllegalStateException("pixivdownload-app module not found");
    }
}
