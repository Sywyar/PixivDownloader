package top.sywyar.pixivdownload.push;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.i18n.MessageResolver;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("推送插件文案故障安全解析")
class PushPluginMessagesTest {

    @Test
    @DisplayName("解析器返回空值时日志码与受控详情均回退原键")
    void blankResolutionFallsBackToCode() {
        PushResult result = PushResult.failed(
                PushChannelType.BARK, PushResult.DETAIL_CHANNEL_UNAVAILABLE);

        for (String value : new String[]{null, "", "   "}) {
            MessageResolver resolver = new FixedMessageResolver(value);
            assertThat(PushPluginMessages.forLog(resolver, "push.log.send.failed"))
                    .isEqualTo("push.log.send.failed");
            assertThat(PushPluginMessages.detail(resolver, Locale.US, result))
                    .isEqualTo(PushResult.DETAIL_CHANNEL_UNAVAILABLE);
            assertThat(PushPluginMessages.detailForLog(resolver, result))
                    .isEqualTo(PushResult.DETAIL_CHANNEL_UNAVAILABLE);
        }
    }

    private record FixedMessageResolver(String value) implements MessageResolver {
        @Override
        public String get(String code, Object... args) {
            return value;
        }

        @Override
        public String get(Locale locale, String code, Object... args) {
            return value;
        }

        @Override
        public String getOrDefault(String code, String defaultMessage, Object... args) {
            return value;
        }

        @Override
        public String getOrDefault(Locale locale, String code, String defaultMessage, Object... args) {
            return value;
        }

        @Override
        public String getForLog(String code, Object... args) {
            return value;
        }
    }
}
