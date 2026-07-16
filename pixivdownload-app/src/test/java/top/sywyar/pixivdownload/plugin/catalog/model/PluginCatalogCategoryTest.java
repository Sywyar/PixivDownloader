package top.sywyar.pixivdownload.plugin.catalog.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link PluginCatalogCategory} 单测：分类 id 词表、精确解析与「未知 / 缺省 → 实用工具」稳定回退、聚合项 {@code all}。
 */
@DisplayName("PluginCatalogCategory 插件市场分类词表")
class PluginCatalogCategoryTest {

    @Test
    @DisplayName("分类 id 与设计对齐，且互不相同")
    void categoryIds() {
        assertThat(PluginCatalogCategory.TRANSLATE.id()).isEqualTo("translate");
        assertThat(PluginCatalogCategory.UTILITY.id()).isEqualTo("utility");
        assertThat(PluginCatalogCategory.AGGREGATE_ID).isEqualTo("all");
        assertThat(PluginCatalogCategory.values()).extracting(PluginCatalogCategory::id)
                .containsExactlyInAnyOrder("translate", "download-type", "download", "convert", "notify",
                        "backup", "security", "ui", "utility", "dependency")
                .doesNotContain("all");
    }

    @Test
    @DisplayName("fromId：已知（大小写 / 空白不敏感）/ 未知 / 空")
    void fromId() {
        assertThat(PluginCatalogCategory.fromId("  Backup ")).contains(PluginCatalogCategory.BACKUP);
        assertThat(PluginCatalogCategory.fromId(" Download-Type "))
                .contains(PluginCatalogCategory.DOWNLOAD_TYPE);
        assertThat(PluginCatalogCategory.fromId("nope")).isEmpty();
        assertThat(PluginCatalogCategory.fromId("all")).as("聚合项不是条目分类").isEmpty();
        assertThat(PluginCatalogCategory.fromId(null)).isEmpty();
        assertThat(PluginCatalogCategory.fromId("  ")).isEmpty();
    }

    @Test
    @DisplayName("resolve：未知 / 缺省一律回退到实用工具（永不为 null）")
    void resolveFallsBackToUtility() {
        assertThat(PluginCatalogCategory.resolve("ui")).isEqualTo(PluginCatalogCategory.UI);
        assertThat(PluginCatalogCategory.resolve("weird-category")).isEqualTo(PluginCatalogCategory.UTILITY);
        assertThat(PluginCatalogCategory.resolve(null)).isEqualTo(PluginCatalogCategory.UTILITY);
        assertThat(PluginCatalogCategory.FALLBACK).isEqualTo(PluginCatalogCategory.UTILITY);
    }

    @Test
    @DisplayName("isKnown：区分已知分类与回退值")
    void isKnown() {
        assertThat(PluginCatalogCategory.isKnown("notify")).isTrue();
        assertThat(PluginCatalogCategory.isKnown("dependency")).isTrue();
        assertThat(PluginCatalogCategory.isKnown("all")).isFalse();
        assertThat(PluginCatalogCategory.isKnown("nope")).isFalse();
    }
}
