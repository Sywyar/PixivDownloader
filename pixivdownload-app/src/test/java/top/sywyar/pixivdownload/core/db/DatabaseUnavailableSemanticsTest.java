package top.sywyar.pixivdownload.core.db;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;
import top.sywyar.pixivdownload.core.database.DatabaseAvailability;
import top.sywyar.pixivdownload.core.database.DatabaseUnavailableException;
import top.sywyar.pixivdownload.core.download.DownloadLifecycleRegistry;
import top.sywyar.pixivdownload.core.download.DownloadTaskRegistry;
import top.sywyar.pixivdownload.core.metadata.CoreWorkMetadataRepository;
import top.sywyar.pixivdownload.core.metadata.CoreWorkQueryService;
import top.sywyar.pixivdownload.core.stats.db.StatsQueryStoreImpl;
import top.sywyar.pixivdownload.core.work.model.WorkType;
import top.sywyar.pixivdownload.core.work.query.WorkQuery;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.plugin.api.download.submission.DownloadTaskException;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

@DisplayName("无库时的 SDK 查询和命令语义")
class DatabaseUnavailableSemanticsTest {
    private final DatabaseConfig config = new DatabaseConfig(
            new DatabasePoolProperties(), mock(AppMessages.class));
    private final UnavailableDataSource source = new UnavailableDataSource();
    private final DatabaseAvailability availability = config.databaseAvailability(source);

    @Test
    @DisplayName("展示查询为空且数据库状态不可用，不触达内部仓库")
    void emptyQueriesAreAccompaniedByUnavailableState() {
        var query = new CoreWorkQueryService(null, null, null, null, null, availability);
        var metadata = new CoreWorkMetadataRepository(availability, null, null, null, null);
        var statistics = new StatsQueryStoreImpl(top.sywyar.pixivdownload.core.db.DatabaseProviderTestFixtures.main(source));
        assertThat(availability.available()).isFalse();
        assertThat(query.search(mock(WorkQuery.class)).content()).isEmpty();
        assertThat(query.searchAll(null)).isEmpty();
        assertThat(query.hasWork(WorkType.ARTWORK, 1)).isFalse();
        assertThat(query.hasActiveWork(WorkType.NOVEL, 1)).isFalse();
        assertThat(query.seriesNeighbors(WorkType.ARTWORK, 1)).isEmpty();
        assertThat(query.authorNames(List.of(1L))).isEmpty();
        assertThat(metadata.find(WorkType.ARTWORK, 1)).isEmpty();
        assertThat(statistics.overview().totalArtworks()).isZero();
        assertThat(statistics.topAuthors(10)).isEmpty();
        assertThat(statistics.topTags(10)).isEmpty();
        assertThat(statistics.monthlyArtworkCounts()).isEmpty();
    }

    @Test
    @DisplayName("SDK 资产展示为空，所有权与完整性判定及媒体写入明确拒绝")
    void fileQueriesDoNotTurnAbsenceIntoPermissions() throws Exception {
        var pixiv = mock(PixivDatabase.class);
        org.mockito.Mockito.when(pixiv.isUnavailable()).thenReturn(true);
        var files = new top.sywyar.pixivdownload.core.download.LocalWorkAssetService(
                null, null, pixiv, null, null, null, null, null);
        for (WorkType type : List.of(WorkType.ARTWORK, WorkType.NOVEL)) {
            assertThat(files.findAsset(type, 1)).isEmpty();
            assertThat(files.existingThumbnail(type, 1, 0)).isEmpty();
            assertThat(files.rawFile(type, 1, 0)).isEmpty();
            assertThatThrownBy(() -> files.isReadOnly(type, 1)).isInstanceOf(DatabaseUnavailableException.class);
            assertThatThrownBy(() -> files.hasCompleteFiles(type, 1)).isInstanceOf(DatabaseUnavailableException.class);
        }
        var media = new top.sywyar.pixivdownload.core.asset.artwork.ArtworkMediaStoreImpl(top.sywyar.pixivdownload.core.db.DatabaseProviderTestFixtures.main(source), null);
        assertThat(media.find(1, 0)).isEmpty();
        assertThatThrownBy(() -> media.save(1, 0, null)).isInstanceOf(DatabaseUnavailableException.class);
    }

    @Test
    @DisplayName("缺库下载在预约任务和调用插件前拒绝")
    void downloadDoesNotReserveOrInvokeHooks() {
        var tasks = mock(DownloadTaskRegistry.class);
        var lifecycle = new DownloadLifecycleRegistry(tasks, availability);
        assertThatThrownBy(() -> lifecycle.submit(null, null, null))
                .isInstanceOf(DownloadTaskException.class);
        assertThatThrownBy(() -> lifecycle.options(null, null))
                .isInstanceOf(DatabaseUnavailableException.class);
        assertThatThrownBy(() -> lifecycle.checkAdmission(null))
                .isInstanceOf(DatabaseUnavailableException.class);
        assertThatThrownBy(() -> lifecycle.register(null, null, null, null))
                .isInstanceOf(DatabaseUnavailableException.class);
        assertThatThrownBy(() -> lifecycle.track(null, null, null, null, null, true))
                .isInstanceOf(DatabaseUnavailableException.class);
        var attempt = new top.sywyar.pixivdownload.plugin.api.download.lifecycle.DownloadAttempt(
                java.util.UUID.randomUUID(), "fixture", "1");
        for (var phase : List.of(top.sywyar.pixivdownload.plugin.api.download.lifecycle.DownloadEvent.Phase.ACCEPTED,
                top.sywyar.pixivdownload.plugin.api.download.lifecycle.DownloadEvent.Phase.COMPLETED)) {
            assertThatThrownBy(() -> lifecycle.publish(new top.sywyar.pixivdownload.plugin.api.download.lifecycle.DownloadEvent(attempt, phase)))
                    .isInstanceOf(DatabaseUnavailableException.class);
        }
        verifyNoInteractions(tasks);
    }

    @Test
    @DisplayName("事务在借连接和执行回调前明确拒绝，不伪造提交")
    void transactionCallbackIsNeverExecuted() {
        var invoked = new AtomicBoolean();
        var transaction = new TransactionTemplate(config.transactionManager(source, DatabaseTarget.unavailable()));
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> invoked.set(true)))
                .isInstanceOf(DatabaseUnavailableException.class).hasMessage("DATABASE_UNAVAILABLE");
        assertThat(invoked).isFalse();
        assertThatThrownBy(source::getConnection).isInstanceOf(java.sql.SQLNonTransientConnectionException.class);
    }
}
