package top.sywyar.pixivdownload.duplicate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import top.sywyar.pixivdownload.core.database.DatabaseUnavailableException;
import top.sywyar.pixivdownload.core.hash.ArtworkHashIndexMaintenance;
import top.sywyar.pixivdownload.core.hash.ArtworkHashIndexQuery;
import top.sywyar.pixivdownload.i18n.MessageResolver;
import top.sywyar.pixivdownload.plugin.api.maintenance.MaintenanceContext;
import java.util.List;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("无数据库的重复扫描与查询")
class DuplicateUnavailableTest {
    @Test
    @DisplayName("查询返回标明不可用的空页，扫描和维护不接纳工作")
    void unavailableNeverReadsOrStartsWork() {
        var query = mock(ArtworkHashIndexQuery.class);
        var maintenance = mock(ArtworkHashIndexMaintenance.class);
        var messages = mock(MessageResolver.class);
        var groups = new DuplicateService(query, messages, () -> false);
        var page = groups.groups(null, null, null, 0, 20);
        assertThat(page.groups()).isEmpty();
        assertThat(page.scan().state()).isEqualTo("UNAVAILABLE");
        var scan = new DuplicateScanService(maintenance, groups, messages, Runnable::run, () -> false);
        assertThatThrownBy(() -> scan.startScan(true)).isInstanceOf(DatabaseUnavailableException.class);
        var task = new DuplicateHashBackfillTask(maintenance, groups, messages, () -> false);
        assertThatThrownBy(() -> task.execute(new MaintenanceContext("manual", 1, (a, b) -> {})))
                .isInstanceOf(DatabaseUnavailableException.class);
        verifyNoInteractions(query, maintenance, messages);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("扫描中失效后不处理下一作品或宣布完成")
    void failureDuringScanStopsProgress(boolean force) {
        var available = new AtomicBoolean(true);
        var maintenance = mock(ArtworkHashIndexMaintenance.class);
        var groups = mock(DuplicateService.class);
        var messages = mock(MessageResolver.class);
        when(maintenance.missingArtworkCount()).thenReturn(2);
        when(maintenance.artworkCount()).thenReturn(2L);
        when(maintenance.artworkIdsNewestFirst()).thenReturn(List.of(1L, 2L));
        when(maintenance.artworkIdsMissingHashes(Integer.MAX_VALUE)).thenReturn(List.of(1L, 2L));
        when(maintenance.rebuildArtwork(1L)).thenAnswer(call -> {
            available.set(false);
            return OptionalInt.of(1);
        });
        var scan = new DuplicateScanService(maintenance, groups, messages, Runnable::run, available::get);
        assertThat(scan.startScan(force).state()).isEqualTo("UNAVAILABLE");
        verify(maintenance, never()).rebuildArtwork(2L);
        verifyNoInteractions(groups);
    }
}
