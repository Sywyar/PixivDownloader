package top.sywyar.pixivdownload.i18n;

import java.util.Locale;

/**
 * Minimal message lookup contract shared with optional plugins.
 */
public interface MessageResolver {

    /**
     * Returns the locale associated with the current execution context.
     */
    default Locale currentLocale() {
        return Locale.getDefault();
    }

    /**
     * Normalizes an explicitly requested locale to the resolver's supported locale set.
     */
    default Locale normalizeLocale(Locale locale) {
        return locale == null ? currentLocale() : locale;
    }

    String get(String code, Object... args);

    String get(Locale locale, String code, Object... args);

    String getOrDefault(String code, String defaultMessage, Object... args);

    String getOrDefault(Locale locale, String code, String defaultMessage, Object... args);

    String getForLog(String code, Object... args);
}
