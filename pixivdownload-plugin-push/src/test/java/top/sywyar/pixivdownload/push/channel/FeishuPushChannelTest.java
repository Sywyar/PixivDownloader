package top.sywyar.pixivdownload.push.channel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.push.channel.dingtalk.DingTalkPushChannel;
import top.sywyar.pixivdownload.push.channel.feishu.FeishuPushChannel;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("飞书签名算法单元测试")
class FeishuPushChannelTest {

    private static final String SECRET = "abcdefghijklmnopqrstuvwxyz0123456789";
    private static final String TIMESTAMP = "1599360473";

    @Test
    @DisplayName("签名与飞书官方算法一致：以 timestamp\\n密钥 为 HMAC 密钥、对空串签名 → Base64（不 URL 编码）")
    void signMatchesFeishuAlgorithm() throws Exception {
        String actual = FeishuPushChannel.sign(SECRET, TIMESTAMP);

        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec((TIMESTAMP + "\n" + SECRET).getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] signData = mac.doFinal(new byte[0]);
        String expected = Base64.getEncoder().encodeToString(signData);

        assertThat(actual).isEqualTo(expected);
    }

    @Test
    @DisplayName("签名为原始 Base64、未做 URL 编码（不含 %）")
    void signIsNotUrlEncoded() throws Exception {
        assertThat(FeishuPushChannel.sign(SECRET, TIMESTAMP)).doesNotContain("%");
    }

    @Test
    @DisplayName("与钉钉算法不同：飞书对空串签名，钉钉对 timestamp\\n密钥 串签名")
    void differsFromDingTalk() throws Exception {
        long ts = Long.parseLong(TIMESTAMP);
        assertThat(FeishuPushChannel.sign(SECRET, TIMESTAMP))
                .isNotEqualTo(DingTalkPushChannel.sign(SECRET, ts));
    }
}
