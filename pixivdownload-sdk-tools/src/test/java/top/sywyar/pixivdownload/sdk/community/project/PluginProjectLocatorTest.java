package top.sywyar.pixivdownload.sdk.community.project;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import top.sywyar.pixivdownload.sdk.community.format.ContractException;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

@DisplayName("Git 工程定位与真实路径边界")
class PluginProjectLocatorTest {
    @TempDir Path root;

    @Test
    @DisplayName("仅 Git 跟踪的标识参加选择，四工程和多个包候选均需明确选择")
    void selectsTrackedProjectsAndModelOutputs() throws Exception {
        git("init", "--quiet");
        for (String project : List.of(".", "examples/download-type-plugin", "examples/gradle-plugin", "examples/sbt-plugin")) {
            Path folder = root.resolve(project);
            Files.createDirectories(folder);
            Files.writeString(folder.resolve(PluginProjectMarker.FILE_NAME), "pixivdownloader-plugin-project-v1\n", StandardCharsets.UTF_8);
            String model = project.contains("gradle") ? "build.gradle" : project.contains("sbt") ? "build.sbt" : "pom.xml";
            Files.writeString(folder.resolve(model), "", StandardCharsets.UTF_8);
            Files.createDirectories(folder.resolve("output"));
            Files.writeString(folder.resolve("output/plugin.jar"), "fixture", StandardCharsets.UTF_8);
        }
        assertThat(PluginProjectLocator.discover(root)).isEmpty();
        git("add", ".");
        assertThat(PluginProjectLocator.discover(root)).extracting(PluginProjectLocator.Project::projectDir)
                .containsExactly(".", "examples/download-type-plugin", "examples/gradle-plugin", "examples/sbt-plugin");
        assertThatThrownBy(() -> PluginProjectLocator.select(root, null, "maven-java17-v1", null, List.of("output/plugin.jar")))
                .isInstanceOf(ContractException.class);
        for (var project : PluginProjectLocator.discover(root)) {
            var result = PluginProjectLocator.select(root, project.projectDir(), project.profiles().get(0), null, List.of("output/plugin.jar"));
            assertThat(result.artifact()).isEqualTo(root.resolve(project.projectDir()).normalize().resolve("output/plugin.jar"));
        }
        assertThatThrownBy(() -> PluginProjectLocator.select(root, ".", "maven-java17-v1", null,
                List.of("output/plugin.jar", "output/another.jar"))).isInstanceOf(ContractException.class);
        assertThatThrownBy(() -> PluginProjectLocator.select(root, ".", "maven-java17-v1", "other.jar",
                List.of("output/plugin.jar"))).isInstanceOf(ContractException.class);
        assertThat(PluginProjectLocator.select(root, ".", "maven-java17-v1", "output/plugin.jar",
                List.of("output/plugin.jar", "output/another.jar")).artifact()).endsWith(Path.of("output/plugin.jar"));
    }

    @Test
    @DisplayName("工程自身可达到 64 层，固定 marker 不占用 projectDir 的层数预算")
    void locatesMaximumProjectDepth() throws Exception {
        git("init", "--quiet");
        String projectDir = "a/".repeat(63) + "a";
        Path project = Files.createDirectories(root.resolve(projectDir));
        Files.writeString(project.resolve(PluginProjectMarker.FILE_NAME), "pixivdownloader-plugin-project-v1\n", StandardCharsets.UTF_8);
        Files.writeString(project.resolve("pom.xml"), "", StandardCharsets.UTF_8);
        git("-c", "core.longpaths=true", "add", ".");
        assertThat(PluginProjectLocator.discover(root)).extracting(PluginProjectLocator.Project::projectDir).containsExactly(projectDir);
    }

    @Test
    @DisplayName("相对路径单位与真实别名检测，不能通过分隔符设备名或链接越界")
    void validatesPortablePaths() throws Exception {
        for (String path : List.of("../x", "a/../x", "/x", "C:/x", "a\\b", "a//b", "a/", "con.txt", "NUL",
                "x.", "x ", "a/COM1.bin", "ｃｏｎ.txt", "a:stream")) {
            assertThatThrownBy(() -> CommunityPaths.relative(path, false)).isInstanceOf(ContractException.class);
        }
        String exact = "a/".repeat(63) + "x".repeat(898);
        assertThat(CommunityPaths.relative(exact, false)).hasSize(1024);
        assertThatThrownBy(() -> CommunityPaths.relative(exact + "x", false)).isInstanceOf(ContractException.class);
        assertThat(CommunityPaths.relative("a/".repeat(63) + "x", false)).isNotBlank();
        assertThatThrownBy(() -> CommunityPaths.relative("a/".repeat(64) + "x", false)).isInstanceOf(ContractException.class);
        Files.writeString(root.resolve("Example.jar"), "existing", StandardCharsets.UTF_8);
        assertThatThrownBy(() -> CommunityPaths.resolve(root, "example.jar", false, false)).isInstanceOf(ContractException.class);
        Files.writeString(root.resolve("Ａ.jar"), "existing", StandardCharsets.UTF_8);
        assertThatThrownBy(() -> CommunityPaths.resolve(root, "A.jar", false, false)).isInstanceOf(ContractException.class);
        assertThat(CommunityPaths.resolve(root, "Example.jar", false, true)).isEqualTo(root.resolve("Example.jar"));
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    @DisplayName("真实 Windows 组件长度与父级目录联接错误不能当成文件缺失")
    void rejectsWindowsPaths() throws Exception {
        String exact = "x".repeat(255);
        Files.writeString(root.resolve(exact), "data", StandardCharsets.UTF_8);
        assertThat(CommunityPaths.resolve(root, exact, false, true)).isEqualTo(root.resolve(exact));
        assertThatThrownBy(() -> CommunityPaths.resolve(root, exact + "x", false, false)).isInstanceOf(Exception.class);
        Path target = Files.createDirectory(root.resolve("target"));
        Path junction = root.resolve("junction");
        Process process = new ProcessBuilder("cmd.exe", "/c", "mklink", "/J", junction.toString(), target.toString())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        assertThat(process.waitFor()).isZero();
        try {
            assertThatThrownBy(() -> CommunityPaths.resolve(root, "junction/file", false, false)).isInstanceOf(ContractException.class);
            assertThatThrownBy(() -> CommunityPaths.resolve(junction, "file", false, false)).isInstanceOf(ContractException.class);
        } finally { Files.delete(junction); }
    }

    private void git(String... arguments) throws Exception {
        var command = new ArrayList<>(List.of("git", "-C", root.toString()));
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor()).as(output).isZero();
    }
}
