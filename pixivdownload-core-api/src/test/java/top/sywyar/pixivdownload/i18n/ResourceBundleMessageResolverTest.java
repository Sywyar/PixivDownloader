package top.sywyar.pixivdownload.i18n;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("插件资源文案解析器")
class ResourceBundleMessageResolverTest {

    @Test
    @DisplayName("无显式语言时沿用宿主当前请求语言")
    void usesFallbackCurrentLocaleForPluginOwnedKeys() {
        MessageResolver resolver = ResourceBundleMessageResolver.of(
                new FixedLocaleFallback(Locale.US), getClass().getClassLoader(), "i18n.test.messages");

        assertThat(resolver.get("owner.message")).isEqualTo("English");
    }

    @Test
    @DisplayName("显式但不受支持的语言沿用宿主归一化规则")
    void normalizesExplicitLocaleThroughFallback() {
        MessageResolver resolver = ResourceBundleMessageResolver.of(
                new FixedLocaleFallback(Locale.SIMPLIFIED_CHINESE),
                getClass().getClassLoader(), "i18n.test.messages");

        assertThat(resolver.get(Locale.JAPANESE, "owner.message")).isEqualTo("English");
    }

    @Test
    @DisplayName("插件未声明的 key 回退到宿主解析器")
    void fallsBackToHostResolverForSharedKeys() {
        MessageResolver resolver = ResourceBundleMessageResolver.of(
                new FixedLocaleFallback(Locale.SIMPLIFIED_CHINESE),
                getClass().getClassLoader(), "i18n.test.messages");

        assertThat(resolver.get("shared.message", "arg")).isEqualTo("host:shared.message:arg");
    }

    private record FixedLocaleFallback(Locale currentLocale) implements MessageResolver {

        @Override
        public Locale normalizeLocale(Locale locale) {
            if (locale != null && Locale.SIMPLIFIED_CHINESE.getLanguage().equals(locale.getLanguage())) {
                return Locale.SIMPLIFIED_CHINESE;
            }
            return Locale.US;
        }

        @Override
        public String get(String code, Object... args) {
            return getOrDefault(currentLocale, code, code, args);
        }

        @Override
        public String get(Locale locale, String code, Object... args) {
            return getOrDefault(locale, code, code, args);
        }

        @Override
        public String getOrDefault(String code, String defaultMessage, Object... args) {
            return getOrDefault(currentLocale, code, defaultMessage, args);
        }

        @Override
        public String getOrDefault(Locale locale, String code, String defaultMessage, Object... args) {
            String suffix = args == null || args.length == 0 ? "" : ":" + args[0];
            return "host:" + code + suffix;
        }

        @Override
        public String getForLog(String code, Object... args) {
            return getOrDefault(Locale.getDefault(), code, code, args);
        }
    }
}
