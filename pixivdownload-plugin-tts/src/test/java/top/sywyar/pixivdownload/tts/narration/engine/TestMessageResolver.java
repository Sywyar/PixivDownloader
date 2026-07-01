package top.sywyar.pixivdownload.tts.narration.engine;

import top.sywyar.pixivdownload.i18n.MessageResolver;

import java.text.MessageFormat;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;

final class TestMessageResolver implements MessageResolver {

    static final TestMessageResolver INSTANCE = new TestMessageResolver();

    private static final String BASE_NAME = "i18n.tts.messages";
    private static final ResourceBundle.Control CONTROL =
            ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES);

    private TestMessageResolver() {
    }

    @Override
    public String get(String code, Object... args) {
        return get(Locale.SIMPLIFIED_CHINESE, code, args);
    }

    @Override
    public String get(Locale locale, String code, Object... args) {
        String pattern = lookup(locale, code, code);
        return format(locale, pattern, args);
    }

    @Override
    public String getOrDefault(String code, String defaultMessage, Object... args) {
        return getOrDefault(Locale.SIMPLIFIED_CHINESE, code, defaultMessage, args);
    }

    @Override
    public String getOrDefault(Locale locale, String code, String defaultMessage, Object... args) {
        String pattern = lookup(locale, code, defaultMessage == null ? code : defaultMessage);
        return format(locale, pattern, args);
    }

    @Override
    public String getForLog(String code, Object... args) {
        return get(Locale.SIMPLIFIED_CHINESE, code, args);
    }

    private static String format(Locale locale, String pattern, Object... args) {
        return args == null || args.length == 0
                ? pattern
                : new MessageFormat(pattern, locale == null ? Locale.ROOT : locale).format(args);
    }

    private static String lookup(Locale locale, String code, String fallback) {
        try {
            ResourceBundle bundle = ResourceBundle.getBundle(
                    BASE_NAME,
                    locale == null ? Locale.SIMPLIFIED_CHINESE : locale,
                    TestMessageResolver.class.getClassLoader(),
                    CONTROL);
            return bundle.containsKey(code) ? bundle.getString(code) : fallback;
        } catch (MissingResourceException e) {
            return fallback;
        }
    }
}
