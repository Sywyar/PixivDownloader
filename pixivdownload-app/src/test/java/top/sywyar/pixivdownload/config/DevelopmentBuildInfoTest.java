package top.sywyar.pixivdownload.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("开发运行的源码身份")
class DevelopmentBuildInfoTest {
    @TempDir Path root;

    @Test
    @DisplayName("真实 Git 工作树读取最近有效可达标签、分支、提交和未提交状态")
    void identifiesCheckout() throws Exception {
        git("init", "-b", "fixture/build-info");
        Files.writeString(root.resolve("source.txt"), "one", StandardCharsets.UTF_8);
        git("add", "source.txt");
        commit();
        git("tag", "v7.3.2");
        Files.writeString(root.resolve("source.txt"), "two", StandardCharsets.UTF_8);
        git("add", "source.txt");
        commit();
        git("tag", "v9.9.9-invalid");
        var clean = DevelopmentBuildInfo.read(root, root);
        assertThat(clean.baseVersion()).isEqualTo("7.3.2");
        assertThat(clean.branch()).isEqualTo("fixture/build-info");
        assertThat(clean.revision()).isEqualTo(git("rev-parse", "--short=8", "HEAD").strip());
        assertThat(clean.displayVersion("4.5.6")).isEqualTo("4.5.6-dev." + clean.revision());
        Files.writeString(root.resolve("untracked.txt"), "new", StandardCharsets.UTF_8);
        assertThat(DevelopmentBuildInfo.read(root, root).displayVersion("4.5.6"))
                .isEqualTo("4.5.6-dev." + clean.revision() + ".dirty");
        Path linked = root.resolve("linked");
        git("worktree", "add", "--detach", linked.toString(), "HEAD");
        var detached = DevelopmentBuildInfo.read(linked, linked);
        assertThat(detached.branch()).isEmpty();
        assertThat(detached.revision()).isEqualTo(clean.revision());
        assertThat(detached.baseVersion()).isEqualTo("7.3.2");
        assertThat(detached.directory()).isEqualTo(linked.toAbsolutePath().toString());
    }

    @Test
    @DisplayName("非 Git 目录保留运行目录且版本明确标为未知开发身份")
    void sourceArchive() {
        var info = DevelopmentBuildInfo.read(root, root);
        assertThat(info.directory()).isEqualTo(root.toAbsolutePath().toString());
        assertThat(info.branch()).isEmpty();
        assertThat(info.baseVersion()).isEmpty();
        assertThat(info.displayVersion("4.5.6")).isEqualTo("4.5.6-dev.unknown");
    }

    private void commit() throws Exception {
        git("-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid",
                "-c", "commit.gpgsign=false", "commit", "-m", "fixture");
    }

    @Test
    @DisplayName("Git 状态输出超限时不把未核实的工作树标记为干净提交")
    void oversizedStatusIsUnknown() throws Exception {
        git("init", "-b", "fixture/large-status");
        Files.writeString(root.resolve("source.txt"), "source", StandardCharsets.UTF_8);
        git("add", "source.txt");
        commit();
        for (int index = 0; index < 400; index++) {
            Files.writeString(root.resolve("untracked-" + index + "-" + "x".repeat(180)), "", StandardCharsets.UTF_8);
        }
        var info = DevelopmentBuildInfo.read(root, root);
        assertThat(info.branch()).isEqualTo("fixture/large-status");
        assertThat(info.displayVersion("4.5.6")).isEqualTo("4.5.6-dev.unknown");
    }

    private String git(String... arguments) throws Exception {
        var command = new ArrayList<>(List.of("git", "-C", root.toString()));
        command.addAll(List.of(arguments));
        var process = new ProcessBuilder(command).redirectErrorStream(true).start();
        assertThat(process.waitFor(10, TimeUnit.SECONDS)).isTrue();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.exitValue()).describedAs(output).isZero();
        return output;
    }
}
