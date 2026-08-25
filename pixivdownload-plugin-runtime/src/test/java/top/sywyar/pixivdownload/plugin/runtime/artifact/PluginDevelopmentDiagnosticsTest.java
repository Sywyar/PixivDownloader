package top.sywyar.pixivdownload.plugin.runtime.artifact;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PluginDevelopmentDiagnosticsTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("开发模式诊断会报告已编译模块与仅有源码的模块")
    void reportsCompiledAndSourceOnlyModules() {
        Path compiledRoot = tempDir.resolve("pixivdownload-plugin-compiled");
        Path sourceRoot = tempDir.resolve("pixivdownload-plugin-source-only");
        PluginDevelopmentArtifacts.DevelopmentDiscovery discovery =
                new PluginDevelopmentArtifacts.DevelopmentDiscovery(
                        tempDir,
                        tempDir.resolve("target/pixivdownload-plugin-dev-runtime"),
                        List.of(new PluginDevelopmentArtifacts.DevelopmentPluginArtifact(
                                compiledRoot,
                                compiledRoot.resolve("target/classes"),
                                compiledRoot.resolve("target/classes/plugin.properties"))),
                        List.of(new PluginDevelopmentArtifacts.DevelopmentSourceModule(
                                sourceRoot,
                                sourceRoot.resolve("src/main/resources/plugin.properties"),
                                "source-only")));

        assertThat(PluginDevelopmentDiagnostics.sourceFailures(discovery)).singleElement().satisfies(failure -> {
            assertThat(failure.source()).isEqualTo("source-only");
            assertThat(failure.reason()).contains("target/classes/plugin.properties", sourceRoot.toString());
        });

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PrintStream originalError = System.err;
        try (PrintStream captured = new PrintStream(output, true, StandardCharsets.UTF_8)) {
            System.setErr(captured);
            PluginDevelopmentDiagnostics.printBanner(tempDir.resolve("plugins"), discovery);
        } finally {
            System.setErr(originalError);
        }
        String banner = output.toString(StandardCharsets.UTF_8);
        assertThat(banner)
                .contains("PIXIVDOWNLOAD PLUGIN DEVELOPMENT MODE ENABLED")
                .contains("Compiled plugin modules: 1 [pixivdownload-plugin-compiled]")
                .contains("Source plugin modules without target/classes output:  "
                        + "[pixivdownload-plugin-source-only]");
    }
}
