package top.sywyar.pixivdownload.plugin.api.gui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("桌面业务上下文")
class DesktopUiContextTest {

    @Test
    @DisplayName("启动与当前插件快照均不泄漏可变集合")
    void copiesPluginSnapshots() {
        DesktopUiPluginSnapshot plugin = plugin("fixture");
        List<DesktopUiPluginSnapshot> startup = new ArrayList<>(List.of(plugin));
        List<DesktopUiPluginSnapshot> current = new ArrayList<>(List.of(plugin));
        DesktopUiContext context = context(stubHost(new AtomicBoolean()), startup, () -> current, () -> "system");

        startup.clear();
        List<DesktopUiPluginSnapshot> observed = context.currentPluginSnapshots();
        current.clear();

        assertThat(context.startupPluginSnapshots()).containsExactly(plugin);
        assertThat(observed).containsExactly(plugin);
        assertThatThrownBy(observed::clear).isInstanceOf(UnsupportedOperationException.class);
        assertThat(context.currentPluginSnapshots()).isEmpty();
    }

    @Test
    @DisplayName("主题偏好规范化且退出请求委托宿主")
    void normalizesThemeAndDelegatesExit() {
        AtomicBoolean exitRequested = new AtomicBoolean();
        DesktopUiContext context = context(
                stubHost(exitRequested),
                List.of(),
                List::of,
                () -> "  DARK  "
        );

        context.requestApplicationExit();

        assertThat(context.themePreference()).isEqualTo("dark");
        assertThat(context.selectedProviderId()).isEqualTo("gui-compose");
        assertThat(context.resolveText(DesktopUiText.key("desktop.test"))).isEqualTo("desktop.test");
        assertThat(exitRequested).isTrue();
    }

    @Test
    @DisplayName("端口越界与空的当前快照都会失败关闭")
    void rejectsInvalidBusinessContext() {
        DesktopUiHost host = stubHost(new AtomicBoolean());
        assertThatThrownBy(() -> new DesktopUiContext(
                false,
                0,
                ".",
                Path.of("config.yaml"),
                host,
                List.of(),
                List::of,
                DesktopUiText::fallback,
                () -> "system"
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> context(host, List.of(), () -> null, () -> "system"))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("currentPlugins returned null");
    }

    private static DesktopUiContext context(
            DesktopUiHost host,
            List<DesktopUiPluginSnapshot> startup,
            java.util.function.Supplier<List<DesktopUiPluginSnapshot>> current,
            java.util.function.Supplier<String> theme
    ) {
        return new DesktopUiContext(
                false,
                6999,
                ".",
                Path.of("config.yaml"),
                "gui-compose",
                host,
                startup,
                current,
                text -> text.key().isBlank() ? text.fallback() : text.key(),
                theme
        );
    }

    private static DesktopUiPluginSnapshot plugin(String id) {
        return new DesktopUiPluginSnapshot(
                id,
                false,
                id,
                1L,
                false,
                null,
                "",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );
    }

    private static DesktopUiHost stubHost(AtomicBoolean exitRequested) {
        return (DesktopUiHost) Proxy.newProxyInstance(
                DesktopUiHost.class.getClassLoader(),
                new Class<?>[]{DesktopUiHost.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("requestApplicationExit")) {
                        exitRequested.set(true);
                        return null;
                    }
                    Class<?> type = method.getReturnType();
                    if (!type.isPrimitive()) return null;
                    if (type == boolean.class) return false;
                    if (type == char.class) return '\0';
                    return 0;
                }
        );
    }
}
