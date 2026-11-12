package top.sywyar.pixivdownload.core.pixiv.filename;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("作品文件名格式化")
class PixivWorkFileNameFormatterTest {

    @Test
    @DisplayName("系列变量与来源用户名使用下载快照，名称清洗且不递归展开")
    void seriesAndSourceVariables() {
        var context = new PixivWorkFileNameFormatter.Context(null, null, 8L, "系列/{page}", 0L, "来源/User");
        String template = "{series_id}_{series_name}_{series_title}_{series_order}_{username}_{author_name}";
        assertThat(PixivWorkFileNameFormatter.formatAll(template, 42, "Title", 7L, "Author",
                123, 2, false, 0, 180, context)).containsExactly(
                "8_系列_{page}_系列_{page}_0_来源_User_Author",
                "8_系列_{page}_系列_{page}_0_来源_User_Author_p1");
        var missing = new PixivWorkFileNameFormatter.Context(null, null, null, "stale", 3L, null);
        assertThat(PixivWorkFileNameFormatter.formatAll(template, 42, "Title", 7L, "Author",
                123, 1, false, 0, 180, missing)).containsExactly("_____Author");
        var unknownOrder = new PixivWorkFileNameFormatter.Context(null, null, 8L, null, -1L, null);
        assertThat(PixivWorkFileNameFormatter.formatAll("{series_id}_{series_order}_{series_title}",
                42, "Title", 7L, "Author", 123, 1, false, 0, 180, unknownOrder)).containsExactly("8__");
    }

    @Test
    @DisplayName("收藏夹变量按当次选择展开并保留安全清洗与空值语义")
    void collectionVariables() {
        String template = "{collection_id}_{collection_name}_{artwork_id}_p{page}";
        assertThat(PixivWorkFileNameFormatter.formatAll(template, 42, "Title", null, null,
                123, 2, false, 0, 180, 7L, "收藏😀/unsafe"))
                .containsExactly("7_收藏😀_unsafe_42_p0", "7_收藏😀_unsafe_42_p1");
        assertThat(PixivWorkFileNameFormatter.formatAll(template, 42, "Title", null, null,
                123, 1, false, 0)).containsExactly("__42_p0");
        assertThat(PixivWorkFileNameFormatter.formatAll("{collection_name}{collection_id}", 42, "Title", null, null,
                123, 1, false, 0, 180, null, "stale"))
                .containsExactly("42_p0");
        assertThat(PixivWorkFileNameFormatter.formatAll("{collection_name}", 42, "Title", null, null,
                123, 2, false, 0, 8, 7L, "abcdefghijk"))
                .containsExactly("abcdefgh", "abcde_p1");
    }

    @Test
    @DisplayName("共享定义保留下载变量求值及未支持变量的字面值")
    void sharedDefinitionsPreserveDownloadSemantics() {
        assertThat(PixivWorkFileNameFormatter.formatAll(
                "{artwork_id}_{author_id}_{page}_{count}_{ai}_{ai+}_{R18}_{R18+}_{extension}_{name}_{ex}_{artworkId}",
                42, "Title", 7L, "Author", 123, 1, true, 2)).containsExactly(
                "42_7_0_1_AI_AI_R18G_R18G_{extension}_{name}_{ex}_{artworkId}");
    }

    @Test
    @DisplayName("应展开变量并合规化标题和作者名")
    void shouldFormatVariablesAndSanitizeNameParts() {
        List<String> names = PixivWorkFileNameFormatter.formatAll(
                "{artwork_id}-{artwork_title}-{author_id}-{author_name}-{timestamp}-{page}-{count}-{ai+}-{R18+}",
                12345L,
                "A/B:C*D?",
                777L,
                "CON",
                1700000000L,
                2,
                true,
                2
        );

        assertThat(names).containsExactly(
                "12345-A_B_C_D_-777-_CON-1700000000-0-2-AI-R18G",
                "12345-A_B_C_D_-777-_CON-1700000000-1-2-AI-R18G"
        );
    }

    @Test
    @DisplayName("normalizeBaseNameWithSuffix：长标题应保留语言后缀，不被 180 字符上限截断")
    void shouldPreserveSuffixWhenBaseIsTooLong() {
        String longTitle = "甲".repeat(220);
        String result = PixivWorkFileNameFormatter.normalizeBaseNameWithSuffix(longTitle, "_zh-CN", "fallback");
        assertThat(result).endsWith("_zh-CN");
        assertThat(result.length()).isLessThanOrEqualTo(180);
        // 基础部分必须真的被截短：原 220 字 + 后缀 6 字 远超 180
        assertThat(result.length()).isEqualTo(180);
    }

    @Test
    @DisplayName("normalizeBaseNameWithSuffix：suffix 为空时退化为 normalizeBaseName")
    void shouldFallBackToNormalizeWhenSuffixEmpty() {
        String result = PixivWorkFileNameFormatter.normalizeBaseNameWithSuffix("title", "", "fb");
        assertThat(result).isEqualTo("title");
        assertThat(PixivWorkFileNameFormatter.normalizeBaseNameWithSuffix(null, "_en", "CON"))
                .isEqualTo("_CON_en");
        assertThat(PixivWorkFileNameFormatter.normalizeBaseNameWithSuffix("...", "_en", ""))
                .isEqualTo("untitled_en");
    }

    @Test
    @DisplayName("normalizeBaseNameWithSuffix：长标题的变体名与原文合订本名必须不同（防覆盖 / 防误删）")
    void variantPathMustDifferFromBasePathForLongTitles() {
        String longTitle = "甲".repeat(220);
        String base = PixivWorkFileNameFormatter.normalizeBaseName(longTitle, "1");
        String variant = PixivWorkFileNameFormatter.normalizeBaseNameWithSuffix(longTitle, "_zh-CN", "1_zh-CN");
        assertThat(variant).isNotEqualTo(base);
        assertThat(variant).endsWith("_zh-CN");
    }

    @Test
    @DisplayName("重复文件名应自动追加页码")
    void shouldMakeDuplicateNamesUnique() {
        List<String> names = PixivWorkFileNameFormatter.formatAll(
                "{artwork_title}",
                12345L,
                "Same",
                null,
                null,
                1700000000L,
                3,
                false,
                0
        );

        assertThat(names).containsExactly("Same", "Same_p1", "Same_p2");
        assertThat(PixivWorkFileNameFormatter.normalizeProvidedBaseNames(
                List.of("same_p2", "same", "SAME", "same_p2"), 4, 12345L))
                .containsExactly("same_p2", "same", "SAME_p2_2", "same_p2_p3");
    }
}
