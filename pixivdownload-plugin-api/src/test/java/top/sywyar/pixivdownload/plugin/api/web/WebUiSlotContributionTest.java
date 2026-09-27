package top.sywyar.pixivdownload.plugin.api.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("UI 槽位国际化资源声明")
class WebUiSlotContributionTest {
    @Test
    @DisplayName("原有构造方式不追加命名空间，显式声明仅规范首尾空白")
    void optionalNamespacePreservesExistingConstructors() {
        assertThat(new WebUiSlotContribution("demo", "settings-card", null, 0).i18nNamespace()).isEmpty();
        assertThat(new WebUiSlotContribution("demo", "settings-card", null, 0, Map.of()).i18nNamespace()).isEmpty();
        assertThat(new WebUiSlotContribution("demo", "settings-card", null, 0, Map.of(), null).i18nNamespace()).isEmpty();
        assertThat(new WebUiSlotContribution("demo", "settings-card", null, 0, Map.of(), " demo-ui ")
                .i18nNamespace()).isEqualTo("demo-ui");
    }
}
