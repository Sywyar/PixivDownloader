package top.sywyar.pixivdownload.mail.template;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.mail.TestMessageResolver;
import top.sywyar.pixivdownload.mail.TestNotificationTemplates;
import top.sywyar.pixivdownload.notification.NotificationScenario;

import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    @DisplayName("未知模板 id 拒绝渲染")
    void unknownIdIsRejected() {
        assertThatThrownBy(() -> registry.render("does-not-exist", Locale.US, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does-not-exist");
    }
}
