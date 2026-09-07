package top.sywyar.pixivdownload.guicompose.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiHost;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiPluginSnapshot;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

@DisplayName("Compose 配置未保存状态")
class DesktopConfigurationControllerTest {
    @Test
    @DisplayName("首次加载缺项或默认配置时没有未保存更改")
    void startsCleanWithMissingOrDefaultPreferences() throws Exception {
        for (Map<String, String> stored : List.of(
                Map.<String, String>of(),
                Map.of(
                        "app.language", "",
                        "app.gui-provider", "",
                        "app.theme", "system",
                        "app.config-menu-expand-all", "false"
                ),
                Map.of(
                        "app.language", "en-US",
                        "app.gui-provider", "compose",
                        "app.theme", "dark",
                        "app.config-menu-expand-all", "true"
                ),
                Map.of(
                        "app.language", "unknown",
                        "app.gui-provider", "unavailable",
                        "app.theme", "unavailable",
                        "app.config-menu-expand-all", ""
                )
        )) {
            Map<String, String> config = new HashMap<>(stored);
            try (ComposeDesktopUiModel model = model(config)) {
                assertEquals(0, pendingCount(model), stored.toString());
                assertFalse(nodes(model).anyMatch(node -> node.id().equals("settings.impact.effect")));
                save(model);
                assertEquals(stored, config);
                assertEquals("gui.config.notice.saved-no-change", text(model, "config.notice").fallback());
            }
        }
    }

    @Test
    @DisplayName("界面偏好的编辑与还原准确计数且保存后归零")
    void tracksEditsRevertsAndSave() throws Exception {
        Locale originalLocale = Locale.getDefault();
        Map<String, String> stored = new HashMap<>();
        try (ComposeDesktopUiModel model = model(stored)) {
            select(model, "language", "en-US");
            assertEquals(1, pendingCount(model));
            select(model, "provider", "alternate");
            assertEquals(2, pendingCount(model));
            assertEquals("gui.label.process-restart-required", text(model, "settings.impact.effect").key());
            select(model, "theme", "dark");
            toggleExpandAll(model, true);
            assertEquals(4, pendingCount(model));
            model.loadConfiguration();
            model.rebuild();
            assertEquals(4, pendingCount(model));

            select(model, "language", "follow-system");
            select(model, "provider", "compose");
            select(model, "theme", "system");
            toggleExpandAll(model, false);
            assertEquals(0, pendingCount(model));
            save(model);
            assertEquals(Map.of(), stored);

            select(model, "theme", "dark");
            assertEquals(1, pendingCount(model));
            assertEquals("gui.label.hot-reload", text(model, "settings.impact.effect").key());
            save(model);
            assertEquals("dark", stored.get("app.theme"));
            assertEquals(0, pendingCount(model));
            model.loadConfiguration();
            model.rebuild();
            assertEquals(0, pendingCount(model));
        } finally {
            Locale.setDefault(originalLocale);
        }
        try (ComposeDesktopUiModel model = model(stored)) {
            assertEquals(0, pendingCount(model));
        }
    }

    @Test
    @DisplayName("重新加载已删除的界面偏好时使用默认基线")
    void reloadsDefaultsAfterStoredPreferencesAreRemoved() throws Exception {
        Map<String, String> stored = new HashMap<>(Map.of("app.theme", "dark"));
        try (ComposeDesktopUiModel model = model(stored)) {
            stored.clear();
            model.loadConfiguration();
            model.rebuild();
            assertEquals(0, pendingCount(model));
            DesktopUiNode.Choice theme = (DesktopUiNode.Choice) nodes(model)
                    .filter(node -> node.id().equals("interface.theme.input"))
                    .findFirst().orElseThrow();
            assertEquals(List.of("system"), theme.selectedIds());
        }
    }

    private static void select(ComposeDesktopUiModel model, String preference, String value) {
        model.dispatch(model.snapshot(), new DesktopUiNode.Event(
                DesktopUiNode.EventType.SELECTION,
                "interface." + preference + ".input",
                DesktopUiNode.Value.selection(value)
        ));
    }

    private static void toggleExpandAll(ComposeDesktopUiModel model, boolean value) {
        model.dispatch(model.snapshot(), new DesktopUiNode.Event(
                DesktopUiNode.EventType.CHANGE,
                "interface.config-menu-expand-all.input",
                DesktopUiNode.Value.bool(value)
        ));
    }

    private static void save(ComposeDesktopUiModel model) {
        awaitReady(model);
        model.dispatch(model.snapshot(), new DesktopUiNode.Event(
                DesktopUiNode.EventType.ACTIVATE, "config.save", DesktopUiNode.Value.empty()
        ));
        awaitReady(model);
    }

    private static void awaitReady(ComposeDesktopUiModel model) {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            while (nodes(model).filter(node -> node.id().equals("config.save"))
                    .map(DesktopUiNode.Button.class::cast).anyMatch(button -> !button.enabled())) {
                Thread.sleep(10);
            }
        });
    }

    private static int pendingCount(ComposeDesktopUiModel model) {
        return Integer.parseInt(text(model, "settings.unsaved-count").arguments().get(0));
    }

    private static DesktopUiNode.TextToken text(ComposeDesktopUiModel model, String id) {
        DesktopUiNode.Text text = (DesktopUiNode.Text) nodes(model)
                .filter(node -> node.id().equals(id))
                .findFirst().orElseThrow();
        return text.text();
    }

    private static Stream<DesktopUiNode> nodes(ComposeDesktopUiModel model) {
        return model.snapshot().document().pages().stream()
                .filter(page -> page.id().equals("settings"))
                .flatMap(page -> descendants(page.content()));
    }

    private static Stream<DesktopUiNode> descendants(DesktopUiNode node) {
        return Stream.concat(Stream.of(node), node.childNodes().stream().flatMap(DesktopConfigurationControllerTest::descendants));
    }

    private static ComposeDesktopUiModel model(Map<String, String> stored) {
        DesktopUiHost.ConfigFile config = new DesktopUiHost.ConfigFile() {
            @Override
            public Map<String, String> readAll(Collection<String> keys) {
                Map<String, String> result = new HashMap<>(stored);
                result.keySet().retainAll(keys);
                return result;
            }

            @Override
            public void writeAll(Map<String, String> values) { stored.putAll(values); }

            @Override
            public void removeAll(Collection<String> keys) { keys.forEach(stored::remove); }

            @Override
            public DesktopUiHost.ConfigSnapshot snapshot() {
                return new DesktopUiHost.ConfigSnapshot(false, List.of());
            }

            @Override
            public void restore(DesktopUiHost.ConfigSnapshot snapshot) {
                throw new AssertionError("unexpected rollback");
            }
        };
        DesktopUiHost host = (DesktopUiHost) Proxy.newProxyInstance(
                DesktopUiHost.class.getClassLoader(),
                new Class<?>[]{DesktopUiHost.class},
                (proxy, method, arguments) -> {
                    switch (method.getName()) {
                        case "applicationName": return "PixivDownloader";
                        case "applicationConfig": return config;
                        case "resolveDatabasePath": return Path.of("data", "test.db");
                        case "defaultBackfillOptions": return new DesktopUiHost.BackfillOptions(
                                "data/test.db", "localhost", 8080, false, 1000, 0, false
                        );
                        case "loadImageClassifierSettings": return new DesktopUiHost.ImageClassifierSettings(
                                "", false, "http://localhost:6999", List.of()
                        );
                        case "backendSnapshot": return new DesktopUiHost.BackendSnapshot(DesktopUiHost.BackendState.STOPPED, null);
                        case "maintenanceSnapshot": return new DesktopUiHost.MaintenanceSnapshot(false, "", 0, 0, "", 0, 0, 0);
                        case "onboardingState": return new DesktopUiHost.OnboardingSnapshot(true, true, 0, true, true);
                        case "message", "requireSafeConfigKey", "requireSafeConfigValue": return arguments[0];
                        case "visibleLocales": return List.of(new DesktopUiHost.UiLocale("en-US", "English", "_en"));
                        case "matchLocale": return "en-US".equals(arguments[0])
                                ? Optional.of(new DesktopUiHost.UiLocale("en-US", "English", "_en")) : Optional.empty();
                        case "detectSystemLocale": return Locale.US;
                        case "guiGet", "controlCenterSnapshot": return DesktopUiHost.GuiResponse.unreachable();
                        case "withCredentialLocks":
                            ((DesktopUiHost.IoOperation) arguments[1]).run();
                            return null;
                    }
                    Class<?> type = method.getReturnType();
                    if (type == boolean.class) return false;
                    if (type == int.class) return 0;
                    if (type == String.class) return "";
                    if (type == List.class) return List.of();
                    if (type == Map.class) return Map.of();
                    if (type == Set.class) return Set.of();
                    if (type == Optional.class) return Optional.empty();
                    if (type == AutoCloseable.class) return (AutoCloseable) () -> {};
                    throw new AssertionError("unexpected host call: " + method.getName());
                }
        );
        DesktopUiPluginSnapshot provider = new DesktopUiPluginSnapshot(
                "compose", false, "compose", 1, true, null, "",
                List.of(), List.of(), List.of(), List.of(), List.of()
        );
        DesktopUiPluginSnapshot alternate = new DesktopUiPluginSnapshot(
                "alternate", false, "alternate", 1, true, null, "",
                List.of(), List.of(), List.of(), List.of(), List.of()
        );
        ComposeDesktopUiModel model = new ComposeDesktopUiModel(
                8080, ".", Path.of("config.yaml"), "compose", host, () -> List.of(provider, alternate)
        );
        awaitReady(model);
        return model;
    }
}
