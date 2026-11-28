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
        haltChild(action, committed, committed ? 72 : 71);
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

    @ParameterizedTest
    @CsvSource({"prepare,external-same,guarded", "prepare,external-different,plain",
            "install,own,plain", "install,own,guarded", "install,external-same,guarded",
            "install,external-different,plain", "install,edited-in-place,guarded",
            "legacy,external-same,plain", "legacy,external-different,plain"})
    @org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
    @DisplayName("新建目标按发布身份恢复；准备后出现的外部文件和旧日志的歧义目标不阻断重启")
    void recoversNewTargetWithoutClaimingExternalFiles(String boundary, String owner, String mode,
            org.springframework.boot.test.system.CapturedOutput output) throws Exception {
        Path outside = Files.createDirectories(directory.resolve("previous-custom-root"));
        Path previous = Files.writeString(outside.resolve("body.txt"), "old body", StandardCharsets.UTF_8);
        Path target = outside.resolve("new-image.png");
        haltChild(boundary + "-" + mode, false, boundary.equals("prepare") ? 73 : 71);
        assertThat(Files.exists(target)).isEqualTo(boundary.equals("install"));
        String external = owner.equals("external-same") ? "new image" : "independent image";
        if (!owner.equals("own")) {
            // A new directory entry can point to identical bytes. An edit can also keep the original file identity.
            if (!owner.equals("edited-in-place")) Files.deleteIfExists(target);
            Files.writeString(target, external, StandardCharsets.UTF_8);
        }
        String property = RuntimeFiles.DATA_DIR_PROPERTY;
        String saved = System.getProperty(property);
        System.setProperty(property, directory.resolve("data").toString());
        try (var source = new SingleConnectionDataSource("jdbc:sqlite:" + directory.resolve("facts.db"), true)) {
            var journal = FileOperationTestSupport.journal(source);
            assertThat(journal.operations()).hasSize(1);
            String operation = journal.operations().getFirst();
            assertThat(journal.entries(operation)).hasSize(2);
            var recovery = new StagedFileDeletion(TestI18nBeans.appMessages(), journal);
            recovery.recoverPending();
            recovery.recoverPending();
            assertThat(journal.operations()).isEmpty();
            assertThat(Files.readString(previous)).isEqualTo("old body");
            assertThat(new JdbcTemplate(source).queryForObject("SELECT value FROM business", String.class)).isEqualTo("old");
            if (owner.equals("own")) assertThat(target).doesNotExist();
            else assertThat(Files.readString(target)).isEqualTo(external);
            var notices = output.getAll().lines().filter(line -> line.contains(operation)).toList();
            if (owner.equals("own")) assertThat(notices).isEmpty();
            else assertThat(notices).singleElement().asString().contains("WARN", "preserved 1").doesNotContain(target.toString(), external);
            try (var files = Files.list(outside); var backups = Files.list(RuntimeFiles.deleteStagingDirectory())) {
                assertThat(files.map(path -> path.getFileName().toString())).noneMatch(name -> name.startsWith(".work-file-"));
                assertThat(backups).isEmpty();
            }
        } finally {
            if (saved == null) System.clearProperty(property); else System.setProperty(property, saved);
        }
    }

    private void haltChild(String action, boolean committed, int expectedExit) throws Exception {
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        Path arguments = directory.resolve("child.args");
        Files.writeString(arguments, "-cp\n" + quote(classpath) + "\n" + Child.class.getName() + "\n"
                + quote(directory.toString()) + "\n" + action + "\n" + committed, StandardCharsets.UTF_8);
        Path log = directory.resolve("child.log");
        Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "@" + arguments).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertThat(child.waitFor(45, TimeUnit.SECONDS)).isTrue();
            assertThat(child.exitValue()).withFailMessage(Files.readString(log)).isEqualTo(expectedExit);
        } finally {
            if (child.isAlive()) child.destroyForcibly().waitFor();
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
                    if (args[1].startsWith("prepare-") && Boolean.TRUE.equals(jdbc.queryForObject(
                            "SELECT EXISTS(SELECT 1 FROM file_operation_entries WHERE committed = 0)", Boolean.class))) {
                        Runtime.getRuntime().halt(73);
                    }
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
                Path newTarget = target.resolveSibling("new-image.png");
                var files = new LinkedHashMap<Path, Path>();
                files.put(target, staged);
                files.put(newTarget, image);
                if (args[1].startsWith("legacy-")) {
                    // Old writers recorded hashes and backups, with no durable new-target identity.
                    String operation = UUID.randomUUID().toString();
                    Path backups = Files.createDirectories(RuntimeFiles.deleteStagingDirectory().resolve(operation));
                    Files.copy(target, backups.resolve("0.old"));
                    Files.copy(staged, backups.resolve("0.new"));
                    Files.copy(image, backups.resolve("1.new"));
                    FileOperationTestSupport.journal(source, manager).prepare(operation, List.of(
                            new FileOperationJournal.Entry(0, target, hash(target), hash(staged)),
                            new FileOperationJournal.Entry(1, newTarget, null, hash(image))));
                    Files.copy(staged, target, StandardCopyOption.REPLACE_EXISTING);
                    Runtime.getRuntime().halt(71);
                }
                if (args[1].endsWith("-guarded")) deletion.changeFiles(files, List.of(), Set.of(newTarget), records);
                else deletion.publishFiles(files, records);
            }
            throw new AssertionError("Expected process termination");
        }

        private static String hash(Path path) throws Exception {
            return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
        }
    }
}
