package top.sywyar.pixivdownload.gui.controlcenter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import top.sywyar.pixivdownload.gui.controller.GuiControlCenterController;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopControlCenterAvailability;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopDashboardCardContribution;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopDashboardSnapshot;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiIcon;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiTone;
import top.sywyar.pixivdownload.plugin.api.gui.document.DesktopUiNode.TextToken;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.runtime.ExternalCapabilityOwner;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("GUI 控制中心只读端点")
class GuiControlCenterControllerTest {

    private static final Instant NOW = Instant.parse("2026-08-20T00:00:00Z");

    @Test
    @DisplayName("本机请求返回带可信 owner 的物化快照")
    void localRequestReturnsMaterializedSnapshot() throws Exception {
        ExternalCapabilityOwner owner = new ExternalCapabilityOwner("stats", "stats", 3L, 7L);
        DesktopControlCenterRegistry registry = registry(Set.of(owner));
        registry.registerPrepared(owner, List.of(() -> new DesktopDashboardSnapshot(
                List.of(new DesktopDashboardCardContribution(
                        "works", 0, TextToken.raw("Works"), TextToken.raw("42"), TextToken.raw("Total"),
                        DesktopUiTone.SUCCESS, DesktopUiIcon.STATISTICS,
                        DesktopControlCenterAvailability.AVAILABLE, NOW)),
                List.of(), NOW)), List.of());
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new GuiControlCenterController(registry))
                .build();

        mockMvc.perform(get("/api/gui/control-center"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cards[0].owner.pluginId").value("stats"))
                .andExpect(jsonPath("$.cards[0].owner.generation").value(3))
                .andExpect(jsonPath("$.cards[0].owner.publication").value(7))
                .andExpect(jsonPath("$.cards[0].card.cardId").value("works"))
                .andExpect(jsonPath("$.cards[0].card.primaryValue.fallback").value("42"));
    }

    @Test
    @DisplayName("非本机请求返回 403 且不调用插件 Source")
    void remoteRequestIsRejectedBeforeRefresh() throws Exception {
        ExternalCapabilityOwner owner = new ExternalCapabilityOwner("stats", "stats", 1L, 1L);
        AtomicInteger calls = new AtomicInteger();
        DesktopControlCenterRegistry registry = registry(Set.of(owner));
        registry.registerPrepared(owner, List.of(() -> {
            calls.incrementAndGet();
            return new DesktopDashboardSnapshot(List.of(), List.of(), NOW);
        }), List.of());
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new GuiControlCenterController(registry))
                .build();

        mockMvc.perform(get("/api/gui/control-center").with(request -> {
                    request.setRemoteAddr("8.8.8.8");
                    return request;
                }))
                .andExpect(status().isForbidden());

        assertThat(calls).hasValue(0);
    }

    private static DesktopControlCenterRegistry registry(Set<ExternalCapabilityOwner> admitted) {
        return new DesktopControlCenterRegistry(
                admitted::contains, Runnable::run,
                Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofSeconds(2));
    }
}
