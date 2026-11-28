package top.sywyar.pixivdownload.pixivcontent;

import jakarta.servlet.http.HttpServletRequest;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.HandlerMapping;

import static org.assertj.core.api.Assertions.assertThat;

class RequestDiagnosticsTest {

    @Test
    @DisplayName("日志字段只含路由模板、按名称排序的数字路径 ID 与耗时，非数字值不原样输出")
    void fieldsContainOnlyTheRouteNumericIdsAndElapsedTime() {
        var attributes = new HashMap<String, Object>();
        var request = request(attributes);
        RequestDiagnostics.markStarted(request);
        attributes.put(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/pixiv-content/artworks/{id}/pages/{pageIndex}/image");
        attributes.put(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, Map.of("pageIndex", "0", "id", "42"));

        assertThat(RequestDiagnostics.of(request).fields())
                .matches("operation=/api/pixiv-content/artworks/\\{id}/pages/\\{pageIndex}/image id=42 pageIndex=0 elapsedMs=\\d+");

        attributes.put(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, Map.of("id", "42\nforged log line"));
        assertThat(RequestDiagnostics.of(request).fields()).contains("id=invalid").doesNotContain("forged", "\n");
    }

    @Test
    @DisplayName("请求线程之外仍返回可用的字段")
    void outsideARequestTheFieldsStayUsable() {
        assertThat(RequestDiagnostics.current().fields()).matches("operation=unknown elapsedMs=\\d+");
    }

    private static HttpServletRequest request(Map<String, Object> attributes) {
        return (HttpServletRequest) Proxy.newProxyInstance(HttpServletRequest.class.getClassLoader(),
                new Class<?>[]{HttpServletRequest.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getAttribute" -> attributes.get((String) args[0]);
                    case "setAttribute" -> attributes.put((String) args[0], args[1]);
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }
}
