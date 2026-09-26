package top.sywyar.pixivdownload.guicompose.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode.*;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiHost;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Compose 安全动作与敏感输入")
class DesktopSecurityControllerTest {
    @Test
    @DisplayName("密码保留在失败表单中，成功或取消后清除，快照不包含密码")
    void passwordLifecycle() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        AtomicReference<Map<?, ?>> payload = new AtomicReference<>();
        AtomicReference<DesktopUiHost.GuiResponse> response = new AtomicReference<>(reply(401, Map.of("error", "invalid-current")));
        try (var model = DesktopConfigurationControllerTest.model(new HashMap<>(), Map.of(
                "minimumPasswordLength", args -> 8,
                "guiPostJson", args -> { requests.incrementAndGet(); payload.set((Map<?, ?>) args[1]); return response.get(); }))) {
            activate(model, "password");
            change(model, "current", "Old-secret-1234");
            change(model, "new", "New-secret-5678");
            change(model, "confirm", "New-secret-5678");
            assertFalse(model.snapshot().toString().contains("secret"));
            activate(model, "submit");
            await(model);
            assertEquals("current", security(model).errorField());
            assertEquals("password", security(model).panel());
            response.set(reply(200, Map.of("success", true)));
            activate(model, "submit");
            await(model);
            assertEquals("Old-secret-1234", payload.get().get("oldPassword"));
            assertEquals("", security(model).panel());
            assertEquals(1, security(model).successRevision());
            activate(model, "password");
            activate(model, "submit");
            assertEquals(2, requests.get());
            assertEquals("current", security(model).errorField());
            change(model, "current", "Temporary-secret");
            activate(model, "close");
            activate(model, "password");
            activate(model, "submit");
            assertEquals("current", security(model).errorField());
            assertEquals(2, requests.get());
        }
    }

    @Test
    @DisplayName("会话注销失败和无效成功响应不误报成功，邀请打开真实页面")
    void sessionsAndInvites() throws Exception {
        AtomicReference<String> endpoint = new AtomicReference<>();
        AtomicReference<Object> uri = new AtomicReference<>();
        AtomicReference<DesktopUiHost.GuiResponse> response = new AtomicReference<>(reply(200, Map.of()));
        try (var model = DesktopConfigurationControllerTest.model(new HashMap<>(), Map.of(
                "guiPostJson", args -> { endpoint.set((String) args[0]); return response.get(); },
                "openExternalUri", args -> { uri.set(args[0]); return null; }))) {
            activate(model, "sessions");
            activate(model, "logout");
            await(model);
            assertEquals("logout-all", endpoint.get());
            assertEquals("sessions", security(model).panel());
            assertEquals(0, security(model).successRevision());
            response.set(reply(500, Map.of("code", "save-failed", "error", "localized message")));
            activate(model, "logout");
            await(model);
            assertEquals("gui.compose.security.save-failed", security(model).notice().key());
            response.set(reply(200, Map.of("success", true)));
            activate(model, "logout");
            await(model);
            assertEquals(1, security(model).successRevision());
            activate(model, "invites");
            await(model);
            assertEquals("/pixiv-invite-manage.html", ((java.net.URI) uri.get()).getPath());
        }
    }

    @Test
    @DisplayName("连接只保存自身字段，拒绝残缺地址和缺少证书的 HTTPS")
    void connectionValidationAndScopedSave() throws Exception {
        var stored = new HashMap<>(Map.of("server.port", "8080", "ssl.domain", "localhost", "unrelated.setting", "keep"));
        try (var model = DesktopConfigurationControllerTest.model(stored, Map.of("validateCoreConfigValue", args -> null))) {
            activate(model, "connection");
            await(model);
            change(model, "domain", "127.0.0.");
            activate(model, "save");
            assertEquals("domain", security(model).errorField());
            assertEquals("localhost", stored.get("ssl.domain"));
            change(model, "domain", "example.test");
            model.dispatch(model.snapshot(), new Event(EventType.CHANGE, "security.https", Value.bool(true)));
            activate(model, "save");
            assertEquals("certificate", security(model).errorField());
            change(model, "certificate", "config/server.crt");
            change(model, "private-key", "config/server.key");
            activate(model, "save");
            await(model);
            assertEquals("true", stored.get("server.ssl.enabled"));
            assertEquals("example.test", stored.get("ssl.domain"));
            assertEquals("keep", stored.get("unrelated.setting"));
            assertEquals("connection", security(model).successOperation());
        }
    }

    @Test
    @DisplayName("密码长度和重复检查、HTTPS 证书组合保持明确")
    void localValidation() {
        assertEquals("invalid-length", SecurityInputValidation.passwordErrors("old-long", "short", "short", 8).get("new"));
        assertEquals("same-password", SecurityInputValidation.passwordErrors("same-long", "same-long", "same-long", 8).get("new"));
        assertFalse(SecurityInputValidation.passwordErrors("        ", "new-long", "new-long", 8).containsKey("current"));
        var fields = Map.of("https", "true", "domain", "localhost", "port", "8080");
        assertTrue(SecurityInputValidation.connectionErrors(fields, true).isEmpty());
        assertFalse(SecurityInputValidation.connectionErrors(fields, false).isEmpty());
        assertFalse(SecurityInputValidation.connectionErrors(Map.of("https", "true", "domain", "localhost",
                "port", "8080", "certificate", "only.pem"), true).isEmpty());
    }

    @Test
    @DisplayName("浏览器打开失败在安全页可见，连接缺省端口来自宿主")
    void browserFailureAndHostPort() throws Exception {
        try (var model = DesktopConfigurationControllerTest.model(new HashMap<>(), Map.of(
                "openExternalUri", args -> { throw new IllegalStateException("unavailable"); }))) {
            activate(model, "invites");
            await(model);
            assertEquals("gui.compose.security.browser-failed", security(model).notice().key());
            activate(model, "connection");
            await(model);
            assertEquals("8080", security(model).inputs().stream().filter(it -> it.bindingId().equals("security.port"))
                    .findFirst().orElseThrow().value());
        }
    }

    private static DesktopUiHost.GuiResponse reply(int status, Map<String, Object> body) {
        return new DesktopUiHost.GuiResponse(true, status, DesktopUiHost.GuiValue.of(body), "", false);
    }
    private static void activate(ComposeDesktopUiModel model, String action) {
        synchronized (model) {
            model.dispatch(model.snapshot(), new Event(EventType.ACTIVATE, "security." + action, Value.empty()));
        }
    }
    private static void change(ComposeDesktopUiModel model, String field, String value) {
        synchronized (model) {
            model.dispatch(model.snapshot(), new Event(EventType.CHANGE, "security." + field + ".input", Value.text(value)));
        }
    }
    private static SecurityOverview security(ComposeDesktopUiModel model) {
        return (SecurityOverview) ((Surface) model.snapshot().document().pages().stream()
                .filter(page -> page.id().equals("security")).findFirst().orElseThrow().content()).content();
    }
    private static void await(ComposeDesktopUiModel model) {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            while (model.busy()) Thread.sleep(10);
            model.rebuild();
        });
    }
}
