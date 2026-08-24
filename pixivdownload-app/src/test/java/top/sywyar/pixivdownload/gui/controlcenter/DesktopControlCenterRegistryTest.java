package top.sywyar.pixivdownload.gui.controlcenter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopAutomationSnapshot;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopAutomationTaskContribution;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopControlCenterAvailability;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopDashboardCardContribution;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopDashboardSnapshot;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopRunningTaskContribution;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiIcon;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiTone;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiText;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.runtime.ExternalCapabilityOwner;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("桌面控制中心物化注册表")
class DesktopControlCenterRegistryTest {

    private static final Instant NOW = Instant.parse("2026-08-20T00:00:00Z");

    @Test
    @DisplayName("多 owner 卡片与任务确定排序，自动化只保留未来二十四小时")
    void sortsMultipleOwnersAndBoundsAutomationWindow() {
        Set<ExternalCapabilityOwner> admitted = ConcurrentHashMap.newKeySet();
        MutableClock clock = new MutableClock(NOW);
        DesktopControlCenterRegistry registry = registry(admitted, Runnable::run, clock);
        ExternalCapabilityOwner beta = owner("beta", 2L);
        ExternalCapabilityOwner alpha = owner("alpha", 1L);
        admitted.addAll(List.of(beta, alpha));

        registry.registerPrepared(beta, List.of(() -> dashboard(
                List.of(card("beta", 0, NOW)),
                List.of(task("queued", 0, DesktopRunningTaskContribution.Status.QUEUED, NOW)))), List.of());
        registry.registerPrepared(alpha, List.of(() -> dashboard(
                List.of(card("alpha", 0, NOW)),
                List.of(task("running", 99, DesktopRunningTaskContribution.Status.RUNNING, NOW)))),
                List.of(() -> new DesktopAutomationSnapshot(List.of(
                                automationTask("later", 2, List.of(
                                        NOW.minusSeconds(1), NOW.plusSeconds(3600), NOW.plus(Duration.ofHours(25)))),
                                automationTask("first", 1, List.of(NOW.plusSeconds(60), NOW.plusSeconds(60)))),
                        DesktopControlCenterAvailability.AVAILABLE, NOW)));

        registry.refresh();
        DesktopControlCenterRegistry.Snapshot snapshot = registry.snapshot();

        assertThat(snapshot.cards()).extracting(item -> item.owner().pluginId() + ":" + item.card().cardId())
                .containsExactly("alpha:alpha", "beta:beta");
        assertThat(snapshot.runningTasks()).extracting(item -> item.task().taskId())
                .containsExactly("running", "queued");
        assertThat(snapshot.automations()).singleElement().satisfies(owned -> {
            assertThat(owned.snapshot().tasks()).extracting(DesktopAutomationTaskContribution::taskId)
                    .containsExactly("first", "later");
            assertThat(owned.snapshot().tasks().get(0).nextRuns()).containsExactly(NOW.plusSeconds(60));
            assertThat(owned.snapshot().tasks().get(1).nextRuns()).containsExactly(NOW.plusSeconds(3600));
        });
    }

    @Test
    @DisplayName("单项非法、重复与超限被隔离，陈旧时间投影为 STALE")
    void rejectsBadDuplicateAndOverLimitItems() {
        ExternalCapabilityOwner owner = owner("demo", 1L);
        MutableClock clock = new MutableClock(NOW);
        DesktopControlCenterRegistry registry = registry(Set.of(owner), Runnable::run, clock);
        List<DesktopDashboardCardContribution> cards = new ArrayList<>();
        cards.add(card("stale", 0, NOW.minusSeconds(61)));
        cards.add(card("stale", 1, NOW));
        cards.add(card("future", 2, NOW.plusSeconds(2)));
        cards.add(new DesktopDashboardCardContribution(
                "too-long", 3, DesktopUiText.raw("x".repeat(513)), DesktopUiText.raw("1"), DesktopUiText.raw("Detail"),
                DesktopUiTone.DEFAULT, DesktopUiIcon.INFO,
                DesktopControlCenterAvailability.AVAILABLE, NOW));
        for (int index = 0; index < 40; index++) {
            cards.add(card("card-" + index, index + 10, NOW));
        }
        registry.registerPrepared(owner,
                List.of(() -> dashboard(cards, List.of())), List.of());

        registry.refresh();
        List<DesktopDashboardCardContribution> accepted = registry.snapshot().cards().stream()
                .map(DesktopControlCenterRegistry.OwnedDashboardCard::card)
                .toList();

        assertThat(accepted).hasSize(DesktopControlCenterRegistry.MAX_CARDS_PER_OWNER);
        assertThat(accepted).extracting(DesktopDashboardCardContribution::cardId)
                .doesNotContain("future", "too-long").doesNotHaveDuplicates();
        assertThat(accepted.get(0).availability()).isEqualTo(DesktopControlCenterAvailability.STALE);
    }

    @Test
    @DisplayName("Source 异常只让本 owner 陈旧且无历史自动化投影为不可用")
    void sourceFailureIsOwnerScoped() {
        ExternalCapabilityOwner degraded = owner("degraded", 1L);
        ExternalCapabilityOwner healthy = owner("healthy", 2L);
        Set<ExternalCapabilityOwner> admitted = Set.of(degraded, healthy);
        AtomicBoolean fail = new AtomicBoolean();
        DesktopControlCenterRegistry registry = registry(admitted, Runnable::run, new MutableClock(NOW));
        registry.registerPrepared(degraded, List.of(() -> {
            if (fail.get()) throw new IllegalStateException("plugin detail must not escape");
            return dashboard(List.of(card("degraded", 0, NOW)), List.of());
        }), List.of(() -> {
            throw new IllegalStateException("automation detail must not escape");
        }));
        registry.registerPrepared(healthy,
                List.of(() -> dashboard(List.of(card("healthy", 0, NOW)), List.of())), List.of());

        registry.refresh();
        fail.set(true);
        registry.refresh();
        DesktopControlCenterRegistry.Snapshot snapshot = registry.snapshot();

        assertThat(snapshot.cards()).extracting(item -> item.card().availability())
                .containsExactly(DesktopControlCenterAvailability.STALE,
                        DesktopControlCenterAvailability.AVAILABLE);
        assertThat(snapshot.automations()).singleElement().satisfies(owned ->
                assertThat(owned.snapshot().availability())
                        .isEqualTo(DesktopControlCenterAvailability.UNAVAILABLE));
    }

    @Test
    @DisplayName("超时中的刷新保留旧纯值并标记陈旧")
    void timedOutRefreshMarksCachedValuesStale() {
        ExternalCapabilityOwner owner = owner("slow", 1L);
        MutableClock clock = new MutableClock(NOW);
        Queue<Runnable> pending = new ArrayDeque<>();
        DesktopControlCenterRegistry registry = registry(Set.of(owner), pending::add, clock);
        registry.registerPrepared(owner,
                List.of(() -> dashboard(List.of(card("slow", 0, clock.instant())), List.of())), List.of());

        registry.refresh();
        pending.remove().run();
        assertThat(registry.snapshot().cards().get(0).card().availability())
                .isEqualTo(DesktopControlCenterAvailability.AVAILABLE);

        registry.refresh();
        clock.advance(Duration.ofSeconds(3));
        registry.refresh();

        assertThat(registry.snapshot().cards().get(0).card().availability())
                .isEqualTo(DesktopControlCenterAvailability.STALE);
    }

    @Test
    @DisplayName("admission 撤回立即隐藏旧缓存且旧 owner 不能撤掉替代者")
    void exactAdmissionAndWithdrawalProtectReplacement() {
        Set<ExternalCapabilityOwner> admitted = ConcurrentHashMap.newKeySet();
        DesktopControlCenterRegistry registry = registry(admitted, Runnable::run, new MutableClock(NOW));
        ExternalCapabilityOwner oldOwner = owner("replaceable", 1L);
        admitted.add(oldOwner);
        registry.registerPrepared(oldOwner,
                List.of(() -> dashboard(List.of(card("old", 0, NOW)), List.of())), List.of());
        registry.refresh();
        admitted.remove(oldOwner);
        assertThat(registry.snapshot().cards()).isEmpty();

        ExternalCapabilityOwner replacement = owner("replaceable", 2L);
        admitted.add(replacement);
        registry.registerPrepared(replacement,
                List.of(() -> dashboard(List.of(card("new", 0, NOW)), List.of())), List.of());
        registry.refresh();
        registry.unregisterPrepared(oldOwner);

        assertThat(registry.snapshot().cards()).singleElement().satisfies(item -> {
            assertThat(item.owner().publication()).isEqualTo(2L);
            assertThat(item.card().cardId()).isEqualTo("new");
        });
    }

    private static DesktopControlCenterRegistry registry(Set<ExternalCapabilityOwner> admitted,
                                                         java.util.concurrent.Executor executor,
                                                         Clock clock) {
        return new DesktopControlCenterRegistry(admitted::contains, executor, clock, Duration.ofSeconds(2));
    }

    private static ExternalCapabilityOwner owner(String pluginId, long publication) {
        return new ExternalCapabilityOwner(pluginId, pluginId, 1L, publication);
    }

    private static DesktopDashboardSnapshot dashboard(List<DesktopDashboardCardContribution> cards,
                                                       List<DesktopRunningTaskContribution> tasks) {
        return new DesktopDashboardSnapshot(cards, tasks, NOW);
    }

    private static DesktopDashboardCardContribution card(String id, int order, Instant observedAt) {
        return new DesktopDashboardCardContribution(
                id, order, DesktopUiText.raw(id), DesktopUiText.raw("1"), DesktopUiText.raw("Detail"),
                DesktopUiTone.DEFAULT, DesktopUiIcon.INFO,
                DesktopControlCenterAvailability.AVAILABLE, observedAt);
    }

    private static DesktopRunningTaskContribution task(
            String id, int order, DesktopRunningTaskContribution.Status status, Instant observedAt) {
        return new DesktopRunningTaskContribution(
                id, order, DesktopUiText.raw(id), DesktopUiText.raw("Detail"), status, null,
                DesktopControlCenterAvailability.AVAILABLE, observedAt);
    }

    private static DesktopAutomationTaskContribution automationTask(
            String id, int order, List<Instant> nextRuns) {
        return new DesktopAutomationTaskContribution(
                id, order, DesktopUiText.raw(id), DesktopUiText.raw("Hourly"),
                DesktopAutomationTaskContribution.Status.IDLE,
                DesktopAutomationTaskContribution.LastResult.NEVER, nextRuns, NOW);
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
