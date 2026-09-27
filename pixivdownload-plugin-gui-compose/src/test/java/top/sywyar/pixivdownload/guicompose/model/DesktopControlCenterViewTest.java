package top.sywyar.pixivdownload.guicompose.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiHost;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiPluginSnapshot;
import top.sywyar.pixivdownload.plugin.api.web.*;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Compose 首页存储指标")
class DesktopControlCenterViewTest {
    @Test
    @DisplayName("插件页投影真实描述符与独立状态，读取失败清除旧的恢复模式")
    void projectsPluginMetadataAndClearsUnavailableFacts() throws Exception {
        var response = new java.util.concurrent.atomic.AtomicReference<>(new DesktopUiHost.GuiResponse(
                true, 200, DesktopUiHost.GuiValue.of(Map.of("recoveryMode", true,
                "observedAt", "2025-01-02T00:00:00Z", "plugins", List.of(
                Map.of("id", "sample:one", "name", "Sample", "description", "Manage collections",
                        "iconKey", "images", "colorToken", "blue", "status", "STARTED",
                        "verification", Map.of("status", "UNVERIFIED_LOCAL")),
                Map.of("id", "sample.one", "status", "CRASHED",
                        "verification", Map.of("status", "VERIFIED_OFFICIAL"))))), "", false));
        try (var model = DesktopConfigurationControllerTest.model(new HashMap<>(), Map.of(
                "guiGet", args -> "plugins/status".equals(args[0]) ? response.get()
                        : DesktopUiHost.GuiResponse.unreachable()))) {
            model.loadPluginStatus();
            model.rebuild();
            var overview = plugins(model);
            assertTrue(overview.recoveryMode());
            assertEquals(List.of("sample:one", "sample.one"), overview.plugins().stream()
                    .map(DesktopUiNode.PluginEntry::id).toList());
            assertEquals("Manage collections", overview.plugins().get(0).description());
            assertEquals("images", overview.plugins().get(0).iconKey());
            assertFalse(overview.plugins().get(0).needsAttention());
            assertTrue(overview.plugins().get(1).needsAttention());
            assertTrue(DesktopUiEventProtocol.index(model.snapshot().document())
                    .keySet().containsAll(List.of("plugins.refresh", "plugins.manage")));
            response.set(DesktopUiHost.GuiResponse.unreachable());
            model.loadPluginStatus();
            model.rebuild();
            assertFalse(plugins(model).recoveryMode());
            assertTrue(plugins(model).plugins().isEmpty());
            assertEquals("gui.plugins.state.offline", plugins(model).noticeKey());
        }
    }

    private static DesktopUiNode.PluginOverview plugins(ComposeDesktopUiModel model) {
        var page = model.snapshot().document().pages().stream().filter(p -> p.id().equals("plugins")).findFirst().orElseThrow();
        return assertInstanceOf(DesktopUiNode.PluginOverview.class,
                assertInstanceOf(DesktopUiNode.Surface.class, page.content()).content());
    }

    @Test
    @DisplayName("自动化保留来源、过期状态和真实时间，管理入口只绑定同一来源的注册路由")
    void projectsAutomationFactsAndOwnedActions() throws Exception {
        var source = new DesktopUiPluginSnapshot("sample", false, "sample", 1, false, null, "",
                List.of(), List.of(), List.of(), List.of(WebRouteContribution.admin("/sample.html")),
                List.of(new NavigationContribution("plans", Set.of(NavigationPlacements.DESKTOP_QUICK_START),
                        "sample", "label", "/sample.html", "download", AccessPolicy.ADMIN, 0)));
        var task = Map.of("taskId", "task-one", "title", token("Actual plan"),
                "triggerSummary", token("Every day"), "status", "SUSPENDED", "lastResult", "ERROR",
                "nextRuns", List.of("2025-01-02T01:00:00Z", "invalid", "2025-01-02T01:00:00Z"),
                "observedAt", "2025-01-02T00:00:00Z");
        var data = Map.of("observedAt", "2025-01-02T00:00:00Z", "automations", List.of(
                Map.of("owner", Map.of("pluginId", "sample"), "snapshot",
                        Map.of("availability", "STALE", "tasks", List.of(task))),
                Map.of("owner", Map.of("pluginId", "another"), "snapshot",
                        Map.of("availability", "AVAILABLE", "tasks", List.of(task)))));
        try (var model = DesktopConfigurationControllerTest.model(new HashMap<>(), Map.of(
                "controlCenterSnapshot", args -> new DesktopUiHost.GuiResponse(true, 200,
                        DesktopUiHost.GuiValue.of(data), "", false)), () -> List.of(source))) {
            model.rebuild();
            var page = model.snapshot().document().pages().stream().filter(p -> p.id().equals("automation")).findFirst().orElseThrow();
            var overview = assertInstanceOf(DesktopUiNode.AutomationOverview.class,
                    assertInstanceOf(DesktopUiNode.Surface.class, page.content()).content());
            assertTrue(overview.known());
            assertEquals(2, overview.plans().size());
            var first = overview.plans().get(0);
            assertEquals("Actual plan", first.title().fallback());
            assertEquals("SUSPENDED", first.status());
            assertEquals("ERROR", first.lastResult());
            assertEquals("STALE", first.availability());
            assertEquals(List.of(java.time.Instant.parse("2025-01-02T01:00:00Z").toEpochMilli()), first.nextRuns());
            assertNotNull(first.actionId());
            assertNull(overview.plans().get(1).actionId());
            assertEquals(1, overview.management().size());
            assertTrue(DesktopUiEventProtocol.index(model.snapshot().document()).containsKey(overview.management().get(0).id()));
        }
    }

    @Test
    @DisplayName("首页读取真实贡献并保留过期标记，拒绝未注册的快捷地址")
    void projectsFactsAndKeepsOnlyOwnedRoutes() throws Exception {
        var source = new DesktopUiPluginSnapshot("sample", false, "sample", 1, false, null, "",
                List.of(), List.of(), List.of(), List.of(WebRouteContribution.admin("/sample.html")),
                List.of(
                        new NavigationContribution("valid", Set.of(NavigationPlacements.DESKTOP_QUICK_START),
                                "sample", "label", "/sample.html", "images", AccessPolicy.ADMIN, 0, Set.of(), "nav.description"),
                        new NavigationContribution("invalid", Set.of(NavigationPlacements.DESKTOP_QUICK_START),
                                "sample", "label", "/unregistered.html", "download", AccessPolicy.ADMIN, 1)));
        var task = Map.of("taskId", "active", "title", token("Actual task"),
                "supportingText", token("Actual detail"), "status", "RUNNING", "progress", .6,
                "availability", "STALE", "observedAt", "2025-01-01T00:00:00Z");
        var metric = Map.of("cardId", "count", "title", token("Actual count"),
                "primaryValue", token("431"), "supportingText", token("Actual detail"),
                "availability", "AVAILABLE", "observedAt", "2025-01-01T00:00:00Z");
        var data = Map.of(
                "runningTasks", List.of(Map.of("owner", Map.of("pluginId", "sample"), "task", task)),
                "cards", List.of(Map.of("owner", Map.of("pluginId", "sample"), "card", metric)));
        try (var model = DesktopConfigurationControllerTest.model(new HashMap<>(), Map.of(
                "controlCenterSnapshot", args -> new DesktopUiHost.GuiResponse(true, 200,
                        DesktopUiHost.GuiValue.of(data), "", false)), () -> List.of(source))) {
            model.rebuild();
            var home = home(model);
            assertTrue(home.tasksKnown());
            assertEquals(1, home.shortcuts().size());
            assertEquals("images", home.shortcuts().get(0).symbol());
            assertEquals("sample", home.shortcuts().get(0).button().help().namespace());
            assertEquals("nav.description", home.shortcuts().get(0).button().help().key());
            assertTrue(DesktopUiEventProtocol.index(model.snapshot().document())
                    .containsKey(home.shortcuts().get(0).button().id()));
            assertEquals("Actual task", home.tasks().get(0).title().fallback());
            assertNull(home.tasks().get(0).progress());
            assertEquals("gui.compose.home.freshness.stale", home.tasks().get(0).freshness().key());
            assertEquals("431", home.metrics().get(0).value().fallback());
            assertEquals("gui.compose.home.storage-available", home.metrics().get(1).title().key());
        }
    }

    @Test
    @DisplayName("首页快捷入口随插件贡献撤回与恢复，不根据内置图标补业务文案")
    void homeNavigationLifecycle() throws Exception {
        var source = new DesktopUiPluginSnapshot("sample", false, "sample", 1, false, "sample", "name",
                List.of(), List.of(), List.of(), List.of(WebRouteContribution.admin("/sample.html")),
                List.of(new NavigationContribution("entry", NavigationPlacements.DESKTOP_QUICK_START,
                        "sample", "custom.title", "/sample.html", "download", AccessPolicy.ADMIN, 0)));
        var sources = new java.util.concurrent.atomic.AtomicReference<List<DesktopUiPluginSnapshot>>(List.of());
        var opened = new java.util.concurrent.atomic.AtomicInteger();
        try (var model = DesktopConfigurationControllerTest.model(new HashMap<>(), Map.of(
                "openExternalUri", args -> { opened.incrementAndGet(); return null; }), sources::get)) {
            assertTrue(home(model).shortcuts().isEmpty());
            sources.set(List.of(source));
            model.rebuild();
            var button = home(model).shortcuts().get(0).button();
            assertEquals("custom.title", button.label().key());
            assertEquals("gui.compose.home.shortcut.open", button.help().key());
            var observed = model.snapshot();
            sources.set(List.of());
            model.dispatch(observed, new DesktopUiNode.Event(DesktopUiNode.EventType.ACTIVATE, button.id(), DesktopUiNode.Value.empty()));
            assertEquals(0, opened.get());
            assertTrue(home(model).shortcuts().isEmpty());
            sources.set(List.of(source));
            model.rebuild();
            assertEquals(1, home(model).shortcuts().size());
        }
    }

    @Test
    @DisplayName("连接失败不报告空任务，成功返回空数组才表示没有来源报告任务")
    void distinguishesEmptyFromUnavailable() throws Exception {
        for (boolean reachable : List.of(false, true)) {
            try (var model = DesktopConfigurationControllerTest.model(new HashMap<>(), Map.of(
                    "controlCenterSnapshot", args -> reachable
                            ? new DesktopUiHost.GuiResponse(true, 200, DesktopUiHost.GuiValue.of(
                                    Map.of("runningTasks", List.of(), "cards", List.of())), "", false)
                            : DesktopUiHost.GuiResponse.unreachable()))) {
                model.rebuild();
                assertEquals(reachable, home(model).tasksKnown());
                assertTrue(home(model).tasks().isEmpty());
            }
        }
    }

    private static Map<String, Object> token(String text) {
        return Map.of("fallback", text, "key", "", "arguments", List.of());
    }

    @Test
    @DisplayName("系统摘要读取已保存的代理配置，插件离线不显示零个运行")
    void projectsSavedProxyAndPluginAvailability() throws Exception {
        for (String address : List.of("127.0.0.1", "::1")) {
            var stored = new HashMap<>(Map.of("proxy.enabled", "true", "proxy.host", address, "proxy.port", "7890"));
            try (var model = DesktopConfigurationControllerTest.model(stored, Map.of())) {
                model.rebuild();
                var system = home(model).system();
                assertEquals("gui.compose.home.proxy.enabled", system.proxy().key());
                assertEquals(address.contains(":") ? "[::1]:7890" : "127.0.0.1:7890", system.endpoint().fallback());
                assertEquals("gui.compose.home.freshness.unavailable", system.plugins().key());
                stored.put("proxy.enabled", "false");
                model.rebuild();
                assertEquals("gui.compose.home.proxy.disabled", home(model).system().proxy().key());
                assertNull(home(model).system().endpoint());
            }
        }
        try (var model = DesktopConfigurationControllerTest.model(new HashMap<>(), Map.of(
                "guiGet", args -> new DesktopUiHost.GuiResponse(true, 200, DesktopUiHost.GuiValue.of(Map.of(
                        "observedAt", "2025-01-01T00:00:00Z",
                        "plugins", List.of(Map.of("id", "one", "status", "STARTED"),
                                Map.of("id", "two", "status", "DISABLED")))), "", false)))) {
            model.loadPluginStatus();
            model.rebuild();
            assertEquals(List.of("1", "2"), home(model).system().plugins().arguments());
        }
    }

    private static DesktopUiNode.HomeOverview home(ComposeDesktopUiModel model) {
        var surface = (DesktopUiNode.Surface) model.snapshot().document().pages().get(0).content();
        return assertInstanceOf(DesktopUiNode.HomeOverview.class, surface.content());
    }

    @Test
    @DisplayName("容量按 1024 进制紧凑显示")
    void formatsCompactBinarySizes() {
        assertEquals("0 B", DesktopControlCenterView.formatCompactBinarySize(0L));
        assertEquals("1.5 KB", DesktopControlCenterView.formatCompactBinarySize(1536L));
        assertEquals("100 GB", DesktopControlCenterView.formatCompactBinarySize(100L << 30));
        assertEquals("1 TB", DesktopControlCenterView.formatCompactBinarySize(1L << 40));
    }
}
