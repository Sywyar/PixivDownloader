package top.sywyar.pixivdownload.gui.i18n;

import top.sywyar.pixivdownload.i18n.AppLocale;

import java.text.MessageFormat;
import java.util.Locale;
import java.util.ResourceBundle;

public final class GuiMessages {

    private static final String BASE_NAME = "i18n.gui";

    private static volatile Locale localeOverride;

    private GuiMessages() {
    }

    public static Locale currentLocale() {
        return localeOverride != null ? localeOverride : AppLocale.normalize(Locale.getDefault());
    }

    public static void setLocale(Locale locale) {
        localeOverride = AppLocale.normalize(locale);
    }

    public static void clearLocaleOverride() {
        localeOverride = null;
    }

    public static String get(String key, Object... args) {
        ResourceBundle bundle = ResourceBundle.getBundle(BASE_NAME, currentLocale());
        String pattern = bundle.containsKey(key) ? bundle.getString(key) : key;
        return args == null || args.length == 0
                ? pattern
                : new MessageFormat(pattern, currentLocale()).format(args);
    }
}
