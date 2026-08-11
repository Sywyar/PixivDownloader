package top.sywyar.pixivdownload.notificationbase;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;
import top.sywyar.pixivdownload.notification.NotificationScenario;
import top.sywyar.pixivdownload.plugin.api.notification.ImmutableNotificationTemplateCatalog;
import top.sywyar.pixivdownload.plugin.api.notification.NotificationTemplateContribution;

import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("GUI 站内信测试端点")
class NotificationInboxTestControllerTest {

    private NotificationInboxServiceTest.MemoryMapper mapper;
    private NotificationInboxTestController controller;

    @BeforeEach
    void setUp() {
        LocaleContextHolder.setLocale(Locale.US);
        mapper = new NotificationInboxServiceTest.MemoryMapper();
        InboxNotificationSink sink = new InboxNotificationSink(
                catalog(), new NotificationInboxService(mapper), java.util.List.of(Locale.US), () -> false);
        controller = new NotificationInboxTestController(sink);
    }

    @AfterEach
    void tearDown() {
        LocaleContextHolder.resetLocaleContext();
    }

    @Test
    @DisplayName("两个测试动作可在关闭站内信时分别写入单条和全部模板")
    void writesSingleAndAllTemplatesRegardlessOfEnabledSwitch() {
        var single = controller.test(request("127.0.0.1"));

        assertThat(single.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(single.getBody()).isNotNull();
        assertThat(single.getBody().success()).isTrue();
        assertThat(mapper.findLatest(null, false, 100)).singleElement()
                .extracting(NotificationMessage::scenarioId)
                .isEqualTo(NotificationScenario.RUN_SUMMARY.id());

        var all = controller.testAll(request("127.0.0.1"));

        assertThat(all.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(all.getBody()).isNotNull();
        assertThat(all.getBody().success()).isTrue();
        assertThat(all.getBody().total()).isEqualTo(NotificationScenario.values().length);
        assertThat(mapper.findLatest(null, false, 100)).hasSize(NotificationScenario.values().length + 1);
    }

    @Test
    @DisplayName("非本地请求不能触发站内信测试写入")
    void rejectsRemoteRequest() {
        var response = controller.testAll(request("192.0.2.1"));

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        assertThat(mapper.findLatest(null, false, 100)).isEmpty();
    }

    private static ImmutableNotificationTemplateCatalog catalog() {
        return new ImmutableNotificationTemplateCatalog(Arrays.stream(NotificationScenario.values())
                .map(scenario -> new NotificationTemplateContribution(
                        scenario.id(), "inbox", Locale.US,
                        "Title {{task_name}}", "Body {{task_id}}"))
                .toList());
    }

    private static HttpServletRequest request(String remoteAddress) {
        return (HttpServletRequest) Proxy.newProxyInstance(
                NotificationInboxTestControllerTest.class.getClassLoader(),
                new Class<?>[]{HttpServletRequest.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getRemoteAddr" -> remoteAddress;
                    case "getHeader" -> "Host".equals(args[0]) ? "localhost:6999" : null;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }
}
