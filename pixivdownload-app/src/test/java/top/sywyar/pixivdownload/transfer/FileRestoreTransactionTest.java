package top.sywyar.pixivdownload.transfer;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

@DisplayName("文件导入的耐久备份与中断恢复")
class FileRestoreTransactionTest {
    @TempDir Path root;
    private final ObjectMapper json = new ObjectMapper();

    @Test @DisplayName("准备不改目标，提交回读验证并保留空文件与原内容的备份")
    void preparesAndCommitsWithoutDiscardingBackups() throws Exception {
        Path target = write("config", "# original\nport: 1");
        Path empty = write("empty", "");
        Path source = write("source", "# original\nport: 2");
        Path run = root.resolve("run");
        var transaction = new FileRestoreTransaction(run, Map.of("config", target, "empty", empty));
        transaction.prepare(List.of(new FileRestoreTransaction.Replacement("config", source, FileRestoreTransaction.stateDigest(target)),
                new FileRestoreTransaction.Replacement("empty", source, FileRestoreTransaction.stateDigest(empty))));
        assertThat(Files.readString(target)).contains("port: 1");
        transaction.commit();
        transaction.commit();
        assertThat(Files.readString(target)).isEqualTo(Files.readString(source));
        assertThat(Files.readString(run.resolve("0.before"))).contains("port: 1");
        assertThat(Files.size(run.resolve("1.before"))).isZero();
        assertThat(transaction.recover()).isEqualTo(FileRestoreTransaction.Status.COMPLETE);
    }

    @Test @DisplayName("确认后目标改变时拒绝提交，不覆盖其它操作的结果")
    void rejectsChangedTargetsBeforeWriting() throws Exception {
        Path target = write("config", "original");
        Path source = write("source", "imported");
        var transaction = new FileRestoreTransaction(root.resolve("run"), Map.of("config", target));
        transaction.prepare(List.of(new FileRestoreTransaction.Replacement("config", source, FileRestoreTransaction.stateDigest(target))));
        Files.writeString(target, "other-save");
        assertThatThrownBy(transaction::commit).hasMessage("transfer.target-changed");
        assertThat(Files.readString(target)).isEqualTo("other-save");
    }

    @Test @DisplayName("重命名成功但进度尚未写回时可恢复，新增文件也恢复为不存在")
    void recoversInterruptedWritesIncludingNewFiles() throws Exception {
        Path target = write("config", "original");
        Path created = root.resolve("new-config");
        Path source = write("source", "imported");
        Path run = root.resolve("run");
        var targets = Map.of("config", target, "new", created);
        var transaction = new FileRestoreTransaction(run, targets);
        transaction.prepare(List.of(new FileRestoreTransaction.Replacement("config", source, FileRestoreTransaction.stateDigest(target)),
                new FileRestoreTransaction.Replacement("new", source, FileRestoreTransaction.stateDigest(created))));
        interrupted(run, 2);
        Files.copy(run.resolve("0.after"), target, StandardCopyOption.REPLACE_EXISTING);
        Files.copy(run.resolve("1.after"), created);
        assertThat(new FileRestoreTransaction(run, targets).recover()).isEqualTo(FileRestoreTransaction.Status.ROLLED_BACK);
        assertThat(Files.readString(target)).isEqualTo("original");
        assertThat(created).doesNotExist();
        assertThat(run.resolve("0.before")).exists();
        assertThat(transaction.recover()).isEqualTo(FileRestoreTransaction.Status.ROLLED_BACK);
    }

    @Test @DisplayName("恢复冲突保留现场，篡改备份与目标映射均不得继续")
    void preservesConflictsAndRejectsReboundTargets() throws Exception {
        Path target = write("config", "original");
        Path source = write("source", "imported");
        Path run = root.resolve("run");
        var transaction = new FileRestoreTransaction(run, Map.of("config", target));
        transaction.prepare(List.of(new FileRestoreTransaction.Replacement("config", source, FileRestoreTransaction.stateDigest(target))));
        interrupted(run, 1);
        Files.writeString(target, "outside-change");
        assertThatThrownBy(transaction::recover).hasMessage("transfer.target-changed");
        assertThat(Files.readString(target)).isEqualTo("outside-change");
        Path unrelated = write("unrelated", "original");
        assertThatThrownBy(() -> new FileRestoreTransaction(run, Map.of("config", unrelated)).recover())
                .hasMessage("transfer.restore-invalid");
        Files.writeString(run.resolve("0.before"), "tampered");
        assertThatThrownBy(transaction::recover).hasMessage("transfer.restore-invalid");
        assertThat(Files.readString(unrelated)).isEqualTo("original");
    }

    private void interrupted(Path run, int attempted) throws Exception {
        Path file = run.resolve("journal.json");
        var journal = json.readValue(Files.readAllBytes(file), FileRestoreTransaction.Journal.class);
        Files.write(file, json.writeValueAsBytes(new FileRestoreTransaction.Journal(1,
                FileRestoreTransaction.Status.APPLYING, attempted, journal.entries())));
    }
    private Path write(String name, String value) throws Exception {
        return Files.writeString(root.resolve(name), value);
    }
}
