package top.sywyar.pixivdownload.gui;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.*;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

@DisplayName("导入窗口状态与真实退出、替代进程的衔接")
class WindowStateTransferProcessTest {
    @TempDir Path root;

    @ParameterizedTest
    @ValueSource(strings = {"first-save", "resized", "external-change", "unselected", "restart-fails"})
    @DisplayName("正常关闭保留待恢复基线，未选中或重启失败继续保存，外部修改仍拒绝")
    void handsOffWithoutOverwritingWindowBaseline(String scenario) throws Exception {
        Process source = WindowStateTransferTestEntry.command(root, scenario)
                .redirectErrorStream(true).redirectOutput(root.resolve("source.log").toFile()).start();
        ProcessHandle replacement = null;
        try {
            assertThat(source.waitFor(60, TimeUnit.SECONDS)).as("source exit").isTrue();
            assertThat(source.exitValue()).as(Files.readString(root.resolve("source.log"))).isZero();
            if (!scenario.equals("restart-fails")) {
                Path pid = root.resolve("replacement.pid");
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
                while (!Files.exists(pid) && System.nanoTime() < deadline) Thread.sleep(25);
                replacement = ProcessHandle.of(Long.parseLong(Files.readString(pid))).orElse(null);
                if (replacement != null) replacement.onExit().get(60, TimeUnit.SECONDS);
                assertThat(root.resolve("completed")).as(Files.readString(root.resolve("replacement.log"))).exists();
                assertThat(Files.readString(root.resolve("completed"))).isEqualTo("predecessor-exited; instance-acquired");
            }
            var json = new ObjectMapper();
            var job = json.readTree(root.resolve("data/environment-transfers/pending.json").toFile());
            assertThat(job.path("status").asText()).isEqualTo(
                    scenario.equals("external-change") || scenario.equals("restart-fails") ? "FAILED" : "SUCCEEDED");
            assertThat(Files.readString(root.resolve("close-save-result"))).isEqualTo(
                    scenario.equals("unselected") || scenario.equals("restart-fails") ? "true" : "false");
            var window = json.readTree(root.resolve("state/gui/window-state.json").toFile());
            assertThat(window.path("width").intValue()).isEqualTo(switch (scenario) {
                case "external-change" -> 1500;
                case "unselected", "restart-fails" -> 1300;
                default -> 1440;
            });
            assertThat(Files.readString(root.resolve("config/config.yaml"))).contains(
                    scenario.equals("external-change") || scenario.equals("restart-fails") ? "server.port: 7001" : "server.port: 8123");
        } finally {
            if (source.isAlive()) source.destroyForcibly().waitFor();
            if (replacement != null && replacement.isAlive()) {
                replacement.destroyForcibly();
                replacement.onExit().get(10, TimeUnit.SECONDS);
            }
        }
    }
}
