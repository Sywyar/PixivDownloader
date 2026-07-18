package top.sywyar.pixivdownload.push.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.push.PushChannelType;
import top.sywyar.pixivdownload.push.PushResult;
import top.sywyar.pixivdownload.push.TestMessageResolver;

import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("推送测试响应详情投影")
class PushTestResponseTest {

    @Test
    @DisplayName("受控失败原因按请求语言解析")
    void controlledDetailUsesRequestedLocale() {
        PushResult result = PushResult.failed(
                PushChannelType.BARK, PushResult.DETAIL_SETTINGS_INCOMPLETE);

        String chinese = PushTestResponse.from(
                List.of(result), TestMessageResolver.INSTANCE, Locale.SIMPLIFIED_CHINESE)
                .results().get(0).detail();
        String english = PushTestResponse.from(
                List.of(result), TestMessageResolver.INSTANCE, Locale.US)
                .results().get(0).detail();

        assertThat(chinese)
                .isEqualTo(TestMessageResolver.INSTANCE.get(
                        Locale.SIMPLIFIED_CHINESE, PushResult.DETAIL_SETTINGS_INCOMPLETE))
                .isNotEqualTo(PushResult.DETAIL_SETTINGS_INCOMPLETE);
        assertThat(english)
                .isEqualTo(TestMessageResolver.INSTANCE.get(
                        Locale.US, PushResult.DETAIL_SETTINGS_INCOMPLETE))
                .isNotEqualTo(PushResult.DETAIL_SETTINGS_INCOMPLETE)
                .isNotEqualTo(chinese);
    }

    @Test
    @DisplayName("外部脱敏诊断保持原文且解析器异常回退稳定原因键")
    void rawDetailAndResolverFailureRemainSafe() {
        PushResult raw = PushResult.failed(PushChannelType.BARK, "HTTP 503: unavailable");
        PushResult controlled = PushResult.failed(
                PushChannelType.BARK, PushResult.DETAIL_CHANNEL_UNAVAILABLE);
        PushResult successful = PushResult.ok(PushChannelType.BARK);

        PushTestResponse response = PushTestResponse.from(
                List.of(raw, controlled, successful), TestMessageResolver.THROWING, Locale.US);

        assertThat(response.results())
                .extracting(PushTestResponse.Item::detail)
                .containsExactly("HTTP 503: unavailable", PushResult.DETAIL_CHANNEL_UNAVAILABLE, null);
    }

    @Test
    @DisplayName("空结果元素归一为可展示的受控失败")
    void nullResultIsNormalized() {
        PushTestResponse response = PushTestResponse.from(
                java.util.Arrays.asList((PushResult) null),
                TestMessageResolver.INSTANCE,
                Locale.SIMPLIFIED_CHINESE);

        assertThat(response.results()).singleElement().satisfies(item -> {
            assertThat(item.channel()).isEqualTo("unknown");
            assertThat(item.status()).isEqualTo(PushResult.Status.FAILED.name());
            assertThat(item.detail()).isEqualTo(TestMessageResolver.INSTANCE.get(
                    Locale.SIMPLIFIED_CHINESE, PushResult.DETAIL_UNEXPECTED_ERROR));
        });
    }
}
