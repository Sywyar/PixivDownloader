package top.sywyar.pixivdownload.novel.db;

import top.sywyar.pixivdownload.core.db.CoreSchemaContribution;
import top.sywyar.pixivdownload.plugin.api.PathColumnSpec;
import top.sywyar.pixivdownload.plugin.api.SchemaContribution;
import top.sywyar.pixivdownload.plugin.api.TableSpec;

import java.util.List;

import static top.sywyar.pixivdownload.core.db.SchemaSpecs.autoIncrementPrimaryKey;
import static top.sywyar.pixivdownload.core.db.SchemaSpecs.column;
import static top.sywyar.pixivdownload.core.db.SchemaSpecs.explicitIndex;
import static top.sywyar.pixivdownload.core.db.SchemaSpecs.uniqueConstraint;

/**
 * 小说域 schema 的 contribution 声明（小说主表、系列、标签/收藏夹关联、内嵌插图、
 * 译文、名词映射表、AI 朗读花名册与脚本——DDL 均随 NovelDatabase）。
 * 小说正文 {@code raw_content} 是权威数据，属核心长期事实数据，ownerPluginId 为 core
 * （卸载投影测试：小说画廊插件未安装时正文与 meta 仍须完整保存）。
 * FTS 虚拟表 {@code novels_fts} 及其影子表不入受管 schema。
 */
public final class NovelSchemaContribution {

    public static final SchemaContribution CONTRIBUTION = createContribution();

    private NovelSchemaContribution() {}

    private static SchemaContribution createContribution() {
        List<TableSpec> tables = List.of(
                new TableSpec(
                        "novels",
                        List.of(
                                column("novel_id", "INTEGER", false, null, 1),
                                column("title", "TEXT", true, null, 0),
                                column("folder", "TEXT", true, null, 0),
                                column("count", "INTEGER", true, null, 0),
                                column("extensions", "TEXT", true, null, 0),
                                column("time", "INTEGER", true, null, 0),
                                column("R18", "INTEGER", false, null, 0),
                                column("is_ai", "INTEGER", false, null, 0),
                                column("author_id", "INTEGER", false, null, 0),
                                column("description", "TEXT", false, null, 0),
                                column("file_name", "INTEGER", true, "1", 0),
                                column("file_author_name_id", "INTEGER", false, null, 0),
                                column("series_id", "INTEGER", false, null, 0),
                                column("series_order", "INTEGER", false, null, 0),
                                column("word_count", "INTEGER", false, null, 0),
                                column("text_length", "INTEGER", false, null, 0),
                                column("reading_time_seconds", "INTEGER", false, null, 0),
                                column("page_count", "INTEGER", false, null, 0),
                                column("is_original", "INTEGER", false, null, 0),
                                column("x_language", "TEXT", false, null, 0),
                                column("raw_content", "TEXT", false, null, 0),
                                column("cover_ext", "TEXT", false, null, 0),
                                column("deleted", "INTEGER", true, "0", 0)
                        ),
                        List.of(
                                uniqueConstraint("time"),
                                explicitIndex("idx_novels_author_id", false, "author_id"),
                                explicitIndex("idx_novels_series_order", false, "series_id", "series_order")
                        )
                ),
                new TableSpec(
                        "novel_series",
                        List.of(
                                column("series_id", "INTEGER", false, null, 1),
                                column("title", "TEXT", true, null, 0),
                                column("author_id", "INTEGER", false, null, 0),
                                column("updated_time", "INTEGER", true, null, 0),
                                column("description", "TEXT", false, null, 0),
                                column("cover_ext", "TEXT", false, null, 0),
                                column("cover_folder", "TEXT", false, null, 0)
                        ),
                        List.of()
                ),
                new TableSpec(
                        "novel_tags",
                        List.of(
                                column("novel_id", "INTEGER", true, null, 1),
                                column("tag_id", "INTEGER", true, null, 2)
                        ),
                        List.of(
                                explicitIndex("idx_novel_tags_tag_id", false, "tag_id")
                        )
                ),
                new TableSpec(
                        "novel_series_tags",
                        List.of(
                                column("series_id", "INTEGER", true, null, 1),
                                column("tag_id", "INTEGER", true, null, 2)
                        ),
                        List.of(
                                explicitIndex("idx_novel_series_tags_tag_id", false, "tag_id")
                        )
                ),
                new TableSpec(
                        "novel_collections",
                        List.of(
                                column("collection_id", "INTEGER", true, null, 1),
                                column("novel_id", "INTEGER", true, null, 2),
                                column("added_time", "INTEGER", true, null, 0)
                        ),
                        List.of(
                                explicitIndex("idx_novel_collections_novel", false, "novel_id")
                        )
                ),
                new TableSpec(
                        "novel_images",
                        List.of(
                                column("novel_id", "INTEGER", true, null, 1),
                                column("image_id", "TEXT", true, null, 2),
                                column("ext", "TEXT", true, null, 0)
                        ),
                        List.of()
                ),
                new TableSpec(
                        "novel_translations",
                        List.of(
                                column("novel_id", "INTEGER", true, null, 1),
                                column("lang_code", "TEXT", true, null, 2),
                                column("raw_content", "TEXT", true, null, 0),
                                column("title", "TEXT", false, null, 0),
                                column("description", "TEXT", false, null, 0),
                                column("created_time", "INTEGER", true, null, 0)
                        ),
                        List.of()
                ),
                new TableSpec(
                        "novel_series_title_translations",
                        List.of(
                                column("series_id", "INTEGER", true, null, 1),
                                column("lang_code", "TEXT", true, null, 2),
                                column("title", "TEXT", true, null, 0),
                                column("description", "TEXT", false, null, 0),
                                column("created_time", "INTEGER", true, null, 0)
                        ),
                        List.of()
                ),
                new TableSpec(
                        "novel_glossaries",
                        List.of(
                                autoIncrementPrimaryKey("id"),
                                column("name", "TEXT", true, null, 0),
                                column("series_id", "INTEGER", false, null, 0),
                                column("novel_id", "INTEGER", false, null, 0),
                                column("created_time", "INTEGER", true, null, 0),
                                column("updated_time", "INTEGER", true, null, 0)
                        ),
                        List.of(
                                explicitIndex("idx_novel_glossaries_series", false, "series_id"),
                                explicitIndex("idx_novel_glossaries_novel", false, "novel_id")
                        )
                ),
                new TableSpec(
                        "novel_glossary_entries",
                        List.of(
                                column("glossary_id", "INTEGER", true, null, 1),
                                column("source", "TEXT", true, null, 2),
                                column("lang_code", "TEXT", true, null, 3),
                                column("target", "TEXT", true, null, 0),
                                column("created_time", "INTEGER", true, null, 0)
                        ),
                        List.of()
                ),
                new TableSpec(
                        "novel_narration_casts",
                        List.of(
                                autoIncrementPrimaryKey("id"),
                                column("name", "TEXT", true, null, 0),
                                column("series_id", "INTEGER", false, null, 0),
                                column("novel_id", "INTEGER", false, null, 0),
                                column("created_time", "INTEGER", true, null, 0),
                                column("updated_time", "INTEGER", true, null, 0)
                        ),
                        List.of(
                                explicitIndex("idx_novel_narration_casts_series", false, "series_id"),
                                explicitIndex("idx_novel_narration_casts_novel", false, "novel_id")
                        )
                ),
                new TableSpec(
                        "novel_narration_voices",
                        List.of(
                                column("cast_id", "INTEGER", true, null, 1),
                                column("character_id", "INTEGER", true, null, 2),
                                column("name", "TEXT", true, null, 0),
                                column("gender", "TEXT", false, null, 0),
                                column("age", "TEXT", false, null, 0),
                                column("control_instruction", "TEXT", true, null, 0),
                                column("edited_by_user", "INTEGER", true, "0", 0),
                                column("ref_audio_ext", "TEXT", false, null, 0),
                                column("ref_audio_text", "TEXT", false, null, 0),
                                column("ref_audio_source", "TEXT", false, null, 0),
                                column("ref_audio_time", "INTEGER", false, null, 0),
                                column("created_time", "INTEGER", true, null, 0)
                        ),
                        List.of()
                ),
                new TableSpec(
                        "novel_narration_scripts",
                        List.of(
                                column("novel_id", "INTEGER", true, null, 1),
                                column("lang", "TEXT", true, null, 2),
                                column("cast_id", "INTEGER", true, null, 0),
                                column("segment_size", "INTEGER", true, null, 0),
                                column("analyzed_time", "INTEGER", true, null, 0),
                                column("script_json", "TEXT", true, null, 0)
                        ),
                        List.of()
                )
        );

        List<PathColumnSpec> pathColumns = List.of(
                new PathColumnSpec("novels", "novel_id", List.of("folder")),
                new PathColumnSpec("novel_series", "series_id", List.of("cover_folder"))
        );

        return new SchemaContribution(CoreSchemaContribution.OWNER_PLUGIN_ID,
                tables, List.of(), List.of(), pathColumns);
    }
}
