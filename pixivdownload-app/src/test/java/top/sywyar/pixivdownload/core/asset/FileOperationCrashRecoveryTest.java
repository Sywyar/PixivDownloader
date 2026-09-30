package top.sywyar.pixivdownload.core.asset;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.i18n.TestI18nBeans;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("文件事务在进程退出后的真实 SQLite 恢复")
class FileOperationCrashRecoveryTest {
    @TempDir Path directory;

    @ParameterizedTest
    @CsvSource({"delete,false", "delete,true", "replace,false", "replace,true"})
    @DisplayName("提交前恢复旧文件和记录，提交后保留新状态；恢复允许原下载根以外的登记路径")
    void recoversAfterProcessExit(String action, boolean committed) throws Exception {
        Path outside = Files.createDirectories(directory.resolve("previous-custom-root"));
        Path file = Files.writeString(outside.resolve("body.txt"), "old body", StandardCharsets.UTF_8);
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        Path arguments = directory.resolve("child.args");
        Files.writeString(arguments, "-cp\n" + quote(classpath) + "\n" + Child.class.getName() + "\n"
                + quote(directory.toString()) + "\n" + action + "\n" + committed, StandardCharsets.UTF_8);
        Path log = directory.resolve("child.log");
        Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "@" + arguments).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        assertThat(child.waitFor(45, TimeUnit.SECONDS)).isTrue();
        assertThat(child.exitValue()).withFailMessage(Files.readString(log)).isEqualTo(committed ? 72 : 71);
        String property = RuntimeFiles.DATA_DIR_PROPERTY;
        String previous = System.getProperty(property);
        System.setProperty(property, directory.resolve("data").toString());
        try (var source = new SingleConnectionDataSource("jdbc:sqlite:" + directory.resolve("facts.db"), true)) {
            var jdbc = new JdbcTemplate(source);
            var journal = FileOperationTestSupport.journal(source);
            assertThat(journal.operations()).hasSize(1);
            new StagedFileDeletion(TestI18nBeans.appMessages(), journal).recoverPending();
            assertThat(jdbc.queryForObject("SELECT value FROM business", String.class)).isEqualTo(committed ? "new" : "old");
            if (committed && action.equals("delete")) assertThat(file).doesNotExist();
            else assertThat(Files.readString(file)).isEqualTo(committed ? "new body" : "old body");
            assertThat(Files.exists(outside.resolve("new-image.png"))).isEqualTo(committed && action.equals("replace"));
            assertThat(journal.operations()).isEmpty();
            // 再次启动必须幂等，不复活已提交删除，也不删除已提交替换。
            new StagedFileDeletion(TestI18nBeans.appMessages(), journal).recoverPending();
        } finally {
            if (previous == null) System.clearProperty(property); else System.setProperty(property, previous);
        }
    }

    private static String quote(String value) { return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""; }

    public static final class Child {
        public static void main(String[] args) throws Exception {
            top.sywyar.pixivdownload.common.Utf8ConsoleStreams.install();
            Path root = Path.of(args[0]);
            boolean afterCommit = Boolean.parseBoolean(args[2]);
            System.setProperty(RuntimeFiles.DATA_DIR_PROPERTY, root.resolve("data").toString());
            var source = new SingleConnectionDataSource("jdbc:sqlite:" + root.resolve("facts.db"), true);
            var jdbc = new JdbcTemplate(source);
            jdbc.execute("CREATE TABLE business(value TEXT)");
            jdbc.update("INSERT INTO business VALUES ('old')");
            var manager = new DataSourceTransactionManager(source) {
                @Override protected void doCommit(DefaultTransactionStatus status) {
                    super.doCommit(status);
                    if (afterCommit && Boolean.TRUE.equals(jdbc.queryForObject(
                            "SELECT EXISTS(SELECT 1 FROM file_operation_entries WHERE committed = 1)", Boolean.class))) {
                        Runtime.getRuntime().halt(72);
                    }
                }
            };
            var deletion = new StagedFileDeletion(TestI18nBeans.appMessages(), FileOperationTestSupport.journal(source, manager));
            Path target = root.resolve("previous-custom-root/body.txt");
            Runnable records = () -> {
                jdbc.update("UPDATE business SET value = 'new'");
                if (!afterCommit) Runtime.getRuntime().halt(71);
            };
            if (args[1].equals("delete")) deletion.deleteAtomically(List.of(target), records);
            else {
                Path staged = Files.writeString(root.resolve("new.txt"), "new body", StandardCharsets.UTF_8);
                Path image = Files.writeString(root.resolve("image.png"), "new image", StandardCharsets.UTF_8);
                deletion.publishFiles(Map.of(target, staged, target.resolveSibling("new-image.png"), image), records);
            }
            throw new AssertionError("Expected process termination");
        }
    }
}
