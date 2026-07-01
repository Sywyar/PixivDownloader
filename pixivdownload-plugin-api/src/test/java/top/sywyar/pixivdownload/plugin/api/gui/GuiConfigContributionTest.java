package top.sywyar.pixivdownload.plugin.api.gui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("GUI 配置 contribution 纯数据契约")
class GuiConfigContributionTest {

    @Test
    @DisplayName("空列表与可选文本按纯数据模型安全归一化")
    void nullListsAndOptionalTextAreNormalized() {
        GuiConfigContribution contribution = new GuiConfigContribution(null, null);
        GuiConfigGroupContribution group = new GuiConfigGroupContribution("demo", "group.label", "  ", 10, true);
        GuiConfigFieldContribution field = new GuiConfigFieldContribution(
                "demo.token",
                GuiConfigGroups.NOTIFICATION,
                "field.label",
                null,
                "  ",
                GuiConfigFieldType.STRING,
                null,
                20,
                false,
                true,
                null,
                null,
                null,
                null,
                null);

        assertThat(contribution.groups()).isEmpty();
        assertThat(contribution.fields()).isEmpty();
        assertThat(group.i18nNamespace()).isNull();
        assertThat(field.helpKey()).isEmpty();
        assertThat(field.i18nNamespace()).isNull();
        assertThat(field.defaultValue()).isEmpty();
        assertThat(field.enumValues()).isEmpty();
        assertThat(field.enabledWhen()).isEmpty();
        assertThat(field.visibleWhen()).isEmpty();
    }

    @Test
    @DisplayName("字段元数据保留分组、类型、排序、敏感标记与条件")
    void fieldMetadataIsCarriedAsPureData() {
        GuiConfigFieldContribution field = new GuiConfigFieldContribution(
                "demo.mode",
                GuiConfigGroups.PLUGINS,
                "field.mode.label",
                "field.mode.help",
                "demo",
                GuiConfigFieldType.ENUM,
                "auto",
                30,
                true,
                false,
                List.of("auto", "manual"),
                List.of(GuiConfigCondition.isTrue("demo.enabled")),
                List.of(GuiConfigCondition.notBlank("demo.token")),
                1,
                10);

        assertThat(field.key()).isEqualTo("demo.mode");
        assertThat(field.groupId()).isEqualTo(GuiConfigGroups.PLUGINS);
        assertThat(field.type()).isEqualTo(GuiConfigFieldType.ENUM);
        assertThat(field.defaultValue()).isEqualTo("auto");
        assertThat(field.order()).isEqualTo(30);
        assertThat(field.sensitive()).isTrue();
        assertThat(field.requiresRestart()).isFalse();
        assertThat(field.enumValues()).containsExactly("auto", "manual");
        assertThat(field.enabledWhen()).containsExactly(
                new GuiConfigCondition("demo.enabled", GuiConfigConditionOperator.TRUE, null));
        assertThat(field.visibleWhen()).containsExactly(
                new GuiConfigCondition("demo.token", GuiConfigConditionOperator.NOT_BLANK, null));
        assertThat(field.minValue()).isEqualTo(1);
        assertThat(field.maxValue()).isEqualTo(10);
    }

    @Test
    @DisplayName("条件工厂只产生 key/operator/value 三元组")
    void conditionFactoriesCreatePureTriples() {
        assertThat(GuiConfigCondition.isFalse("a"))
                .isEqualTo(new GuiConfigCondition("a", GuiConfigConditionOperator.FALSE, null));
        assertThat(GuiConfigCondition.equalsTo("b", "x"))
                .isEqualTo(new GuiConfigCondition("b", GuiConfigConditionOperator.EQUALS, "x"));
        assertThat(GuiConfigCondition.notEqualsTo("c", "y"))
                .isEqualTo(new GuiConfigCondition("c", GuiConfigConditionOperator.NOT_EQUALS, "y"));
        assertThat(GuiConfigCondition.blank("d"))
                .isEqualTo(new GuiConfigCondition("d", GuiConfigConditionOperator.BLANK, null));
    }
}
