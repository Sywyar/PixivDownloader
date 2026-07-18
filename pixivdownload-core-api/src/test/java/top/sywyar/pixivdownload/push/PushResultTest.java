package top.sywyar.pixivdownload.push;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("推送结果受控诊断契约")
class PushResultTest {

    @Test
    @DisplayName("已声明的受控原因键均可被精确识别")
    void controlledDetailKeysAreRecognized() {
        for (String key : List.of(
                PushResult.DETAIL_CHANNEL_UNAVAILABLE,
                PushResult.DETAIL_CHANNEL_NOT_CONFIGURED,
                PushResult.DETAIL_SETTINGS_INCOMPLETE,
                PushResult.DETAIL_SETTINGS_TYPE_MISMATCH,
                PushResult.DETAIL_UNEXPECTED_ERROR,
                PushResult.DETAIL_SERIALIZATION_FAILED,
                PushResult.DETAIL_SIGNING_FAILED,
                PushResult.DETAIL_INVALID_CONTENT_TYPE,
                PushResult.DETAIL_INVALID_URL)) {
            assertThat(PushResult.failed(PushChannelType.BARK, key).detailIsMessageKey())
                    .as("受控原因键 %s", key)
                    .isTrue();
        }
    }

    @Test
    @DisplayName("同前缀未知文本与外部诊断不被误判为文案键")
    void unknownPrefixAndRawDetailAreNotMessageKeys() {
        assertThat(PushResult.failed(
                PushChannelType.BARK,
                PushResult.DETAIL_MESSAGE_PREFIX + "vendor-error").detailIsMessageKey()).isFalse();
        assertThat(PushResult.failed(
                PushChannelType.BARK, "HTTP 503: unavailable").detailIsMessageKey()).isFalse();
        assertThat(PushResult.ok(PushChannelType.BARK).detailIsMessageKey()).isFalse();
    }
}
