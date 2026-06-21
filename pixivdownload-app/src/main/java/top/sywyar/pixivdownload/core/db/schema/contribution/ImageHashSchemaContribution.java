package top.sywyar.pixivdownload.core.db.schema.contribution;

import top.sywyar.pixivdownload.plugin.api.schema.SchemaContribution;
import top.sywyar.pixivdownload.plugin.api.schema.TableSpec;

import java.util.List;

import static top.sywyar.pixivdownload.core.db.schema.SchemaSpecs.column;
import static top.sywyar.pixivdownload.core.db.schema.SchemaSpecs.explicitIndex;

/**
 * 图片感知哈希域 schema 的 contribution 声明。
 * 属核心本地资源索引（含 page=-1 不可哈希哨兵语义），不随 duplicate 插件声明为私有 schema；
 * 下载后即时计算 Hash 的链路在核心服务 {@code core.hash.ArtworkHashService}（根包扫描），不随 duplicate 禁用。
 */
public final class ImageHashSchemaContribution {

    public static final SchemaContribution CONTRIBUTION = createContribution();

    private ImageHashSchemaContribution() {}

    private static SchemaContribution createContribution() {
        List<TableSpec> tables = List.of(
                new TableSpec(
                        "artwork_image_hashes",
                        List.of(
                                column("artwork_id", "INTEGER", true, null, 1),
                                column("page", "INTEGER", true, null, 2),
                                column("ext", "TEXT", true, null, 0),
                                column("dhash", "INTEGER", true, null, 0),
                                column("ahash", "INTEGER", false, null, 0),
                                column("created_time", "INTEGER", true, null, 0)
                        ),
                        List.of(
                                explicitIndex("idx_artwork_image_hashes_dhash", false, "dhash")
                        )
                )
        );

        return new SchemaContribution(CoreSchemaContribution.OWNER_PLUGIN_ID,
                tables, List.of(), List.of(), List.of());
    }
}
