package top.sywyar.pixivdownload.guicompose.model;

import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiHost;
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode;
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode.*;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 管理安全表单与受控本地动作，密码不投影到页面快照。 */
final class DesktopSecurityController {
    private static final Map<String, String> CONNECTION_KEYS = Map.of(
            "https", "server.ssl.enabled", "domain", "ssl.domain", "port", "server.port",
            "certificate", "server.ssl.certificate", "private-key", "server.ssl.certificate-private-key");
    private final ComposeDesktopUiModel owner;
    private final DesktopUiHost host;
    private final int serverPort;
    private final Map<String, String> formValues;
    private volatile String panel = "";
    private volatile TextToken notice;
    private volatile String errorField = "";
    private volatile long formRevision;
    private volatile long successRevision;
    private volatile String successOperation = "";
    private volatile String runningAddress = "";
    private volatile boolean connectionLoaded;
    private volatile boolean keyStoreConfigured;

    DesktopSecurityController(ComposeDesktopUiModel owner, DesktopUiHost host, Map<String, String> formValues, int serverPort) {
        this.owner = owner;
        this.host = host;
        this.formValues = formValues;
        this.serverPort = serverPort;
    }

    DesktopUiNode page(Map<String, Runnable> nextActions) {
        List<Button> actions = new ArrayList<>();
        actions.add(action("password", nextActions, () -> open("password")));
        actions.add(action("sessions", nextActions, () -> open("sessions")));
        actions.add(action("connection", nextActions, this::loadConnection));
        actions.add(action("invites", nextActions, () -> {
            inputChanged();
            owner.runBusy(() -> {
                try { host.openExternalUri(owner.webUri("/pixiv-invite-manage.html")); }
                catch (Exception failure) { notice = label("browser-failed"); }
            });
        }));
        actions.add(action("close", nextActions, this::close));
        actions.add(action("submit", nextActions, this::changePassword));
        actions.add(action("logout", nextActions, this::logout));
        actions.add(action("save", nextActions, this::saveConnection));
        List<TextInput> inputs = new ArrayList<>();
        for (String field : List.of("current", "new", "confirm", "domain", "port", "certificate", "private-key")) {
            boolean secret = List.of("current", "new", "confirm").contains(field);
            inputs.add(new TextInput("security." + field + ".input", "security." + field,
                    label(field), null, secret ? InputKind.PASSWORD : InputKind.TEXT,
                    secret ? "" : value(field), 24, 1, !owner.busy(), formRevision));
        }
        return new SecurityOverview("security.overview", panel, owner.busy(), host.minimumPasswordLength(),
                inputs, new Toggle("security.https", "security.https", label("https"), null,
                ToggleStyle.SWITCH, Boolean.parseBoolean(value("https")), !owner.busy()),
                actions, notice, errorField, formRevision, successRevision, successOperation,
                runningAddress, keyStoreConfigured);
    }

    private Button action(String name, Map<String, Runnable> actions, Runnable callback) {
        String id = "security." + name;
        actions.put(id, callback);
        boolean allowed = !owner.busy() && switch (name) {
            case "submit" -> panel.equals("password");
            case "logout" -> panel.equals("sessions");
            case "save" -> panel.equals("connection") && connectionLoaded;
            default -> true;
        };
        String labelKey = switch (name) {
            case "password", "sessions", "connection", "invites" -> name + ".action";
            case "submit" -> "password.action";
            case "logout" -> "sessions.action";
            default -> name;
        };
        return new Button(id, id, label(labelKey), null, ButtonStyle.NORMAL, allowed);
    }

    void inputChanged() {
        notice = null;
        errorField = "";
    }

    private void open(String next) {
        if (owner.busy()) return;
        clearSecrets();
        inputChanged();
        panel = next;
        owner.rebuild();
    }

    private void close() {
        if (owner.busy()) return;
        clearSecrets();
        inputChanged();
        panel = "";
        owner.rebuild();
    }

    void clearSecrets() {
        for (String name : List.of("current", "new", "confirm")) formValues.remove("security." + name);
        formRevision++;
    }

    private void changePassword() {
        String current = value("current"), next = value("new"), confirm = value("confirm");
        var errors = SecurityInputValidation.passwordErrors(current, next, confirm, host.minimumPasswordLength());
        if (!errors.isEmpty()) {
            var error = errors.entrySet().iterator().next();
            errorField = error.getKey();
            notice = label(error.getValue());
            owner.rebuild();
            return;
        }
        request("change-password", Map.of("oldPassword", current, "newPassword", next), "password");
    }

    private void logout() {
        request("logout-all", Map.of(), "sessions");
    }

    private void request(String endpoint, Map<String, String> body, String operation) {
        inputChanged();
        owner.runBusy(() -> {
            try {
                var response = host.guiPostJson(endpoint, body, 5_000);
                if (response.reachable() && response.is2xx() && !response.bodyLimitExceeded()
                        && response.body() != null && response.body().path("success").asBoolean(false)) {
                    succeeded(operation);
                    return;
                }
                String code = response.body() == null ? "" : response.body()
                        .path(endpoint.equals("logout-all") ? "code" : "error").asText("");
                String key = switch (code) {
                    case "invalid-current" -> "invalid-current";
                    case "weak-password" -> "invalid-length";
                    case "same-password" -> "same-password";
                    case "setup-incomplete" -> "setup-incomplete";
                    case "save-failed" -> "save-failed";
                    default -> response.reachable() ? "request-failed" : "offline";
                };
                errorField = code.equals("invalid-current") ? "current"
                        : List.of("weak-password", "same-password").contains(code) ? "new" : "";
                notice = label(key);
            } catch (RuntimeException failure) {
                notice = label("request-failed");
            }
        });
    }

    private void succeeded(String operation) {
        clearSecrets();
        inputChanged();
        panel = "";
        successOperation = operation;
        successRevision++;
    }

    private void loadConnection() {
        open("connection");
        connectionLoaded = false;
        owner.runBusy(() -> {
            try {
                List<String> keys = new ArrayList<>(CONNECTION_KEYS.values());
                keys.add("server.ssl.key-store");
                var saved = host.applicationConfig().readAll(keys);
                CONNECTION_KEYS.forEach((field, key) -> formValues.put("security." + field,
                        saved.getOrDefault(key, field.equals("domain") ? "localhost" : field.equals("port") ? Integer.toString(serverPort) : "")));
                keyStoreConfigured = !saved.getOrDefault("server.ssl.key-store", "").isBlank();
                runningAddress = "";
                var response = host.guiGet("status", 5_000);
                if (response.reachable() && response.is2xx() && response.body() != null) {
                    var status = response.body();
                    String scheme = status.path("httpsEnabled").asBoolean(false) ? "https" : "http";
                    String domain = status.path("domain").asText("");
                    int port = status.path("port").asInt(0);
                    if (OnboardingProxySettings.validHost(domain) && port > 0 && port <= 65535)
                        runningAddress = new URI(scheme, null, domain, port, null, null, null).toASCIIString();
                }
                connectionLoaded = true;
                formRevision++;
            } catch (Exception failure) {
                notice = label("load-failed");
            }
        });
    }

    private void saveConnection() {
        if (!connectionLoaded) return;
        Map<String, String> fields = new LinkedHashMap<>();
        CONNECTION_KEYS.forEach((field, key) -> fields.put(field, value(field).trim()));
        var errors = SecurityInputValidation.connectionErrors(fields, keyStoreConfigured);
        if (!errors.isEmpty()) {
            var error = errors.entrySet().iterator().next();
            errorField = error.getKey();
            notice = label(error.getValue());
            owner.rebuild();
            return;
        }
        inputChanged();
        owner.runBusy(() -> {
            try {
                Map<String, String> saved = new LinkedHashMap<>();
                for (var entry : CONNECTION_KEYS.entrySet()) {
                    String key = host.requireSafeConfigKey(entry.getValue());
                    String value = host.requireSafeConfigValue(fields.get(entry.getKey()));
                    host.validateCoreConfigValue(key, value);
                    saved.put(key, value);
                }
                var file = host.applicationConfig();
                var before = file.snapshot();
                try {
                    file.writeAll(saved);
                } catch (Exception failure) {
                    try { file.restore(before); } catch (Exception rollback) { failure.addSuppressed(rollback); }
                    throw failure;
                }
                owner.securityConfigSaved(saved);
                succeeded("connection");
            } catch (Exception failure) {
                notice = label("save-failed");
            }
        });
    }

    private String value(String name) { return formValues.getOrDefault("security." + name, ""); }

    private static TextToken label(String key) {
        return new TextToken("gui-compose", "gui.compose.security." + key, "", List.of());
    }
}
