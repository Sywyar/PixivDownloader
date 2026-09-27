package top.sywyar.pixivdownload.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.plugin.api.web.I18nContribution;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("AI 设置槽位资源依赖")
class AiPluginUiSlotContributionTest {
    @Test
    @DisplayName("设置槽位声明插件拥有的命名空间，不依赖宿主硬编码资源清单")
    void settingsDeclareOwnedNamespace() {
        var plugin = new AiPlugin();
        assertThat(plugin.uiSlots()).filteredOn(slot -> slot.target().equals("settings-card"))
                .singleElement().satisfies(slot -> {
                    assertThat(slot.i18nNamespace()).isNotBlank();
                    assertThat(plugin.i18n()).extracting(I18nContribution::namespace)
                            .contains(slot.i18nNamespace());
                    assertThat(slot.metadata()).isEmpty();
                });
    }
}
