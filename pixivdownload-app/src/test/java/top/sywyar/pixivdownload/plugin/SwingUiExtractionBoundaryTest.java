package top.sywyar.pixivdownload.plugin;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SwingUiExtractionBoundaryTest {
    @Test
    void appProductionSourcesDoNotImportSwing() throws Exception {
        Path sourceRoot = locateModule().resolve("src/main/java");
        List<Path> offenders;
        try (var files = Files.walk(sourceRoot)) {
            offenders = files.filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> {
                        try { return Files.readString(path).contains("javax.swing"); }
                        catch (Exception failure) { throw new IllegalStateException(failure); }
                    })
                    .toList();
        }
        assertThat(offenders).as("Swing implementation belongs to pixivdownload-plugin-gui-swing").isEmpty();
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
