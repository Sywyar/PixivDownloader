package top.sywyar.pixivdownload.stats;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.core.stats.StatsAggregates;
import top.sywyar.pixivdownload.core.stats.StatsQueryStore;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopControlCenterAvailability;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiIcon;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiTone;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StatsServiceTest {

    @Test
    @DisplayName("桌面快照只复用现有统计总览")
    void desktopSnapshotUsesOnlyExistingOverviewAggregation() {
        StatsService service = new StatsService(new OverviewOnlyStore(
                new StatsAggregates.Overview(42, 84, 3, 7, 5, 6, 2)));

        var snapshot = service.snapshot();

        assertThat(snapshot.runningTasks()).isEmpty();
        assertThat(snapshot.cards()).singleElement().satisfies(card -> {
            assertThat(card.cardId()).isEqualTo("total-artworks");
            assertThat(card.order()).isEqualTo(40);
            assertThat(card.title().namespace()).isEqualTo("stats");
            assertThat(card.title().key()).isEqualTo("overview.artworks");
            assertThat(card.primaryValue().fallback()).isEqualTo("42");
            assertThat(card.supportingText().key()).isEqualTo("plugin.summary");
            assertThat(card.tone()).isEqualTo(DesktopUiTone.INFO);
            assertThat(card.icon()).isEqualTo(DesktopUiIcon.STATISTICS);
            assertThat(card.availability()).isEqualTo(DesktopControlCenterAvailability.AVAILABLE);
            assertThat(card.observedAt()).isEqualTo(snapshot.observedAt());
        });
    }

    private record OverviewOnlyStore(StatsAggregates.Overview overview) implements StatsQueryStore {
        @Override
        public List<StatsAggregates.AuthorStat> topAuthors(int limit) {
            throw new AssertionError("desktop snapshot must not query author rankings");
        }

        @Override
        public List<StatsAggregates.TagStat> topTags(int limit) {
            throw new AssertionError("desktop snapshot must not query tag rankings");
        }

        @Override
        public List<StatsAggregates.MonthlyStat> monthlyArtworkCounts() {
            throw new AssertionError("desktop snapshot must not query monthly history");
        }
    }
}
