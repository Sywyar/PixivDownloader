package top.sywyar.pixivdownload.common.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("安全请求路径解析")
class SafeRequestPathTest {

    @ParameterizedTest
    @CsvSource({
            "/api/downloaded/%72awfile/12345/0, /api/downloaded/rawfile/12345/0",
            "/%61pi/downloaded/rawfile/12345/0, /api/downloaded/rawfile/12345/0",
            "/files/%E4%BD%9C%E5%93%81+one%20two.png, /files/作品+one two.png",
            "/files/100%25.png, /files/100%.png",
            "/files/%2572awfile.png, /files/%72awfile.png",
            "/api;v=1/%64ownloaded/rawfile;v=2/12345/0, /api/downloaded/rawfile/12345/0"
    })
    @DisplayName("按 MVC 路径段语义仅解码一次并保留合法动态参数")
    void decodesSegmentsOnce(String uri, String expected) {
        assertThat(SafeRequestPath.resolve(new MockHttpServletRequest("GET", uri))).contains(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/%2Fdownloaded/x", "/api/%5cdownloaded/x", "/api/%00/x",
            "/api/%0a/x", "/api/../x", "/api/%2e/x", "/api/%2e%2e/x", "/api/%/x", "/api/%GG/x"})
    @DisplayName("拒绝会改变路径层级或包含控制字符和畸形转义的路径")
    void rejectsAmbiguousSegments(String uri) {
        assertThat(SafeRequestPath.resolve(new MockHttpServletRequest("GET", uri))).isEmpty();
    }

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
