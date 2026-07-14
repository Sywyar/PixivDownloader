package top.sywyar.pixivdownload.douyin.download.validation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.douyin.client.DouyinClientErrorCode;
import top.sywyar.pixivdownload.douyin.client.DouyinClientException;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("DouyinMediaPayloadValidator 媒体载荷校验")
class DouyinMediaPayloadValidatorTest {

    @Test
    @DisplayName("通用二进制类型的纯文本验证码响应会被拒绝")
    void rejectsPlainVerificationTextWithGenericContentType() {
        byte[] payload = "captcha verify required".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> DouyinMediaPayloadValidator.requireMediaPayload(
                "application/octet-stream", payload))
                .isInstanceOf(DouyinClientException.class)
                .extracting(error -> ((DouyinClientException) error).code())
                .isEqualTo(DouyinClientErrorCode.LOGIN_OR_VERIFY_PAGE);
    }

    @Test
    @DisplayName("通用二进制类型的可打印非媒体文本会被拒绝")
    void rejectsPrintableNonMediaTextWithGenericContentType() {
        byte[] payload = "temporary upstream response".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> DouyinMediaPayloadValidator.requireMediaPayload(
                "application/octet-stream", payload))
                .isInstanceOf(DouyinClientException.class)
                .extracting(error -> ((DouyinClientException) error).code())
                .isEqualTo(DouyinClientErrorCode.NETWORK_ERROR);
    }

    @Test
    @DisplayName("二进制媒体中偶然出现 login 文本不会被误判")
    void acceptsBinaryPayloadContainingIncidentalLoginText() {
        byte[] payload = new byte[64];
        byte[] marker = "login".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(marker, 0, payload, 24, marker.length);

        assertThatCode(() -> DouyinMediaPayloadValidator.requireMediaPayload(
                "application/octet-stream", payload))
                .doesNotThrowAnyException();
    }
}
