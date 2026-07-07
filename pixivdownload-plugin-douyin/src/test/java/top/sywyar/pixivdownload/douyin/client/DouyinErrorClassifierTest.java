package top.sywyar.pixivdownload.douyin.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("DouyinErrorClassifier 抖音错误页识别")
class DouyinErrorClassifierTest {

    @Test
    @DisplayName("识别 PC WAF JS challenge 页为登录或验证页")
    void detectsPcWafChallengePage() {
        String html = """
                <!DOCTYPE html><html><head>
                <script src="https://lf-waf-js.byted-static.com/obj/waf-jschallenge/out-sha256.js"></script>
                </head></html>
                """;

        assertThat(DouyinErrorClassifier.looksLikeLoginOrRiskPage(
                html.getBytes(StandardCharsets.UTF_8))).isTrue();
    }

    @Test
    @DisplayName("识别移动端验证码 SDK 页为登录或验证页")
    void detectsMobileVerifyCenterPage() {
        String html = """
                <script>
                window.TTGCaptcha.init(options);
                var srcList = ["https://lf-rc1.yhgfb-cn-static.com/obj/rc-verifycenter/sec_sdk_build/captcha/index.js"];
                </script>
                <script src="https://lf-c-flwb.bytetos.com/obj/rc-client-security/web/dn/1.0.2.0-alpha.5/bdms.NVI6Q9.js"></script>
                """;

        assertThat(DouyinErrorClassifier.looksLikeLoginOrRiskText(html)).isTrue();
    }
}
