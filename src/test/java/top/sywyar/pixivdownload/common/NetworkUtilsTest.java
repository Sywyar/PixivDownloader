package top.sywyar.pixivdownload.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("NetworkUtils tests")
class NetworkUtilsTest {

    @Test
    @DisplayName("local requests require loopback remote address and host")
    void localRequestRequiresLoopbackRemoteAndHost() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("Host", "localhost:6999");

        assertThat(NetworkUtils.isLocalRequest(request)).isTrue();

        request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("Host", "pixiv.example.com");

        assertThat(NetworkUtils.isLocalRequest(request)).isFalse();
    }

    @Test
    @DisplayName("forwarded remote clients are not local")
    void forwardedRemoteClientIsNotLocal() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("Host", "localhost:6999");
        request.addHeader("X-Forwarded-For", "203.0.113.10");

        assertThat(NetworkUtils.isLocalRequest(request)).isFalse();
    }

    @Test
    @DisplayName("standard Forwarded remote clients are not local")
    void standardForwardedRemoteClientIsNotLocal() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("Host", "localhost:6999");
        request.addHeader("Forwarded", "for=203.0.113.10;proto=https");

        assertThat(NetworkUtils.isLocalRequest(request)).isFalse();
    }
}
