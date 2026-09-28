package top.sywyar.pixivdownload.core.metadata;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import top.sywyar.pixivdownload.core.db.schema.DatabaseInitializer;
import top.sywyar.pixivdownload.core.metadata.sidecar.CuratedWorkMeta;
import top.sywyar.pixivdownload.core.work.model.WorkType;

import javax.sql.DataSource;

/** 快照与查询列在同一 SQL 中更新；未登记和软删除作品不会产生孤立快照。 */
@Repository
public class WorkMetadataStore {
    private final JdbcTemplate jdbc;

    public WorkMetadataStore(DataSource dataSource, DatabaseInitializer initializer) {
        jdbc = new JdbcTemplate(dataSource);
    }

    public void save(WorkType type, long id, CuratedWorkMeta meta, boolean replaceSnapshot) {
        if (type == null || id <= 0) throw new IllegalArgumentException("Invalid work identity");
        String document = meta.hasDocument() ? meta.document().toString() : null;
        if (type == WorkType.ARTWORK) {
            jdbc.update("UPDATE artworks SET upload_time = ?, is_original = ?, metadata_json = COALESCE(?, metadata_json)"
                            + " WHERE artwork_id = ? AND deleted = 0 AND (? IS NULL OR ? OR metadata_json IS NULL)",
                    meta.uploadTime(), meta.isOriginal(), document, id, document, replaceSnapshot);
        } else {
            jdbc.update("UPDATE novels SET upload_time = ?,"
                            + " metadata_json = COALESCE(?, metadata_json) WHERE novel_id = ? AND deleted = 0",
                    meta.uploadTime(), document, id);
        }
    }
}
