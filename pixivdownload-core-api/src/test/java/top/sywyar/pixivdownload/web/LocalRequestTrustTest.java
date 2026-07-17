package top.sywyar.pixivdownload.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("本地请求信任判定")
class LocalRequestTrustTest {

    @Test
    @DisplayName("不含 Origin 的本地请求判定只校验连接与转发链")
    void localRequestCheckDoesNotRequireOrigin() {
        assertThat(LocalRequestTrust.isLocalRequest(
                "127.0.0.1", "localhost:6999", null, null, null)).isTrue();
        assertThat(LocalRequestTrust.isTrustedLocalRequest(
                "127.0.0.1", "localhost:6999", null, null, null,
                "https://example.invalid")).isFalse();
    }

    @Test
    @DisplayName("任一外部连接或转发地址都不得被判为本地请求")
    void externalConnectionOrForwardedAddressIsRejected() {
        assertThat(LocalRequestTrust.isLocalRequest(
                "192.0.2.1", "localhost:6999", null, null, null)).isFalse();
        assertThat(LocalRequestTrust.isLocalRequest(
                "127.0.0.1", "localhost:6999", "127.0.0.1, 192.0.2.1", null, null)).isFalse();
        assertThat(LocalRequestTrust.isLocalRequest(
                "127.0.0.1", "localhost:6999", null, null,
                "for=192.0.2.1;proto=https")).isFalse();
    }
}
