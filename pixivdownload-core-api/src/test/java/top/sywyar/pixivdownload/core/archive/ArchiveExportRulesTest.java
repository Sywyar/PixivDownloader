package top.sywyar.pixivdownload.core.archive;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ArchiveExportRules 归档导出契约测试")
class ArchiveExportRulesTest {

    @Test
    @DisplayName("作品 ID 应保序去重并在归一化后应用排除项")
    void normalizesIdsAndAppliesExclusions() {
        List<Long> ids = Arrays.asList(3L, null, 0L, -1L, 3L, 5L, 7L);

        assertThat(ArchiveExportRules.normalizeIds(ids)).containsExactly(3L, 5L, 7L);
        assertThat(ArchiveExportRules.normalizeIdSet(ids)).containsExactly(3L, 5L, 7L);
        assertThat(ArchiveExportRules.applyExclusions(ids, List.of(5L, 99L)))
                .containsExactly(3L, 7L);
    }

    @Test
    @DisplayName("分组与格式 token 应保持既有大小写和空白规则")
    void normalizesGroupingAndFormatTokens() {
        assertThat(ArchiveExportRules.groupById(" ID ")).isTrue();
        assertThat(ArchiveExportRules.groupById("author")).isFalse();
        assertThat(ArchiveExportRules.normalizeFormatToken(null)).isEqualTo("zip");
        assertThat(ArchiveExportRules.normalizeFormatToken(" ZIP ")).isEqualTo("zip");
        assertThat(ArchiveExportRules.supportsFormat("rar")).isFalse();
    }

    @Test
    @DisplayName("归档路径应净化危险字符并保留安全的相对层级")
    void sanitizesArchiveEntryPaths() {
        assertThat(ArchiveExportRules.safeSegment("a/b:c*d", "fallback")).isEqualTo("a_b_c_d");
        assertThat(ArchiveExportRules.safeSegment("name...", "fallback")).isEqualTo("name");
        assertThat(ArchiveExportRules.safeRelativePath("a\\..\\b")).isEqualTo("a/file/b");
        assertThat(ArchiveExportRules.authorSegment(7L, null)).isEqualTo("author-7");
        assertThat(ArchiveExportRules.workSegment(9L, "title/name")).isEqualTo("9 - title_name");
    }

    @Test
    @DisplayName("条目字节与请求列表应形成防御性快照")
    void snapshotsEntriesAndRequestList() {
        byte[] manifest = "[]".getBytes(StandardCharsets.UTF_8);
        ArchiveExportEntry entry = ArchiveExportEntry.bytes("manifest.json", manifest);
        manifest[0] = '!';

        List<ArchiveExportEntry> entries = new java.util.ArrayList<>();
        entries.add(entry);
        ArchiveExportRequest request = new ArchiveExportRequest(
                entries, "artworks", 1, 1, "zip", null);
        entries.clear();

        assertThat(entry.bytes()).isEqualTo("[]".getBytes(StandardCharsets.UTF_8));
        byte[] exposed = entry.bytes();
        exposed[0] = '!';
        assertThat(entry.bytes()).isEqualTo("[]".getBytes(StandardCharsets.UTF_8));
        assertThat(request.entries()).containsExactly(entry);
        assertThat(ArchiveExportEntry.file(Path.of("a.png"), "works/a.png", 1L).workId())
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("完成后删除指令应只保存作品类型 token 与 ID 快照")
    void snapshotsDeletionInstructionWithoutCallback() {
        List<Long> ids = new java.util.ArrayList<>(List.of(3L, 5L));
        ArchiveWorkDeletion deletion = new ArchiveWorkDeletion(" ARTWORK ", ids);
        ids.clear();

        assertThat(deletion.workType()).isEqualTo("ARTWORK");
        assertThat(deletion.workIds()).containsExactly(3L, 5L);
        assertThatThrownBy(() -> deletion.workIds().add(7L))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> new ArchiveWorkDeletion(" ", List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("空结果应保留已解析作品数且不暴露归档 token")
    void representsEmptyArchive() {
        ArchiveExportResult result = ArchiveExportResult.empty(3);

        assertThat(result.workCount()).isEqualTo(3);
        assertThat(result.fileCount()).isZero();
        assertThat(result.archiveExpireSeconds()).isZero();
        assertThat(result.emptyArchive()).isTrue();
    }
}
