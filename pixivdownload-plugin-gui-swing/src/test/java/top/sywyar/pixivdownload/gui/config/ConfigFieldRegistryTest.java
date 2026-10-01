package top.sywyar.pixivdownload.gui.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.guiswing.SwingHost;
import top.sywyar.pixivdownload.plugin.api.gui.*;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigFieldRegistryTest {
    @Test
    @DisplayName("宿主配置的分组、标签、帮助和枚举从宿主解析，不使用界面插件的同名资源")
    void resolvesCoreFieldTextFromItsOwner() {
        var messages = Map.of(
                "gui.config.group.download", "Host group",
                "gui.config.field.ffmpeg.max-concurrent.label", "Host field",
                "host.help", "Host help",
                "host.option", "Host option");
        var field = new GuiConfigFieldContribution(
                "sample.mode", "sample", "gui.config.field.ffmpeg.max-concurrent.label", "host.help", null,
                GuiConfigFieldType.ENUM, "one", 1, false, GuiConfigEffect.BACKEND_RESTART,
                List.of("one"), List.of(), List.of(), null, null, true, Map.of("one", "host.option"));
        var host = (DesktopUiHost) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{DesktopUiHost.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "coreConfigGroups" -> List.of(new GuiConfigGroupContribution("sample", "gui.config.group.download", 1));
                    case "coreConfigFields" -> List.of(field);
                    case "message" -> messages.get(args[0]);
                    case "resolveLocale" -> {
                        var locale = new DesktopUiHost.UiLocale("en-US", "English", "en");
                        yield new DesktopUiHost.UiLocaleResolution(locale, List.of(locale));
                    }
                    default -> throw new AssertionError(method.getName());
                });
        SwingHost.install(new DesktopUiContext(false, 8080, ".", Path.of("config.yaml"), "gui-swing", host,
                List.of(), List::of, text -> text.fallback(), () -> "system"));
        var snapshot = ConfigFieldRegistry.snapshot();
        assertThat(snapshot.groups()).containsExactly("Host group");
        assertThat(snapshot.fields()).singleElement().satisfies(actual -> {
            assertThat(actual.label()).isEqualTo("Host field");
            assertThat(actual.helpText()).isEqualTo("Host help");
            assertThat(actual.enumValueLabels()).containsEntry("one", "Host option");
            assertThat(actual.pluginContributed()).isFalse();
        });
    }
}
