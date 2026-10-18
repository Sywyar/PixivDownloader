package top.sywyar.pixivdownload.mail;

import jakarta.mail.Session;
import jakarta.mail.internet.ContentType;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
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

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @ExtendWith(OutputCaptureExtension.class)
    @DisplayName("发送失败保留脱敏诊断，不外抛或在异常栈中泄露密码")
    void sendShouldRedactFailures(boolean mailException, CapturedOutput output) {
        MailConfig config = enabledConfig();
        JavaMailSender sender = mockSender();
        var cause = new IllegalStateException("authentication rejected: " + config.getPassword());
        RuntimeException failure = mailException
                ? new MailSendException("sender rejected", cause)
                : new IllegalArgumentException("sender rejected", cause);
        doThrow(failure).when(sender).send(any(MimeMessage.class));

        TrackingMailService service = new TrackingMailService(config, messages, settings -> sender);
        assertThatNoException().isThrownBy(() -> service.send("hi", "<p>body</p>"));
        verify(sender, times(1)).send(any(MimeMessage.class));
        assertThat(output.getAll()).contains("sender rejected", "authentication rejected", "[redacted]")
                .doesNotContain(config.getPassword());
    }

    @Test
    @DisplayName("send 在 sender 成功时实际发出一封信，subject 自动拼接前缀")
    void sendShouldDeliverWithSubjectPrefix() throws Exception {
        MailConfig config = enabledConfig();
        config.setSubjectPrefix("  [PD]  ");
        config.setFrom(" ");
        config.setTo(" admin@example.com, , second@example.com ");
        JavaMailSender sender = mockSender();

        TrackingMailService service = new TrackingMailService(config, messages, settings -> sender);
        service.send("Hello 世界", "<p>正文 🌸</p>");

        verify(sender, times(1)).createMimeMessage();
        ArgumentCaptor<MimeMessage> sent = ArgumentCaptor.forClass(MimeMessage.class);
        verify(sender).send(sent.capture());
        MimeMessage message = sent.getValue();
        message.saveChanges();
        assertThat(message.getSubject()).isEqualTo("[PD] Hello 世界");
        assertThat(message.getFrom()).extracting(Object::toString).containsExactly("u@example.com");
        assertThat(message.getAllRecipients()).extracting(Object::toString)
                .containsExactly("admin@example.com", "second@example.com");
        assertThat(message.getContent()).isEqualTo("<p>正文 🌸</p>");
        ContentType contentType = new ContentType(message.getContentType());
        assertThat(contentType.getBaseType()).isEqualTo("text/html");
        assertThat(contentType.getParameter("charset")).isEqualToIgnoringCase("UTF-8");
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

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("草稿发送失败统一返回脱敏摘要，保留原始原因供内部诊断")
    void sendTestShouldPropagateSenderError(boolean mailException) {
        JavaMailSender sender = mockSender();
        MailSenderSettings settings = new MailSenderSettings("smtp.example.com", 465, MailSecurity.SSL,
                "u@example.com", "secret-not-shown", "u@example.com", "to@example.com", "", "[X]");
        var cause = new IllegalStateException("authentication rejected: " + settings.password());
        RuntimeException failure = mailException
                ? new MailSendException("smtp denied", cause)
                : new IllegalArgumentException("smtp denied", cause);
        doThrow(failure).when(sender).send(any(MimeMessage.class));
        TrackingMailService service = new TrackingMailService(new MailConfig(), messages, supplied -> sender);

        assertThatThrownBy(() -> service.sendTest(settings, "Hi", "<p>body</p>"))
                .isInstanceOf(MailService.MailSendException.class)
                .hasCause(failure)
                .hasMessageContaining("smtp denied")
                .hasMessageContaining("authentication rejected")
                .hasMessageContaining("[redacted]")
                .satisfies(ex -> {
                    // 失败摘要必须脱敏：绝不回显密码
                    assertThat(ex.getMessage()).doesNotContain("secret-not-shown");
                });
        verify(sender, times(1)).send(any(MimeMessage.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"x", "a7q", "long-secret"})
    @DisplayName("异常摘要对任意非空密码脱敏，不按密码长度跳过")
    void redactsPasswordsRegardlessOfLength(String password) {
        var failure = new IllegalArgumentException("smtp rejected",
                new IllegalStateException("authentication: " + password));

        assertThat(MailService.safeMessage(failure, password))
                .isEqualTo("smtp rejected; authentication: [redacted]");
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
