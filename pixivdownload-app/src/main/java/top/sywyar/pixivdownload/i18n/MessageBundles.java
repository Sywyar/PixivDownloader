package top.sywyar.pixivdownload.i18n;

import java.text.MessageFormat;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;

public final class MessageBundles {

    private static final String BASE_NAME = "i18n.messages";

    private MessageBundles() {
    }

    public static String get(String code, Object... args) {
        return get(Locale.getDefault(), code, args);
    }

    public static String get(Locale locale, String code, Object... args) {
        return getOrDefault(locale, code, code, args);
    }

    public static String getOrDefault(Locale locale, String code, String defaultMessage, Object... args) {
        Locale normalizedLocale = AppLocale.normalize(locale);
        String pattern = defaultMessage != null ? defaultMessage : code;

        try {
            ResourceBundle bundle = ResourceBundle.getBundle(BASE_NAME, normalizedLocale);
            if (bundle.containsKey(code)) {
                pattern = bundle.getString(code);
            }
        } catch (MissingResourceException ignored) {
            // Fall back to the provided default message when the bundle is unavailable.
        }

        return new MessageFormat(pattern, normalizedLocale)
                .format(args == null ? new Object[0] : args);
    }
}
