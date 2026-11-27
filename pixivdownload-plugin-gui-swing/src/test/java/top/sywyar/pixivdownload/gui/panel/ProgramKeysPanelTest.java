package top.sywyar.pixivdownload.gui.panel;

import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.guiswing.SwingHost;
import top.sywyar.pixivdownload.plugin.api.gui.*;

import javax.swing.*;
import java.awt.Component;
import java.awt.Container;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class ProgramKeysPanelTest {
    private static final String KEY = "4f1c2b8e-1d2a-4c55-9a77-3e2f6f2a9b10";

    @Test void createOpensTheFullPageEditorAndRequiresASource() throws Exception {
        var fixture = loadedPanel("example:read", true);
        SwingUtilities.invokeAndWait(() -> {
            var panel = fixture.panel();
            assertTrue(children(panel).anyMatch(c -> c instanceof JLabel label && label.getText() != null && label.getText().contains("empty.description")));
            named(panel, "program-keys.create").doClick(0);
            assertEquals("editor", fixture.page().get());
            var editor = panel.editorPage();
            assertTrue(children(editor).anyMatch(c -> c instanceof JButton group && "program-keys.group".equals(group.getName())
                    && "Legacy Plugin".equals(group.getText())));
            assertTrue(children(editor).anyMatch(c -> c instanceof JCheckBox box && box.getText().equals("Read items")));
            assertTrue(children(editor).anyMatch(JTextField.class::isInstance));
            named(editor, "program-keys.review").doClick(0);
            assertTrue(children(editor).anyMatch(c -> c instanceof JLabel label && label.getText() != null
                    && label.getText().contains("source.required")));
            text(editor, "cancel").doClick(0);
            assertEquals("overview", fixture.page().get());
        });
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"example:read,true", "core:api-reference.read,true", "core:api-reference.read,false"})
    void reviewSummarisesTheDraftAndReturningKeepsIt(String grant, boolean addressAvailable) throws Exception {
        var fixture = loadedPanel(grant, addressAvailable);
        SwingUtilities.invokeAndWait(() -> {
            var panel = fixture.panel();
            named(panel, "program-keys.create").doClick(0);
            var editor = panel.editorPage();
            children(editor).filter(JTextField.class::isInstance).map(JTextField.class::cast).findFirst().orElseThrow()
                    .setText("worker");
            children(editor).filter(c -> c instanceof JRadioButton radio && radio.getText().equals("source.any"))
                    .map(JRadioButton.class::cast).findFirst().orElseThrow().doClick(0);
            var permission = children(editor).filter(c -> c instanceof JCheckBox box && box.getText().equals("Read items"))
                    .map(JCheckBox.class::cast).findFirst().orElseThrow();
            permission.setSelected(true);
            named(editor, "program-keys.review").doClick(0);
            assertTrue(children(editor).anyMatch(c -> c instanceof JLabel label && label.getText() != null
                    && label.getText().contains("Legacy Plugin")));
            assertTrue(children(editor).anyMatch(c -> c instanceof JLabel label && label.getText() != null
                    && label.getText().contains("Read items")));
            assertFalse(children(editor).anyMatch(c -> c == permission));
            text(editor, "review.back").doClick(0);
            assertTrue(children(editor).anyMatch(c -> c == permission));
            assertTrue(permission.isSelected());
            assertEquals("worker", children(editor).filter(JTextField.class::isInstance).map(JTextField.class::cast)
                    .findFirst().orElseThrow().getText());
            named(editor, "program-keys.review").doClick(0);
            named(editor, "program-keys.save").doClick(0);
        });
        assertTrue(fixture.created().await(5, TimeUnit.SECONDS));
        SwingUtilities.invokeAndWait(() -> {
            var editor = fixture.panel().editorPage();
            if (addressAvailable) {
                named(editor, "program-keys.copy-instructions").doClick(0);
                String key = grant.equals("core:api-reference.read") ? "created.instructions" : "instructions.restricted";
                assertEquals(key + " https://studio.example.test:7443/api/openapi.json", fixture.clipboard().get());
            } else {
                assertFalse(children(editor).anyMatch(c -> "program-keys.copy-instructions".equals(c.getName())));
            }
            var verify = children(editor).filter(c -> "program-keys.verify".equals(c.getName())).findFirst();
            assertEquals(addressAvailable, verify.isPresent());
            verify.ifPresent(commands -> assertTrue(((JTextArea) commands).getText()
                    .contains("Bearer $PIXIV_PROGRAM_KEY\" https://studio.example.test:7443/api/security/program-keys/self")));
            assertFalse(verify.map(commands -> ((JTextArea) commands).getText().contains("test-secret")).orElse(false));
            text(editor, "copy").doClick(0);
            assertEquals("test-secret", fixture.clipboard().get());
        });
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"core:api-reference.read,created.instructions", "example:read,instructions.restricted"})
    void existingKeyOffersAPromptMatchingItsCurrentPermissions(String grant, String expected) throws Exception {
        var row = Map.<String, Object>of("id", KEY, "revision", 3, "name", "agent", "permissionCount", 1, "anyIp", true,
                "networks", List.of(), "createdAt", 0, "transport", "HTTPS_REQUIRED");
        var detail = Map.<String, Object>of("id", KEY, "revision", 3, "policy", Map.of("name", "agent",
                "permissions", List.of(grant), "anyIp", true, "networks", List.of()));
        var fixture = loadedPanel(grant, true, List.of(row), detail);
        SwingUtilities.invokeAndWait(() -> named(fixture.panel(), "program-keys.prompt").doClick(0));
        assertTrue(fixture.editor().await(5, TimeUnit.SECONDS));
        SwingUtilities.invokeAndWait(() -> {
            var editor = fixture.panel().editorPage();
            assertTrue(children(editor).noneMatch(c -> "program-keys.secret".equals(c.getName())));
            named(editor, "program-keys.copy-instructions").doClick(0);
            assertEquals(expected + " https://studio.example.test:7443/api/openapi.json", fixture.clipboard().get());
            text(editor, "done").doClick(0);
            assertEquals("overview", fixture.page().get());
        });
    }

    private record Fixture(ProgramKeysPanel panel, AtomicReference<String> page, CountDownLatch created,
                           AtomicReference<String> clipboard, CountDownLatch editor) {}

    private static Fixture loadedPanel(String grant, boolean addressAvailable) throws Exception {
        return loadedPanel(grant, addressAvailable, List.of(), Map.of());
    }

    private static Fixture loadedPanel(String grant, boolean addressAvailable, List<Map<String, Object>> keys,
                                       Map<String, Object> detail) throws Exception {
        var created = new CountDownLatch(1);
        var editorShown = new CountDownLatch(1);
        var clipboard = new AtomicReference<String>();
        var host = (DesktopUiHost) Proxy.newProxyInstance(ProgramKeysPanelTest.class.getClassLoader(), new Class<?>[]{DesktopUiHost.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("backendUri")) {
                        if (!addressAvailable) throw new IllegalStateException("Address unavailable");
                        return java.net.URI.create("https://studio.example.test:7443" + args[0]);
                    }
                    if (method.getName().equals("copyText")) {
                        clipboard.set((String) args[0]);
                        return null;
                    }
                    if (method.getName().equals("guiPostJson")) {
                        return new DesktopUiHost.GuiResponse(true, 201,
                                DesktopUiHost.GuiValue.of(Map.of("secret", "test-secret")), "", false);
                    }
                    assertEquals("guiGet", method.getName());
                    var catalog = List.of(Map.of("id", grant, "owner", "example",
                            "ownerLabel", "Legacy Plugin", "label", "Read items"));
                    Object body = switch ((String) args[0]) {
                        case "program-keys/permissions" -> catalog;
                        case "program-keys" -> keys;
                        default -> detail;
                    };
                    return new DesktopUiHost.GuiResponse(true, 200, DesktopUiHost.GuiValue.of(body), "", false);
                });
        SwingHost.install(new DesktopUiContext(false, 8080, ".", Path.of("config.yaml"), "swing", host,
                List.of(), List::of, token -> {
                    if (token.key().equals("created.title")) created.countDown();
                    if (!token.arguments().isEmpty()) return token.key() + " " + token.arguments().getFirst();
                    return token.fallback();
                }, () -> "system"));
        var ready = new CountDownLatch(1);
        var page = new AtomicReference<>("overview");
        var panel = new AtomicReference<ProgramKeysPanel>();
        SwingUtilities.invokeAndWait(() -> {
            panel.set(new ProgramKeysPanel(new ProgramKeysPanel.Navigator() {
                @Override public void showEditor() { page.set("editor"); editorShown.countDown(); }
                @Override public void showOverview() { page.set("overview"); }
            }));
            named(panel.get(), "program-keys.create").addPropertyChangeListener("enabled", event -> {
                if (Boolean.TRUE.equals(event.getNewValue())) ready.countDown();
            });
            named(panel.get(), "program-keys.refresh").doClick(0);
        });
        assertTrue(ready.await(5, TimeUnit.SECONDS));
        return new Fixture(panel.get(), page, created, clipboard, editorShown);
    }

    private static AbstractButton named(Container panel, String name) {
        return children(panel).filter(c -> c instanceof AbstractButton b && name.equals(b.getName()))
                .map(AbstractButton.class::cast).findFirst().orElseThrow();
    }

    private static JButton text(Container panel, String text) {
        return children(panel).filter(c -> c instanceof JButton b && text.equals(b.getText()))
                .map(JButton.class::cast).findFirst().orElseThrow();
    }

    private static Stream<Component> children(Container panel) {
        return Stream.of(panel.getComponents()).flatMap(child -> child instanceof Container container
                ? Stream.concat(Stream.of(child), children(container)) : Stream.of(child));
    }
}
