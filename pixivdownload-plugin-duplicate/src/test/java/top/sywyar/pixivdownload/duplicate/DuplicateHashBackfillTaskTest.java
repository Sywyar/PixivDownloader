package top.sywyar.pixivdownload.duplicate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.core.hash.ArtworkHashIndexMaintenance;
import top.sywyar.pixivdownload.i18n.MessageResolver;
import top.sywyar.pixivdownload.plugin.api.maintenance.MaintenanceContext;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("重复哈希回填维护任务")
class DuplicateHashBackfillTaskTest {

    @Test
    @DisplayName("经核心重建端口回填并通过维护上下文上报逐作品进度")
    void rebuildsHashesAndReportsProgressThroughContext() {
        ArtworkHashIndexMaintenance maintenance = mock(ArtworkHashIndexMaintenance.class);
        DuplicateService duplicateService = mock(DuplicateService.class);
        MessageResolver messages = mock(MessageResolver.class);
        when(maintenance.artworkIdsMissingHashes(Integer.MAX_VALUE)).thenReturn(List.of(1L, 2L, 3L));
        when(maintenance.rebuildArtwork(1L)).thenReturn(OptionalInt.of(2));
        when(maintenance.rebuildArtwork(2L)).thenReturn(OptionalInt.empty());
        when(maintenance.rebuildArtwork(3L)).thenReturn(OptionalInt.of(0));
        DuplicateHashBackfillTask task = new DuplicateHashBackfillTask(
                maintenance, duplicateService, messages);
        List<String> progress = new ArrayList<>();
        MaintenanceContext context = new MaintenanceContext(
                "manual", 123L, (done, total) -> progress.add(done + "/" + total));

        task.execute(context);

        assertThat(progress).containsExactly("0/3", "1/3", "2/3", "3/3");
        verify(duplicateService).invalidate();
        verify(messages).getForLog("duplicate.log.backfill.done", 2, 2);
    }
}
