package top.sywyar.pixivdownload.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.plugin.api.web.I18nContribution;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("AI 依赖插件的界面归属")
class AiPluginUiSlotContributionTest {
    @Test
    @DisplayName("模型配置保留自有资源，不再贡献翻译业务槽位")
    void settingsDeclareOwnedNamespace() {
        var plugin = new AiPlugin();
        assertThat(plugin.uiSlots()).isEmpty();
        assertThat(plugin.guiConfigContributions()).isNotEmpty();
        assertThat(plugin.i18n()).extracting(I18nContribution::namespace).contains("ai");
    }
}
