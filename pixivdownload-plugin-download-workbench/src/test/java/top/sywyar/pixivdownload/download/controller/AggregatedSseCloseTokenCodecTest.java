package top.sywyar.pixivdownload.download.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("聚合 SSE 关闭令牌编解码测试")
class AggregatedSseCloseTokenCodecTest {

    private final AggregatedSseCloseTokenCodec codec = new AggregatedSseCloseTokenCodec();

    @Test
    @DisplayName("应完整恢复经过认证的连接身份")
    void roundTripsAuthenticatedConnectionIdentity() {
        long issuedAt = 1_700_000_000_000L;

        String token = codec.create("connection-1", "owner-1", false, issuedAt);

        assertThat(codec.parse(token)).isEqualTo(
                new AggregatedSseCloseTokenCodec.Payload("connection-1", "owner-1", false, issuedAt));
    }

    @Test
    @DisplayName("应拒绝被篡改或格式非法的令牌")
    void rejectsTamperedAndMalformedTokens() {
        String token = codec.create("connection-1", null, true, 1_700_000_000_000L);
        char replacement = token.charAt(0) == 'A' ? 'B' : 'A';
        String tampered = replacement + token.substring(1);

        assertThat(codec.parse(tampered)).isNull();
        assertThat(codec.parse("not-a-token")).isNull();
        assertThat(codec.parse(" ")).isNull();
    }
}
