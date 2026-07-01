package top.sywyar.pixivdownload.i18n;

import java.util.Locale;

/**
 * Minimal message lookup contract shared with optional plugins.
 */
public interface MessageResolver {

    String get(String code, Object... args);

    String get(Locale locale, String code, Object... args);

    String getOrDefault(String code, String defaultMessage, Object... args);

    String getOrDefault(Locale locale, String code, String defaultMessage, Object... args);

    String getForLog(String code, Object... args);
}
