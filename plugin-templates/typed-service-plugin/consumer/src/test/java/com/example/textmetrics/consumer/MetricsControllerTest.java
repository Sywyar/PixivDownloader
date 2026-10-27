package com.example.textmetrics.consumer;

import com.example.textmetrics.api.TextMetrics;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import top.sywyar.pixivdownload.i18n.LocaleBundlePolicy;
import top.sywyar.pixivdownload.i18n.ResourceBundleMessageResolver;
import top.sywyar.pixivdownload.plugin.api.service.PluginServices;
import top.sywyar.pixivdownload.plugin.api.service.ServiceAccessException;
import top.sywyar.pixivdownload.plugin.api.service.ServiceKey;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("服务消费示例的结构化本地化失败响应")
class MetricsControllerTest {
    @ParameterizedTest
    @EnumSource(value = ServiceAccessException.Reason.class, names = {"UNAVAILABLE", "NOT_ACTIVE", "INVOCATION_FAILED"})
    @DisplayName("缺席、失效和提供方失败保留机器码，按请求语言输出 error")
    void localizedFailures(ServiceAccessException.Reason reason) throws Exception {
        PluginServices services = new PluginServices() {
            public <T> Optional<T> find(ServiceKey<T> key) {
                if (reason == ServiceAccessException.Reason.UNAVAILABLE) return Optional.empty();
                return Optional.of(key.type().cast((TextMetrics) request -> { throw new ServiceAccessException(reason); }));
            }
        };
        LocaleBundlePolicy policy = new LocaleBundlePolicy() {
            public Locale normalize(Locale requested) { return requested; }
            public List<String> resourceSuffixChain(Locale requested) {
                return requested.equals(Locale.SIMPLIFIED_CHINESE) ? List.of("") : List.of("en", "");
            }
        };
        var messages = ResourceBundleMessageResolver.of(null, getClass().getClassLoader(), policy,
                "i18n.web.text-metrics-consumer");
        var controller = new MetricsController(services, messages);
        var mapper = new ObjectMapper();
        String previous = null;
        for (Locale locale : List.of(Locale.SIMPLIFIED_CHINESE, Locale.US)) {
            var response = controller.analyze(new TextMetrics.Request(List.of("private input")), locale);
            assertEquals(503, response.getStatusCode().value());
            var body = mapper.valueToTree(response.getBody());
            assertEquals(2, body.size());
            assertEquals(reason.name(), body.get("code").textValue());
            String error = body.get("error").textValue();
            assertEquals(messages.get(locale, "service.unavailable"), error);
            assertNotEquals("service.unavailable", error);
            assertFalse(error.isBlank());
            assertFalse(error.contains("private input"));
            if (previous != null) assertNotEquals(previous, error);
            previous = error;
        }
    }
}
