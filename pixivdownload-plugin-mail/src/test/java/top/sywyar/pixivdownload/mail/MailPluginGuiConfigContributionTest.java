package top.sywyar.pixivdownload.mail;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.mail.preset.MailPresetRegistry;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigActionContribution;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigContribution;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigFieldContribution;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigFieldLayoutContribution;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigFieldType;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigGroups;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigPresetContribution;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigSectionContribution;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigSectionLayout;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigSectionNoticeContribution;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigSectionNoticeStyle;

import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("mail 插件 GUI 配置贡献")
class MailPluginGuiConfigContributionTest {

    private final MailPlugin plugin = new MailPlugin();

    @Test
    @DisplayName("重复读取复用不可变邮件声明，语言切换不把翻译烘焙进贡献")
    void reusesImmutableLocaleIndependentContributions() {
        var contributions = plugin.guiConfigContributions();
        Locale previous = Locale.getDefault();
        try {
            for (Locale locale : List.of(Locale.US, Locale.SIMPLIFIED_CHINESE)) {
                Locale.setDefault(locale);
                assertThat(new MailPlugin().guiConfigContributions()).isSameAs(contributions);
                assertThat(contributions.get(0).fields()).allSatisfy(field -> {
                    assertThat(field.labelKey()).isEqualTo("gui.config.field." + field.key() + ".label");
                    assertThat(field.helpKey()).isEqualTo("gui.config.field." + field.key() + ".help");
                });
            }
        } finally {
            Locale.setDefault(previous);
        }
        assertThatThrownBy(contributions::clear).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(contributions.get(0).fields()::clear).isInstanceOf(UnsupportedOperationException.class);
        var presets = serviceSection().presets();
        assertThatThrownBy(presets::clear).isInstanceOf(UnsupportedOperationException.class);
        var preset = presets.stream().filter(value -> !value.values().isEmpty()).findFirst().orElseThrow();
        assertThatThrownBy(() -> preset.values().put("mail.host", "changed.invalid"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(presets.stream().map(GuiConfigPresetContribution::order).toList()).isSorted();
    }

    @Test
    @DisplayName("只贡献 mail 自己的通知字段")
    void contributesOnlyMailFields() {
        List<GuiConfigFieldContribution> fields = contributions().stream()
                .flatMap(contribution -> contribution.fields().stream())
                .toList();

        assertThat(fields).extracting(GuiConfigFieldContribution::key)
                .containsExactly(
                        "mail.enabled",
                        "mail.host",
                        "mail.port",
                        "mail.security",
                        "mail.username",
                        "mail.password",
                        "mail.from",
                        "mail.to",
                        "mail.socks-proxy",
                        "mail.subject-prefix");
        assertThat(fields).allSatisfy(field -> {
            assertThat(field.key()).startsWith("mail.");
            assertThat(field.groupId()).isEqualTo(GuiConfigGroups.NOTIFICATION);
            assertThat(field.i18nNamespace()).isEqualTo(MailPlugin.ID);
            assertThat(field.contributesGroupVisibility()).isTrue();
        });
    }

    @Test
    @DisplayName("邮件密码是唯一敏感密码字段")
    void passwordIsTheOnlySensitivePasswordField() {
        List<GuiConfigFieldContribution> sensitiveFields = contributions().stream()
                .flatMap(contribution -> contribution.fields().stream())
                .filter(GuiConfigFieldContribution::sensitive)
                .toList();

        assertThat(sensitiveFields).extracting(GuiConfigFieldContribution::key)
                .containsExactly("mail.password");
        assertThat(sensitiveFields).allSatisfy(field ->
                assertThat(field.type()).isEqualTo(GuiConfigFieldType.PASSWORD));
    }

    @Test
    @DisplayName("贡献通知服务顶部提示且不单独撑出通知分组")
    void contributesNotificationServiceNotice() {
        GuiConfigSectionContribution section = noticeSection();

        assertThat(section.groupId()).isEqualTo(GuiConfigGroups.NOTIFICATION);
        assertThat(section.i18nNamespace()).isEqualTo(MailPlugin.ID);
        assertThat(section.layout()).isEqualTo(GuiConfigSectionLayout.FIELD_LIST);
        assertThat(section.order()).isLessThan(serviceSection().order());
        assertThat(section.mergeable()).isTrue();
        assertThat(section.contributesGroupVisibility()).isFalse();
        assertThat(section.fieldLayouts()).isEmpty();
        assertThat(section.actions()).isEmpty();
        assertThat(section.presets()).isEmpty();
        assertThat(section.notices()).singleElement().satisfies(notice -> {
            GuiConfigSectionNoticeContribution resolved = (GuiConfigSectionNoticeContribution) notice;
            assertThat(resolved.noticeId()).isEqualTo("notification.service.concurrent");
            assertThat(resolved.textKey()).isEqualTo("gui.config.notification.hint");
            assertThat(resolved.i18nNamespace()).isEqualTo(MailPlugin.ID);
            assertThat(resolved.style()).isEqualTo(GuiConfigSectionNoticeStyle.HINT);
        });
    }

    @Test
    @DisplayName("贡献邮件卡片、预设与测试动作")
    void contributesMailCardPresetsAndActions() {
        GuiConfigSectionContribution section = serviceSection();

        assertThat(section.groupId()).isEqualTo(GuiConfigGroups.NOTIFICATION);
        assertThat(section.i18nNamespace()).isEqualTo(MailPlugin.ID);
        assertThat(section.layout()).isEqualTo(GuiConfigSectionLayout.CARD_SWITCHER);
        assertThat(section.layoutLabelKey()).isEqualTo("gui.config.notification.service.label");
        assertThat(section.layoutHelpKey()).isEqualTo("gui.config.notification.service.help");
        assertThat(section.presetLabelKey()).isEqualTo("gui.config.mail.preset.label");
        assertThat(section.presetHelpKey()).isEqualTo("gui.config.mail.preset.help");
        assertThat(section.mergeable()).isTrue();
        assertThat(section.contributesGroupVisibility()).isTrue();
        assertThat(section.fieldLayouts()).extracting(GuiConfigFieldLayoutContribution::cardId)
                .containsOnly("mail");
        assertThat(section.fieldLayouts()).allSatisfy(layout -> {
            assertThat(layout.fieldKey()).startsWith("mail.");
            assertThat(layout.cardLabelKey()).isEqualTo("gui.config.notification.service.mail");
            assertThat(layout.i18nNamespace()).isEqualTo(MailPlugin.ID);
        });
        assertThat(section.actions()).extracting(GuiConfigActionContribution::actionId)
                .containsExactly("mail.test", "mail.test-all");
        assertThat(section.actions()).extracting(GuiConfigActionContribution::cardId)
                .containsOnly("mail");
        assertThat(section.actions()).extracting(GuiConfigActionContribution::endpoint)
                .containsExactly("mail-test", "mail-test-all");
        assertThat(section.actions()).allSatisfy(action -> {
            assertThat(action.i18nNamespace()).isEqualTo(MailPlugin.ID);
            assertThat(action.sendingNoticeKey()).startsWith("gui.config.mail.");
            assertThat(action.resultRules()).isNotEmpty();
            assertThat(action.payloadFields()).allSatisfy(payload ->
                    assertThat(payload.fieldKey()).startsWith("mail."));
        });
        assertThat(section.presets()).hasSize(new MailPresetRegistry().all().size());
        assertThat(section.presets()).extracting(GuiConfigPresetContribution::cardId)
                .containsOnly("mail");
        assertThat(section.presets()).allSatisfy(preset -> {
            assertThat(preset.i18nNamespace()).isEqualTo(MailPlugin.ID);
            assertThat(preset.labelKey()).startsWith("mail.preset.name.");
            assertThat(preset.values().keySet()).allMatch(key -> key.startsWith("mail."));
        });
        assertThat(section.presets())
                .filteredOn(preset -> !preset.values().isEmpty())
                .allSatisfy(preset -> {
                    assertThat(preset.matchFieldKey()).isEqualTo("mail.host");
                    assertThat(preset.values().keySet()).isSubsetOf(Set.of(
                            "mail.host", "mail.port", "mail.security"));
                });
    }

    @Test
    @DisplayName("邮件 GUI 文案命名空间归 mail 插件所有")
    void ownsMailI18nNamespace() {
        assertThat(plugin.i18n()).singleElement().satisfies(i18n -> {
            assertThat(i18n.namespace()).isEqualTo(MailPlugin.ID);
            assertThat(i18n.baseName()).isEqualTo("i18n.web.mail");
        });
    }

    private GuiConfigSectionContribution serviceSection() {
        return contributions().stream()
                .flatMap(contribution -> contribution.sections().stream())
                .filter(section -> "notification.services".equals(section.sectionId()))
                .findFirst()
                .orElseThrow();
    }

    private GuiConfigSectionContribution noticeSection() {
        return contributions().stream()
                .flatMap(contribution -> contribution.sections().stream())
                .filter(section -> "notification.service.notice".equals(section.sectionId()))
                .findFirst()
                .orElseThrow();
    }

    private List<GuiConfigContribution> contributions() {
        return plugin.guiConfigContributions();
    }
}
