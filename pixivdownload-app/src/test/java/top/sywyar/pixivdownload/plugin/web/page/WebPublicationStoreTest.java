package top.sywyar.pixivdownload.plugin.web.page;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import top.sywyar.pixivdownload.plugin.api.security.OperationVerification;
import top.sywyar.pixivdownload.plugin.api.web.page.PagePublications;
import top.sywyar.pixivdownload.plugin.api.web.page.PageResourceProfile;
import static org.assertj.core.api.Assertions.*;
import static top.sywyar.pixivdownload.plugin.web.page.WebPublicationStore.*;

@DisplayName("Web 正式发布独立存储与故障恢复")
class WebPublicationStoreTest {
    @TempDir Path root;
    Path directory() { return root.resolve("data"); }
    Path enrollment() { return root.resolve("config/enrolled"); }
    Snapshot next() {
        return snapshot(1, new WebPublicationStore(directory(), enrollment()).snapshot().epoch());
    }

    static Snapshot snapshot(long revision, String epoch) {
        var profile = new PageResourceProfile("resources", "r" + revision, List.of(
                new PageResourceProfile.Source(revision == 1 ? PageResourceProfile.Kind.SCRIPT : PageResourceProfile.Kind.WORKER, "self")));
        var policy = new OperationVerification.Policy("p" + revision, "", OperationVerification.INTERACTION, List.of(profile));
        var requirement = new Requirement("owner:submit", "{\"type\":\"object\"}", "proof", "verifier", "verifier-package", policy);
        var page = new Page("{\"draft\":" + revision + "}", "{\"pageId\":\"owner:page\",\"title\":\"Revision " + revision + "\"}", "",
                List.of(requirement), List.of(new PagePublications.Permission("verifier", "proof", profile)));
        return new Snapshot(revision, epoch, Map.of("owner:page", page));
    }

    @ParameterizedTest @ValueSource(strings = {"prepare", "snapshot", "replace", "committed", "activate", "returned"})
    @DisplayName("实际进程在写入和返回前退出，重启只恢复完整的页面、保护和资源授权")
    void recoversAtomicCommit(String point) throws Exception {
        var store = new WebPublicationStore(directory(), enrollment());
        var previous = next();
        store.commit(0, previous);
        Path arguments = root.resolve("child.args");
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        Files.writeString(arguments, "-cp\n" + quote(classpath) + "\n" + CrashWriter.class.getName() + "\n"
                + quote(root.toString()) + "\n" + point, StandardCharsets.UTF_8);
        Path log = root.resolve("child.log");
        Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "@" + arguments).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertThat(child.waitFor(45, TimeUnit.SECONDS)).isTrue();
            assertThat(child.exitValue()).withFailMessage(Files.readString(log, StandardCharsets.UTF_8)).isEqualTo(25);
        } finally { if (child.isAlive()) child.destroyForcibly().waitFor(); }
        var recovered = new WebPublicationStore(directory(), enrollment()).snapshot();
        boolean committed = List.of("committed", "activate", "returned").contains(point);
        assertThat(recovered).isEqualTo(committed ? snapshot(2, previous.epoch()) : previous);
    }

    private static String quote(String value) { return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""; }

    public static final class CrashWriter {
        public static void main(String[] args) {
            top.sywyar.pixivdownload.common.Utf8ConsoleStreams.install();
            Path root = Path.of(args[0]);
            var store = new WebPublicationStore(root.resolve("data"), root.resolve("config/enrolled"), at -> {
                if (at.equals(args[1])) Runtime.getRuntime().halt(25);
            });
            var previous = store.snapshot();
            store.commit(previous.revision(), snapshot(previous.revision() + 1, previous.epoch()));
            if (args[1].equals("returned")) Runtime.getRuntime().halt(25);
            throw new AssertionError("Expected process termination");
        }
    }

    @Test @DisplayName("缺失或损坏提交记录及不可变快照不会补写空策略")
    void rejectsMissingAndCorruptState() throws Exception {
        var store = new WebPublicationStore(directory(), enrollment());
        store.commit(0, next());
        Path current = directory().resolve("current.json");
        byte[] committed = Files.readAllBytes(current);
        Files.delete(current);
        assertThatThrownBy(store::snapshot).hasMessage("PAGE_PUBLICATION_UNAVAILABLE");
        assertThatThrownBy(() -> new WebPublicationStore(directory(), enrollment()).snapshot())
                .hasMessage("PAGE_PUBLICATION_UNAVAILABLE");
        assertThat(Files.exists(current)).isFalse();
        Files.writeString(current, "{}");
        assertThatThrownBy(store::snapshot).hasMessage("PAGE_PUBLICATION_UNAVAILABLE");
        Files.write(current, committed);
        String file = JSON.readTree(committed).path("file").asText();
        Files.writeString(directory().resolve(file), "{}");
        assertThatThrownBy(store::snapshot).hasMessage("PAGE_PUBLICATION_UNAVAILABLE");
        assertThatThrownBy(() -> new WebPublicationStore(directory(), enrollment()).snapshot())
                .hasMessage("PAGE_PUBLICATION_UNAVAILABLE");
    }

    @Test @DisplayName("并发修订冲突和登记缺失均保留原提交，不隐式重新初始化")
    void casAndEnrollment() throws Exception {
        var first = new WebPublicationStore(directory(), enrollment());
        var second = new WebPublicationStore(directory(), enrollment());
        first.commit(0, next());
        assertThatThrownBy(() -> second.commit(0, next())).hasMessage("PAGE_REVISION_CONFLICT");
        assertThat(second.snapshot()).isEqualTo(next());
        Files.delete(enrollment());
        assertThatThrownBy(() -> new WebPublicationStore(directory(), enrollment()).snapshot())
                .hasMessage("PAGE_PUBLICATION_UNAVAILABLE");
        assertThat(Files.exists(enrollment())).isFalse();
    }

    @Test @DisplayName("重复发布只保留有界快照，清理不影响其它文件或当前提交")
    void boundsSnapshotRetention() throws Exception {
        var store = new WebPublicationStore(directory(), enrollment());
        Files.writeString(directory().resolve("operator-note.txt"), "keep");
        for (int revision = 0; revision < 12; revision++) {
            store.commit(revision, new Snapshot(revision + 1, store.snapshot().epoch(), next().pages()));
            assertThat(store.snapshot().revision()).isEqualTo(revision + 1);
        }
        try (var files = Files.list(directory())) {
            assertThat(files.filter(path -> path.getFileName().toString().matches("[a-f0-9-]{36}\\.json")).count()).isEqualTo(2);
        }
        assertThat(Files.readString(directory().resolve("operator-note.txt"))).isEqualTo("keep");
        assertThat(new WebPublicationStore(directory(), enrollment()).snapshot().revision()).isEqualTo(12);
    }

    @Test @DisplayName("本机恢复读取不初始化，精确摘要恢复完整保护并更新文档代际")
    void explicitRecoveryPreservesProtectionAndInvalidatesDocuments() throws Exception {
        var reader = WebPublicationStore.openForRecovery(directory(), enrollment());
        assertThatThrownBy(reader::snapshot).hasMessage("PAGE_PUBLICATION_UNAVAILABLE");
        assertThat(Files.exists(directory())).isFalse();
        assertThat(Files.exists(enrollment())).isFalse();
        var store = new WebPublicationStore(directory(), enrollment());
        var expected = next();
        store.commit(0, expected);
        var selected = reader.recoveryCandidates().get(0);
        assertThat(selected.revision()).isEqualTo(1);
        String oldIdentity = store.snapshot().identity();
        byte[] committed = Files.readAllBytes(directory().resolve("current.json"));
        assertThatThrownBy(() -> reader.recover("../current.json", selected.sha256())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> reader.recover(selected.file(), "0".repeat(64))).hasMessage("PAGE_RECOVERY_DIGEST_CHANGED");
        assertThat(Files.readAllBytes(directory().resolve("current.json"))).isEqualTo(committed);
        Files.writeString(directory().resolve("current.json"), "broken");
        Files.delete(enrollment());
        reader.recover(selected.file(), selected.sha256());
        var recovered = new WebPublicationStore(directory(), enrollment()).snapshot();
        assertThat(recovered.pages()).isEqualTo(expected.pages());
        assertThat(recovered.identity()).isNotEqualTo(oldIdentity);
        assertThat(recovered.revision()).isZero();
        assertThat(reader.recoveryCandidates()).anyMatch(candidate -> candidate.sha256().equals(selected.sha256()));
    }
}
