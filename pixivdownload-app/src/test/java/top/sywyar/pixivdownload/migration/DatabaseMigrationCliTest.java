package top.sywyar.pixivdownload.migration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import top.sywyar.pixivdownload.core.db.PostgresTestDatabase;
import top.sywyar.pixivdownload.core.db.sqlite.SqliteDatabase;
import top.sywyar.pixivdownload.gui.SingleInstanceManager;
import top.sywyar.pixivdownload.sdk.SdkVersion;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "PIXIV_TEST_POSTGRES_URL", matches = ".+")
@DisplayName("停机迁移真实命令行入口")
class DatabaseMigrationCliTest {
    @TempDir Path directory;
    private final ObjectMapper json = new ObjectMapper();

    @Test
    @DisplayName("独立进程导出声明、迁移旧库并写入可核对的最终报告，重试不覆盖目标")
    void migratesAndRejectsNonemptyTargetInRealProcess() throws Exception {
        Path source = directory.resolve("old.db");
        try (var connection = SqliteDatabase.openConnection(source); var sql = connection.createStatement()) {
            sql.execute("CREATE TABLE artworks(artwork_id INTEGER PRIMARY KEY,title TEXT NOT NULL,folder TEXT NOT NULL,"
                    + "count INTEGER NOT NULL,extensions TEXT NOT NULL,time INTEGER NOT NULL UNIQUE)");
            sql.execute("INSERT INTO artworks VALUES(5000000001,'旧库 🌸','{0}/作品',1,'png',1700000000123)");
        }
        Path manifest = directory.resolve("schema.json");
        assertThat(run("export", "--export-schema", manifest.toString())).isZero();
        assertThat(json.readTree(manifest.toFile()).path("sdkVersion").asText()).isEqualTo(SdkVersion.VERSION);
        try (var database = new PostgresTestDatabase()) {
            Path config = targetConfig(database);
            Path report = directory.resolve("migration.json");
            assertThat(run("migrate", "--source", source.toString(), "--target-config", config.toString(),
                    "--report", report.toString())).isZero();
            var result = json.readTree(report.toFile());
            assertThat(result.path("status").asText()).isEqualTo("DATA_VERIFIED");
            assertThat(result.path("result").path("tables").path("artworks").path("rows").asLong()).isOne();
            var jdbc = new JdbcTemplate(database.pool());
            assertThat(jdbc.queryForObject("SELECT title FROM artworks", String.class)).isEqualTo("旧库 🌸");
            jdbc.update("INSERT INTO tags(name) VALUES('post-migration')");
            Path retryReport = directory.resolve("retry.json");
            assertThat(run("retry", "--source", source.toString(), "--target-config", config.toString(),
                    "--report", retryReport.toString())).isEqualTo(2);
            assertThat(json.readTree(retryReport.toFile()).path("status").asText()).isEqualTo("FAILED_TARGET_NOT_VERIFIED");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM tags", Integer.class)).isOne();
            assertThat(directory.resolve("data/pixiv_download.db")).doesNotExist();
        }
    }

    @Test
    @DisplayName("已有应用实例持锁时 CLI 拒绝执行且不输出成功报告")
    void refusesHeldInstanceLock() throws Exception {
        String key = "pixivdownload.instance-dir";
        String previous = System.getProperty(key);
        System.setProperty(key, directory.resolve("instance").toString());
        try (var instance = SingleInstanceManager.acquire()) {
            assertThat(instance).isNotNull();
            Path manifest = directory.resolve("blocked.json");
            assertThat(run("locked", "--export-schema", manifest.toString())).isEqualTo(2);
            assertThat(manifest).doesNotExist();
        } finally {
            if (previous == null) System.clearProperty(key); else System.setProperty(key, previous);
        }
    }

    private Path targetConfig(PostgresTestDatabase database) throws Exception {
        var properties = database.properties();
        Path config = directory.resolve("target.yaml");
        Files.writeString(config, "database.engine: postgresql\ndatabase.jdbc-url: "
                + json.writeValueAsString(properties.getJdbcUrl()) + "\ndatabase.username: "
                + json.writeValueAsString(properties.getUsername()) + "\ndatabase.password: "
                + json.writeValueAsString(properties.getPassword()) + "\n", StandardCharsets.UTF_8);
        return config;
    }

    private int run(String label, String... arguments) throws Exception {
        List<String> command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Dfile.encoding=UTF-8", "-Duser.language=en", "-Dpixivdownload.plugin-dev.enabled=false"));
        for (String root : List.of("config", "state", "data", "instance", "plugins")) {
            command.add("-Dpixivdownload." + root + "-dir=" + directory.resolve(root));
        }
        command.addAll(List.of("-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                DatabaseMigration.class.getName()));
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true)
                .redirectOutput(directory.resolve(label + ".log").toFile()).start();
        try {
            assertThat(process.waitFor(60, TimeUnit.SECONDS)).as("CLI should finish").isTrue();
            return process.exitValue();
        } finally {
            if (process.isAlive()) process.destroyForcibly().waitFor();
        }
    }
}
