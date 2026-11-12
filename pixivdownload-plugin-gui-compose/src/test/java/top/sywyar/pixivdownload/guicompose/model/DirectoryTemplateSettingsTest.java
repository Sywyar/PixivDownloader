package top.sywyar.pixivdownload.guicompose.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode;
import top.sywyar.pixivdownload.plugin.api.gui.*;

import java.util.*;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class DirectoryTemplateSettingsTest {
    static final String ROOT = "fixture.directory";
    static final String USER = "fixture.user";
    static final String SERIES = "fixture.series";
    static final String DEFAULT = "pictures/{artwork_id}";
    static final String USER_TEMPLATE = "pictures/{username}/{artwork_id}";

    static List<GuiConfigFieldContribution> fields() {
        return List.of(field(ROOT, DEFAULT), field(USER, USER_TEMPLATE), field(SERIES, ""));
    }

    private static GuiConfigFieldContribution field(String key, String value) {
        return new GuiConfigFieldContribution(key, GuiConfigGroups.DOWNLOAD, key,
                key.equals(ROOT) ? "gui.config.field.download.root-folder.help" : "",
                GuiConfigFieldType.DIRECTORY_TEMPLATE, value, 0, false, GuiConfigEffect.PROCESS_RESTART);
    }

    static GuiConfigSectionContribution section() {
        return new GuiConfigSectionContribution("fixture-directories", GuiConfigGroups.DOWNLOAD,
                "gui.config.directory.illustration", "gui.config.directory.help", null,
                GuiConfigSectionLayout.DIRECTORY_TEMPLATES, 0,
                List.of(new GuiConfigFieldLayoutContribution(ROOT, 0),
                        new GuiConfigFieldLayoutContribution(USER, "user", "gui.config.directory.mode.user", 1),
                        new GuiConfigFieldLayoutContribution(SERIES, "series", "gui.config.directory.mode.series", 2)),
                List.of(), List.of());
    }

    static ComposeDesktopUiModel model(Map<String, String> stored, boolean plugin) {
        var groups = List.of(new GuiConfigGroupContribution(GuiConfigGroups.DOWNLOAD, "gui.config.group.download", 0));
        var sources = plugin ? List.of(new DesktopUiPluginSnapshot(
                "independent", false, "independent", 1, false, null, "",
                List.of(), List.of(new GuiConfigContribution(groups, fields(), List.of(section()))),
                List.of(), List.of(), List.of())) : List.<DesktopUiPluginSnapshot>of();
        return DesktopConfigurationControllerTest.model(stored, Map.of(
                "coreConfigFields", args -> plugin ? List.of() : fields(),
                "coreConfigGroups", args -> groups,
                "coreConfigSections", args -> plugin ? List.of() : List.of(section()),
                "validateCoreConfigValue", args -> null,
                "validateDirectoryTemplate", args -> null,
                "pluginConfig", args -> java.lang.reflect.Proxy.newProxyInstance(
                        DesktopUiHost.ConfigFile.class.getClassLoader(), new Class<?>[]{DesktopUiHost.ConfigFile.class},
                        (proxy, method, arguments) -> {
                            if (method.getName().equals("readAll")) return Map.of(ROOT, DEFAULT, USER, USER_TEMPLATE, SERIES, "");
                            throw new AssertionError(method.getName());
                        })
        ), () -> sources);
    }

    @Test
    @DisplayName("目录覆盖增删和模式切换只改草稿，保存后空值不会恢复默认覆盖")
    void editAndSave() throws Exception {
        var stored = new HashMap<String, String>();
        try (var model = model(stored, false)) {
            assertFalse(nodes(model).anyMatch(n -> n.id().equals("config.app." + SERIES + ".input")));
            assertEquals(USER_TEMPLATE, input(model, USER).value());
            activate(model, "config.section.fixture-directories.add");
            assertEquals("", input(model, SERIES).value());
            assertTrue(stored.isEmpty());
            var choice = (DesktopUiNode.Choice) nodes(model).filter(n -> n.id().equals("config.app." + SERIES + ".mode")).findFirst().orElseThrow();
            assertEquals(1, choice.options().size(), "已使用的模式不能重复选择");
            activate(model, "config.app." + SERIES + ".remove");
            dispatch(model, new DesktopUiNode.Event(DesktopUiNode.EventType.SELECTION,
                    "config.app." + USER + ".mode", DesktopUiNode.Value.selection("config.app." + SERIES)));
            assertEquals(USER_TEMPLATE, input(model, SERIES).value());
            assertFalse(nodes(model).anyMatch(n -> n.id().equals("config.app." + USER + ".input")));
            activate(model, "config.save");
            DesktopConfigurationControllerTest.awaitReady(model);
            activate(model, "config.restart.later");
            assertEquals("", stored.get(USER));
            assertEquals(USER_TEMPLATE, stored.get(SERIES));
            activate(model, "config.app." + SERIES + ".remove");
            activate(model, "config.save");
            DesktopConfigurationControllerTest.awaitReady(model);
            activate(model, "config.restart.later");
            activate(model, "config.reload");
            DesktopConfigurationControllerTest.awaitReady(model);
            assertEquals("", stored.get(SERIES));
            assertFalse(nodes(model).anyMatch(n -> n.id().endsWith(".remove")));
            assertEquals(DEFAULT, input(model, ROOT).value());
        }
    }

    @Test
    @DisplayName("未知插件声明的目录组合可显示和定位空模式，不依赖具体插件标识")
    void independentPlugin() throws Exception {
        try (var model = model(new HashMap<>(), true)) {
            assertTrue(nodes(model).anyMatch(n -> n.id().equals("config.independent." + USER + ".input")));
            activate(model, "config.independent." + SERIES + ".row.locate");
            assertTrue(nodes(model).anyMatch(n -> n.id().equals("config.independent." + SERIES + ".input")));
            assertTrue(nodes(model).filter(DesktopUiNode.SettingsWorkspace.class::isInstance)
                    .map(DesktopUiNode.SettingsWorkspace.class::cast)
                    .allMatch(workspace -> workspace.locatedRow().equals("config.independent." + SERIES + ".row")));
        }
    }

    private static DesktopUiNode.TextInput input(ComposeDesktopUiModel model, String key) {
        return nodes(model).filter(n -> n.id().equals("config.app." + key + ".input"))
                .map(DesktopUiNode.TextInput.class::cast).findFirst().orElseThrow();
    }

    private static Stream<DesktopUiNode> nodes(ComposeDesktopUiModel model) {
        return model.snapshot().document().pages().stream().filter(page -> page.id().equals("settings"))
                .flatMap(page -> descendants(page.content()));
    }

    private static Stream<DesktopUiNode> descendants(DesktopUiNode node) {
        return Stream.concat(Stream.of(node), node.childNodes().stream().flatMap(DirectoryTemplateSettingsTest::descendants));
    }

    private static void activate(ComposeDesktopUiModel model, String id) {
        dispatch(model, new DesktopUiNode.Event(DesktopUiNode.EventType.ACTIVATE, id, DesktopUiNode.Value.empty()));
    }

    private static void dispatch(ComposeDesktopUiModel model, DesktopUiNode.Event event) {
        model.dispatch(model.snapshot(), event);
    }
}
