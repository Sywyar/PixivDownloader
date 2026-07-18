package top.sywyar.pixivdownload.push;

import top.sywyar.pixivdownload.i18n.MessageResolver;

import java.util.Locale;

/** push 插件文案解析的故障安全入口。 */
public final class PushPluginMessages {

    private PushPluginMessages() {
    }

    public static String forLog(MessageResolver messages, String code, Object... args) {
        if (messages == null) {
            return code;
        }
        try {
            return fallbackIfBlank(messages.getForLog(code, args), code);
        } catch (RuntimeException ignored) {
            return code;
        }
    }

    public static String detail(MessageResolver messages, Locale locale, PushResult result) {
        if (result == null || !result.detailIsMessageKey() || messages == null) {
            return result == null ? null : result.detail();
        }
        try {
            return fallbackIfBlank(
                    messages.getOrDefault(locale, result.detail(), result.detail()),
                    result.detail());
        } catch (RuntimeException ignored) {
            return result.detail();
        }
    }

    public static String detailForLog(MessageResolver messages, PushResult result) {
        if (result == null || !result.detailIsMessageKey()) {
            return result == null ? null : result.detail();
        }
        return forLog(messages, result.detail());
    }

    private static String fallbackIfBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
