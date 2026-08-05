package top.sywyar.pixivdownload.i18n;

import java.text.MessageFormat;
import java.util.Locale;
import java.util.Map;

/**
 * 面向日志 / GUI 面板的静态文案解析器（baseName {@code i18n.messages}）。
 * <p>
 * 不做 {@code ResourceBundle} 默认语言回退：按 catalog 回退链「目标语言 → fallback → source」
 * 逐层查找 exact bundle（{@link StaticBundleLoader}），目标语言优先。
 */
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
        LocaleCatalog catalog = LocaleCatalog.defaultCatalog();
        LocaleDescriptor target = catalog.resolve(locale);
        String pattern = defaultMessage != null ? defaultMessage : code;

        for (LocaleDescriptor descriptor : catalog.fallbackChain(target)) {
            Map<String, String> exact = StaticBundleLoader.exact(BASE_NAME, descriptor);
            String value = exact.get(code);
            if (value != null) {
                pattern = value;
                break;
            }
        }

        return new MessageFormat(pattern, target.toLocale())
                .format(args == null ? new Object[0] : args);
    }
}
