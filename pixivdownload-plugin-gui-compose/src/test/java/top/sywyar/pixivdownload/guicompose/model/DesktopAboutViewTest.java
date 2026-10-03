package top.sywyar.pixivdownload.guicompose.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiHost;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Compose 关于页")
class DesktopAboutViewTest {
    @Test
    @DisplayName("平台资料使用真实运行时和宿主版本，项目及维护者链接经宿主打开")
    void platformFactsAndLinks() throws Exception {
        var opened = new AtomicReference<String>();
        try (var model = DesktopConfigurationControllerTest.model(new HashMap<>(), Map.of(
                "applicationVersion", args -> "2.3.4-fixture",
                "applicationBuildChannel", args -> DesktopUiHost.BuildChannel.NIGHTLY,
                "developmentMode", args -> true,
                "projectUrl", args -> "https://example.com/project",
                "releasesUrl", args -> "https://example.com/releases",
                "openExternalUri", args -> { opened.set(args[0].toString()); return null; }
        ))) {
            var about = overview(model);
            var facts = about.facts().stream().collect(Collectors.toMap(DesktopUiNode.AboutFact::id, DesktopUiNode.AboutFact::value));
            assertEquals("2.3.4-fixture", about.version());
            assertEquals(about.version() + "(" + top.sywyar.pixivdownload.sdk.SdkVersion.current() + ")",
                    facts.get("version").fallback());
            assertEquals("gui.compose.about.nightly", facts.get("channel").key());
            assertEquals("gui.compose.about.development", facts.get("mode").key());
            assertEquals(System.getProperty("os.name"), facts.get("os").fallback());
            assertEquals(System.getProperty("os.version"), facts.get("os-version").fallback());
            assertEquals(System.getProperty("os.arch"), facts.get("architecture").fallback());
            assertEquals(System.getProperty("java.runtime.version"), facts.get("java").fallback());
            assertEquals(kotlin.KotlinVersion.CURRENT.toString(), facts.get("kotlin").fallback());
            assertEquals(Integer.toString(Runtime.getRuntime().availableProcessors()), facts.get("processors").fallback());
            assertEquals(ComposeApplicationInfo.bytes(Runtime.getRuntime().maxMemory()), facts.get("heap"));
            assertFalse(facts.get("cpu").fallback().isBlank() && facts.get("cpu").key().isBlank());
            assertEquals(java.util.Set.of("version", "channel", "mode", "os", "os-version", "architecture",
                    "cpu", "processors", "memory", "heap", "java", "java-vendor", "vm", "interface", "kotlin", "launch",
                    "plugins", "directory", "branch"), facts.keySet());
            var endpoints = DesktopUiEventProtocol.index(model.snapshot().document());
            assertTrue(endpoints.keySet().containsAll(about.links().stream().map(DesktopUiNode.Link::id).toList()));
            about.maintainers().forEach(person -> assertTrue(endpoints.containsKey(person.link().id())));
            activate(model, "about.project");
            awaitIdle(model);
            assertEquals("https://example.com/project", opened.get());
        }
    }

    @Test
    @DisplayName("硬件字段只提取型号，未知内存保留不可用状态")
    void hardwareValues() throws Exception {
        try (var reader = new java.io.BufferedReader(new java.io.StringReader(
                "processor : 0\nserial : private\nmodel name : Example CPU\nmodel name : Other CPU\n"))) {
            assertEquals("Example CPU", ComposeApplicationInfo.cpuModel(reader));
        }
        try (var reader = new java.io.BufferedReader(new java.io.StringReader("Hardware : Example ARM\n"))) {
            assertEquals("Example ARM", ComposeApplicationInfo.cpuModel(reader));
        }
        try (var reader = new java.io.BufferedReader(new java.io.StringReader("processor : 0\n"))) {
            assertNull(ComposeApplicationInfo.cpuModel(reader));
        }
        assertEquals("32.0 GiB", ComposeApplicationInfo.bytes(32L * 1024 * 1024 * 1024).fallback());
        assertEquals("gui.compose.about.unknown", ComposeApplicationInfo.bytes(-1).key());
    }

    @Test
    @DisplayName("插件信息复用完整状态快照，安装列表变化后刷新，开发目录不在普通模式泄露")
    void pluginVersionFacts() throws Exception {
        var snapshot = new AtomicReference<>(Map.<String, Object>of(
                "sdkVersion", "8.2.3-dev.ab123456",
                "development", Map.of("directory", "D:/fixture", "branch", "fixture/about"),
                "plugins", List.of(
                        Map.of("id", "plugin-z", "source", "external", "version", "4.5.6",
                                "displayVersion", "4.5.6-dev.ab123456",
                                "sdkRequirement", Map.of("specified", true, "required", "8.2")),
                        Map.of("id", "plugin-a", "source", "external", "version", "2.3.4"),
                        Map.of("id", "missing", "source", "not-installed"))
        ));
        try (var model = DesktopConfigurationControllerTest.model(new HashMap<>(), Map.of(
                "guiGet", args -> new DesktopUiHost.GuiResponse(true, 200,
                        DesktopUiHost.GuiValue.of(snapshot.get()), "", false),
                "developmentMode", args -> false
        ))) {
            model.loadPluginStatus();
            model.rebuildStatus();
            var facts = overview(model).facts().stream().collect(Collectors.toMap(
                    DesktopUiNode.AboutFact::id, fact -> fact));
            assertFalse(facts.containsKey("directory"));
            assertFalse(facts.containsKey("branch"));
            assertTrue(facts.get("version").value().fallback().endsWith("(8.2.3-dev.ab123456)"));
            assertEquals("plugin-a-2.3.4(*)\nplugin-z-4.5.6-dev.ab123456(8.2.0)", facts.get("plugins").value().fallback());
            assertTrue(facts.get("plugins").expandable());
            snapshot.set(Map.of("plugins", List.of()));
            model.loadPluginStatus();
            model.rebuildStatus();
            assertEquals("gui.plugins.state.empty", overview(model).facts().stream()
                    .filter(f -> f.id().equals("plugins")).findFirst().orElseThrow().value().key());
        }
    }

    @Test
    @DisplayName("渠道只使用构建元数据，与版本字符串及开发模式互不影响")
    void buildChannelDoesNotDependOnVersionOrRunMode() throws Exception {
        var labels = Map.of(
                DesktopUiHost.BuildChannel.LOCAL, "local",
                DesktopUiHost.BuildChannel.RELEASE, "release",
                DesktopUiHost.BuildChannel.NIGHTLY, "nightly",
                DesktopUiHost.BuildChannel.UNKNOWN, "unknown"
        );
        for (var entry : labels.entrySet()) {
            for (boolean development : List.of(false, true)) {
                try (var model = DesktopConfigurationControllerTest.model(new HashMap<>(), Map.of(
                        "applicationVersion", args -> "2.3.4-nightly.fixture",
                        "currentVersionNightly", args -> true,
                        "applicationBuildChannel", args -> entry.getKey(),
                        "developmentMode", args -> development
                ))) {
                    var facts = overview(model).facts().stream()
                            .collect(Collectors.toMap(DesktopUiNode.AboutFact::id, DesktopUiNode.AboutFact::value));
                    assertEquals("gui.compose.about." + entry.getValue(), facts.get("channel").key());
                    assertEquals("gui.compose.about." + (development ? "development" : "normal"), facts.get("mode").key());
                }
            }
        }
    }

    @Test
    @DisplayName("检查中禁用重复提交，失败可重试且检查结果以内联状态呈现")
    void inlineUpdateStates() throws Exception {
        var response = new AtomicReference<>(DesktopUiHost.GuiResponse.unreachable());
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var model = DesktopConfigurationControllerTest.model(new HashMap<>(), Map.of(
                "guiGet", args -> {
                    if (!"update/check?force=true".equals(args[0])) return DesktopUiHost.GuiResponse.unreachable();
                    entered.countDown();
                    try { if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("check was not released"); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
                    return response.get();
                }
        ))) {
            assertEquals(DesktopUiNode.AboutUpdateState.UNKNOWN, overview(model).updateState());
            activate(model, "about.update.check");
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertEquals(DesktopUiNode.AboutUpdateState.CHECKING, overview(model).updateState());
            assertFalse(overview(model).checkUpdate().enabled());
            release.countDown();
            awaitIdle(model);
            assertEquals(DesktopUiNode.AboutUpdateState.ERROR, overview(model).updateState());
            assertTrue(overview(model).checkUpdate().enabled());
            assertTrue(model.snapshot().document().dialogs().isEmpty());
            var cases = List.of(
                    Map.of("enabled", true, "checkSucceeded", true, "updateAvailable", false),
                    Map.of("enabled", false, "checkSucceeded", false),
                    Map.of("enabled", true, "checkSucceeded", false)
            );
            var expected = List.of(DesktopUiNode.AboutUpdateState.CURRENT, DesktopUiNode.AboutUpdateState.DISABLED,
                    DesktopUiNode.AboutUpdateState.ERROR);
            for (int i = 0; i < cases.size(); i++) {
                response.set(new DesktopUiHost.GuiResponse(true, 200, DesktopUiHost.GuiValue.of(cases.get(i)), "", false));
                activate(model, "about.update.check");
                awaitIdle(model);
                assertEquals(expected.get(i), overview(model).updateState());
                assertTrue(model.snapshot().document().dialogs().isEmpty());
            }
            response.set(new DesktopUiHost.GuiResponse(true, 200, DesktopUiHost.GuiValue.of(Map.of(
                    "enabled", true, "checkSucceeded", true, "updateAvailable", true,
                    "latestVersion", "2.3.5-fixture", "assetUrl", "https://example.com/update"
            )), "", false));
            activate(model, "about.update.check");
            awaitIdle(model);
            assertEquals(DesktopUiNode.AboutUpdateState.AVAILABLE, overview(model).updateState());
            assertFalse(overview(model).updates().isEmpty());
            assertTrue(DesktopUiEventProtocol.index(model.snapshot().document()).containsKey("about.update.official.install"));
        } finally { release.countDown(); }
    }

    static DesktopUiNode.AboutOverview overview(ComposeDesktopUiModel model) {
        var page = model.snapshot().document().pages().stream().filter(it -> it.id().equals("about")).findFirst().orElseThrow();
        return assertInstanceOf(DesktopUiNode.AboutOverview.class,
                assertInstanceOf(DesktopUiNode.Surface.class, page.content()).content());
    }

    private static void activate(ComposeDesktopUiModel model, String id) {
        synchronized (model) {
            model.dispatch(model.snapshot(), new DesktopUiNode.Event(DesktopUiNode.EventType.ACTIVATE, id, DesktopUiNode.Value.empty()));
        }
    }

    private static void awaitIdle(ComposeDesktopUiModel model) {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            while (model.busy() || !overview(model).checkUpdate().enabled()) Thread.sleep(10);
        });
    }
}
