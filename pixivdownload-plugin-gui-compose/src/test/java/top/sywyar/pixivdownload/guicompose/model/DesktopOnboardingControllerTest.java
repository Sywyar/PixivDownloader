package top.sywyar.pixivdownload.guicompose.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiPluginSnapshot;
import top.sywyar.pixivdownload.plugin.api.gui.GuiOnboardingStepContribution;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

@DisplayName("Compose 首次引导")
class DesktopOnboardingControllerTest {
    @Test
    @DisplayName("界面显示的代理默认值同时写入动作读取的表单状态")
    void storesVisibleProxyDefaultsInFormState() {
        Map<String, String> values = new HashMap<>();

        DesktopOnboardingController.initializeProxyDefaults(values, "127.0.0.1", 7890);

        assertEquals("true", values.get("welcome.proxy.enabled"));
        assertEquals("127.0.0.1", values.get("welcome.proxy.host"));
        assertEquals("7890", values.get("welcome.proxy.port"));

        values.put("welcome.proxy.host", "proxy.example");
        DesktopOnboardingController.initializeProxyDefaults(values, "localhost", 8080);
        assertEquals("proxy.example", values.get("welcome.proxy.host"));
        assertEquals("7890", values.get("welcome.proxy.port"));
    }

    @Test
    @DisplayName("插件引导按贡献顺序选择且不依赖插件 ID")
    void selectsFirstGenericPluginContribution() {
        GuiOnboardingStepContribution later = guide("later", 20);
        GuiOnboardingStepContribution earlier = guide("earlier", 10);

        GuiOnboardingStepContribution selected = DesktopOnboardingController.firstGuideStep(List.of(
                source("plugin-a", later),
                source("plugin-b", earlier)
        ));

        assertSame(earlier, selected);
    }

    private static GuiOnboardingStepContribution guide(String id, int order) {
        return new GuiOnboardingStepContribution(
                id,
                "sample",
                "guide.title",
                "guide.body",
                List.of("guide.point"),
                "guide.open",
                "/guide.html",
                "guide.waiting",
                id,
                order
        );
    }

    private static DesktopUiPluginSnapshot source(
            String id,
            GuiOnboardingStepContribution guide
    ) {
        return new DesktopUiPluginSnapshot(
                id,
                false,
                id,
                1,
                false,
                null,
                "",
                List.of(),
                List.of(),
                List.of(guide),
                List.of(),
                List.of()
        );
    }
}
