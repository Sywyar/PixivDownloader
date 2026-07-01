package top.sywyar.pixivdownload.notification;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.mail.MailConfig;
import top.sywyar.pixivdownload.mail.MailService;
import top.sywyar.pixivdownload.mail.TestMessageResolver;
import top.sywyar.pixivdownload.mail.template.MailTemplateRegistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

@DisplayName("邮件通知介质场景覆盖")
class MailNotificationSinkCoverageTest {

    private final MailNotificationSink sink = new MailNotificationSink(
            new MailTemplateRegistry(TestMessageResolver.INSTANCE),
            new MailService(new MailConfig(), TestMessageResolver.INSTANCE));

    @Test
    @DisplayName("每个通知场景都有中英文可渲染邮件模板")
    void everyScenarioRenderable() {
        assertThat(NotificationScenario.values()).isNotEmpty();
        for (NotificationScenario scenario : NotificationScenario.values()) {
            assertThatCode(() -> sink.verifyRenderable(scenario))
                    .as("mail 缺少场景 [%s] 的渲染资源", scenario.id())
                    .doesNotThrowAnyException();
        }
    }
}
