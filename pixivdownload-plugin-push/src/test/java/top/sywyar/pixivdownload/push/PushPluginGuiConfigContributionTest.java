package top.sywyar.pixivdownload.push;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigActionContribution;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigActionPayloadField;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigContribution;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigFieldContribution;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigFieldLayoutContribution;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigFieldType;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigGroups;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigSectionContribution;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigSectionLayout;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigSectionNoticeContribution;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigSectionNoticeStyle;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("push 插件 GUI 配置贡献")
class PushPluginGuiConfigContributionTest {

    private static final Set<String> CHANNELS = Set.of(
            "bark",
            "dingtalk",
            "telegram",
            "feishu",
            "wecom",
            "pushplus",
            "serverchan",
            "webhook");
    private static final Set<String> SECRET_FIELDS = Set.of(
            "push.bark.device-key",
            "push.dingtalk.access-token",
            "push.dingtalk.secret",
            "push.telegram.bot-token",
            "push.feishu.webhook-key",
            "push.feishu.secret",
            "push.wecom.key",
            "push.pushplus.token",
            "push.serverchan.send-key",
            "push.webhook.url");

    private final PushPlugin plugin = new PushPlugin();

    @Test
    @DisplayName("只贡献 push 自己的通知字段")
    void contributesOnlyPushFields() {
        List<GuiConfigFieldContribution> fields = contributions().stream()
                .flatMap(contribution -> contribution.fields().stream())
                .toList();

        assertThat(fields).isNotEmpty();
        assertThat(fields).allSatisfy(field -> {
            assertThat(field.key()).startsWith("push.");
            assertThat(field.groupId()).isEqualTo(GuiConfigGroups.NOTIFICATION);
            assertThat(field.i18nNamespace()).isEqualTo(PushPlugin.ID);
            assertThat(field.contributesGroupVisibility()).isTrue();
        });
        assertThat(fields).extracting(GuiConfigFieldContribution::key)
                .contains("push.enabled")
                .doesNotContain("mail.enabled")
                .noneMatch(key -> key.startsWith("notification.scenario."));
    }

    @Test
    @DisplayName("推送凭证字段统一声明为敏感密码字段")
    void credentialFieldsAreSensitivePasswords() {
        List<GuiConfigFieldContribution> fields = contributions().stream()
                .flatMap(contribution -> contribution.fields().stream())
                .filter(field -> SECRET_FIELDS.contains(field.key()))
                .toList();

        assertThat(fields).hasSize(SECRET_FIELDS.size());
        assertThat(fields).extracting(GuiConfigFieldContribution::key)
                .containsExactlyInAnyOrderElementsOf(SECRET_FIELDS);
        assertThat(fields).allSatisfy(field -> {
            assertThat(field.type()).isEqualTo(GuiConfigFieldType.PASSWORD);
            assertThat(field.sensitive()).isTrue();
        });
    }

    @Test
    @DisplayName("贡献推送总开关 section 与各渠道卡片")
    void contributesMasterAndChannelSections() {
        List<GuiConfigSectionContribution> sections = contributions().stream()
                .flatMap(contribution -> contribution.sections().stream())
                .toList();
        GuiConfigSectionContribution notice = section(sections, "notification.service.notice");
        GuiConfigSectionContribution master = section(sections, "push.master");
        GuiConfigSectionContribution services = section(sections, "notification.services");

        assertThat(notice.groupId()).isEqualTo(GuiConfigGroups.NOTIFICATION);
        assertThat(notice.layout()).isEqualTo(GuiConfigSectionLayout.FIELD_LIST);
        assertThat(notice.order()).isLessThan(master.order());
        assertThat(notice.mergeable()).isTrue();
        assertThat(notice.contributesGroupVisibility()).isFalse();
        assertThat(notice.fieldLayouts()).isEmpty();
        assertThat(notice.notices()).singleElement().satisfies(item -> {
            GuiConfigSectionNoticeContribution resolved = (GuiConfigSectionNoticeContribution) item;
            assertThat(resolved.noticeId()).isEqualTo("notification.service.concurrent");
            assertThat(resolved.textKey()).isEqualTo("gui.config.notification.hint");
            assertThat(resolved.i18nNamespace()).isEqualTo(PushPlugin.ID);
            assertThat(resolved.style()).isEqualTo(GuiConfigSectionNoticeStyle.HINT);
        });
        assertThat(master.groupId()).isEqualTo(GuiConfigGroups.NOTIFICATION);
        assertThat(master.layout()).isEqualTo(GuiConfigSectionLayout.FIELD_LIST);
        assertThat(master.fieldLayouts()).extracting(GuiConfigFieldLayoutContribution::fieldKey)
                .containsExactly("push.enabled");
        assertThat(services.groupId()).isEqualTo(GuiConfigGroups.NOTIFICATION);
        assertThat(services.i18nNamespace()).isEqualTo(PushPlugin.ID);
        assertThat(services.layout()).isEqualTo(GuiConfigSectionLayout.CARD_SWITCHER);
        assertThat(services.layoutLabelKey()).isEqualTo("gui.config.notification.service.label");
        assertThat(services.layoutHelpKey()).isEqualTo("gui.config.notification.service.help");
        assertThat(services.mergeable()).isTrue();
        assertThat(services.contributesGroupVisibility()).isTrue();
        assertThat(services.fieldLayouts()).allSatisfy(layout -> {
            assertThat(layout.fieldKey()).startsWith("push.");
            assertThat(layout.cardId()).isIn(CHANNELS);
            assertThat(layout.cardLabelKey()).isEqualTo("gui.config.notification.service." + layout.cardId());
            assertThat(layout.i18nNamespace()).isEqualTo(PushPlugin.ID);
        });
        assertThat(services.fieldLayouts()).extracting(GuiConfigFieldLayoutContribution::cardId)
                .containsAll(CHANNELS);
    }

    @Test
    @DisplayName("每个推送渠道贡献当前渠道与全模板测试动作")
    void contributesChannelTestActions() {
        GuiConfigSectionContribution services = contributions().stream()
                .flatMap(contribution -> contribution.sections().stream())
                .filter(section -> "notification.services".equals(section.sectionId()))
                .findFirst()
                .orElseThrow();

        assertThat(services.actions()).hasSize(CHANNELS.size() * 2);
        assertThat(services.actions()).extracting(GuiConfigActionContribution::endpoint)
                .containsOnly("push-test", "push-test-all");
        assertThat(services.actions()).allSatisfy(action -> {
            assertThat(action.i18nNamespace()).isEqualTo(PushPlugin.ID);
            assertThat(action.cardId()).isIn(CHANNELS);
            assertThat(action.resultRules()).isNotEmpty();
            assertThat(action.sendingNoticeKey()).startsWith("gui.config.push.");
            assertThat(action.payloadFields()).first().satisfies(payload -> {
                GuiConfigActionPayloadField field = (GuiConfigActionPayloadField) payload;
                assertThat(field.payloadPath()).isEqualTo(action.cardId() + ".enabled");
                assertThat(field.fieldKey()).isNull();
                assertThat(field.literalValue()).isEqualTo("true");
            });
        });
        assertThat(services.actions().stream()
                .map(GuiConfigActionContribution::cardId)
                .collect(Collectors.toSet()))
                .containsExactlyInAnyOrderElementsOf(CHANNELS);
    }

    @Test
    @DisplayName("推送 GUI 文案命名空间归 push 插件所有")
    void ownsPushI18nNamespace() {
        assertThat(plugin.i18n()).singleElement().satisfies(i18n -> {
            assertThat(i18n.namespace()).isEqualTo(PushPlugin.ID);
            assertThat(i18n.baseName()).isEqualTo("i18n.web.push");
        });
    }

    private static GuiConfigSectionContribution section(List<GuiConfigSectionContribution> sections, String id) {
        return sections.stream()
                .filter(section -> id.equals(section.sectionId()))
                .findFirst()
                .orElseThrow();
    }

    private List<GuiConfigContribution> contributions() {
        return plugin.guiConfigContributions();
    }
}
