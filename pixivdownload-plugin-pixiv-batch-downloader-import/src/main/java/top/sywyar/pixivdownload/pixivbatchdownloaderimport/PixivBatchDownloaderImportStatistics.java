package top.sywyar.pixivdownload.pixivbatchdownloaderimport;

import top.sywyar.pixivdownload.core.work.importing.WorkFileImportRequest;
import top.sywyar.pixivdownload.core.work.model.WorkType;
import top.sywyar.pixivdownload.plugin.api.gui.*;
import top.sywyar.pixivdownload.plugin.api.storage.PluginDatabase;
import top.sywyar.pixivdownload.plugin.api.schema.*;

import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import top.sywyar.pixivdownload.plugin.api.storage.relational.DatabaseSession;
import top.sywyar.pixivdownload.plugin.api.storage.relational.Query;

/** 插件私有的累计导入记录；作品类型和 ID 共同去重，画廊删除不撤销已完成的导入。 */
public final class PixivBatchDownloaderImportStatistics implements DesktopDashboardSource {
    private static final String NAMESPACE = "pixiv-batch-downloader-import";
    private final PluginDatabase dataSource;
    private ImportedWork pending;

    static List<SchemaContribution> schema() {
        return List.of(new SchemaContribution(List.of(new TableSpec("imported_works", List.of(
                new ColumnSpec("work_type", "TEXT", true, null, 1),
                new ColumnSpec("work_id", "INTEGER", true, null, 2),
                new ColumnSpec("imported_at", "INTEGER", true, null, 0)), List.of())), List.of(), List.of()));
    }

    public PixivBatchDownloaderImportStatistics(PluginDatabase dataSource) throws SQLException {
        this.dataSource = dataSource;
    }

    synchronized void recordImported(WorkFileImportRequest request) throws SQLException {
        flushPending();
        pending = new ImportedWork(request.workType(), request.workId(), System.currentTimeMillis());
        flushPending();
    }

    /** 下次接收前补写上一笔成功记录；失败时暂停新导入，不积压作品元数据。 */
    synchronized void flushPending() throws SQLException {
        if (!dataSource.available()) throw new SQLException("DATABASE_UNAVAILABLE", "08003");
        if (pending == null) return;
        dataSource.transaction(session -> session.insert("imported_works",
                Map.of("work_type", pending.type().name(), "work_id", pending.id(), "imported_at", pending.importedAt()),
                DatabaseSession.Conflict.ignore("work_type", "work_id")));
        pending = null;
    }

    @Override
    public synchronized DesktopDashboardSnapshot snapshot() {
        if (pending != null) throw new IllegalStateException("IMPORT_STATISTICS_UNAVAILABLE");
        try {
            long count = dataSource.read(session -> (Long) session.query(Query.count("imported_works", null)).get(0).get("count"));
            var observedAt = Instant.now();
            var card = new DesktopDashboardCardContribution(
                    "imported-works",
                    75,
                    DesktopUiText.plugin(NAMESPACE, "overview.importedWorks", "Imported works"),
                    DesktopUiText.raw(Long.toString(count)),
                    DesktopUiText.plugin(NAMESPACE, "overview.importedWorks.help", "Unique works imported by PixivBatchDownloader import support; retained after gallery deletion."),
                    DesktopUiTone.INFO,
                    DesktopUiIcon.STATISTICS,
                    DesktopControlCenterAvailability.AVAILABLE,
                    observedAt
            );
            return new DesktopDashboardSnapshot(List.of(card), List.of(), observedAt);
        } catch (SQLException failure) {
            // 让宿主按既有能力故障语义降级该卡片，不能把查询失败显示为零。
            throw new IllegalStateException("IMPORT_STATISTICS_UNAVAILABLE", failure);
        }
    }

    private record ImportedWork(WorkType type, long id, long importedAt) {}
}
