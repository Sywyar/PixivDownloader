package top.sywyar.pixivdownload.mail.template;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.mail.TestMessageResolver;
import top.sywyar.pixivdownload.mail.TestNotificationTemplates;
import top.sywyar.pixivdownload.notification.NotificationScenario;
import top.sywyar.pixivdownload.i18n.MessageResolver;
import top.sywyar.pixivdownload.plugin.api.notification.ImmutableNotificationTemplateCatalog;
import top.sywyar.pixivdownload.plugin.api.notification.NotificationTemplateContribution;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("MailTemplateRegistry 单元测试")
class MailTemplateRegistryTest {

    private final MailTemplateRegistry registry = new MailTemplateRegistry(
            TestMessageResolver.INSTANCE,
            TestNotificationTemplates.catalog());

    @Test
    @DisplayName("邮件配置测试模板仍由 mail 插件本地渲染")
    void configSuccessRemainsMailOwned() throws Exception {
        RenderedMail rendered = registry.render(
                MailTemplateRegistry.TEMPLATE_CONFIG_SUCCESS,
                Locale.SIMPLIFIED_CHINESE,
                Map.of(
                        "username", "管理员",
                        "app_name", "PixivDownloader",
                        "smtp_host", "smtp.example.com",
                        "time", "2026-05-27 12:34:56"));

        assertThat(rendered.subject()).contains("邮件配置成功");
        assertThat(rendered.htmlBody())
                .contains("smtp.example.com")
                .doesNotContain("{{", "PHPSESSID");
    }

    @Test
    @DisplayName("业务通知从稳定目录取模板并只替换运行期占位符")
    void contributedTemplateIsRendered() throws Exception {
        String id = NotificationScenario.RUN_SUMMARY.id();
        RenderedMail rendered = registry.render(
                id,
                Locale.US,
                Map.of("task_name", "Example task", "completed", "5"));

        assertThat(rendered.subject()).contains("Notification").contains("5");
        assertThat(rendered.htmlBody()).contains("Example task").doesNotContain("{{");
        assertThat(registry.templates()).containsKeys(
                MailTemplateRegistry.TEMPLATE_CONFIG_SUCCESS, id);
    }

    @Test
    @DisplayName("本地模板先本地化再替换值且保留替换值中的特殊字符")
    void localTemplateUsesTwoPasses() throws Exception {
        MessageResolver messages = mock(MessageResolver.class);
        when(messages.get(Locale.US, "mail.template.mail-config-success.subject"))
                .thenReturn("{{i18n:greeting}} / {{missing}}");
        when(messages.get(Locale.US, "greeting")).thenReturn("Hello {{username}}");
        MailTemplateRegistry local = new MailTemplateRegistry(messages, TestNotificationTemplates.catalog());

        RenderedMail rendered = local.render(MailTemplateRegistry.TEMPLATE_CONFIG_SUCCESS, Locale.US,
                Map.of("username", "$1\\🌸 {{unexpanded}}"));

        assertThat(rendered.subject()).isEqualTo("Hello $1\\🌸 {{unexpanded}} / ");
    }

    @Test
    @DisplayName("业务模板不再本地化且区分缺失值与显式空值")
    void contributedTemplatePreservesLiteralValues() throws Exception {
        String template = "{{value}}|{{ missing }}|{{ nullable }}|{{i18n:foreign}}";
        MailTemplateRegistry contributed = new MailTemplateRegistry(TestMessageResolver.INSTANCE,
                new ImmutableNotificationTemplateCatalog(List.of(new NotificationTemplateContribution(
                        "sample", "mail", Locale.US, template, template))));
        Map<String, String> values = new HashMap<>();
        values.put("value", "$1\\🌸 {{nested}}");
        values.put("nullable", null);
        values.put("i18n:foreign", "runtime value");

        RenderedMail rendered = contributed.render("sample", Locale.US, values);

        assertThat(rendered.subject()).isEqualTo("$1\\🌸 {{nested}}||{{ nullable }}|runtime value");
        assertThat(rendered.htmlBody()).isEqualTo(rendered.subject());
    }

    @Test
    @DisplayName("未知模板 id 拒绝渲染")
    void unknownIdIsRejected() {
        assertThatThrownBy(() -> registry.render("does-not-exist", Locale.US, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does-not-exist");
    }
}
