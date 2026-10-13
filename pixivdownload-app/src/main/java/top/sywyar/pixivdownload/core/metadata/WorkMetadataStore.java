package top.sywyar.pixivdownload.core.metadata;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import top.sywyar.pixivdownload.core.db.MainDatabase;
import top.sywyar.pixivdownload.core.db.schema.DatabaseInitializer;
import top.sywyar.pixivdownload.core.metadata.sidecar.CuratedWorkMeta;
import top.sywyar.pixivdownload.core.work.model.WorkType;
import top.sywyar.pixivdownload.plugin.api.storage.relational.Expression;
import java.util.LinkedHashMap;
import static top.sywyar.pixivdownload.plugin.api.storage.relational.Expression.*;
import static top.sywyar.pixivdownload.plugin.api.storage.relational.Expression.Operator.*;

/** 快照与查询列原子更新；未登记和软删除作品不会产生孤立快照。 */
@Repository
public class WorkMetadataStore {
    private final MainDatabase database;

    public WorkMetadataStore(MainDatabase database, DatabaseInitializer initializer) {
        this.database = database;
    }

    // 辅助快照失败只回滚保存点，不能让下载主事务失效。
    @Transactional(propagation = Propagation.NESTED)
    public void save(WorkType type, long id, CuratedWorkMeta meta, boolean replaceSnapshot) {
        if (type == null || id <= 0) throw new IllegalArgumentException("Invalid work identity");
        String document = meta.hasDocument() ? meta.document().toString() : null;
        String table = type == WorkType.ARTWORK ? "artworks" : "novels";
        String key = type == WorkType.ARTWORK ? "artwork_id" : "novel_id";
        var changes = new LinkedHashMap<String, Expression>();
        changes.put("upload_time", value(meta.uploadTime()));
        changes.put("metadata_json", call(COALESCE, value(document), column(table, "metadata_json")));
        Expression where = call(AND, call(EQUAL, column(table, key), value(id)), call(EQUAL, column(table, "deleted"), value(0)));
        if (type == WorkType.ARTWORK) {
            changes.put("is_original", value(meta.isOriginal() == null ? null : meta.isOriginal() ? 1L : 0L));
            if (document != null && !replaceSnapshot) where = call(AND, where, call(IS_NULL, column(table, "metadata_json")));
        }
        Expression condition = where;
        database.transaction(s -> s.update(table, changes, condition));
    }
}
