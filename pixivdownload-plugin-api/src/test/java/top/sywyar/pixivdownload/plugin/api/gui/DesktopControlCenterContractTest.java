package top.sywyar.pixivdownload.plugin.api.gui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.plugin.api.gui.document.DesktopUiNode.TextToken;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("桌面控制中心纯值契约")
class DesktopControlCenterContractTest {

    private static final Instant NOW = Instant.parse("2026-08-20T00:00:00Z");

    @Test
    @DisplayName("首页与自动化快照防御性复制列表")
    void snapshotsDefensivelyCopyLists() {
        List<DesktopDashboardCardContribution> cards = new ArrayList<>();
        cards.add(card("works"));
        DesktopDashboardSnapshot dashboard = new DesktopDashboardSnapshot(cards, List.of(), NOW);
        cards.clear();

        List<Instant> nextRuns = new ArrayList<>(List.of(NOW.plusSeconds(60)));
        DesktopAutomationTaskContribution task = new DesktopAutomationTaskContribution(
                "schedule", 0, TextToken.raw("Schedule"), TextToken.raw("Hourly"),
                DesktopAutomationTaskContribution.Status.IDLE,
                DesktopAutomationTaskContribution.LastResult.NEVER, nextRuns, NOW);
        nextRuns.clear();
        DesktopAutomationSnapshot automation = new DesktopAutomationSnapshot(
                new ArrayList<>(List.of(task)), DesktopControlCenterAvailability.AVAILABLE, NOW);

        assertThat(dashboard.cards()).extracting(DesktopDashboardCardContribution::cardId)
                .containsExactly("works");
        assertThat(task.nextRuns()).containsExactly(NOW.plusSeconds(60));
        assertThat(automation.tasks()).containsExactly(task);
        assertThatThrownBy(() -> dashboard.cards().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("运行进度拒绝非有限值与范围外数值")
    void runningProgressIsBounded() {
        assertThatThrownBy(() -> runningTask(Double.NaN))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> runningTask(-0.01d))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> runningTask(1.01d))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(runningTask(null).progress()).isNull();
        assertThat(runningTask(0.5d).progress()).isEqualTo(0.5d);
    }

    private static DesktopDashboardCardContribution card(String id) {
        return new DesktopDashboardCardContribution(
                id, 0, TextToken.raw("Title"), TextToken.raw("1"), TextToken.raw("Detail"),
                DesktopUiTone.DEFAULT, DesktopUiIcon.INFO,
                DesktopControlCenterAvailability.AVAILABLE, NOW);
    }

    private static DesktopRunningTaskContribution runningTask(Double progress) {
        return new DesktopRunningTaskContribution(
                "running", 0, TextToken.raw("Running"), TextToken.raw("Detail"),
                DesktopRunningTaskContribution.Status.RUNNING, progress,
                DesktopControlCenterAvailability.AVAILABLE, NOW);
    }
}
