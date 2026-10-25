package top.sywyar.pixivdownload.gui.panel.configtab;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.gui.i18n.GuiMessages;
import top.sywyar.pixivdownload.guiswing.SwingHost;
import top.sywyar.pixivdownload.plugin.api.gui.*;
import top.sywyar.pixivdownload.plugin.api.web.*;

import javax.swing.*;
import java.awt.Component;
import java.awt.Container;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class PluginMarketConfigSectionTest {
    @Test
    @DisplayName("市场入口使用活动贡献，后台打开当前地址，撤回后点击不能继续打开")
    void usesCurrentNavigationAndControlledHost() throws Exception {
        var current = new AtomicReference<List<DesktopUiPluginSnapshot>>(List.of());
        var opened = new AtomicReference<URI>();
        var worker = new AtomicReference<Thread>();
        var opens = new AtomicInteger();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var host = (DesktopUiHost) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{DesktopUiHost.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "coreConfigGroups" -> List.of();
                    case "resolveLocale" -> {
                        var locale = new DesktopUiHost.UiLocale("en-US", "English", "en");
                        yield new DesktopUiHost.UiLocaleResolution(locale, List.of(locale));
                    }
                    case "openExternalUri" -> {
                        worker.set(Thread.currentThread());
                        opened.set((URI) args[0]);
                        opens.incrementAndGet();
                        entered.countDown();
                        if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("test wait expired");
                        yield null;
                    }
                    default -> throw new AssertionError(method.getName());
                });
        SwingHost.install(new DesktopUiContext(false, 8080, ".", Path.of("config.yaml"), "fixture-ui",
                host, List.of(), current::get, text -> text.fallback(), () -> "system"));
        var context = (ConfigSectionContext) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{ConfigSectionContext.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "newContentPanel" -> new JPanel();
                    case "allFields" -> List.of();
                    case "addFields", "resetScrollToTopOnFirstShow" -> null;
                    default -> throw new AssertionError(method.getName());
                });
        var config = (DesktopUiHost.ConfigFile) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{DesktopUiHost.ConfigFile.class}, (proxy, method, args) -> {
                    if (method.getName().equals("read")) return null;
                    throw new AssertionError(method.getName());
                });
        var section = new PluginMarketConfigSection(context, config, path -> "https://localhost:9443" + path);
        var button = new AtomicReference<JButton>();
        var navigation = new NavigationContribution("fixture-entry", NavigationPlacements.PLUGINS_SEGMENT,
                "fixture", "label", "/plugin-market.html", "store", AccessPolicy.ADMIN, 1);
        var snapshot = new DesktopUiPluginSnapshot("fixture-owner", false, "fixture-owner", 7, false,
                "fixture", "name", List.of(), List.of(), List.of(), List.of(), List.of(navigation));
        try {
            SwingUtilities.invokeAndWait(() -> {
                button.set(descendants(section.build()).filter(JButton.class::isInstance).map(JButton.class::cast)
                        .filter(item -> item.getText().equals(GuiMessages.get("gui.config.market.action.open-web")))
                        .findFirst().orElseThrow());
                section.afterEnabledStates();
                assertThat(button.get().isEnabled()).isFalse();
                current.set(List.of(snapshot));
                section.afterEnabledStates();
                assertThat(button.get().isEnabled()).isTrue();
                button.get().doClick();
            });
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(opened.get()).isEqualTo(URI.create("https://localhost:9443/plugin-market.html"));
            SwingUtilities.invokeAndWait(() -> {
                assertThat(Thread.currentThread()).isNotSameAs(worker.get());
                button.get().doClick();
                current.set(List.of());
                button.get().doClick();
                assertThat(button.get().isEnabled()).isFalse();
            });
            assertThat(opens.get()).isEqualTo(1);
        } finally {
            release.countDown();
            if (worker.get() != null) worker.get().join(5000);
        }
    }

    private static Stream<Component> descendants(Component component) {
        return Stream.concat(Stream.of(component), component instanceof Container container
                ? Arrays.stream(container.getComponents()).flatMap(PluginMarketConfigSectionTest::descendants)
                : Stream.empty());
    }
}
