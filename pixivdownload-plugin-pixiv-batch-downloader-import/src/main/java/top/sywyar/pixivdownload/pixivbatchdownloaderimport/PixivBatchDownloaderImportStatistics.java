package top.sywyar.pixivdownload.pixivbatchdownloaderimport;

import top.sywyar.pixivdownload.core.work.importing.WorkFileImportRequest;
import top.sywyar.pixivdownload.core.work.model.WorkType;
import top.sywyar.pixivdownload.plugin.api.gui.*;
import top.sywyar.pixivdownload.plugin.api.storage.PluginDataSource;

import java.sql.SQLException;
import java.time.Instant;
import java.util.List;

/** 插件私有的累计导入记录；作品类型和 ID 共同去重，画廊删除不撤销已完成的导入。 */
public final class PixivBatchDownloaderImportStatistics implements DesktopDashboardSource {
    private static final String NAMESPACE = "pixiv-batch-downloader-import";
    private final PluginDataSource dataSource;
    private ImportedWork pending;

    public PixivBatchDownloaderImportStatistics(PluginDataSource dataSource) throws SQLException {
        this.dataSource = dataSource;
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS imported_works (
                        work_type TEXT NOT NULL,
                        work_id INTEGER NOT NULL,
                        imported_at INTEGER NOT NULL,
                        PRIMARY KEY (work_type, work_id)
                    )
                    """);
        }
    }

    synchronized void recordImported(WorkFileImportRequest request) throws SQLException {
        flushPending();
        pending = new ImportedWork(request.workType(), request.workId(), System.currentTimeMillis());
        flushPending();
    }

    /** 下次接收前补写上一笔成功记录；失败时暂停新导入，不积压作品元数据。 */
    synchronized void flushPending() throws SQLException {
        if (pending == null) return;
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("""
                     INSERT INTO imported_works (work_type, work_id, imported_at) VALUES (?, ?, ?)
                     ON CONFLICT (work_type, work_id) DO NOTHING
                     """)) {
            statement.setString(1, pending.type().name());
            statement.setLong(2, pending.id());
            statement.setLong(3, pending.importedAt());
            statement.executeUpdate();
            pending = null;
        }
    }

    @Override
    public synchronized DesktopDashboardSnapshot snapshot() {
        if (pending != null) throw new IllegalStateException("IMPORT_STATISTICS_UNAVAILABLE");
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT COUNT(*) FROM imported_works")) {
            result.next();
            var observedAt = Instant.now();
            var card = new DesktopDashboardCardContribution(
                    "imported-works",
                    75,
                    DesktopUiText.plugin(NAMESPACE, "overview.importedWorks", "Imported works"),
                    DesktopUiText.raw(Long.toString(result.getLong(1))),
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
