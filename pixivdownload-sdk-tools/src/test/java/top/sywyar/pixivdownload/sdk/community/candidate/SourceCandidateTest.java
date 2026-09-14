package top.sywyar.pixivdownload.sdk.community.candidate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.sywyar.pixivdownload.sdk.community.format.CommunityJson;
import top.sywyar.pixivdownload.sdk.community.project.PluginProjectMarker;
import top.sywyar.pixivdownload.sdk.community.submission.VersionSubmission.BuildProfile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import static org.assertj.core.api.Assertions.*;

@DisplayName("源码 CI 候选归档")
class SourceCandidateTest {
    @TempDir Path root;

    @Test
    @DisplayName("三种工程均归档模型指定的原始包，拒绝路径与版本冲突且不执行插件类")
    void packagesExactModelOutput() throws Exception {
        Process init = new ProcessBuilder("git", "-C", root.toString(), "init", "--quiet").start();
        assertThat(init.waitFor()).isZero();
        Path source = root.resolve("Probe.java");
        Files.writeString(source, "package sample; public class Probe { static { if (true) throw new Error(\"MUST_NOT_LOAD\"); } }", StandardCharsets.UTF_8);
        assertThat(javax.tools.ToolProvider.getSystemJavaCompiler().run(null, null, null, "--release", "17", "-d", root.toString(), source.toString())).isZero();
        byte[] code = Files.readAllBytes(root.resolve("sample/Probe.class"));
        for (String tool : List.of("maven", "gradle", "sbt")) {
            Path project = Files.createDirectory(root.resolve(tool));
            Files.writeString(project.resolve(PluginProjectMarker.FILE_NAME), "pixivdownloader-plugin-project-v1\n", StandardCharsets.UTF_8);
            Files.writeString(project.resolve(switch (tool) { case "maven" -> "pom.xml"; case "gradle" -> "build.gradle.kts"; default -> "build.sbt"; }), "", StandardCharsets.UTF_8);
            Process add = new ProcessBuilder("git", "-C", root.toString(), "add", "--", tool).start();
            assertThat(add.waitFor()).isZero();
            Path artifact = project.resolve("actual-output.jar");
            try (var jar = new JarOutputStream(Files.newOutputStream(artifact))) {
                jar.putNextEntry(new JarEntry("plugin.properties"));
                jar.write("plugin.id=sample\nplugin.version=7.8.9-rc.12\nplugin.class=sample.Probe\npixiv.execution-mode=host-process-full-trust\n".getBytes(StandardCharsets.UTF_8));
                jar.closeEntry(); jar.putNextEntry(new JarEntry("sample/Probe.class")); jar.write(code); jar.closeEntry();
            }
            var profile = new BuildProfile(tool + "-java17-v1", tool, "actual-output.jar");
            Path output = Files.createDirectory(root.resolve(tool + "-candidate"));
            var candidate = SourceCandidate.create(root, profile, List.of("actual-output.jar"), "7.8.9-rc.12",
                    "1234", "owner/source", "a".repeat(40), "5678", 1, output);
            assertThat(SourceCandidate.read(Files.readAllBytes(output.resolve("source-candidate.json")))).isEqualTo(candidate);
            assertThat(Files.readAllBytes(output.resolve(candidate.artifact().file()))).isEqualTo(Files.readAllBytes(artifact));
            assertThat(candidate.artifact().sha256()).isEqualTo(CommunityJson.sha256(Files.readAllBytes(artifact)));
            assertThatThrownBy(() -> SourceCandidate.create(root, profile, List.of("actual-output.jar"), "7.8.10",
                    "1234", "owner/source", "a".repeat(40), "5678", 1, output)).hasMessageContaining("MODEL_PACKAGE_VERSION_MISMATCH");
            assertThatThrownBy(() -> SourceCandidate.read(new String(CommunityJson.encode(candidate), StandardCharsets.UTF_8)
                    .replace(candidate.artifact().file(), "../escape.jar").getBytes(StandardCharsets.UTF_8))).isInstanceOf(RuntimeException.class);
        }
    }
}
