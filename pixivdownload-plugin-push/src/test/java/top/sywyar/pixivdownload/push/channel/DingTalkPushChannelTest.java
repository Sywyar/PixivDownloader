package top.sywyar.pixivdownload.push.channel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.push.channel.dingtalk.DingTalkPushChannel;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("钉钉加签算法单元测试")
class DingTalkPushChannelTest {

    private static final String SECRET = "SECabcdefghijklmnopqrstuvwxyz0123456789";
    private static final long TIMESTAMP = 1_700_000_000_000L;

    @Test
    @DisplayName("加签与钉钉官方算法一致：HmacSHA256(timestamp\\n密钥) → Base64 → URL 编码一次")
    void signMatchesDingTalkAlgorithm() throws Exception {
        String actual = DingTalkPushChannel.sign(SECRET, TIMESTAMP);

        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] signData = mac.doFinal((TIMESTAMP + "\n" + SECRET).getBytes(StandardCharsets.UTF_8));
        String expected = URLEncoder.encode(
                Base64.getEncoder().encodeToString(signData), StandardCharsets.UTF_8);

        assertThat(actual).isEqualTo(expected);
    }

    @Test
    @DisplayName("签名已完成 URL 编码：不含裸 + / = 字符")
    void signIsUrlEncodedExactlyOnce() throws Exception {
        String sign = DingTalkPushChannel.sign(SECRET, TIMESTAMP);
        // Base64 字母表中的 + / = 必须已被编码为 %2B / %2F / %3D；同时不得出现二次编码的 %25。
        assertThat(sign).doesNotContain("+").doesNotContain("/").doesNotContain("=");
        assertThat(sign).doesNotContain("%25");
    }

    @Test
    @DisplayName("时间戳不同则签名不同（时间戳参与签名）")
    void signDependsOnTimestamp() throws Exception {
        assertThat(DingTalkPushChannel.sign(SECRET, TIMESTAMP))
                .isNotEqualTo(DingTalkPushChannel.sign(SECRET, TIMESTAMP + 1));
    }
}
