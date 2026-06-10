package top.sywyar.pixivdownload.quota;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import top.sywyar.pixivdownload.i18n.LocalizedException;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ArchiveExportSupport 单元测试")
class ArchiveExportSupportTest {

    @Test
    @DisplayName("normalizeFormat 对空值应回退为 zip，对 ZIP 大小写不敏感")
    void shouldNormalizeFormat() {
        assertThat(ArchiveExportSupport.normalizeFormat(null)).isEqualTo("zip");
        assertThat(ArchiveExportSupport.normalizeFormat("  ")).isEqualTo("zip");
        assertThat(ArchiveExportSupport.normalizeFormat("ZIP")).isEqualTo("zip");
    }

    @Test
    @DisplayName("normalizeFormat 对不支持的格式应抛出 400")
    void shouldRejectUnsupportedFormat() {
        assertThatThrownBy(() -> ArchiveExportSupport.normalizeFormat("rar"))
                .isInstanceOfSatisfying(LocalizedException.class, e ->
                        assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    @DisplayName("groupById 仅在显式传入 id（忽略大小写与空白）时为真")
    void shouldDetectGroupById() {
        assertThat(ArchiveExportSupport.groupById("id")).isTrue();
        assertThat(ArchiveExportSupport.groupById(" ID ")).isTrue();
        assertThat(ArchiveExportSupport.groupById("author")).isFalse();
        assertThat(ArchiveExportSupport.groupById(null)).isFalse();
    }

    @Test
    @DisplayName("normalizeIds 应去重并过滤 null 与非正数")
    void shouldNormalizeIds() {
        List<Long> ids = Arrays.asList(3L, null, 0L, -1L, 3L, 5L);
        assertThat(ArchiveExportSupport.normalizeIds(ids)).containsExactly(3L, 5L);
        assertThat(ArchiveExportSupport.normalizeIds(null)).isEmpty();
    }

    @Test
    @DisplayName("applyExclusions 应在归一化后移除排除列表中的 ID")
    void shouldApplyExclusions() {
        List<Long> ids = Arrays.asList(1L, 2L, 3L);
        assertThat(ArchiveExportSupport.applyExclusions(ids, List.of(2L))).containsExactly(1L, 3L);
        assertThat(ArchiveExportSupport.applyExclusions(ids, null)).containsExactly(1L, 2L, 3L);
    }

    @Test
    @DisplayName("safeSegment 应替换非法字符、去除尾部点并在过长时截断")
    void shouldSanitizeSegment() {
        assertThat(ArchiveExportSupport.safeSegment("a/b:c*d", "fallback")).isEqualTo("a_b_c_d");
        assertThat(ArchiveExportSupport.safeSegment("name...", "fallback")).isEqualTo("name");
        assertThat(ArchiveExportSupport.safeSegment("..", "fallback")).isEqualTo("fallback");
        assertThat(ArchiveExportSupport.safeSegment(null, "fallback")).isEqualTo("fallback");
        assertThat(ArchiveExportSupport.safeSegment("x".repeat(200), "fallback")).hasSize(120);
    }

    @Test
    @DisplayName("safeRelativePath 应统一分隔符并净化每一段")
    void shouldSanitizeRelativePath() {
        assertThat(ArchiveExportSupport.safeRelativePath("a\\..\\b")).isEqualTo("a/file/b");
        assertThat(ArchiveExportSupport.safeRelativePath(null)).isEqualTo("file");
    }

    @Test
    @DisplayName("authorSegment 在缺少作者名时应回退为 author-{id} 或 unknown-author")
    void shouldBuildAuthorSegment() {
        assertThat(ArchiveExportSupport.authorSegment(7L, "Artist")).isEqualTo("Artist");
        assertThat(ArchiveExportSupport.authorSegment(7L, null)).isEqualTo("author-7");
        assertThat(ArchiveExportSupport.authorSegment(null, " ")).isEqualTo("unknown-author");
    }
}
