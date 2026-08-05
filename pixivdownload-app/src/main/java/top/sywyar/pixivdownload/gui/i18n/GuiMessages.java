package top.sywyar.pixivdownload.gui.i18n;

import top.sywyar.pixivdownload.i18n.LocaleCatalog;
import top.sywyar.pixivdownload.i18n.LocaleDescriptor;
import top.sywyar.pixivdownload.i18n.StaticBundleLoader;

import java.text.MessageFormat;
import java.util.Locale;
import java.util.Map;

/**
 * GUI 文案解析器（baseName {@code i18n.gui}），全部经 {@link LocaleCatalog} 归一化。
 * <p>
 * 不做 {@code ResourceBundle} 默认语言回退：按 catalog 回退链「目标语言 → fallback → source」
 * 逐层查找 exact bundle，目标语言优先。
 */
public final class GuiMessages {

    private static final String BASE_NAME = "i18n.gui";

    private static volatile Locale localeOverride;

    private GuiMessages() {
    }

    public static Locale currentLocale() {
        LocaleCatalog catalog = LocaleCatalog.defaultCatalog();
        return localeOverride != null ? catalog.resolve(localeOverride).toLocale() : catalog.resolve(Locale.getDefault()).toLocale();
    }

    public static void setLocale(Locale locale) {
        localeOverride = locale;
    }

    public static void clearLocaleOverride() {
        localeOverride = null;
    }

    public static String get(String key, Object... args) {
        LocaleCatalog catalog = LocaleCatalog.defaultCatalog();
        LocaleDescriptor target = catalog.resolve(currentLocale());
        String pattern = key;
        for (LocaleDescriptor descriptor : catalog.fallbackChain(target)) {
            Map<String, String> exact = StaticBundleLoader.exact(BASE_NAME, descriptor);
            String value = exact.get(key);
            if (value != null) {
                pattern = value;
                break;
            }
        }
        return args == null || args.length == 0
                ? pattern
                : new MessageFormat(pattern, target.toLocale()).format(args);
    }
}
