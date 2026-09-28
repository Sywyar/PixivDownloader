package top.sywyar.pixivdownload.guicompose.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiHost;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiPluginSnapshot;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigCondition;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigContribution;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigFieldContribution;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigFieldType;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigGroupContribution;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Compose 配置未保存状态")
class DesktopConfigurationControllerTest {
    @Test
    @DisplayName("保存按当前凭据检查必填，空框沿用、外部删除及显式清除不会误判")
    @SuppressWarnings("unchecked")
    void validatesEffectiveCredentialsBeforeSaving() throws Exception {
        var enabled = new GuiConfigFieldContribution("demo.enabled", "demo", "enabled", GuiConfigFieldType.BOOL, "false", 1);
        var secret = new GuiConfigFieldContribution("demo.secret", "demo", "secret", GuiConfigFieldType.PASSWORD, "", 2)
                .requiredWhen(GuiConfigCondition.isTrue("demo.enabled"));
        var source = new DesktopUiPluginSnapshot("demo", false, "demo", 1, false, "demo", "demo",
                List.of(), List.of(new GuiConfigContribution(
                        List.of(new GuiConfigGroupContribution("demo", "demo", "demo", 1, true)),
                        List.of(enabled, secret), List.of())), List.of(), List.of(), List.of());
        Map<String, String> app = new HashMap<>();
        Map<String, String> plugin = new HashMap<>(Map.of("demo.enabled", "false"));
        Map<String, String> credentials = new HashMap<>();
        var unreadable = new java.util.concurrent.atomic.AtomicBoolean();
        var writes = new java.util.concurrent.atomic.AtomicInteger();
        DesktopUiHost.ConfigFile file = new DesktopUiHost.ConfigFile() {
            public Map<String, String> readAll(Collection<String> keys) { return Map.copyOf(plugin); }
            public void writeAll(Map<String, String> values) { writes.incrementAndGet(); plugin.putAll(values); }
            public void removeAll(Collection<String> keys) { keys.forEach(plugin::remove); }
            public DesktopUiHost.ConfigSnapshot snapshot() { return new DesktopUiHost.ConfigSnapshot(false, List.of()); }
            public void restore(DesktopUiHost.ConfigSnapshot snapshot) { throw new AssertionError("unexpected rollback"); }
        };
        try (var model = model(app, Map.of(
                "pluginConfig", args -> file,
                "readCredentials", args -> {
                    assertEquals("demo", args[0]);
                    if (unreadable.get()) throw new java.io.UncheckedIOException(new java.io.IOException("unavailable"));
                    return Map.copyOf(credentials);
                },
                "snapshotCredentials", args -> new DesktopUiHost.CredentialSnapshot(false, new byte[0]),
                "updateCredentials", args -> {
                    ((Map<String, String>) args[1]).forEach((key, value) -> {
                        if (value.isBlank()) credentials.remove(key); else credentials.put(key, value);
                    });
                    return null;
                }), () -> List.of(source))) {
            dispatch(model, new DesktopUiNode.Event(DesktopUiNode.EventType.CHANGE,
                    "config.demo.demo.enabled.input", DesktopUiNode.Value.bool(true)));
            select(model, "theme", "dark");
            for (String blank : List.of("", " \t")) {
                dispatch(model, new DesktopUiNode.Event(DesktopUiNode.EventType.CHANGE,
                        "config.demo.demo.secret.input", DesktopUiNode.Value.text(blank)));
                assertCredentialSaveRejected(model);
                assertEquals(0, writes.get());
                assertTrue(app.isEmpty());
                assertTrue(credentials.isEmpty());
            }
            dispatch(model, new DesktopUiNode.Event(DesktopUiNode.EventType.CHANGE,
                    "config.demo.demo.secret.input", DesktopUiNode.Value.text("fixture-secret")));
            save(model);
            assertEquals("true", plugin.get("demo.enabled"));
            assertEquals("fixture-secret", credentials.get("demo.secret"));
            assertFalse(plugin.containsKey("demo.secret"));
            assertFalse(app.containsKey("demo.secret"));
            select(model, "theme", "light");
            save(model);
            assertEquals("fixture-secret", credentials.get("demo.secret"));
            select(model, "theme", "dark");
            unreadable.set(true);
            assertCredentialSaveRejected(model);
            unreadable.set(false);
            credentials.clear();
            assertCredentialSaveRejected(model);
            assertEquals("light", app.get("app.theme"));
            credentials.put("demo.secret", "fixture-secret");
            activate(model, "config.demo.demo.secret.clear.button");
            awaitReady(model);
            assertTrue(credentials.isEmpty());
            assertCredentialSaveRejected(model);
            dispatch(model, new DesktopUiNode.Event(DesktopUiNode.EventType.CHANGE,
                    "config.demo.demo.enabled.input", DesktopUiNode.Value.bool(false)));
            save(model);
            assertEquals("false", plugin.get("demo.enabled"));
        }
    }

    private static void assertCredentialSaveRejected(ComposeDesktopUiModel model) {
        activate(model, "config.save");
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            while (model.busy() || nodes(model).filter(DesktopUiNode.SettingsWorkspace.class::isInstance)
                    .map(DesktopUiNode.SettingsWorkspace.class::cast)
                    .noneMatch(workspace -> workspace.invalidRow().equals("config.demo.demo.secret.row"))) Thread.sleep(10);
        });
        var workspace = nodes(model).filter(DesktopUiNode.SettingsWorkspace.class::isInstance)
                .map(DesktopUiNode.SettingsWorkspace.class::cast).findFirst().orElseThrow();
        assertEquals("config.demo.demo.secret.row", workspace.invalidRow());
    }

    @Test
    @DisplayName("条件必填拒绝引用缺席字段，健康字段继续显示")
    void rejectsUnavailableRequiredCondition() throws Exception {
        var required = new GuiConfigFieldContribution(
                "demo.value",
                "demo",
                "value",
                GuiConfigFieldType.STRING,
                "",
                1
        ).requiredWhen(GuiConfigCondition.isTrue("missing"));
        var healthy = new GuiConfigFieldContribution(
                "demo.healthy",
                "demo",
                "healthy",
                GuiConfigFieldType.STRING,
                "",
                2
        );
        try (var model = model(new HashMap<>(Map.of("demo.healthy", "")), Map.of(
                "coreConfigGroups", args -> List.of(new GuiConfigGroupContribution("demo", "demo", null, 1, true)),
                "coreConfigFields", args -> List.of(required, healthy)))) {
            assertFalse(nodes(model).anyMatch(node -> node.id().equals("config.app.demo.value.input")));
            assertTrue(nodes(model).anyMatch(node -> node.id().equals("config.app.demo.healthy.input")));
        }
    }

    @Test
    @DisplayName("完整保存草稿检查条件必填，拦截未编辑空值且不写入其它设置")
    void validatesRequiredFieldsBeforeAnyWrite() throws Exception {
        var enabled = new GuiConfigFieldContribution(
                "demo.enabled",
                "demo",
                "enabled",
                GuiConfigFieldType.BOOL,
                "false",
                1
        );
        var value = new GuiConfigFieldContribution(
                "demo.value",
                "demo",
                "value",
                GuiConfigFieldType.STRING,
                "",
                2
        ).requiredWhen(GuiConfigCondition.isTrue("demo.enabled"));
        Map<String, java.util.function.Function<Object[], Object>> fields = Map.of(
                "validateCoreConfigValue", args -> null,
                "coreConfigGroups", args -> List.of(new GuiConfigGroupContribution("demo", "demo", null, 1, true)),
                "coreConfigFields", args -> List.of(enabled, value));
        for (String blank : List.of("", " \t")) {
            Map<String, String> stored = new HashMap<>(Map.of("demo.enabled", "false", "demo.value", blank));
            try (var model = model(stored, fields)) {
                dispatch(model, new DesktopUiNode.Event(
                        DesktopUiNode.EventType.CHANGE,
                        "config.app.demo.enabled.input",
                        DesktopUiNode.Value.bool(true)
                ));
                select(model, "theme", "dark");
                assertTrue(nodes(model).filter(DesktopUiNode.Toggle.class::isInstance)
                        .map(DesktopUiNode.Toggle.class::cast).filter(node -> node.id().equals("config.app.demo.enabled.input"))
                        .findFirst().orElseThrow().selected());
                assertTrue(nodes(model).filter(node -> node.id().equals("config.save"))
                        .map(DesktopUiNode.Button.class::cast).findFirst().orElseThrow().enabled());
                activate(model, "config.save");
                assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
                    while (model.busy() || nodes(model).filter(DesktopUiNode.SettingsWorkspace.class::isInstance)
                            .map(DesktopUiNode.SettingsWorkspace.class::cast)
                            .noneMatch(workspace -> !workspace.invalidRow().isEmpty())) Thread.sleep(10);
                });
                assertEquals(Map.of("demo.enabled", "false", "demo.value", blank), stored);
                var workspace = nodes(model).filter(DesktopUiNode.SettingsWorkspace.class::isInstance)
                        .map(DesktopUiNode.SettingsWorkspace.class::cast).findFirst().orElseThrow();
                assertEquals("config.app.demo.value.row", workspace.invalidRow());
                assertEquals(workspace.invalidRow(), workspace.locatedRow());
                dispatch(model, new DesktopUiNode.Event(
                        DesktopUiNode.EventType.CHANGE,
                        "config.app.demo.value.input",
                        DesktopUiNode.Value.text("fixture/custom")
                ));
                save(model);
                assertEquals("true", stored.get("demo.enabled"));
                assertEquals("fixture/custom", stored.get("demo.value"));
            }
            stored = new HashMap<>(Map.of("demo.enabled", "false", "demo.value", "fixture/custom"));
            try (var model = model(stored, fields)) {
                dispatch(model, new DesktopUiNode.Event(
                        DesktopUiNode.EventType.CHANGE,
                        "config.app.demo.value.input",
                        DesktopUiNode.Value.text(blank)
                ));
                save(model);
                assertEquals(blank, stored.get("demo.value"));
            }
        }
    }

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
                assertFalse(nodes(model).filter(node -> node.id().equals("config.save"))
                        .map(DesktopUiNode.Button.class::cast).findFirst().orElseThrow().enabled());
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
            assertEquals("gui.compose.settings.effect.process", text(model, "settings.impact.effect").key());
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
            assertEquals("gui.compose.settings.effect.immediate", text(model, "settings.impact.effect").key());
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

    @Test
    @DisplayName("重新加载前允许保留草稿，确认后恢复文件值且不写配置")
    void confirmsBeforeDiscardingDrafts() throws Exception {
        Map<String, String> stored = new HashMap<>();
        try (ComposeDesktopUiModel model = model(stored)) {
            select(model, "theme", "dark");
            activate(model, "config.reload");
            assertEquals("config.reload.dialog", model.snapshot().document().dialogs().get(0).id());
            assertEquals(1, pendingCount(model));
            activate(model, "config.reload.cancel");
            assertEquals(1, pendingCount(model));
            activate(model, "config.reload");
            activate(model, "config.reload.confirm");
            assertEquals(0, pendingCount(model));
            assertEquals(Map.of(), stored);
            assertEquals(List.of(), model.snapshot().document().dialogs());
        }
    }

    @Test
    @DisplayName("放弃修改恢复语言与主题预览且不写配置")
    void restoresSavedPreviewsWhenDiscarded() throws Exception {
        Locale previous = Locale.getDefault();
        Map<String, String> stored = new HashMap<>(Map.of("app.language", "en-US", "app.theme", "light"));
        try (ComposeDesktopUiModel model = model(stored)) {
            select(model, "language", "follow-system");
            Locale.setDefault(Locale.JAPAN);
            select(model, "theme", "dark");
            assertEquals("dark", model.themePreference());
            assertEquals("light", stored.get("app.theme"));
            activate(model, "config.reload");
            activate(model, "config.reload.confirm");
            assertEquals(Locale.US, Locale.getDefault());
            assertEquals("light", model.themePreference());
            assertEquals(0, pendingCount(model));
            assertEquals(Map.of("app.language", "en-US", "app.theme", "light"), stored);
        } finally { Locale.setDefault(previous); }
    }

    private static void activate(ComposeDesktopUiModel model, String id) {
        dispatch(model, new DesktopUiNode.Event(DesktopUiNode.EventType.ACTIVATE, id, DesktopUiNode.Value.empty()));
    }

    private static void dispatch(ComposeDesktopUiModel model, DesktopUiNode.Event event) {
        synchronized (model) {
            model.dispatch(model.snapshot(), event);
        }
    }

    @Test
    @DisplayName("状态刷新复用设置页，编辑后的草稿和保存绑定仍然有效")
    void statusRefreshReusesSettingsWithoutLosingDraftsOrActions() throws Exception {
        Map<String, String> stored = new HashMap<>();
        try (ComposeDesktopUiModel model = model(stored)) {
            synchronized (model) {
                model.rebuild();
                DesktopUiNode original = workspace(model);
                var securityPage = model.snapshot().document().pages().stream().filter(page -> page.id().equals("security")).findFirst().orElseThrow();
                var aboutPage = model.snapshot().document().pages().stream().filter(page -> page.id().equals("about")).findFirst().orElseThrow();
                model.rebuildStatus();
                assertSame(original, workspace(model));
                assertSame(securityPage, model.snapshot().document().pages().stream().filter(page -> page.id().equals("security")).findFirst().orElseThrow());
                assertSame(aboutPage, model.snapshot().document().pages().stream().filter(page -> page.id().equals("about")).findFirst().orElseThrow());
                select(model, "theme", "dark");
                assertNotSame(original, workspace(model));
                assertEquals(1, pendingCount(model));
                DesktopUiNode edited = workspace(model);
                long interaction = model.snapshot().interactionRevisions().get("interface.theme.input");
                model.rebuildStatus();
                assertSame(edited, workspace(model));
                assertEquals(interaction, model.snapshot().interactionRevisions().get("interface.theme.input"));
            }
            save(model);
            assertEquals("dark", stored.get("app.theme"));
            assertEquals(0, pendingCount(model));
        }
    }

    @Test
    @DisplayName("语言、忙碌状态和插件来源变化使状态刷新中的设置页失效")
    void statusRefreshInvalidatesSettingsForLocaleBusyStateAndSources() throws Exception {
        Locale previous = Locale.getDefault();
        AtomicReference<List<DesktopUiPluginSnapshot>> sources = new AtomicReference<>(List.of());
        try (ComposeDesktopUiModel model = model(new HashMap<>(), Map.of(), sources::get)) {
            synchronized (model) {
                model.rebuild();
                DesktopUiNode original = workspace(model);
                Locale.setDefault(previous.equals(Locale.US) ? Locale.JAPAN : Locale.US);
                model.rebuildStatus();
                assertNotSame(original, workspace(model));
                sources.set(List.of(new DesktopUiPluginSnapshot(
                        "third",
                        false,
                        "third",
                        1,
                        true,
                        null,
                        "",
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of()
                )));
                model.rebuildStatus();
                assertTrue(providerChoice(model).options().stream().anyMatch(option -> option.id().equals("third")));
                long interaction = model.snapshot().interactionRevisions().get("interface.provider.input");
                sources.set(List.of(new DesktopUiPluginSnapshot(
                        "third",
                        false,
                        "third",
                        2,
                        true,
                        null,
                        "",
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of()
                )));
                model.rebuildStatus();
                assertTrue(model.snapshot().interactionRevisions().get("interface.provider.input") > interaction);
                model.setBusy(true);
                model.rebuildStatus();
                assertFalse(providerChoice(model).enabled());
                assertTrue(((DesktopUiNode.SecurityOverview) pageContent(model, "security")).busy());
                model.setBusy(false);
                model.rebuildStatus();
                assertTrue(providerChoice(model).enabled());
                assertFalse(((DesktopUiNode.SecurityOverview) pageContent(model, "security")).busy());
                sources.set(List.of());
                model.rebuildStatus();
                assertFalse(providerChoice(model).options().stream().anyMatch(option -> option.id().equals("third")));
            }
        } finally {
            Locale.setDefault(previous);
        }
    }

    private static DesktopUiNode workspace(ComposeDesktopUiModel model) {
        return nodes(model).filter(node -> node instanceof DesktopUiNode.SettingsWorkspace).findFirst().orElseThrow();
    }

    private static DesktopUiNode pageContent(ComposeDesktopUiModel model, String id) {
        return ((DesktopUiNode.Surface) model.snapshot().document().pages().stream()
                .filter(page -> page.id().equals(id)).findFirst().orElseThrow().content()).content();
    }

    private static DesktopUiNode.Choice providerChoice(ComposeDesktopUiModel model) {
        return (DesktopUiNode.Choice) nodes(model).filter(node -> node.id().equals("interface.provider.input"))
                .findFirst().orElseThrow();
    }

    private static void select(ComposeDesktopUiModel model, String preference, String value) {
        dispatch(model, new DesktopUiNode.Event(
                DesktopUiNode.EventType.SELECTION,
                "interface." + preference + ".input",
                DesktopUiNode.Value.selection(value)
        ));
    }

    private static void toggleExpandAll(ComposeDesktopUiModel model, boolean value) {
        dispatch(model, new DesktopUiNode.Event(
                DesktopUiNode.EventType.CHANGE,
                "interface.config-menu-expand-all.input",
                DesktopUiNode.Value.bool(value)
        ));
    }

    private static void save(ComposeDesktopUiModel model) {
        awaitReady(model);
        activate(model, "config.save");
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            while (model.busy() || pendingCount(model) != 0) Thread.sleep(10);
        });
    }

    private static void awaitReady(ComposeDesktopUiModel model) {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            while (model.busy()) {
                Thread.sleep(10);
            }
        });
    }

    private static int pendingCount(ComposeDesktopUiModel model) {
        return nodes(model).filter(node -> node.id().equals("settings.unsaved-count"))
                .map(DesktopUiNode.Text.class::cast).map(node -> Integer.parseInt(node.text().arguments().get(0))).findFirst().orElse(0);
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

    static ComposeDesktopUiModel model(Map<String, String> stored) {
        return model(stored, Map.of());
    }

    static ComposeDesktopUiModel model(Map<String, String> stored,
            Map<String, java.util.function.Function<Object[], Object>> overrides) {
        return model(stored, overrides, List::of);
    }

    static ComposeDesktopUiModel model(Map<String, String> stored,
            Map<String, java.util.function.Function<Object[], Object>> overrides,
            java.util.function.Supplier<List<DesktopUiPluginSnapshot>> sources) {
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
                    if (overrides.containsKey(method.getName())) return overrides.get(method.getName()).apply(arguments);
                    switch (method.getName()) {
                        case "applicationName": return "PixivDownloader";
                        case "applicationBuildChannel": return DesktopUiHost.BuildChannel.UNKNOWN;
                        case "applicationConfig": return config;
                        case "resolveDatabasePath": return Path.of("data", "test.db");
                        case "defaultBackfillOptions": return new DesktopUiHost.BackfillOptions(
                                "data/test.db", "localhost", 8080, false, 1000, 0, false
                        );
                        case "loadImageClassifierSettings": return new DesktopUiHost.ImageClassifierSettings(
                                "", false, "http://localhost:6999", List.of()
                        );
                        case "backendPort": return arguments[0];
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
                8080, ".", Path.of("config.yaml"), "compose", host,
                () -> java.util.stream.Stream.concat(java.util.stream.Stream.of(provider, alternate), sources.get().stream()).toList()
        );
        awaitReady(model);
        return model;
    }
}
