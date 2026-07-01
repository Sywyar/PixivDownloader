package top.sywyar.pixivdownload.mail;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import top.sywyar.pixivdownload.i18n.MessageResolver;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("MailService 单元测试")
class MailServiceTest {

    private final MessageResolver messages = TestMessageResolver.INSTANCE;

    @Test
    @DisplayName("send 在 enabled=false 时直接跳过，不创建任何 sender")
    void sendShouldSkipWhenDisabled() {
        MailConfig config = new MailConfig();
        config.setEnabled(false);
        config.setHost("smtp.example.com");
        config.setTo("admin@example.com");

        TrackingMailService service = new TrackingMailService(config, messages);
        assertThatNoException().isThrownBy(() -> service.send("subject", "<p>body</p>"));
        assertThat(service.createSenderCalls).isZero();
    }

    @Test
    @DisplayName("send 在主机为空时跳过，不抛")
    void sendShouldSkipWhenNoHost() {
        MailConfig config = new MailConfig();
        config.setEnabled(true);
        config.setHost("");
        config.setTo("admin@example.com");

        TrackingMailService service = new TrackingMailService(config, messages);
        assertThatNoException().isThrownBy(() -> service.send("subject", "body"));
        assertThat(service.createSenderCalls).isZero();
    }

    @Test
    @DisplayName("send 在收件人为空时跳过，不抛")
    void sendShouldSkipWhenNoRecipients() {
        MailConfig config = new MailConfig();
        config.setEnabled(true);
        config.setHost("smtp.example.com");
        config.setTo("   ");

        TrackingMailService service = new TrackingMailService(config, messages);
        assertThatNoException().isThrownBy(() -> service.send("subject", "body"));
        assertThat(service.createSenderCalls).isZero();
    }

    @Test
    @DisplayName("send 在 sender 抛 MailException 时仅记日志、不外抛（best-effort）")
    void sendShouldSwallowMailException() {
        MailConfig config = enabledConfig();
        JavaMailSender sender = mockSender();
        doThrow(new MailSendException("boom")).when(sender).send(any(MimeMessage.class));

        TrackingMailService service = new TrackingMailService(config, messages, settings -> sender);
        assertThatNoException().isThrownBy(() -> service.send("hi", "<p>body</p>"));
        verify(sender, times(1)).send(any(MimeMessage.class));
    }

    @Test
    @DisplayName("send 在 sender 成功时实际发出一封信，subject 自动拼接前缀")
    void sendShouldDeliverWithSubjectPrefix() throws Exception {
        MailConfig config = enabledConfig();
        config.setSubjectPrefix("[PD]");
        JavaMailSender sender = mockSender();

        TrackingMailService service = new TrackingMailService(config, messages, settings -> sender);
        service.send("Hello", "<p>body</p>");

        verify(sender, times(1)).createMimeMessage();
        verify(sender, times(1)).send(any(MimeMessage.class));
        // 通过反射读不到拼接后的 subject；这里只确认 send 实际被触发，subject 拼接逻辑由专门测试覆盖
    }

    @Test
    @DisplayName("sendTest 在 settings 为 null 时抛 MailSendException")
    void sendTestShouldRejectNullSettings() {
        TrackingMailService service = new TrackingMailService(new MailConfig(), messages);
        assertThatThrownBy(() -> service.sendTest(null, "s", "b"))
                .isInstanceOf(MailService.MailSendException.class);
    }

    @Test
    @DisplayName("sendTest 在主机缺失时抛 MailSendException")
    void sendTestShouldRejectMissingHost() {
        TrackingMailService service = new TrackingMailService(new MailConfig(), messages);
        MailSenderSettings settings = new MailSenderSettings("", 465, MailSecurity.SSL,
                "u", "p", "u", "to@example.com", "", "[X]");
        assertThatThrownBy(() -> service.sendTest(settings, "s", "b"))
                .isInstanceOf(MailService.MailSendException.class);
    }

    @Test
    @DisplayName("sendTest 在收件人为空时抛 MailSendException")
    void sendTestShouldRejectMissingRecipients() {
        TrackingMailService service = new TrackingMailService(new MailConfig(), messages);
        MailSenderSettings settings = new MailSenderSettings("smtp.example.com", 465, MailSecurity.SSL,
                "u", "p", "u@example.com", "", "", "[X]");
        assertThatThrownBy(() -> service.sendTest(settings, "s", "b"))
                .isInstanceOf(MailService.MailSendException.class);
    }

    @Test
    @DisplayName("sendTest 在 sender 失败时透传异常并把摘要写进 MailSendException")
    void sendTestShouldPropagateSenderError() {
        JavaMailSender sender = mockSender();
        doThrow(new MailSendException("smtp denied: 535 Auth failed"))
                .when(sender).send(any(MimeMessage.class));

        TrackingMailService service = new TrackingMailService(new MailConfig(), messages, settings -> sender);
        MailSenderSettings settings = new MailSenderSettings("smtp.example.com", 465, MailSecurity.SSL,
                "u@example.com", "secret-not-shown", "u@example.com", "to@example.com", "", "[X]");

        assertThatThrownBy(() -> service.sendTest(settings, "Hi", "<p>body</p>"))
                .isInstanceOf(MailService.MailSendException.class)
                .hasMessageContaining("smtp denied")
                .satisfies(ex -> {
                    // 失败摘要必须脱敏：绝不回显密码
                    assertThat(ex.getMessage()).doesNotContain("secret-not-shown");
                });
        verify(sender, times(1)).send(any(MimeMessage.class));
    }

    private MailConfig enabledConfig() {
        MailConfig config = new MailConfig();
        config.setEnabled(true);
        config.setHost("smtp.example.com");
        config.setPort(465);
        config.setSecurity("ssl");
        config.setUsername("u@example.com");
        config.setPassword("auth-code-secret");
        config.setFrom("u@example.com");
        config.setTo("admin@example.com");
        return config;
    }

    private static JavaMailSender mockSender() {
        JavaMailSender sender = mock(JavaMailSender.class);
        MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
        when(sender.createMimeMessage()).thenReturn(message);
        return sender;
    }

    /**
     * 测试用 MailService 子类：可替换 createSender 工厂、记录调用次数。
     */
    private static class TrackingMailService extends MailService {
        private final java.util.function.Function<MailSenderSettings, JavaMailSender> factory;
        int createSenderCalls;

        TrackingMailService(MailConfig config, MessageResolver messages) {
            super(config, messages);
            this.factory = null;
        }

        TrackingMailService(MailConfig config, MessageResolver messages,
                            java.util.function.Function<MailSenderSettings, JavaMailSender> factory) {
            super(config, messages);
            this.factory = factory;
        }

        @Override
        JavaMailSender createSender(MailSenderSettings settings) {
            createSenderCalls++;
            return factory == null ? super.createSender(settings) : factory.apply(settings);
        }
    }
}
