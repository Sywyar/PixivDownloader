package top.sywyar.pixivdownload.notification;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.i18n.TestI18nBeans;
import top.sywyar.pixivdownload.push.PushChannelType;
import top.sywyar.pixivdownload.push.PushMessage;
import top.sywyar.pixivdownload.push.PushMessageFactory;
import top.sywyar.pixivdownload.push.PushResult;
import top.sywyar.pixivdownload.push.PushService;
import top.sywyar.pixivdownload.push.controller.PushTestRequest;
import top.sywyar.pixivdownload.push.controller.PushTestResponse;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("NotificationPushTestController 单元测试")
class NotificationPushTestControllerTest {

    private AppMessages messages;
    private PushService pushService;
    private NotificationPushTestController controller;

    @BeforeEach
    void setUp() {
        messages = TestI18nBeans.appMessages();
        PushMessageFactory factory = new PushMessageFactory(messages);
        pushService = mock(PushService.class);
        controller = new NotificationPushTestController(pushService, factory, messages);
    }

    @Test
    @DisplayName("testAll 在非本地请求时返回 403")
    void testAllRejectsNonLocalRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.10");
        request.addHeader("Host", "example.com");

        ResponseEntity<PushTestResponse> response = controller.testAll(barkEnabledRequest(), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("testAll 无任何启用渠道时返回空结果，不调用 PushService")
    void testAllWithNoEnabledChannelReturnsEmpty() {
        PushTestRequest empty = new PushTestRequest(null, null, null, null, null, null, null, null);

        ResponseEntity<PushTestResponse> response = controller.testAll(empty, localRequest());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().total()).isZero();
        verify(pushService, times(0)).test(anyList(), any(PushMessage.class));
    }

    @Test
    @DisplayName("testAll 遍历全部通知场景各发一条，全部成功时 success=true、succeeded==total")
    void testAllSendsEveryScenarioOnSuccess() {
        when(pushService.test(anyList(), any(PushMessage.class)))
                .thenReturn(List.of(PushResult.ok(PushChannelType.BARK)));

        ResponseEntity<PushTestResponse> response = controller.testAll(barkEnabledRequest(), localRequest());

        int scenarios = NotificationScenario.values().length;
        PushTestResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.success()).isTrue();
        assertThat(body.total()).isEqualTo(scenarios);
        assertThat(body.succeeded()).isEqualTo(scenarios);
        verify(pushService, times(scenarios)).test(anyList(), any(PushMessage.class));
    }

    @Test
    @DisplayName("testAll 渲染的每条推送消息都无裸 {{}} 残留、且不含 PHPSESSID")
    void testAllRenderedMessagesHaveNoRawPlaceholders() {
        List<PushMessage> sent = new ArrayList<>();
        doAnswer(invocation -> {
            sent.add(invocation.getArgument(1));
            return List.of(PushResult.ok(PushChannelType.BARK));
        }).when(pushService).test(anyList(), any(PushMessage.class));

        controller.testAll(barkEnabledRequest(), localRequest());

        assertThat(sent).hasSize(NotificationScenario.values().length);
        for (PushMessage message : sent) {
            assertThat(message.title()).doesNotContain("{{").doesNotContain("}}");
            assertThat(message.content())
                    .doesNotContain("{{")
                    .doesNotContain("}}")
                    .doesNotContain("PHPSESSID");
        }
    }

    private static PushTestRequest barkEnabledRequest() {
        return new PushTestRequest(
                new PushTestRequest.Bark(true, "https://api.day.app", "device-key", "", false),
                null, null, null, null, null, null, null);
    }

    private static MockHttpServletRequest localRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("Host", "localhost:6999");
        return request;
    }
}
