package top.sywyar.pixivdownload.core.work;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("作品文件名格式化")
class PixivWorkFileNameFormatterTest {

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
    }
}
