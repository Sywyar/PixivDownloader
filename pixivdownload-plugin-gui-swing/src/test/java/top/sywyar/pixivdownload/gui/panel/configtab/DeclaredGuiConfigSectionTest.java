package top.sywyar.pixivdownload.gui.panel.configtab;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.gui.config.*;
import top.sywyar.pixivdownload.guiswing.SwingHost;
import top.sywyar.pixivdownload.plugin.api.gui.*;

import javax.swing.*;
import java.awt.Component;
import java.awt.Container;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Swing 配置动作选择")
class DeclaredGuiConfigSectionTest {
    @Test
    @DisplayName("完整候选仅在明确选择时回填，草稿变化后不接受旧请求")
    void selectionUsesCurrentDraft() throws Exception {
        for (boolean stale : List.of(false, true)) {
            AtomicReference<String> value = new AtomicReference<>("initial");
            CountDownLatch completed = new CountDownLatch(1);
            DesktopUiHost host = (DesktopUiHost) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{DesktopUiHost.class}, (proxy, method, args) -> switch (method.getName()) {
                        case "message", "requireSafeConfigValue" -> args[0];
                        case "resolveLocale" -> {
                            var locale = new DesktopUiHost.UiLocale("en-US", "English", "_en");
                            yield new DesktopUiHost.UiLocaleResolution(locale, List.of(locale));
                        }
                        case "guiPostJson" -> {
                            if (!completed.await(5, TimeUnit.SECONDS)) throw new AssertionError("query timeout");
                            yield new DesktopUiHost.GuiResponse(true, 200, DesktopUiHost.GuiValue.of(Map.of("items",
                                    java.util.stream.IntStream.range(0, 35)
                                            .mapToObj(i -> Map.of("id", "test/" + i + "+中文")).toList())), "", false);
                        }
                        default -> throw new AssertionError(method.getName());
                    });
            SwingHost.install(new DesktopUiContext(false, 6999, ".", Path.of("config.yaml"), "gui-swing",
                    host, List.of(), List::of, text -> text.fallback(), () -> "system"));
            ConfigFieldSpec field = ConfigFieldSpec.builder("demo.value", "Value", FieldType.STRING, "Group")
                    .ownerPluginId("demo").groupId("demo").build();
            ConfigSectionContext context = (ConfigSectionContext) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{ConfigSectionContext.class}, (proxy, method, args) -> switch (method.getName()) {
                        case "newContentPanel" -> new JPanel();
                        case "desktopHost" -> host;
                        case "allFields" -> List.of(field);
                        case "findSpec" -> field;
                        case "currentFieldValue", "actionFieldValue" -> value.get();
                        case "setFieldValue" -> { value.set((String) args[1]); yield null; }
                        case "addFields", "showNotice", "updateEnabledStates", "resetScrollToTopOnFirstShow" -> null;
                        default -> throw new AssertionError(method.getName());
                    });
            var action = new GuiConfigActionSpec("demo.get", "Get", "", null, "demo-get", 10_000, 1,
                    List.of(new GuiConfigActionPayloadField("value", "demo.value")), "", List.of(),
                    GuiConfigActionResultSummary.allItems("items", "id", "").selectInto("demo.value"), "demo");
            var section = new GuiConfigSectionSpec("demo", "demo.settings", "demo", "Group", 1,
                    "", "", "", "", "", "", List.of(), GuiConfigSectionLayout.FIELD_LIST,
                    1, List.of(), List.of(action), List.of(), false, true);
            JComponent component = onEdt(() -> new DeclaredGuiConfigSection(context, "Group", List.of(section)).build());
            try {
                onEdt(() -> { component.addNotify(); return null; });
                JButton button = descendants(component).filter(JButton.class::isInstance).map(JButton.class::cast)
                        .filter(item -> item.getText().equals("Get")).findFirst().orElseThrow();
                JComboBox<?> choices = descendants(component).filter(JComboBox.class::isInstance)
                        .map(JComboBox.class::cast).findFirst().orElseThrow();
                onEdt(() -> { button.doClick(); return null; });
                if (stale) value.set("edited");
                completed.countDown();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (!onEdt(button::isEnabled) && System.nanoTime() < deadline) Thread.sleep(10);
                assertThat(onEdt(button::isEnabled)).isTrue();
                if (stale) {
                    assertThat(onEdt(choices::isVisible)).isFalse();
                    assertThat(value.get()).isEqualTo("edited");
                } else {
                    assertThat(onEdt(choices::getItemCount)).isEqualTo(35);
                    assertThat(onEdt(choices::getSelectedIndex)).isEqualTo(-1);
                    assertThat(value.get()).isEqualTo("initial");
                    onEdt(() -> { choices.setSelectedIndex(34); return null; });
                    assertThat(value.get()).isEqualTo("test/34+中文");
                }
            } finally {
                completed.countDown();
                onEdt(() -> { component.removeNotify(); return null; });
            }
        }
    }

    private static Stream<Component> descendants(Component component) {
        return Stream.concat(Stream.of(component), component instanceof Container container
                ? Arrays.stream(container.getComponents()).flatMap(DeclaredGuiConfigSectionTest::descendants)
                : Stream.empty());
    }

    private static <T> T onEdt(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action);
        SwingUtilities.invokeAndWait(task);
        return task.get();
    }
}
