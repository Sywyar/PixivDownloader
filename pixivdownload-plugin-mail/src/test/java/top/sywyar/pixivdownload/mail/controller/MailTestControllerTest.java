package top.sywyar.pixivdownload.mail.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import top.sywyar.pixivdownload.mail.MailSenderSettings;
import top.sywyar.pixivdownload.mail.MailService;
import top.sywyar.pixivdownload.mail.TestMessageResolver;
import top.sywyar.pixivdownload.mail.template.MailTemplateRegistry;
import top.sywyar.pixivdownload.setup.UserDisplayNameProvider;
import top.sywyar.pixivdownload.mail.template.RenderedMail;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@DisplayName("MailTestController 单元测试")
class MailTestControllerTest {

    private MailTemplateRegistry templateRegistry;
    private MailService mailService;
    private MailTestController controller;
    private UserDisplayNameProvider displayNameProvider;

    @BeforeEach
    void setUp() {
        templateRegistry = new MailTemplateRegistry(TestMessageResolver.INSTANCE);
        mailService = mock(MailService.class);
        displayNameProvider = mock(UserDisplayNameProvider.class);
        controller = new MailTestController(mailService, templateRegistry,
                TestMessageResolver.INSTANCE, displayNameProvider);
    }

    @Test
    @DisplayName("testAll 在非本地请求时返回 403")
    void testAllRejectsNonLocalRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.10");
        request.addHeader("Host", "example.com");

        ResponseEntity<MailTestAllResponse> response = controller.testAll(validRequestBody(), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("testAll 在 body 为 null 时返回 400")
    void testAllRejectsNullBody() {
        MockHttpServletRequest request = localRequest();

        ResponseEntity<MailTestAllResponse> response = controller.testAll(null, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().success()).isFalse();
    }

    @Test
    @DisplayName("testAll 遍历全部模板并在全部成功时返回 success=true、succeeded==total")
    void testAllSendsEveryTemplateOnSuccess() throws Exception {
        doNothing().when(mailService).sendTest(any(MailSenderSettings.class), anyString(), anyString());

        ResponseEntity<MailTestAllResponse> response = controller.testAll(validRequestBody(), localRequest());

        int total = templateRegistry.templates().size();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        MailTestAllResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.success()).isTrue();
        assertThat(body.total()).isEqualTo(total);
        assertThat(body.succeeded()).isEqualTo(total);
        assertThat(body.failures()).isEmpty();
        verify(mailService, times(total)).sendTest(any(MailSenderSettings.class), anyString(), anyString());
    }

    @Test
    @DisplayName("testAll 在某模板发信失败时继续遍历后续模板，并把失败记入 failures")
    void testAllContinuesAfterPerTemplateFailure() throws Exception {
        // 让 overuse-paused 模板的 subject 触发失败，其它模板成功
        doAnswer(invocation -> {
            String subject = invocation.getArgument(1);
            if (subject != null && subject.contains("过度访问")) {
                throw new MailService.MailSendException("smtp denied: 535");
            }
            return null;
        }).when(mailService).sendTest(any(MailSenderSettings.class), anyString(), anyString());

        ResponseEntity<MailTestAllResponse> response = controller.testAll(validRequestBody(), localRequest());

        int total = templateRegistry.templates().size();
        MailTestAllResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.success()).isFalse();
        assertThat(body.total()).isEqualTo(total);
        assertThat(body.succeeded()).isEqualTo(total - 1);
        assertThat(body.failures()).hasSize(1);
        MailTestAllResponse.Failure failure = body.failures().get(0);
        assertThat(failure.templateId()).isEqualTo(MailTemplateRegistry.TEMPLATE_OVERUSE_PAUSED);
        assertThat(failure.error()).contains("smtp denied");
        verify(mailService, times(total)).sendTest(any(MailSenderSettings.class), anyString(), anyString());
    }

    @Test
    @DisplayName("testAll 使用的示例占位符能让全部模板渲染时无裸 {{}} 残留")
    void testAllSamplePlaceholdersFillEveryTemplate() throws Exception {
        List<RenderedMail> rendered = new ArrayList<>();
        doAnswer(invocation -> {
            String subject = invocation.getArgument(1);
            String body = invocation.getArgument(2);
            rendered.add(new RenderedMail(subject, body));
            return null;
        }).when(mailService).sendTest(any(MailSenderSettings.class), anyString(), anyString());

        controller.testAll(validRequestBody(), localRequest());

        assertThat(rendered).hasSize(templateRegistry.templates().size());
        for (RenderedMail mail : rendered) {
            assertThat(mail.subject()).doesNotContain("{{").doesNotContain("}}");
            assertThat(mail.htmlBody())
                    .doesNotContain("{{")
                    .doesNotContain("}}")
                    .doesNotContain("PHPSESSID");
        }
    }

    private static MailTestRequest validRequestBody() {
        return new MailTestRequest(
                "smtp.example.com",
                465,
                "ssl",
                "u@example.com",
                "secret-not-shown",
                "u@example.com",
                "admin@example.com",
                "",
                "[PD]");
    }

    private static MockHttpServletRequest localRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("Host", "localhost:6999");
        return request;
    }
}
