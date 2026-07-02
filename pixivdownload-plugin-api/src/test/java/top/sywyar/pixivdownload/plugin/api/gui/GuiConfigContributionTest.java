package top.sywyar.pixivdownload.plugin.api.gui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("GUI 配置 contribution 纯数据契约")
class GuiConfigContributionTest {

    @Test
    @DisplayName("空列表与可选文本按纯数据模型安全归一化")
    void nullListsAndOptionalTextAreNormalized() {
        GuiConfigContribution contribution = new GuiConfigContribution(null, null, null);
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
        assertThat(contribution.sections()).isEmpty();
        assertThat(group.i18nNamespace()).isNull();
        assertThat(field.helpKey()).isEmpty();
        assertThat(field.i18nNamespace()).isNull();
        assertThat(field.defaultValue()).isEmpty();
        assertThat(field.enumValues()).isEmpty();
        assertThat(field.enabledWhen()).isEmpty();
        assertThat(field.visibleWhen()).isEmpty();
        assertThat(field.contributesGroupVisibility()).isTrue();
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
        assertThat(field.contributesGroupVisibility()).isTrue();
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

    @Test
    @DisplayName("section/action/preset/layout 元数据保持纯数据形态")
    void sectionActionPresetLayoutMetadataIsCarriedAsPureData() {
        GuiConfigFieldLayoutContribution layout = new GuiConfigFieldLayoutContribution(
                "demo.token", "main", "card.main", "  ", 20);
        GuiConfigSectionNoticeContribution notice = new GuiConfigSectionNoticeContribution(
                "demo.notice", "section.notice", "  ", null, 5);
        GuiConfigActionPayloadField payload = new GuiConfigActionPayloadField(
                "settings.enabled", "demo.enabled", GuiConfigActionPayloadType.BOOLEAN);
        GuiConfigActionContribution action = new GuiConfigActionContribution(
                "test",
                "action.test.label",
                "action.test.help",
                "demo",
                "demo-test",
                45_000,
                30,
                List.of(payload));
        GuiConfigPresetContribution preset = new GuiConfigPresetContribution(
                "default",
                "preset.default.label",
                "preset.default.help",
                "demo",
                40,
                "demo.endpoint",
                "https://example.test",
                Map.of("demo.endpoint", "https://example.test"));
        GuiConfigSectionContribution section = new GuiConfigSectionContribution(
                "demo.main",
                GuiConfigGroups.PLUGINS,
                "section.title",
                "section.help",
                "demo",
                "section.layout.label",
                "section.layout.help",
                "",
                "",
                List.of(notice),
                GuiConfigSectionLayout.CARD_SWITCHER,
                50,
                List.of(layout),
                List.of(action),
                List.of(preset));

        assertThat(layout.i18nNamespace()).isNull();
        assertThat(payload.valueType()).isEqualTo(GuiConfigActionPayloadType.BOOLEAN);
        assertThat(payload.literalValue()).isEmpty();
        assertThat(action.payloadFields()).containsExactly(payload);
        assertThat(action.cardId()).isNull();
        assertThat(action.sendingNoticeKey()).isEmpty();
        assertThat(action.resultRules()).isEmpty();
        assertThat(action.resultSummary()).isNull();
        assertThat(preset.values()).containsEntry("demo.endpoint", "https://example.test");
        assertThat(preset.cardId()).isNull();
        assertThat(section.sectionId()).isEqualTo("demo.main");
        assertThat(section.groupId()).isEqualTo(GuiConfigGroups.PLUGINS);
        assertThat(section.layout()).isEqualTo(GuiConfigSectionLayout.CARD_SWITCHER);
        assertThat(section.layoutLabelKey()).isEqualTo("section.layout.label");
        assertThat(section.presetLabelKey()).isEmpty();
        assertThat(section.notices()).containsExactly(notice);
        assertThat(section.mergeable()).isFalse();
        assertThat(section.contributesGroupVisibility()).isTrue();
        assertThat(notice.i18nNamespace()).isNull();
        assertThat(notice.style()).isEqualTo(GuiConfigSectionNoticeStyle.HINT);
        assertThat(section.fieldLayouts()).containsExactly(layout);
        assertThat(section.actions()).containsExactly(action);
        assertThat(section.presets()).containsExactly(preset);
    }

    @Test
    @DisplayName("action payload 支持声明字面量而不绑定配置字段")
    void actionPayloadCanUseLiteralValue() {
        GuiConfigActionPayloadField payload = GuiConfigActionPayloadField.literal(
                "channel.enabled", "true", GuiConfigActionPayloadType.BOOLEAN);

        assertThat(payload.payloadPath()).isEqualTo("channel.enabled");
        assertThat(payload.fieldKey()).isNull();
        assertThat(payload.literalValue()).isEqualTo("true");
        assertThat(payload.valueType()).isEqualTo(GuiConfigActionPayloadType.BOOLEAN);
    }

    @Test
    @DisplayName("action 结果规则与摘要声明保持纯数据归一化")
    void actionResultRulesArePureData() {
        GuiConfigActionResultCondition condition = GuiConfigActionResultCondition.jsonGreaterThan("succeeded", 0);
        GuiConfigActionResultArgument argument = GuiConfigActionResultArgument.summary();
        GuiConfigActionResultRule rule = new GuiConfigActionResultRule(
                "notice.partial", "  ", 20, List.of(condition), List.of(argument));
        GuiConfigActionResultSummary summary = GuiConfigActionResultSummary.nonSuccessItems(
                "results", "channel", "status", "OK", "detail");

        assertThat(condition.source()).isEqualTo(GuiConfigActionResultSource.JSON);
        assertThat(condition.operator()).isEqualTo(GuiConfigActionResultOperator.GREATER_THAN);
        assertThat(condition.value()).isEqualTo("0");
        assertThat(argument.source()).isEqualTo(GuiConfigActionResultSource.SUMMARY);
        assertThat(rule.i18nNamespace()).isNull();
        assertThat(rule.conditions()).containsExactly(condition);
        assertThat(rule.arguments()).containsExactly(argument);
        assertThat(summary.arrayPath()).isEqualTo("results");
        assertThat(summary.statusPath()).isEqualTo("status");
        assertThat(summary.successStatus()).isEqualTo("OK");
    }
}
