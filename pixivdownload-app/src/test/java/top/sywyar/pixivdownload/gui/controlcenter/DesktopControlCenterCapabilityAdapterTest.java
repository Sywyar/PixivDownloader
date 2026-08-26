package top.sywyar.pixivdownload.gui.controlcenter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopControlCenterAvailability;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopDashboardCardContribution;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopDashboardSnapshot;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopDashboardSource;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiIcon;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiText;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiTone;
import top.sywyar.pixivdownload.plugin.lifecycle.PluginCapabilityContributionRegistrar;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.DesktopControlCenterCapabilityAdapter;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.runtime.ExternalCapabilityDrain;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.runtime.ExternalCapabilityInvocationRegistry;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.runtime.ExternalCapabilityPublication;

import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("桌面控制中心 capability 适配器")
class DesktopControlCenterCapabilityAdapterTest {

    @Test
    @DisplayName("只读纯值随精确 publication 发布撤回且注册表不残留 Source")
    void valuesFollowExactPublicationLifecycle() throws Exception {
        Instant now = Instant.parse("2026-08-20T00:00:00Z");
        ExternalCapabilityInvocationRegistry invocations = new ExternalCapabilityInvocationRegistry();
        DesktopControlCenterRegistry registry = new DesktopControlCenterRegistry(
                invocations::acceptsInvocations, Runnable::run,
                Clock.fixed(now, ZoneOffset.UTC), Duration.ofSeconds(2));
        DesktopControlCenterCapabilityAdapter adapter =
                new DesktopControlCenterCapabilityAdapter(registry, invocations);
        PluginCapabilityContributionRegistrar registrar = new PluginCapabilityContributionRegistrar(
                List.of(), List.of(), List.of(adapter), invocations);
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.registerBean(DesktopDashboardSource.class, () -> () -> new DesktopDashboardSnapshot(
                List.of(new DesktopDashboardCardContribution(
                        "works", 0, DesktopUiText.raw("Works"), DesktopUiText.raw("42"), DesktopUiText.raw("Total"),
                        DesktopUiTone.SUCCESS, DesktopUiIcon.STATISTICS,
                        DesktopControlCenterAvailability.AVAILABLE, now)),
                List.of(), now));
        context.refresh();

        PluginCapabilityContributionRegistrar.PreparedOwner prepared =
                registrar.allocateOwner("stats", "stats", 7L);
        registrar.prepareInto(prepared, context);
        ExternalCapabilityPublication publication = registrar.publish(prepared);
        registry.refresh();

        assertThat(registry.snapshot().cards()).singleElement().satisfies(owned -> {
            assertThat(owned.owner().pluginId()).isEqualTo("stats");
            assertThat(owned.owner().generation()).isEqualTo(7L);
            assertThat(owned.card().cardId()).isEqualTo("works");
            assertThat(owned.card().getClass().getClassLoader())
                    .isSameAs(DesktopDashboardCardContribution.class.getClassLoader());
        });

        ExternalCapabilityDrain drain = registrar.withdraw(publication).orElseThrow();
        assertThat(registry.snapshot().cards()).isEmpty();
        assertThat(ownerEntries(registry)).hasSize(1);

        registrar.retireDrained(drain);
        assertThat(ownerEntries(registry)).isEmpty();
        context.close();
        registrar.acknowledgeRetired(drain);
        assertThat(registrar.releaseRetirementProof(drain)).isTrue();
    }

    private static Map<?, ?> ownerEntries(DesktopControlCenterRegistry registry) throws Exception {
        Field owners = DesktopControlCenterRegistry.class.getDeclaredField("owners");
        owners.setAccessible(true);
        return (Map<?, ?>) owners.get(registry);
    }
}
