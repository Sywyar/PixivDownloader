package top.sywyar.pixivdownload.guicompose.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiHost;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiPluginSnapshot;
import top.sywyar.pixivdownload.plugin.api.gui.GuiOnboardingStepContribution;
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Compose 首次引导")
class DesktopOnboardingControllerTest {
    @Test
    @DisplayName("渐进表单保留最短密码和弱密码二次确认，失败后可重试且密码不进入快照")
    void validatesCredentialsAndRecoversFromSubmissionFailure() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        AtomicBoolean configured = new AtomicBoolean();
        CountDownLatch firstRequest = new CountDownLatch(1);
        CountDownLatch releaseRequest = new CountDownLatch(1);
        try (ComposeDesktopUiModel model = DesktopConfigurationControllerTest.model(new HashMap<>(), Map.of(
                "backendSnapshot", args -> new DesktopUiHost.BackendSnapshot(DesktopUiHost.BackendState.RUNNING, null),
                "onboardingState", args -> new DesktopUiHost.OnboardingSnapshot(configured.get(), false, 2, false, false),
                "minimumPasswordLength", args -> 8,
                "recommendedPasswordLength", args -> 12,
                "guiPostJson", args -> {
                    assertEquals("setup/init", args[0]);
                    if (requests.incrementAndGet() == 1) {
                        firstRequest.countDown();
                        try {
                            assertTrue(releaseRequest.await(5, TimeUnit.SECONDS));
                        } catch (InterruptedException interrupted) {
                            throw new AssertionError(interrupted);
                        }
                        throw new IllegalStateException("private transport diagnostic");
                    }
                    configured.set(true);
                    return new DesktopUiHost.GuiResponse(true, 200, null, "", false);
                }
        ))) {
            submitAccount(model);
            assertEquals("gui.welcome.config.invalid.username", account(model).notice().text().fallback());
            changeAccount(model, "welcome.username.input", "Admin");
            changeAccount(model, "welcome.password.input", "short");
            submitAccount(model);
            assertEquals(0, requests.get());
            assertEquals("gui.welcome.config.invalid.password", account(model).notice().text().fallback());

            changeAccount(model, "welcome.password.input", "testpass");
            submitAccount(model);
            assertTrue(account(model).confirmWeakPassword());
            assertEquals(0, requests.get());
            changeAccount(model, "welcome.username.input", "Administrator");
            assertFalse(account(model).confirmWeakPassword());
            submitAccount(model);
            assertTrue(account(model).confirmWeakPassword());
            submitAccount(model);
            assertTrue(firstRequest.await(5, TimeUnit.SECONDS));
            assertTrue(account(model).submitting());
            submitAccount(model);
            assertEquals(1, requests.get());
            assertEquals("", account(model).password().value());
            releaseRequest.countDown();
            awaitNotBusy(model);
            assertEquals("gui.welcome.config.failed", account(model).notice().text().fallback());
            assertEquals("Administrator", account(model).username().value());
            submitAccount(model);
            assertTrue(account(model).confirmWeakPassword());
            submitAccount(model);
            awaitNotBusy(model);
            assertEquals(2, requests.get());
            assertTrue(configured.get());
            assertFalse(model.snapshot().document().navigationVisible());
            var page = assertInstanceOf(DesktopUiNode.Surface.class, model.snapshot().document().pages().get(0).content());
            assertEquals("welcome.proxy.layout", page.content().id());
        } finally {
            releaseRequest.countDown();
        }
    }

    private static DesktopUiNode.AccountSetup account(ComposeDesktopUiModel model) {
        var surface = assertInstanceOf(DesktopUiNode.Surface.class, model.snapshot().document().pages().get(0).content());
        return assertInstanceOf(DesktopUiNode.AccountSetup.class, surface.content());
    }

    private static void changeAccount(ComposeDesktopUiModel model, String id, String value) {
        synchronized (model) {
            model.dispatch(model.snapshot(), new DesktopUiNode.Event(
                    DesktopUiNode.EventType.CHANGE, id, DesktopUiNode.Value.text(value)
            ));
        }
    }

    private static void submitAccount(ComposeDesktopUiModel model) {
        synchronized (model) {
            model.dispatch(model.snapshot(), new DesktopUiNode.Event(
                    DesktopUiNode.EventType.ACTIVATE, "welcome.config.submit", DesktopUiNode.Value.empty()
            ));
        }
    }

    private static void awaitNotBusy(ComposeDesktopUiModel model) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (accountBusy(model) && System.nanoTime() < deadline) Thread.sleep(10);
        assertFalse(accountBusy(model));
    }

    private static boolean accountBusy(ComposeDesktopUiModel model) {
        var surface = (DesktopUiNode.Surface) model.snapshot().document().pages().get(0).content();
        return model.busy() || surface.content() instanceof DesktopUiNode.AccountSetup setup && setup.submitting();
    }

    @Test
    @DisplayName("引导首屏只显示居中等待提示，完成后恢复导航")
    void hidesNavigationUntilOnboardingCompletes() throws Exception {
        AtomicBoolean complete = new AtomicBoolean();
        try (ComposeDesktopUiModel model = DesktopConfigurationControllerTest.model(new HashMap<>(), Map.of(
                "onboardingState", args -> new DesktopUiHost.OnboardingSnapshot(
                        complete.get(), complete.get(), 0, complete.get(), complete.get()
                )
        ))) {
            assertFalse(model.snapshot().document().navigationVisible());
            var content = serviceContent(model);
            assertEquals(DesktopUiNode.Alignment.CENTER, content.alignment());
            assertEquals(3, content.children().size());
            var message = assertInstanceOf(DesktopUiNode.Text.class, content.children().get(0));
            assertEquals(DesktopUiNode.TextStyle.WAITING, message.style());
            assertEquals("gui-compose", message.text().namespace());
            assertEquals("gui.compose.onboarding.preparing", message.text().key());
            var status = assertInstanceOf(DesktopUiNode.Text.class, content.children().get(1));
            assertEquals("gui.compose.onboarding.service-preparing", status.text().key());
            var progress = assertInstanceOf(DesktopUiNode.Progress.class, content.children().get(2));
            assertTrue(progress.indeterminate());
            assertEquals(DesktopUiNode.ProgressStyle.COMPACT_LINEAR, progress.progressStyle());

            complete.set(true);
            model.rebuild();
            assertTrue(model.snapshot().document().navigationVisible());
        }
    }

    @Test
    @DisplayName("启动失败后停止等待动画并显示错误提示")
    @SuppressWarnings("unchecked")
    void replacesWaitingIndicatorWhenServiceFails() throws Exception {
        AtomicReference<Consumer<DesktopUiHost.BackendSnapshot>> listener = new AtomicReference<>();
        try (ComposeDesktopUiModel model = DesktopConfigurationControllerTest.model(new HashMap<>(), Map.of(
                "onboardingState", args -> new DesktopUiHost.OnboardingSnapshot(false, false, 0, false, false),
                "subscribeBackend", args -> {
                    listener.set((Consumer<DesktopUiHost.BackendSnapshot>) args[0]);
                    return (AutoCloseable) () -> {};
                }
        ))) {
            listener.get().accept(new DesktopUiHost.BackendSnapshot(
                    DesktopUiHost.BackendState.FAILED,
                    new IllegalStateException("private diagnostic")
            ));

            assertFalse(model.snapshot().document().navigationVisible());
            var content = serviceContent(model);
            assertEquals(1, content.children().size());
            var message = assertInstanceOf(DesktopUiNode.Text.class, content.children().get(0));
            assertEquals(DesktopUiNode.TextStyle.ERROR, message.style());
            assertEquals("gui-compose", message.text().namespace());
            assertEquals("gui.compose.onboarding.failed", message.text().key());
            assertEquals("", message.text().fallback());
        }
    }

    private static DesktopUiNode.Container serviceContent(ComposeDesktopUiModel model) {
        var page = model.snapshot().document().pages().get(0);
        var surface = assertInstanceOf(DesktopUiNode.Surface.class, page.content());
        var centered = assertInstanceOf(DesktopUiNode.Container.class, surface.content());
        return assertInstanceOf(DesktopUiNode.Container.class, centered.children().get(0));
    }

    @Test
    @DisplayName("界面显示的代理默认值同时写入动作读取的表单状态")
    void storesVisibleProxyDefaultsInFormState() {
        Map<String, String> values = new HashMap<>();

        DesktopOnboardingController.initializeProxyDefaults(values, "127.0.0.1", 7890);

        assertEquals("true", values.get("welcome.proxy.enabled"));
        assertEquals("127.0.0.1", values.get("welcome.proxy.host"));
        assertEquals("7890", values.get("welcome.proxy.port"));

        values.put("welcome.proxy.host", "proxy.example");
        DesktopOnboardingController.initializeProxyDefaults(values, "localhost", 8080);
        assertEquals("proxy.example", values.get("welcome.proxy.host"));
        assertEquals("7890", values.get("welcome.proxy.port"));
    }

    @Test
    @DisplayName("插件引导按贡献顺序选择且不依赖插件 ID")
    void selectsFirstGenericPluginContribution() {
        GuiOnboardingStepContribution later = guide("later", 20);
        GuiOnboardingStepContribution earlier = guide("earlier", 10);

        GuiOnboardingStepContribution selected = DesktopOnboardingController.firstGuideStep(List.of(
                source("plugin-a", later),
                source("plugin-b", earlier)
        ));

        assertSame(earlier, selected);
    }

    private static GuiOnboardingStepContribution guide(String id, int order) {
        return new GuiOnboardingStepContribution(
                id,
                "sample",
                "guide.title",
                "guide.body",
                List.of("guide.point"),
                "guide.open",
                "/guide.html",
                "guide.waiting",
                id,
                order
        );
    }

    private static DesktopUiPluginSnapshot source(
            String id,
            GuiOnboardingStepContribution guide
    ) {
        return new DesktopUiPluginSnapshot(
                id,
                false,
                id,
                1,
                false,
                null,
                "",
                List.of(),
                List.of(),
                List.of(guide),
                List.of(),
                List.of()
        );
    }
}
