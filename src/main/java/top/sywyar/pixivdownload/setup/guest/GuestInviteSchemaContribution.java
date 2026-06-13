package top.sywyar.pixivdownload.setup.guest;

import top.sywyar.pixivdownload.core.db.schema.contribution.CoreSchemaContribution;
import top.sywyar.pixivdownload.plugin.api.schema.SchemaContribution;
import top.sywyar.pixivdownload.plugin.api.schema.TableSpec;

import java.util.List;

import static top.sywyar.pixivdownload.core.db.schema.SchemaSpecs.autoIncrementPrimaryKey;
import static top.sywyar.pixivdownload.core.db.schema.SchemaSpecs.column;
import static top.sywyar.pixivdownload.core.db.schema.SchemaSpecs.explicitIndex;
import static top.sywyar.pixivdownload.core.db.schema.SchemaSpecs.uniqueConstraint;

/**
 * 访客邀请域 schema 的 contribution 声明。访客邀请涉及权限边界，属核心安全层。
 */
public final class GuestInviteSchemaContribution {

    public static final SchemaContribution CONTRIBUTION = createContribution();

    private GuestInviteSchemaContribution() {}

    private static SchemaContribution createContribution() {
        List<TableSpec> tables = List.of(
                new TableSpec(
                        "guest_invites",
                        List.of(
                                autoIncrementPrimaryKey("id"),
                                column("code", "TEXT", true, null, 0),
                                column("name", "TEXT", true, null, 0),
                                column("expire_time", "INTEGER", false, null, 0),
                                column("allow_sfw", "INTEGER", true, "1", 0),
                                column("allow_r18", "INTEGER", true, "0", 0),
                                column("allow_r18g", "INTEGER", true, "0", 0),
                                column("tag_unrestricted", "INTEGER", true, "1", 0),
                                column("author_unrestricted", "INTEGER", true, "1", 0),
                                column("novel_tag_unrestricted", "INTEGER", false, null, 0),
                                column("novel_author_unrestricted", "INTEGER", false, null, 0),
                                column("created_time", "INTEGER", true, null, 0),
                                column("paused", "INTEGER", true, "0", 0),
                                column("revoked", "INTEGER", true, "0", 0),
                                column("first_used_time", "INTEGER", false, null, 0),
                                column("last_used_time", "INTEGER", false, null, 0),
                                column("total_request_count", "INTEGER", true, "0", 0)
                        ),
                        List.of(
                                uniqueConstraint("code"),
                                explicitIndex("idx_guest_invites_code", false, "code")
                        )
                ),
                new TableSpec(
                        "guest_invite_tags",
                        List.of(
                                column("invite_id", "INTEGER", true, null, 1),
                                column("tag_id", "INTEGER", true, null, 2)
                        ),
                        List.of()
                ),
                new TableSpec(
                        "guest_invite_authors",
                        List.of(
                                column("invite_id", "INTEGER", true, null, 1),
                                column("author_id", "INTEGER", true, null, 2)
                        ),
                        List.of()
                ),
                new TableSpec(
                        "guest_invite_novel_tags",
                        List.of(
                                column("invite_id", "INTEGER", true, null, 1),
                                column("tag_id", "INTEGER", true, null, 2)
                        ),
                        List.of()
                ),
                new TableSpec(
                        "guest_invite_novel_authors",
                        List.of(
                                column("invite_id", "INTEGER", true, null, 1),
                                column("author_id", "INTEGER", true, null, 2)
                        ),
                        List.of()
                ),
                new TableSpec(
                        "guest_invite_access_stats",
                        List.of(
                                column("invite_id", "INTEGER", true, null, 1),
                                column("bucket_hour", "INTEGER", true, null, 2),
                                column("request_count", "INTEGER", true, "0", 0)
                        ),
                        List.of(
                                explicitIndex("idx_guest_invite_access_stats_bucket", false, "bucket_hour")
                        )
                )
        );

        return new SchemaContribution(CoreSchemaContribution.OWNER_PLUGIN_ID,
                tables, List.of(), List.of(), List.of());
    }
}
