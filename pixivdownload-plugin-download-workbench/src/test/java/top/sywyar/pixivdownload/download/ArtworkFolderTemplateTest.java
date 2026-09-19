package top.sywyar.pixivdownload.download;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("作品目录模板")
class ArtworkFolderTemplateTest {

    @Test
    @DisplayName("空模板返回空段，交由调用方回退内置结构")
    void blankTemplateYieldsNoSegments() {
        assertThat(ArtworkFolderTemplate.segments(null, 138365786L, "公園", 122036969L, "YaeMiyabi", 1L, 3, false, 1))
                .isEmpty();
        assertThat(ArtworkFolderTemplate.segments("   ", 138365786L, "公園", 122036969L, "YaeMiyabi", 1L, 3, false, 1))
                .isEmpty();
    }

    @Test
    @DisplayName("渲染 (作者ID)作者名")
    void rendersAuthorFolder() {
        assertThat(ArtworkFolderTemplate.segments(
                "({author_id}){author_name}", 138365786L, "公園", 122036969L, "YaeMiyabi", 1L, 3, false, 1))
                .containsExactly("(122036969)YaeMiyabi");
    }

    @Test
    @DisplayName("模板里的 / 用于分层")
    void slashInTemplateCreatesLevels() {
        assertThat(ArtworkFolderTemplate.segments(
                "{author_id}/{artwork_id}", 138365786L, "公園", 122036969L, "YaeMiyabi", 1L, 3, false, 1))
                .containsExactly("122036969", "138365786");
    }

    @Test
    @DisplayName("数据里的 / 被清理成 _，不会制造层级")
    void slashesInDataDoNotCreateLevels() {
        assertThat(ArtworkFolderTemplate.segments(
                "{artwork_title}", 1L, "a/b:c", 2L, "x", 1L, 1, false, 0))
                .containsExactly("a_b_c");
    }

    @Test
    @DisplayName("路径穿越构造被清理并丢弃")
    void traversalIsNeutralised() {
        assertThat(ArtworkFolderTemplate.segments(
                "../../evil", 1L, "t", 2L, "a", 1L, 1, false, 0))
                .containsExactly("evil");
        assertThat(ArtworkFolderTemplate.segments(
                "..", 1L, "t", 2L, "a", 1L, 1, false, 0))
                .isEmpty();
        assertThat(ArtworkFolderTemplate.segments(
                "C:/Windows", 1L, "t", 2L, "a", 1L, 1, false, 0))
                .containsExactly("C_", "Windows");
    }

    @Test
    @DisplayName("作品变量缺省时退化为空串而不抛异常")
    void missingVariablesDegradeToEmpty() {
        assertThat(ArtworkFolderTemplate.segments(
                "({author_id}){author_name}", 1L, "t", null, null, 1L, 1, false, 0))
                .containsExactly("()");
    }
}
