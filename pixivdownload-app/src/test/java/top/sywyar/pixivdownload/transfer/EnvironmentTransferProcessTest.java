package top.sywyar.pixivdownload.transfer;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import top.sywyar.pixivdownload.transfer.archive.TransferArchive;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

@DisplayName("配置导入的真实进程交接")
class EnvironmentTransferProcessTest {
    @TempDir Path root;
    private Path config;
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach void setup() throws Exception {
        config = Files.createDirectory(root.resolve("config")).resolve("config.yaml");
        Files.writeString(config, "# target\nserver.port: 7001\nunknown: keep\n", StandardCharsets.UTF_8);
        new TransferArchive().write(root.resolve("settings.pdbackup"), null, "fixture", List.of(new TransferArchive.Source(
                new TransferArchive.Descriptor("host", TransferArchive.Category.CONFIG, "settings.json", 1),
                () -> new ByteArrayInputStream("{\"server.port\":\"8123\"}".getBytes(StandardCharsets.UTF_8)))));
    }

    @Test @DisplayName("预览无恢复文件，确认不改配置，下一进程持锁导入并保留备份")
    void appliesOnlyInReplacementProcess() throws Exception {
        byte[] original = Files.readAllBytes(config);
        assertThat(run("preview")).isZero();
        assertThat(root.resolve("data")).doesNotExist();
        assertThat(run("prepare")).isZero();
        assertThat(Files.readAllBytes(config)).isEqualTo(original);
        assertThat(status()).isEqualTo("PENDING");
        assertThat(run("run")).isZero();
        assertThat(status()).isEqualTo("SUCCEEDED");
        assertThat(Files.readString(config)).contains("# target", "server.port: 8123", "unknown: keep");
        var job = json.readTree(journal().toFile());
        Path backup = journal().getParent().resolve(job.path("id").asText()).resolve("files/0.before");
        assertThat(Files.readAllBytes(backup)).isEqualTo(original);
        assertThat(run("run")).isZero();
        assertThat(status()).isEqualTo("SUCCEEDED");
    }

    @Test @DisplayName("准备后目标变化保留新内容，重启请求失败不在下次启动重放")
    void rejectsConflictsAndCancelledRestart() throws Exception {
        assertThat(run("prepare")).isZero();
        Files.writeString(config, "server.port: 7333\n");
        assertThat(run("run")).isZero();
        assertThat(status()).isEqualTo("FAILED");
        assertThat(Files.readString(config)).contains("7333");
        assertThat(run("restart-fails")).isNotZero();
        assertThat(status()).isEqualTo("FAILED");
        assertThat(run("run")).isZero();
        assertThat(Files.readString(config)).contains("7333");
    }

    @Test @DisplayName("准备失败清理本次未安排的私有暂存，不留下下次自动执行的任务")
    void discardsUnscheduledPreparation() throws Exception {
        byte[] original = Files.readAllBytes(config);
        assertThat(run("invalid-selection")).isNotZero();
        assertThat(journal()).doesNotExist();
        try (var files = Files.list(journal().getParent())) { assertThat(files.toList()).isEmpty(); }
        assertThat(Files.readAllBytes(config)).isEqualTo(original);
    }

    private Path journal() { return root.resolve("data/environment-transfers/pending.json"); }
    private String status() throws Exception { return json.readTree(journal().toFile()).path("status").asText(); }
    private int run(String action) throws Exception {
        List<String> command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-Dfile.encoding=UTF-8"));
        for (String name : List.of("config", "state", "data", "instance", "plugins"))
            command.add("-Dpixivdownload." + name + "-dir=" + root.resolve(name));
        command.addAll(List.of("-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                EnvironmentTransferTestEntry.class.getName(), action, root.resolve("settings.pdbackup").toString()));
        Path log = root.resolve(action + ".log");
        Process process = new ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertThat(process.waitFor(60, TimeUnit.SECONDS)).as("configuration transfer child should finish").isTrue();
            return process.exitValue();
        } finally {
            if (process.isAlive()) process.destroyForcibly().waitFor();
        }
    }
}
