package top.sywyar.pixivdownload.douyin.client;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

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

    @Test
    @DisplayName("HTTP 200 响应中的明确限流状态码映射为限流")
    void classifiesKnownRateLimitStatusCodes() {
        assertThat(List.of(7, 429, 50_001, 2_190_001, 2_190_020, 28_003_017, 28_003_018))
                .allSatisfy(status -> assertThat(DouyinErrorClassifier.classifyJsonStatus(
                        status(status, null))).isEqualTo(DouyinClientErrorCode.RATE_LIMITED));
    }

    @Test
    @DisplayName("中英文频控消息映射为限流")
    void classifiesRateLimitMessages() {
        assertThat(List.of(
                "Rate limit exceeded",
                "Too many requests",
                "Requests are too frequent",
                "访问频繁，命中风控",
                "请求频率过高",
                "操作太频繁，请稍后重试",
                "触发限流机制",
                "命中频控",
                "quota exhausted"))
                .allSatisfy(message -> assertThat(DouyinErrorClassifier.classifyJsonStatus(
                        status(90_001, message))).isEqualTo(DouyinClientErrorCode.RATE_LIMITED));
    }

    @Test
    @DisplayName("限流提示可从后续消息字段与嵌套提示中识别")
    void readsAllStatusMessageFields() {
        ObjectNode root = status(90_002, "upstream rejected");
        root.putObject("prompts").putArray("details").add("Too many requests");

        assertThat(DouyinErrorClassifier.classifyJsonStatus(root))
                .isEqualTo(DouyinClientErrorCode.RATE_LIMITED);
    }

    @Test
    @DisplayName("验证码、地区和权限错误优先于限流特征")
    void preservesSpecificErrorPriority() {
        assertThat(DouyinErrorClassifier.classifyJsonStatus(
                status(7, "验证码校验失败，访问频繁")))
                .isEqualTo(DouyinClientErrorCode.LOGIN_OR_VERIFY_PAGE);
        assertThat(DouyinErrorClassifier.classifyJsonStatus(
                status(28_003_018, "当前地区请求频率过高")))
                .isEqualTo(DouyinClientErrorCode.REGION_RESTRICTED);
        assertThat(DouyinErrorClassifier.classifyJsonStatus(
                status(50_001, "无权限，操作太频繁")))
                .isEqualTo(DouyinClientErrorCode.PERMISSION_DENIED);
    }

    @Test
    @DisplayName("未知非零状态与单纯风控提示保持原有分类")
    void keepsUnknownAndRiskClassifications() {
        assertThat(DouyinErrorClassifier.classifyJsonStatus(
                status(90_003, "invalid parameter")))
                .isEqualTo(DouyinClientErrorCode.UNSUPPORTED_CONTENT);
        assertThat(DouyinErrorClassifier.classifyJsonStatus(
                status(90_004, "命中风控")))
                .isEqualTo(DouyinClientErrorCode.LOGIN_OR_VERIFY_PAGE);
    }

    private static ObjectNode status(int status, String message) {
        ObjectNode root = JsonNodeFactory.instance.objectNode().put("status_code", status);
        if (message != null) {
            root.put("status_msg", message);
        }
        return root;
    }
}
