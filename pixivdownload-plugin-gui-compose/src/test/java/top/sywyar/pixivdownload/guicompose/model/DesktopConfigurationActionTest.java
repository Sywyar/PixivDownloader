package top.sywyar.pixivdownload.guicompose.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode;
import top.sywyar.pixivdownload.plugin.api.gui.*;
import top.sywyar.pixivdownload.plugin.api.web.WebRouteContribution;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Compose 配置动作的显示与选择回填")
class DesktopConfigurationActionTest {
    @Test
    @DisplayName("各配置布局在目标字段内提供候选，连续选择只修改草稿")
    void selectsWithoutSaving() throws Exception {
        for (var layout : GuiConfigSectionLayout.values()) {
            try (var model = model(new AtomicReference<>(List.of(source("demo.value", false, layout))), () -> response())) {
                var action = button(model);
                assertTrue(fieldNodes(model).anyMatch(node -> node.id().equals(action.id())));
                activate(model, action.id());
                await(model);
                var choice = choices(model).findFirst().orElseThrow();
                assertEquals(35, choice.options().size());
                assertEquals(List.of(), choice.selectedIds());
                assertEquals("initial", input(model).value());
                assertTrue(fieldNodes(model).anyMatch(node -> node.id().equals(choice.id())));
                select(model, choice.id(), choice.options().get(34).id());
                assertEquals("test/34+测试", input(model).value());
                assertEquals(List.of("item.34"), choices(model).findFirst().orElseThrow().selectedIds());
                select(model, choice.id(), choice.options().get(2).id());
                assertEquals("test/2+测试", input(model).value());
                assertEquals(List.of("item.2"), choices(model).findFirst().orElseThrow().selectedIds());
                dispatch(model, new DesktopUiNode.Event(
                        DesktopUiNode.EventType.CHANGE,
                        input(model).id(),
                        DesktopUiNode.Value.text("manual")
                ));
                assertEquals("manual", input(model).value());
                assertTrue(choices(model).findAny().isEmpty());
            }
        }
    }

    @Test
    @DisplayName("错误正文引用与跨 owner 回填目标均不显示动作")
    void rejectsUnsafeDeclarations() throws Exception {
        for (var source : List.of(source("demo.value", true), source("other.value", false))) {
            try (var model = model(new AtomicReference<>(List.of(source)), () -> response())) {
                assertTrue(nodes(model).filter(DesktopUiNode.Button.class::isInstance)
                        .map(DesktopUiNode.Button.class::cast).noneMatch(node -> node.label().key().equals("action.get")));
            }
        }
    }

    @Test
    @DisplayName("请求期间重新加载配置或撤回来源会丢弃迟到候选")
    void discardsStaleResponses() throws Exception {
        for (boolean withdraw : List.of(false, true)) {
            var sources = new AtomicReference<>(List.of(source("demo.value", false)));
            var started = new CountDownLatch(1);
            var finish = new CountDownLatch(1);
            try (var model = model(sources, () -> {
                started.countDown();
                assertTrue(finish.await(5, TimeUnit.SECONDS));
                return response();
            })) {
                activate(model, button(model).id());
                assertTrue(started.await(5, TimeUnit.SECONDS));
                if (withdraw) sources.set(List.of());
                else {
                    model.loadConfiguration();
                    model.rebuild();
                }
                finish.countDown();
                await(model);
                assertTrue(choices(model).findAny().isEmpty());
                if (!withdraw) assertEquals("initial", input(model).value());
            } finally { finish.countDown(); }
        }
    }

    static ComposeDesktopUiModel model(AtomicReference<List<DesktopUiPluginSnapshot>> sources,
                                               Response response) {
        DesktopUiHost.ConfigFile file = new DesktopUiHost.ConfigFile() {
            public Map<String, String> readAll(Collection<String> keys) { return Map.of("demo.value", "initial"); }
            public void writeAll(Map<String, String> values) { fail("selection must not save"); }
            public void removeAll(Collection<String> keys) { fail("selection must not remove"); }
            public DesktopUiHost.ConfigSnapshot snapshot() { return new DesktopUiHost.ConfigSnapshot(false, List.of()); }
            public void restore(DesktopUiHost.ConfigSnapshot snapshot) { fail("unexpected restore"); }
        };
        return DesktopConfigurationControllerTest.model(new HashMap<>(), Map.of(
                "pluginConfig", args -> file,
                "guiPostJson", args -> {
                    try { return response.get(); } catch (Exception e) { throw new AssertionError(e); }
                }), sources::get);
    }

    static DesktopUiPluginSnapshot source(String target, boolean unsafe) {
        return source(target, unsafe, GuiConfigSectionLayout.FIELD_LIST);
    }

    private static DesktopUiPluginSnapshot source(String target, boolean unsafe, GuiConfigSectionLayout layout) {
        var field = new GuiConfigFieldContribution("demo.value", "demo", "field.value",
                GuiConfigFieldType.STRING, "initial", 1);
        var action = new GuiConfigActionContribution("demo.get", "action.get", "", "demo",
                null, "demo-get", 10_000, 1, List.of(new GuiConfigActionPayloadField("value", "demo.value")),
                "", List.of(new GuiConfigActionResultRule("notice.result", "demo", 1, List.of(),
                unsafe ? List.of(GuiConfigActionResultArgument.json("error")) : List.of())),
                GuiConfigActionResultSummary.allItems("items", "id", "").selectInto(target));
        var section = new GuiConfigSectionContribution(
                "demo.settings",
                "demo",
                "",
                "",
                "demo",
                layout,
                1,
                List.of(new GuiConfigFieldLayoutContribution(
                        "demo.value",
                        layout == GuiConfigSectionLayout.CARD_SWITCHER ? "demo.card" : null,
                        "",
                        1
                )),
                List.of(action),
                List.of()
        );
        return new DesktopUiPluginSnapshot("demo", false, "demo", 1, false, "demo", "plugin.name",
                List.of(), List.of(new GuiConfigContribution(List.of(), List.of(field), List.of(section))),
                List.of(), List.of(WebRouteContribution.gui("/api/gui/demo-get")), List.of());
    }

    static DesktopUiHost.GuiResponse response() {
        return new DesktopUiHost.GuiResponse(true, 200, DesktopUiHost.GuiValue.of(Map.of("items",
                java.util.stream.IntStream.range(0, 35).mapToObj(i -> Map.of("id", "test/" + i + "+测试")).toList())), "", false);
    }

    private static DesktopUiNode.Button button(ComposeDesktopUiModel model) {
        return nodes(model).filter(DesktopUiNode.Button.class::isInstance).map(DesktopUiNode.Button.class::cast)
                .filter(node -> node.label().key().equals("action.get")).findFirst().orElseThrow();
    }

    private static DesktopUiNode.TextInput input(ComposeDesktopUiModel model) {
        return nodes(model).filter(DesktopUiNode.TextInput.class::isInstance).map(DesktopUiNode.TextInput.class::cast)
                .filter(node -> node.bindingId().equals("config.demo.demo.value")).findFirst().orElseThrow();
    }

    private static Stream<DesktopUiNode.Choice> choices(ComposeDesktopUiModel model) {
        return nodes(model).filter(DesktopUiNode.Choice.class::isInstance).map(DesktopUiNode.Choice.class::cast)
                .filter(node -> node.label().key().equals("action.get"));
    }

    private static Stream<DesktopUiNode> nodes(ComposeDesktopUiModel model) {
        return model.snapshot().document().pages().stream().flatMap(page -> descendants(page.content()));
    }

    private static Stream<DesktopUiNode> fieldNodes(ComposeDesktopUiModel model) {
        return nodes(model).filter(DesktopUiNode.Form.class::isInstance).map(DesktopUiNode.Form.class::cast)
                .flatMap(form -> form.rows().stream()).filter(row -> row.id().equals("config.demo.demo.value.row"))
                .flatMap(row -> descendants(row.content()));
    }

    private static Stream<DesktopUiNode> descendants(DesktopUiNode node) {
        return Stream.concat(Stream.of(node), node.childNodes().stream().flatMap(DesktopConfigurationActionTest::descendants));
    }

    private static void activate(ComposeDesktopUiModel model, String id) {
        dispatch(model, new DesktopUiNode.Event(DesktopUiNode.EventType.ACTIVATE, id, DesktopUiNode.Value.empty()));
    }

    private static void select(ComposeDesktopUiModel model, String id, String value) {
        dispatch(model, new DesktopUiNode.Event(DesktopUiNode.EventType.SELECTION, id, DesktopUiNode.Value.selection(value)));
    }

    private static void dispatch(ComposeDesktopUiModel model, DesktopUiNode.Event event) {
        synchronized (model) { model.dispatch(model.snapshot(), event); }
    }

    private static void await(ComposeDesktopUiModel model) {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            while (model.busy() || nodes(model).filter(DesktopUiNode.Button.class::isInstance)
                    .map(DesktopUiNode.Button.class::cast)
                    .anyMatch(node -> node.label().key().equals("action.get") && !node.enabled())) {
                Thread.sleep(10);
            }
        });
    }

    interface Response { DesktopUiHost.GuiResponse get() throws Exception; }
}
