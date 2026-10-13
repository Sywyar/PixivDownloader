package top.sywyar.pixivdownload.core.db.schema;

import top.sywyar.pixivdownload.core.pixiv.filename.PixivWorkFileNameFormatter;
import top.sywyar.pixivdownload.plugin.api.database.migration.*;
import top.sywyar.pixivdownload.plugin.api.schema.*;
import top.sywyar.pixivdownload.plugin.registry.schema.DatabaseSchemaRegistry;
import java.util.*;

/** 主库的数据不变量声明；数据库 provider 只消费结构化种子和引用，不认识业务表。 */
public final class CoreMigrationSchema {
    private CoreMigrationSchema() {}
    public static MigrationSchema create(DatabaseSchemaRegistry registry, boolean main) {
        if (!main && registry.contributions().stream().anyMatch(c -> !c.pathColumns().isEmpty()))
            throw new IllegalStateException("PRIVATE_PATH_COLUMNS");
        return create(registry.contributions(), registry.mergedSchema(), main);
    }

    public static MigrationSchema create(List<SchemaContribution> contributions,
            ManagedDatabaseSchema.DatabaseSchema schema, boolean main) {
        Map<String, TableSpec> original = new HashMap<>();
        for (var contribution : contributions) for (var table : contribution.tables())
            original.put(ManagedDatabaseSchema.normalizeIdentifier(table.name()), table);
        List<TableSpec> tables = new ArrayList<>();
        for (var table : schema.tables().values()) {
            var declared = original.get(table.name());
            tables.add(new TableSpec(table.name(), table.columns().stream().map(column -> new ColumnSpec(
                    column.name(), column.type(), column.notNull(), column.defaultValue(), column.primaryKeyPosition(),
                    declared.columns().stream().anyMatch(c -> c.name().equalsIgnoreCase(column.name()) && c.autoIncrement())))
                    .toList(), table.indexes().stream().map(index -> new IndexSpec(index.name(),
                    IndexOrigin.valueOf(index.origin().name()), index.unique(), index.columns())).toList(), declared.checkExpression(), declared.check()));
        }
        List<MigrationSchema.Seed> seeds = new ArrayList<>();
        List<MigrationSchema.Reference> references = new ArrayList<>();
        if (main) {
            if (original.containsKey("file_name_templates")) seeds.add(new MigrationSchema.Seed("file_name_templates",
                    Map.of("id", 1L, "template", PixivWorkFileNameFormatter.DEFAULT_TEMPLATE)));
            for (String relation : List.of("artwork_tags:artwork_id:artworks:artwork_id", "artwork_tags:tag_id:tags:tag_id",
                    "artwork_media:artwork_id:artworks:artwork_id", "novel_tags:novel_id:novels:novel_id",
                    "novel_tags:tag_id:tags:tag_id", "artworks:file_name:file_name_templates:id", "novels:file_name:file_name_templates:id")) {
                String[] parts = relation.split(":");
                if (original.containsKey(parts[0]) && original.containsKey(parts[2])) references.add(new MigrationSchema.Reference(
                        parts[0], parts[1], parts[2], parts[3], MigrationSchema.Encoding.VALUE));
            }
            for (var contribution : contributions) for (var path : contribution.pathColumns())
                for (String column : path.pathColumns()) references.add(new MigrationSchema.Reference(path.table(), column,
                        "path_prefixes", "id", MigrationSchema.Encoding.BRACED_PREFIX));
        }
        return new MigrationSchema(MigrationSnapshot.FORMAT_VERSION, tables, seeds, references.stream().distinct().toList());
    }
}
