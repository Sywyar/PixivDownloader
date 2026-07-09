package top.sywyar.pixivdownload.douyin.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Douyin 请求头凭证边界")
class DouyinRequestHeadersTest {

    @Test
    @DisplayName("普通请求头不携带 Cookie")
    void standardHeadersNeverContainCookie() {
        HttpHeaders headers = new HttpHeaders();

        DouyinRequestHeaders.applyStandard(headers);

        assertThat(headers.containsKey(HttpHeaders.COOKIE)).isFalse();
    }

    @Test
    @DisplayName("完整 Cookie 仅发送到明确的 HTTPS Douyin 凭证 origin")
    void credentialsRequireExplicitHttpsOrigin() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        DouyinRequestHeaders.applyCredentials(headers, URI.create("https://www.douyin.com/aweme/v1/web/"),
                "sessionid=unique-sentinel");
        assertThat(headers.getFirst(HttpHeaders.COOKIE)).isEqualTo("sessionid=unique-sentinel");

        assertThatThrownBy(() -> DouyinRequestHeaders.applyCredentials(new HttpHeaders(),
                URI.create("https://cdn.douyin.com/media"), "sessionid=unique-sentinel"))
                .isInstanceOf(DouyinClientException.class);
        assertThatThrownBy(() -> DouyinRequestHeaders.applyCredentials(new HttpHeaders(),
                URI.create("http://www.douyin.com/aweme/v1/web/"), "sessionid=unique-sentinel"))
                .isInstanceOf(DouyinClientException.class);
    }
}
