package top.sywyar.pixivdownload.guicompose.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode;
import top.sywyar.pixivdownload.plugin.api.gui.*;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class SettingsContributionTest {
    @Test
    @DisplayName("预设和手动清空字段可预览空值，保存时不写入显示占位符")
    void previewsBlankChangesWithoutChangingStoredValues() throws Exception {
        for (String blank : List.of("", "  ")) {
            var group = new GuiConfigGroupContribution("sample", "sample.title", "sample", 1, true);
            var fields = List.of(
                    new GuiConfigFieldContribution("sample.address", "sample", "sample.address.label", GuiConfigFieldType.STRING, "", 1),
                    new GuiConfigFieldContribution("sample.optional", "sample", "sample.optional.label", GuiConfigFieldType.STRING, "", 2));
            var preset = new GuiConfigPresetContribution("example", "sample.preset", 1,
                    Map.of("sample.address", "preset-value", "sample.optional", blank));
            var section = new GuiConfigSectionContribution(
                    "sample.connection", "sample", "", "", "sample", GuiConfigSectionLayout.FIELD_LIST, 1,
                    List.of(new GuiConfigFieldLayoutContribution("sample.address", "", "", 1),
                            new GuiConfigFieldLayoutContribution("sample.optional", "", "", 2)),
                    List.of(), List.of(preset));
            var source = new DesktopUiPluginSnapshot("sample", false, "sample", 1, false, "sample", "sample.title",
                    List.of(), List.of(new GuiConfigContribution(List.of(group), fields, List.of(section))), List.of(), List.of(), List.of());
            Map<String, String> stored = new HashMap<>(Map.of("sample.address", blank, "sample.optional", "previous-value"));
            DesktopUiHost.ConfigFile plugin = (DesktopUiHost.ConfigFile) Proxy.newProxyInstance(
                    DesktopUiHost.ConfigFile.class.getClassLoader(), new Class<?>[]{DesktopUiHost.ConfigFile.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        case "readAll" -> Map.copyOf(stored);
                        case "snapshot" -> new DesktopUiHost.ConfigSnapshot(false, List.of());
                        case "writeAll" -> {
                            ((Map<?, ?>) args[0]).forEach((key, value) -> stored.put((String) key, (String) value));
                            yield null;
                        }
                        default -> throw new AssertionError("unexpected config call: " + method.getName());
                    });
            try (var model = DesktopConfigurationControllerTest.model(new HashMap<>(),
                    Map.of("pluginConfig", args -> plugin), () -> List.of(source))) {
                var choice = nodes(workspace(model)).filter(DesktopUiNode.Choice.class::isInstance)
                        .map(DesktopUiNode.Choice.class::cast).filter(node -> node.id().endsWith(".preset.input"))
                        .findFirst().orElseThrow();
                dispatch(model, new DesktopUiNode.Event(DesktopUiNode.EventType.SELECTION,
                        choice.id(), DesktopUiNode.Value.selection(choice.options().get(0).id())));
                var changes = workspace(model).changes();
                assertEquals(2, changes.size());
                var address = changes.stream().filter(change -> change.rowId().equals("config.sample.sample.address.row")).findFirst().orElseThrow();
                var optional = changes.stream().filter(change -> change.rowId().equals("config.sample.sample.optional.row")).findFirst().orElseThrow();
                assertEquals("—", address.before().fallback());
                assertEquals("preset-value", address.after().fallback());
                assertEquals("previous-value", optional.before().fallback());
                assertEquals("—", optional.after().fallback());

                dispatch(model, new DesktopUiNode.Event(DesktopUiNode.EventType.ACTIVATE, "config.save", DesktopUiNode.Value.empty()));
                assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
                    while (model.busy() || !workspace(model).changes().isEmpty()) Thread.sleep(10);
                });
                assertEquals(Map.of("sample.address", "preset-value", "sample.optional", blank), stored);
                dispatch(model, new DesktopUiNode.Event(DesktopUiNode.EventType.ACTIVATE, "config.restart.later", DesktopUiNode.Value.empty()));
                dispatch(model, new DesktopUiNode.Event(DesktopUiNode.EventType.CHANGE,
                        "config.sample.sample.address.input", DesktopUiNode.Value.text(blank)));
                assertEquals(1, workspace(model).changes().size());
                assertEquals("preset-value", workspace(model).changes().get(0).before().fallback());
                assertEquals("—", workspace(model).changes().get(0).after().fallback());
            }
        }
    }

    @Test
    @DisplayName("搜索可定位未选中的插件分组，秘密不进入摘要且保存失败保留草稿")
    void locatesContributedCardsWithoutExposingSecrets() throws Exception {
        var group = new GuiConfigGroupContribution("sample", "sample.title", "sample", 1, true);
        var fields = List.of(
                new GuiConfigFieldContribution("sample.address", "sample", "sample.address.label", GuiConfigFieldType.STRING, "local", 1),
                new GuiConfigFieldContribution("sample.secret", "sample", "sample.secret.label", GuiConfigFieldType.PASSWORD, "", 2),
                new GuiConfigFieldContribution("sample.other-secret", "sample", "sample.other-secret.label", GuiConfigFieldType.PASSWORD, "", 3));
        var section = new GuiConfigSectionContribution("sample.connection", "sample", GuiConfigSectionLayout.CARD_SWITCHER, 1,
                List.of(new GuiConfigFieldLayoutContribution("sample.address", "first", "sample.first", 1),
                        new GuiConfigFieldLayoutContribution("sample.secret", "second", "sample.second", 2)));
        var source = new DesktopUiPluginSnapshot("sample", false, "sample", 1, false, "sample", "sample.title",
                List.of(), List.of(new GuiConfigContribution(List.of(group), fields, List.of(section))), List.of(), List.of(), List.of());
        DesktopUiHost.ConfigFile plugin = (DesktopUiHost.ConfigFile) Proxy.newProxyInstance(
                DesktopUiHost.ConfigFile.class.getClassLoader(), new Class<?>[]{DesktopUiHost.ConfigFile.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("readAll")) return Map.of("sample.address", "local");
                    throw new AssertionError("unexpected plugin write");
                });
        try (var model = DesktopConfigurationControllerTest.model(new HashMap<>(), Map.of(
                "pluginConfig", args -> plugin,
                "readCredentials", args -> Map.of("sample.secret", "stored-secret-must-stay-private"),
                "updateCredentials", args -> {
                    assertEquals("sample", args[0]);
                    assertEquals(Map.of("sample.secret", ""), args[1]);
                    return null;
                },
                "requireSafeConfigValue", args -> {
                    if (args[0].equals("new-secret-must-stay-private")) throw new IllegalArgumentException("invalid input");
                    return args[0];
                }
        ), () -> List.of(source))) {
            var workspace = workspace(model);
            var secret = workspace.locations().stream().filter(item -> item.rowId().equals("config.sample.sample.secret.row")).findFirst().orElseThrow();
            assertFalse(nodes(workspace).anyMatch(node -> node.id().equals("config.sample.sample.secret.input")));
            dispatch(model, new DesktopUiNode.Event(DesktopUiNode.EventType.ACTIVATE,
                    secret.locate().id(), DesktopUiNode.Value.empty()));
            assertEquals(List.of("config.sample"), workspace(model).categories().selectedIds());
            assertTrue(nodes(workspace(model)).anyMatch(node -> node.id().equals("config.sample.sample.secret.input")));
            dispatch(model, new DesktopUiNode.Event(DesktopUiNode.EventType.CHANGE,
                    "config.sample.sample.secret.input", DesktopUiNode.Value.text("new-secret-must-stay-private")));
            assertEquals(1, workspace(model).changes().size());
            assertFalse(workspace(model).toString().contains("must-stay-private"));
            dispatch(model, new DesktopUiNode.Event(DesktopUiNode.EventType.ACTIVATE, "config.save", DesktopUiNode.Value.empty()));
            assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
                while (model.busy() || workspace(model).invalidRow().isEmpty()) Thread.sleep(10);
            });
            assertEquals(1, workspace(model).changes().size());
            assertEquals(secret.rowId(), workspace(model).invalidRow());
            assertFalse(workspace(model).toString().contains("must-stay-private"));
            dispatch(model, new DesktopUiNode.Event(DesktopUiNode.EventType.CHANGE,
                    "config.sample.sample.other-secret.input", DesktopUiNode.Value.text("other-private-draft")));
            long revision = workspace(model).credentialRevision();
            dispatch(model, new DesktopUiNode.Event(DesktopUiNode.EventType.ACTIVATE,
                    "config.sample.sample.secret.clear.button", DesktopUiNode.Value.empty()));
            assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
                while (model.busy() || workspace(model).changes().size() != 1) Thread.sleep(10);
            });
            assertEquals(revision, workspace(model).credentialRevision());
            assertEquals("", workspace(model).invalidRow());
            assertEquals("config.sample.sample.other-secret.row", workspace(model).changes().get(0).rowId());
            var inputs = nodes(workspace(model)).filter(DesktopUiNode.TextInput.class::isInstance)
                    .map(DesktopUiNode.TextInput.class::cast).toList();
            assertEquals(1, inputs.stream().filter(input -> input.id().equals("config.sample.sample.secret.input")).findFirst().orElseThrow().stateRevision());
            assertEquals(0, inputs.stream().filter(input -> input.id().equals("config.sample.sample.other-secret.input")).findFirst().orElseThrow().stateRevision());
            assertFalse(workspace(model).toString().contains("other-private-draft"));
        }
    }

    private static void dispatch(ComposeDesktopUiModel model, DesktopUiNode.Event event) {
        synchronized (model) {
            model.dispatch(model.snapshot(), event);
        }
    }

    private static DesktopUiNode.SettingsWorkspace workspace(ComposeDesktopUiModel model) {
        return model.snapshot().document().pages().stream().filter(page -> page.id().equals("settings"))
                .flatMap(page -> nodes(page.content())).filter(DesktopUiNode.SettingsWorkspace.class::isInstance)
                .map(DesktopUiNode.SettingsWorkspace.class::cast).findFirst().orElseThrow();
    }

    private static Stream<DesktopUiNode> nodes(DesktopUiNode node) {
        return Stream.concat(Stream.of(node), node.childNodes().stream().flatMap(SettingsContributionTest::nodes));
    }
}
