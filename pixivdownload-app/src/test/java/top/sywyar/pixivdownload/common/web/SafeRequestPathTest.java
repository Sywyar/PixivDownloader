package top.sywyar.pixivdownload.common.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("安全请求路径解析")
class SafeRequestPathTest {

    @Test
    @DisplayName("按路径段移除矩阵参数并保留后续路径")
    void removesMatrixParametersPerSegment() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST",
                "/api;v=1/collections;scope=x/7/icon;trace=1");
        request.setRequestURI("/api;v=1/collections;scope=x/7/icon;trace=1");

        assertThat(SafeRequestPath.resolve(request)).contains("/api/collections/7/icon");
    }

    @Test
    @DisplayName("解析应用 context path 后的安全路径")
    void removesContextPath() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/pixiv/api/plugins/status");
        request.setContextPath("/pixiv");
        request.setRequestURI("/pixiv/api/plugins/status");

        assertThat(SafeRequestPath.resolve(request)).contains("/api/plugins/status");
    }

    @Test
    @DisplayName("拒绝编码分号避免容器解码差异进入安全判定")
    void rejectsEncodedSemicolon() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/plugins/install%3Btrace=1");
        request.setRequestURI("/api/plugins/install%3Btrace=1");

        assertThat(SafeRequestPath.resolve(request)).isEmpty();
    }
}
