package top.sywyar.pixivdownload.i18n;

import java.text.MessageFormat;
import java.util.List;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;

/**
 * Pure JDK message resolver for optional plugins.
 * <p>
 * It resolves plugin-owned bundles with the plugin classloader first, then falls
 * back to the host resolver for shared keys.
 */
public final class ResourceBundleMessageResolver implements MessageResolver {

    private static final ResourceBundle.Control NO_FALLBACK =
            ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES);

    private final MessageResolver fallback;
    private final ClassLoader classLoader;
    private final List<String> baseNames;

    public ResourceBundleMessageResolver(MessageResolver fallback, ClassLoader classLoader, List<String> baseNames) {
        this.fallback = fallback;
        this.classLoader = classLoader == null ? ResourceBundleMessageResolver.class.getClassLoader() : classLoader;
        this.baseNames = baseNames == null ? List.of() : List.copyOf(baseNames);
    }

    public static ResourceBundleMessageResolver of(MessageResolver fallback, ClassLoader classLoader,
                                                   String... baseNames) {
        return new ResourceBundleMessageResolver(fallback, classLoader,
                baseNames == null ? List.of() : List.of(baseNames));
    }

    @Override
    public Locale currentLocale() {
        return fallback == null ? Locale.getDefault() : fallback.currentLocale();
    }

    @Override
    public Locale normalizeLocale(Locale locale) {
        return fallback == null
                ? MessageResolver.super.normalizeLocale(locale)
                : fallback.normalizeLocale(locale);
    }

    @Override
    public String get(String code, Object... args) {
        return getOrDefault(currentLocale(), code, code, args);
    }

    @Override
    public String get(Locale locale, String code, Object... args) {
        return getOrDefault(locale, code, code, args);
    }

    @Override
    public String getOrDefault(String code, String defaultMessage, Object... args) {
        return getOrDefault(currentLocale(), code, defaultMessage, args);
    }

    @Override
    public String getOrDefault(Locale locale, String code, String defaultMessage, Object... args) {
        Locale effectiveLocale = normalizeLocale(locale);
        for (String baseName : baseNames) {
            if (baseName == null || baseName.isBlank()) {
                continue;
            }
            try {
                ResourceBundle bundle = ResourceBundle.getBundle(baseName, effectiveLocale, classLoader, NO_FALLBACK);
                if (bundle.containsKey(code)) {
                    return format(bundle.getString(code), effectiveLocale, args);
                }
            } catch (MissingResourceException ignored) {
                // Try next plugin bundle, then fallback.
            }
        }
        if (fallback != null) {
            return fallback.getOrDefault(effectiveLocale, code, defaultMessage, args);
        }
        return format(defaultMessage, effectiveLocale, args);
    }

    @Override
    public String getForLog(String code, Object... args) {
        return getOrDefault(normalizeLocale(Locale.getDefault()), code, code, args);
    }

    private static String format(String pattern, Locale locale, Object... args) {
        if (args == null || args.length == 0) {
            return pattern;
        }
        return new MessageFormat(pattern, locale).format(args);
    }
}
