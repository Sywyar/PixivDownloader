package top.sywyar.pixivdownload.i18n;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pure JDK message resolver for optional plugins.
 * <p>
 * It resolves plugin-owned bundles with the plugin classloader first, then falls
 * back to the host resolver for shared keys.
 * <p>
 * 资源解析契约（仓库 bundle 约定：root 文件 = 开发源语言 zh-CN，{@code _en} = 全局回退语言 en-US）：
 * 对任意目标语言按 {@code [<lang>_<COUNTRY>, <lang>, en, root]} 顺序逐文件精确查找，
 * 不做 JDK 属性包的隐式默认语言回退，也不触发 JVM 系统默认语言；因此
 * 目标语言缺失时先回退英文、英文缺失才回退中文。物理文件按解析器实例缓存，
 * 解析器实例与插件 ClassLoader 同生命周期，不残留任何全局 ClassLoader 引用。
 */
public final class ResourceBundleMessageResolver implements MessageResolver {

    /** 全局回退语言（仓库约定：en-US 使用 {@code _en} 后缀文件）。 */
    private static final String FALLBACK_LANGUAGE = "en";

    /** root 文件的语言（仓库约定：无后缀文件 = 开发源语言 zh-CN）。root 语言不插入英文回退。 */
    private static final String ROOT_LANGUAGE = "zh";

    private final MessageResolver fallback;
    private final ClassLoader classLoader;
    private final List<String> baseNames;
    private final ConcurrentHashMap<String, Map<String, String>> bundleCache = new ConcurrentHashMap<>();

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
            for (String resourceName : candidateResources(baseName, effectiveLocale)) {
                String value = bundleCache.computeIfAbsent(resourceName, this::load).get(code);
                if (value != null) {
                    return format(value, effectiveLocale, args);
                }
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

    /**
     * 候选物理文件：目标语言的完整 / 语言级文件 → {@code _en}（回退语言）→ root（源语言）。
     * 例如 en-US → {@code <base>_en.properties}；ja-JP → {@code <base>_ja_JP} / {@code <base>_ja} /
     * {@code <base>_en} / {@code <base>}。
     */
    private static List<String> candidateResources(String baseName, Locale locale) {
        String basePath = baseName.replace('.', '/');
        List<String> names = new ArrayList<>(3);
        if (locale != null) {
            String language = locale.getLanguage();
            if (language != null && !language.isBlank()) {
                String country = locale.getCountry();
                if (country != null && !country.isBlank()) {
                    names.add(basePath + "_" + language + "_" + country + ".properties");
                }
                names.add(basePath + "_" + language + ".properties");
                // 目标语言既不是 root 语言也不是回退语言时，插入英文回退；root 语言绝不提前命中英文
                if (!ROOT_LANGUAGE.equalsIgnoreCase(language)
                        && !FALLBACK_LANGUAGE.equalsIgnoreCase(language)) {
                    names.add(basePath + "_" + FALLBACK_LANGUAGE + ".properties");
                }
            }
        }
        names.add(basePath + ".properties");
        return names;
    }

    private Map<String, String> load(String resourceName) {
        try (InputStream in = classLoader.getResourceAsStream(resourceName)) {
            if (in == null) {
                return Map.of();
            }
            try (InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                Properties properties = new Properties();
                properties.load(reader);
                Map<String, String> values = new java.util.LinkedHashMap<>();
                for (String key : properties.stringPropertyNames()) {
                    String normalized = key.startsWith("\uFEFF") ? key.substring(1) : key;
                    values.put(normalized, properties.getProperty(key));
                }
                return Map.copyOf(values);
            }
        } catch (IOException ignored) {
            return Map.of();
        }
    }

    private static String format(String pattern, Locale locale, Object... args) {
        if (args == null || args.length == 0) {
            return pattern;
        }
        return new MessageFormat(pattern, locale).format(args);
    }
}
