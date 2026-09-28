package top.sywyar.pixivdownload.guicompose.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiText;
import top.sywyar.pixivdownload.plugin.api.gui.media.DesktopMediaTool;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class DesktopMediaToolsControllerTest {
    @Test
    @DisplayName("失败文件按页展示，翻页可查看全部身份")
    void failuresCanBePaged() throws Exception {
        var fixture = new Fixture();
        fixture.state.set(new DesktopMediaTool.Status("completed", 25, 25, 25,
                java.util.stream.LongStream.rangeClosed(1, 25)
                        .mapToObj(id -> new DesktopMediaTool.Failure(id, 0)).toList()));
        try (var model = DesktopConfigurationControllerTest.model(new HashMap<>(), Map.of(
                "mediaTools", arguments -> fixture.tools.get(),
                "mediaTool", arguments -> fixture.source))) {
            await(() -> nodes(model).anyMatch(value -> value.id().equals("media.sample.1.files")));
            assertEquals(10, ((DesktopUiNode.Text) node(model, "media.sample.1.files")).text().fallback().lines().count());
            activate(model, "media.sample.1.next");
            activate(model, "media.sample.1.next");
            assertEquals("21 / 0\n22 / 0\n23 / 0\n24 / 0\n25 / 0",
                    ((DesktopUiNode.Text) node(model, "media.sample.1.files")).text().fallback());
            assertFalse(button(model, "media.sample.1.next").enabled());
            fixture.state.set(new DesktopMediaTool.Status("completed", 2, 2, 2,
                    List.of(new DesktopMediaTool.Failure(80, 1), new DesktopMediaTool.Failure(90, 2))));
            activate(model, "media.sample.1.refresh");
            await(() -> ((DesktopUiNode.Text) node(model, "media.sample.1.files")).text().fallback().equals("80 / 1\n90 / 2"));
            assertEquals("1 / 1", ((DesktopUiNode.Text) node(model, "media.sample.1.page")).text().fallback());
            assertFalse(button(model, "media.sample.1.previous").enabled());
            assertFalse(button(model, "media.sample.1.next").enabled());
            fixture.state.set(new DesktopMediaTool.Status("completed", 2, 2, 0, List.of()));
            activate(model, "media.sample.1.refresh");
            await(() -> nodes(model).noneMatch(value -> value.id().equals("media.sample.1.files")));
        }
    }

    @Test
    @DisplayName("预览按页保留文件提示与总数，重新预览和空结果不沿用旧页")
    void previewPagesRetainDetailsAndReset() throws Exception {
        var fixture = new Fixture();
        fixture.files.set(java.util.stream.IntStream.rangeClosed(1, 25)
                .mapToObj(id -> new DesktopMediaTool.Item(id, 0, "file-" + id + ".png", List.of("webp"), true)).toList());
        try (var model = DesktopConfigurationControllerTest.model(new HashMap<>(), Map.of(
                "mediaTools", arguments -> fixture.tools.get(),
                "mediaTool", arguments -> fixture.source))) {
            String prefix = "media.sample.1";
            activate(model, prefix + ".preview");
            await(() -> button(model, prefix + ".start").enabled());
            assertEquals(List.of("25", "25"), ((DesktopUiNode.Text) node(model, prefix + ".ready")).text().arguments());
            assertEquals(10, ((DesktopUiNode.Text) node(model, prefix + ".files")).text().fallback().lines().count());
            activate(model, prefix + ".next");
            activate(model, prefix + ".next");
            var files = ((DesktopUiNode.Text) node(model, prefix + ".files")).text().fallback();
            assertTrue(files.startsWith("21 / 0  file-21.png\n"));
            assertTrue(files.endsWith("25 / 0  file-25.png"));
            assertEquals(5, files.lines().count());
            assertEquals("3 / 3", ((DesktopUiNode.Text) node(model, prefix + ".page")).text().fallback());
            assertEquals(List.of("WEBP"), ((DesktopUiNode.Text) node(model, prefix + ".file.4.formats")).text().arguments());
            assertEquals("media.tools.thumbnail", ((DesktopUiNode.Text) node(model, prefix + ".file.4.thumbnail")).text().key());
            assertTrue(nodes(model).noneMatch(value -> value.id().equals(prefix + ".file.5.formats")));
            assertFalse(button(model, prefix + ".next").enabled());
            activate(model, prefix + ".previous");
            assertTrue(((DesktopUiNode.Text) node(model, prefix + ".files")).text().fallback().startsWith("11 / 0"));
            fixture.files.set(fixture.files.get().subList(0, 1));
            activate(model, prefix + ".preview");
            await(() -> button(model, prefix + ".start").enabled());
            assertEquals("1 / 1", ((DesktopUiNode.Text) node(model, prefix + ".page")).text().fallback());
            assertFalse(button(model, prefix + ".previous").enabled());
            fixture.files.set(List.of());
            activate(model, prefix + ".preview");
            await(() -> button(model, prefix + ".preview").enabled());
            assertFalse(button(model, prefix + ".start").enabled());
            assertTrue(nodes(model).noneMatch(value -> value.id().equals(prefix + ".files")));
            assertEquals("media.tools.empty", ((DesktopUiNode.Text) node(model, prefix + ".ready")).text().key());
        }
    }

    @Test
    @DisplayName("内嵌媒体表单显示预览、主动开始和取消，修改格式使旧预览失效")
    void nativeWorkflowAndInvalidation() {
        var fixture = new Fixture();
        try (var model = DesktopConfigurationControllerTest.model(new HashMap<>(), Map.of(
                "mediaTools", arguments -> fixture.tools.get(),
                "mediaTool", arguments -> { assertEquals(fixture.tool.identity(), arguments[0]); return fixture.source; },
                "openExternalUri", arguments -> { fail("Native tool must not open a browser"); return null; }))) {
            String prefix = "media.sample.1";
            assertTrue(node(model, prefix + ".images") instanceof DesktopUiNode.Choice);
            var help = ((DesktopUiNode.Text) node(model, prefix + ".help")).text();
            assertEquals("batch", help.namespace());
            assertEquals("media.tools.help", help.key());
            assertEquals("media.tools.help", help.fallback());
            assertTrue(help.arguments().isEmpty());
            assertFalse(button(model, prefix + ".start").enabled());
            activate(model, prefix + ".preview");
            await(() -> button(model, prefix + ".start").enabled());
            assertEquals(0, fixture.starts.get());
            synchronized (model) {
                model.dispatch(model.snapshot(), new DesktopUiNode.Event(DesktopUiNode.EventType.SELECTION,
                        prefix + ".images", DesktopUiNode.Value.selections(List.of("png", "webp"))));
            }
            assertFalse(button(model, prefix + ".start").enabled());
            activate(model, prefix + ".preview");
            await(() -> button(model, prefix + ".start").enabled());
            assertEquals("png,webp", fixture.request.get().imageFormats());
            activate(model, prefix + ".start");
            await(() -> fixture.starts.get() == 1 && button(model, prefix + ".cancel").enabled());
            activate(model, prefix + ".cancel");
            await(() -> fixture.state.get().state().equals("cancelled"));
            activate(model, prefix + ".refresh");
            await(() -> button(model, prefix + ".preview").enabled());
            activate(model, prefix + ".check");
            await(() -> nodes(model).anyMatch(value -> value.id().equals(prefix + ".cap.png.result")));
            fixture.tools.set(List.of());
            model.rebuild();
            assertFalse(nodes(model).anyMatch(value -> value.id().startsWith(prefix)));
        } catch (Exception failure) { throw new AssertionError(failure); }
    }

    static final class Fixture {
        final DesktopMediaTool tool = new DesktopMediaTool(new DesktopMediaTool.Identity("sample", "sample", 1, 1),
                new DesktopMediaTool.Description(DesktopUiText.raw("Media processing"), "batch", "original", "webp"));
        final AtomicReference<List<DesktopMediaTool>> tools = new AtomicReference<>(List.of(tool));
        final AtomicInteger starts = new AtomicInteger();
        final AtomicReference<DesktopMediaTool.Request> request = new AtomicReference<>();
        final AtomicReference<List<DesktopMediaTool.Item>> files = new AtomicReference<>(List.of(
                new DesktopMediaTool.Item(42, 0, "source.jpg", List.of("png"), true)));
        final AtomicReference<DesktopMediaTool.Status> state = new AtomicReference<>(new DesktopMediaTool.Status("idle", 0, 0, 0, List.of()));
        final DesktopMediaTool.Source source = new DesktopMediaTool.Source() {
            public DesktopMediaTool.Description description() { return tool.description(); }
            public DesktopMediaTool.Result<DesktopMediaTool.Preview> preview(DesktopMediaTool.Request value) {
                request.set(value);
                return new DesktopMediaTool.Result<>(new DesktopMediaTool.Preview("confirmed-preview", files.get(), files.get().size(), 0, false), null);
            }
            public DesktopMediaTool.Result<DesktopMediaTool.Status> start(String token) {
                assertEquals("confirmed-preview", token);
                starts.incrementAndGet();
                state.set(new DesktopMediaTool.Status("running", 1, 0, 0, List.of()));
                return new DesktopMediaTool.Result<>(state.get(), null);
            }
            public DesktopMediaTool.Status status() { return state.get(); }
            public void cancel() { state.set(new DesktopMediaTool.Status("cancelled", 1, 0, 0, List.of())); }
            public DesktopMediaTool.Result<DesktopMediaTool.Report> capabilities() {
                return new DesktopMediaTool.Result<>(new DesktopMediaTool.Report("ffmpeg", "system", List.of(new DesktopMediaTool.Capability("png", true))), null);
            }
        };
    }
    private static void activate(ComposeDesktopUiModel model, String id) {
        synchronized (model) {
            model.dispatch(model.snapshot(), new DesktopUiNode.Event(DesktopUiNode.EventType.ACTIVATE, id, DesktopUiNode.Value.empty()));
        }
    }
    private static void change(ComposeDesktopUiModel model, String id, DesktopUiNode.Value value) {
        synchronized (model) {
            model.dispatch(model.snapshot(), new DesktopUiNode.Event(DesktopUiNode.EventType.CHANGE, id, value));
        }
    }
    private static DesktopUiNode node(ComposeDesktopUiModel model, String id) {
        return nodes(model).filter(value -> value.id().equals(id)).findFirst().orElseThrow();
    }
    private static DesktopUiNode.Button button(ComposeDesktopUiModel model, String id) { return (DesktopUiNode.Button) node(model, id); }
    static Stream<DesktopUiNode> nodes(ComposeDesktopUiModel model) {
        return model.snapshot().document().pages().stream().filter(page -> page.id().equals("tools")).flatMap(page -> descendants(page.content()));
    }
    private static Stream<DesktopUiNode> descendants(DesktopUiNode node) {
        return Stream.concat(Stream.of(node), node.childNodes().stream().flatMap(DesktopMediaToolsControllerTest::descendants));
    }
    private static void await(java.util.function.BooleanSupplier condition) {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            while (!condition.getAsBoolean()) Thread.sleep(10);
        });
    }
}
