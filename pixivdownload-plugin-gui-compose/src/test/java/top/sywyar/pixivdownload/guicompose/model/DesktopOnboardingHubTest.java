package top.sywyar.pixivdownload.guicompose.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiDocument;
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiHost;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiPluginSnapshot;
import top.sywyar.pixivdownload.plugin.api.gui.GuiOnboardingStepContribution;
import top.sywyar.pixivdownload.plugin.api.web.AccessPolicy;
import top.sywyar.pixivdownload.plugin.api.web.NavigationContribution;
import top.sywyar.pixivdownload.plugin.api.web.NavigationPlacements;
import top.sywyar.pixivdownload.plugin.api.web.WebRouteContribution;

import java.net.URI;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("卡片引导的宿主动作与状态")
class DesktopOnboardingHubTest {
    @Test
    @DisplayName("代理地址格式校验接受完整 IP 和主机名")
    void acceptsCompleteProxyHosts() {
        for (String host : List.of("127.0.0.1", " 192.0.2.1 ", "localhost", "proxy.local", "proxy.example.",
                "proxy-1.example", "代理.example", "::1", "[::1]", "2001:db8::1", "fe80::1%12", "::ffff:192.0.2.1")) {
            assertTrue(DesktopUiNode.OnboardingProxySettings.validHost(host), host);
        }
    }

    @Test
    @DisplayName("未完成、越界或混入协议及端口的代理地址不能通过格式校验")
    void rejectsIncompleteProxyHosts() {
        for (String host : List.of("", " ", "127", "127.0.0", "127.0.0.", "127..0.1", "256.0.0.1",
                "127.0.0.1.", "127.0.0.1:7890", "http://127.0.0.1", "proxy/path", "user@proxy",
                "proxy?query", "proxy#fragment", "proxy host", "-proxy", "proxy-", "proxy..local", "2001:db8:", "[::1")) {
            assertFalse(DesktopUiNode.OnboardingProxySettings.validHost(host), host);
        }
    }

    @Test
    @DisplayName("旧引导进度均恢复为卡片页，手动继续后直接完成")
    void resumesAtHubAndDoesNotAutomaticallyAdvance() throws Exception {
        for (int progress : List.of(3, 4, 5, 6, 7)) {
            AtomicInteger savedProgress = new AtomicInteger(progress);
            AtomicBoolean finished = new AtomicBoolean();
            Map<String, String> config = new HashMap<>(Map.of("proxy.enabled", "false", "proxy.host", "saved-proxy", "proxy.port", "8181"));
            try (var model = DesktopConfigurationControllerTest.model(config, Map.of(
                    "backendSnapshot", args -> running(),
                    "onboardingState", args -> onboarding(savedProgress.get(), finished.get()),
                    "markOnboardingFinished", args -> { finished.set(true); return true; },
                    "saveOnboardingProgress", args -> { savedProgress.set((int) args[0]); return true; },
                    "guiPostJson", args -> new DesktopUiHost.GuiResponse(true, 200, null, "", false),
                    "guiGet", args -> { assertNotEquals("onboarding", args[0]); return DesktopUiHost.GuiResponse.unreachable(); }
            ))) {
                assertEquals(3, hub(model).cards().size());
                assertFalse(hub(model).cards().get(1).open().enabled());
                model.refreshOnboarding();
                model.rebuild();
                assertInstanceOf(DesktopUiNode.OnboardingHub.class, page(model));
                assertEquals("saved-proxy", ((DesktopUiNode.TextInput) descendant(hub(model), "welcome.proxy.host.input")).value());
                activate(model, "welcome.hub.next");
                awaitReady(model);
                assertTrue(finished.get());
                assertFalse(page(model) instanceof DesktopUiNode.OnboardingHub);
                assertTrue(model.snapshot().document().navigationVisible());
                assertEquals("false", config.get("proxy.enabled"));
            }
        }
    }

    @Test
    @DisplayName("继续时校验并保存代理，保存后推进，重载失败明确提示")
    void validatesAndSavesProxyBeforeContinuing() throws Exception {
        for (String outcome : List.of("success", "unreachable", "throw")) {
            CountDownLatch reloading = new CountDownLatch(1);
            CountDownLatch releaseReload = new CountDownLatch(1);
            Map<String, String> config = new HashMap<>(Map.of("proxy.enabled", "true", "proxy.host", "proxy.local", "proxy.port", "8080"));
            AtomicInteger reloads = new AtomicInteger();
            AtomicInteger progress = new AtomicInteger(3);
            AtomicBoolean finished = new AtomicBoolean();
            try (var model = DesktopConfigurationControllerTest.model(config, Map.of(
                    "backendSnapshot", args -> running(),
                    "onboardingState", args -> onboarding(progress.get(), finished.get()),
                    "markOnboardingFinished", args -> { finished.set(true); return true; },
                    "saveOnboardingProgress", args -> { progress.set((int) args[0]); return true; },
                    "markOnboardingProxyConfigured", args -> true,
                    "guiPostJson", args -> {
                        reloads.incrementAndGet();
                        reloading.countDown();
                        assertDoesNotThrow(() -> assertTrue(releaseReload.await(5, TimeUnit.SECONDS)));
                        if (outcome.equals("throw")) throw new IllegalStateException("reload transport failed");
                        return outcome.equals("unreachable") ? DesktopUiHost.GuiResponse.unreachable()
                                : new DesktopUiHost.GuiResponse(true, 200, null, "", false);
                    }
            ))) {
                assertNull(descendant(hub(model), "welcome.proxy.save"));
                change(model, "welcome.proxy.port.input", "70000");
                activate(model, "welcome.hub.next");
                assertEquals(0, reloads.get());
                assertEquals(3, progress.get());
                assertEquals("8080", config.get("proxy.port"));
                assertTrue(hub(model).cards().get(0).settings().invalid());
                int firstAttempt = hub(model).cards().get(0).settings().validationAttempt();
                activate(model, "welcome.hub.next");
                assertTrue(hub(model).cards().get(0).settings().validationAttempt() > firstAttempt);
                change(model, "welcome.proxy.host.input", " ");
                change(model, "welcome.proxy.port.input", "8181");
                activate(model, "welcome.hub.next");
                assertEquals(0, reloads.get());
                assertEquals(" ", hub(model).cards().get(0).settings().host().value());
                change(model, "welcome.proxy.host.input", "127.0.0.");
                activate(model, "welcome.hub.next");
                assertEquals(0, reloads.get());
                assertEquals(3, progress.get());
                assertEquals("proxy.local", config.get("proxy.host"));
                assertTrue(hub(model).cards().get(0).settings().invalid());
                change(model, "welcome.proxy.host.input", "edited.proxy");
                assertEquals("proxy.local", config.get("proxy.host"));
                activate(model, "welcome.hub.next");
                try {
                    assertTrue(reloading.await(5, TimeUnit.SECONDS));
                    assertTrue(hub(model).submitting());
                    assertFalse(hub(model).next().enabled());
                    assertEquals("gui.compose.onboarding.hub.saving", hub(model).next().label().key());
                    activate(model, "welcome.hub.next");
                    assertEquals(1, reloads.get());
                } finally {
                    releaseReload.countDown();
                }
                awaitReady(model);
                assertEquals("edited.proxy", config.get("proxy.host"));
                assertEquals("8181", config.get("proxy.port"));
                assertEquals(1, reloads.get());
                assertTrue(finished.get());
                assertFalse(page(model) instanceof DesktopUiNode.OnboardingHub);
                var dialogs = model.snapshot().document().dialogs();
                if (outcome.equals("success")) assertTrue(dialogs.isEmpty());
                else {
                    assertEquals(1, dialogs.size());
                    assertEquals(DesktopUiDocument.DialogStyle.WARNING, dialogs.get(0).style());
                    var notice = (DesktopUiNode.Text) descendant(
                            dialogs.get(0).content(), "welcome.proxy.reload-failed.message");
                    assertEquals("gui.compose.onboarding.hub.network.reload-failed", notice.text().key());
                    activate(model, "welcome.proxy.reload-failed.close");
                    assertTrue(model.snapshot().document().dialogs().isEmpty());
                }
            }
        }
    }

    @Test
    @DisplayName("保存失败保留草稿并可重试，关闭代理后无效字段不阻止继续")
    void retainsDraftAfterSaveFailureAndAllowsDisablingProxy() throws Exception {
        AtomicInteger writes = new AtomicInteger();
        AtomicInteger progress = new AtomicInteger(3);
        AtomicBoolean finished = new AtomicBoolean();
        Map<String, String> config = new HashMap<>(Map.of("proxy.enabled", "true", "proxy.host", "proxy.local", "proxy.port", "8080")) {
            @Override public void putAll(Map<? extends String, ? extends String> values) {
                if (writes.incrementAndGet() == 1) throw new IllegalStateException("write failed");
                super.putAll(values);
            }
        };
        try (var model = DesktopConfigurationControllerTest.model(config, Map.of(
                "backendSnapshot", args -> running(),
                "onboardingState", args -> onboarding(progress.get(), finished.get()),
                "markOnboardingFinished", args -> { finished.set(true); return true; },
                "saveOnboardingProgress", args -> { progress.set((int) args[0]); return true; },
                "defaultProxyHost", args -> "fallback.proxy",
                "defaultProxyPort", args -> 8080,
                "guiPostJson", args -> new DesktopUiHost.GuiResponse(true, 200, null, "", false)
        ))) {
            change(model, "welcome.proxy.host.input", "edited.proxy");
            activate(model, "welcome.hub.next");
            awaitReady(model);
            assertEquals(3, progress.get());
            assertFalse(finished.get());
            assertEquals("proxy.local", config.get("proxy.host"));
            assertEquals("edited.proxy", hub(model).cards().get(0).settings().host().value());
            assertEquals(DesktopUiNode.TextStyle.ERROR, hub(model).notice().style());
            assertFalse(hub(model).submitting());
            assertEquals("gui.compose.onboarding.hub.finish", hub(model).next().label().key());
            change(model, "welcome.proxy.host.input", "");
            change(model, "welcome.proxy.port.input", "invalid");
            model.dispatch(model.snapshot(), new DesktopUiNode.Event(
                    DesktopUiNode.EventType.CHANGE,
                    "welcome.proxy.enabled.input",
                    DesktopUiNode.Value.bool(false)
            ));
            assertFalse(hub(model).cards().get(0).settings().enabled().selected());
            activate(model, "welcome.hub.next");
            awaitReady(model);
            assertTrue(finished.get());
            assertFalse(page(model) instanceof DesktopUiNode.OnboardingHub);
            assertEquals("false", config.get("proxy.enabled"));
            assertEquals("fallback.proxy", config.get("proxy.host"));
            assertEquals("8080", config.get("proxy.port"));
        }
    }

    @Test
    @DisplayName("打开动作使用活动贡献地址，失败可重试，撤回贡献后移除入口")
    void opensContributedRoutesAndHandlesUnavailableCapabilities() throws Exception {
        var sources = new AtomicReference<>(List.of(source()));
        var opened = new AtomicReference<URI>();
        AtomicInteger attempts = new AtomicInteger();
        try (var model = DesktopConfigurationControllerTest.model(new HashMap<>(), Map.of(
                "backendSnapshot", args -> running(),
                "onboardingState", args -> onboarding(3),
                "openExternalUri", args -> {
                    if (attempts.incrementAndGet() == 1) throw new IllegalStateException("private diagnostic");
                    opened.set((URI) args[0]);
                    return null;
                }
        ), sources::get)) {
            assertEquals(4, hub(model).cards().size());
            activate(model, "welcome.hub.download.open");
            awaitReady(model);
            assertFalse(hub(model).cards().get(1).opened());
            assertEquals(DesktopUiNode.TextStyle.ERROR, hub(model).notice().style());
            activate(model, "welcome.hub.download.open");
            awaitReady(model);
            assertEquals("/sample-download.html", opened.get().getPath());
            assertTrue(hub(model).cards().get(1).opened());
            activate(model, "welcome.hub.guide.sample.open");
            awaitReady(model);
            assertEquals("/sample-guide.html", opened.get().getPath());
            assertTrue(hub(model).cards().get(2).opened());
            model.refreshOnboarding();
            model.rebuild();
            assertInstanceOf(DesktopUiNode.OnboardingHub.class, page(model));
            sources.set(List.of());
            model.rebuild();
            assertEquals(3, hub(model).cards().size());
            assertFalse(hub(model).cards().get(1).open().enabled());
        }
    }

    @Test
    @DisplayName("动图卡片显示宿主检测结果，安装说明可打开且不会完成引导")
    void animationCardUsesFfmpegStatusAndOpensGuide() throws Exception {
        for (boolean installed : List.of(false, true)) {
            var opened = new AtomicReference<URI>();
            Thread inputThread = Thread.currentThread();
            try (var model = DesktopConfigurationControllerTest.model(new HashMap<>(), Map.of(
                    "backendSnapshot", args -> running(),
                    "onboardingState", args -> onboarding(3),
                    "locateFfmpeg", args -> {
                        assertNotSame(inputThread, Thread.currentThread(), "FFmpeg lookup must not block input");
                        return installed ? Optional.of(new DesktopUiHost.FfmpegInstallation(
                                Path.of("ffmpeg"), Path.of("ffprobe"), Path.of("."), DesktopUiHost.FfmpegSource.CUSTOM
                        )) : Optional.empty();
                    },
                    "openExternalUri", args -> { opened.set((URI) args[0]); return null; },
                    "markOnboardingFinished", args -> { fail("opening documentation must not complete onboarding"); return false; }
            ))) {
                awaitReady(model);
                change(model, "welcome.proxy.host.input", "edited.proxy");
                var card = hub(model).cards().stream().filter(c -> c.topic() == DesktopUiNode.OnboardingTopic.ANIMATION)
                        .findFirst().orElseThrow();
                assertTrue(card.open().enabled());
                assertEquals("gui.compose.onboarding.hub.animation." + (installed ? "ready" : "body"), card.description().key());
                activate(model, card.open().id());
                awaitReady(model);
                assertEquals("https", opened.get().getScheme());
                assertEquals("sywyar.github.io", opened.get().getHost());
                assertTrue(opened.get().getFragment().contains("/installation?id="));
                assertInstanceOf(DesktopUiNode.OnboardingHub.class, page(model));
            }
        }
    }

    private static DesktopUiHost.BackendSnapshot running() {
        return new DesktopUiHost.BackendSnapshot(DesktopUiHost.BackendState.RUNNING, null);
    }

    private static DesktopUiHost.OnboardingSnapshot onboarding(int progress) {
        return onboarding(progress, false);
    }

    private static DesktopUiHost.OnboardingSnapshot onboarding(int progress, boolean finished) {
        return new DesktopUiHost.OnboardingSnapshot(finished, true, progress, finished, true);
    }

    private static DesktopUiPluginSnapshot source() {
        return new DesktopUiPluginSnapshot("sample", false, "sample", 1, false, null, "",
                List.of(), List.of(),
                List.of(new GuiOnboardingStepContribution("sample", "sample", "guide.title", "guide.body",
                        List.of("guide.point"), "guide.open", "/sample-guide.html", "guide.waiting", "sample", 0)),
                List.of(WebRouteContribution.admin("/sample-download.html"), WebRouteContribution.admin("/sample-guide.html")),
                List.of(new NavigationContribution("sample.download", Set.of(NavigationPlacements.DESKTOP_QUICK_START),
                        "sample", "download", "/sample-download.html", "download", AccessPolicy.ADMIN, 0)));
    }

    private static DesktopUiNode page(ComposeDesktopUiModel model) {
        return ((DesktopUiNode.Surface) model.snapshot().document().pages().get(0).content()).content();
    }

    private static DesktopUiNode.OnboardingHub hub(ComposeDesktopUiModel model) {
        return assertInstanceOf(DesktopUiNode.OnboardingHub.class, page(model));
    }

    private static DesktopUiNode descendant(DesktopUiNode node, String id) {
        if (node.id().equals(id)) return node;
        for (var child : node.childNodes()) {
            var found = descendant(child, id);
            if (found != null) return found;
        }
        return null;
    }

    private static void activate(ComposeDesktopUiModel model, String id) {
        synchronized (model) {
            model.dispatch(model.snapshot(), new DesktopUiNode.Event(DesktopUiNode.EventType.ACTIVATE, id, DesktopUiNode.Value.empty()));
        }
    }

    private static void change(ComposeDesktopUiModel model, String id, String value) {
        synchronized (model) {
            model.dispatch(model.snapshot(), new DesktopUiNode.Event(DesktopUiNode.EventType.CHANGE, id, DesktopUiNode.Value.text(value)));
        }
    }

    private static void awaitReady(ComposeDesktopUiModel model) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while ((model.busy() || page(model) instanceof DesktopUiNode.OnboardingHub hub && !hub.next().enabled()) && System.nanoTime() < deadline) Thread.sleep(10);
        assertFalse(model.busy());
        if (page(model) instanceof DesktopUiNode.OnboardingHub hub) assertTrue(hub.next().enabled());
    }
}
