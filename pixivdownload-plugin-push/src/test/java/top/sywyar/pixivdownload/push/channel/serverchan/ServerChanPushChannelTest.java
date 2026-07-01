package top.sywyar.pixivdownload.push.channel.serverchan;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Server 酱端点处理")
class ServerChanPushChannelTest {

    @Test
    @DisplayName("sctp SendKey 使用 Server 酱三端点")
    void sctpSendKeyUsesSc3Endpoint() {
        String key = "sctp123tabcdefghijklmnopqrstuvwxyz";

        String endpoint = ServerChanPushChannel.endpoint(key);

        assertThat(endpoint)
                .isEqualTo("https://123.push.ft07.com/send/"
                        + "sctp123tabcdefghijklmnopqrstuvwxyz.send");
    }

    @Test
    @DisplayName("非 sctp SendKey 使用 Turbo 端点")
    void nonSctpSendKeyUsesTurboEndpoint() {
        String key = "SCT1234567890";

        String endpoint = ServerChanPushChannel.endpoint(key);

        assertThat(endpoint).isEqualTo("https://sctapi.ftqq.com/SCT1234567890.send");
    }

    @Test
    @DisplayName("格式不完整的 sctp SendKey 回退到 Turbo 端点")
    void malformedSctpSendKeyFallsBackToTurboEndpoint() {
        String key = "sctp-no-uid";

        String endpoint = ServerChanPushChannel.endpoint(key);

        assertThat(endpoint).isEqualTo("https://sctapi.ftqq.com/sctp-no-uid.send");
    }
}
