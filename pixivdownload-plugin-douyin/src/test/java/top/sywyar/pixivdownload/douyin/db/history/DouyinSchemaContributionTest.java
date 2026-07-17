package top.sywyar.pixivdownload.douyin.db.history;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.douyin.DouyinPlugin;
import top.sywyar.pixivdownload.plugin.api.schema.ColumnSpec;
import top.sywyar.pixivdownload.plugin.api.schema.IndexOrigin;
import top.sywyar.pixivdownload.plugin.api.schema.PathColumnSpec;
import top.sywyar.pixivdownload.plugin.api.schema.TableSpec;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("DouyinSchemaContribution 抖音历史 schema")
class DouyinSchemaContributionTest {

    @Test
    @DisplayName("插件 schema 分离作品、文件与多来源关系三张受管表")
    void contributesDouyinHistoryTables() {
        assertThat(DouyinSchemaContribution.CONTRIBUTION.tables())
                .extracting(TableSpec::name)
                .containsExactly("douyin_works", "douyin_work_files", "douyin_work_relations");

        TableSpec works = table("douyin_works");
        assertThat(works.columns())
                .anySatisfy(column -> assertNullableText(column, "description"))
                .anySatisfy(column -> assertNullableText(column, "item_title"))
                .anySatisfy(column -> assertNullableText(column, "caption"));
        assertThat(works.indexes())
                .anySatisfy(index -> {
                    assertThat(index.origin()).isEqualTo(IndexOrigin.UNIQUE_CONSTRAINT);
                    assertThat(index.unique()).isTrue();
                    assertThat(index.columns()).containsExactly("time");
                })
                .anySatisfy(index -> assertThat(index.name())
                        .isEqualTo("idx_douyin_works_author_time"))
                .anySatisfy(index -> assertThat(index.name())
                        .isEqualTo("idx_douyin_works_collection_order"));

        assertThat(table("douyin_work_files").indexes())
                .singleElement()
                .satisfies(index -> assertThat(index.name())
                        .isEqualTo("idx_douyin_work_files_work_id"));
        TableSpec relations = table("douyin_work_relations");
        assertThat(relations.columns()).extracting(ColumnSpec::name)
                .containsExactly("work_id", "source_type", "source_id", "source_title", "source_url",
                        "source_order", "discovered_time");
        assertThat(relations.indexes()).singleElement().satisfies(index -> {
            assertThat(index.name()).isEqualTo("idx_douyin_work_relations_source");
            assertThat(index.columns()).containsExactly("source_type", "source_id", "source_order");
        });
    }

    @Test
    @DisplayName("folder 列通过中性契约登记为路径前缀列")
    void registersFolderAsPathPrefixColumn() {
        assertThat(DouyinSchemaContribution.CONTRIBUTION.pathColumns()).containsExactly(
                new PathColumnSpec("douyin_works", "work_id", List.of("folder")));
    }

    @Test
    @DisplayName("插件入口直接发布自己的 schema 契约")
    void pluginPublishesSchemaContribution() {
        assertThat(new DouyinPlugin().schema())
                .containsExactly(DouyinSchemaContribution.CONTRIBUTION);
    }

    private static TableSpec table(String name) {
        return DouyinSchemaContribution.CONTRIBUTION.tables().stream()
                .filter(table -> table.name().equals(name))
                .findFirst()
                .orElseThrow();
    }

    private static void assertNullableText(ColumnSpec column, String name) {
        assertThat(column.name()).isEqualTo(name);
        assertThat(column.type()).isEqualTo("TEXT");
        assertThat(column.notNull()).isFalse();
    }
}
