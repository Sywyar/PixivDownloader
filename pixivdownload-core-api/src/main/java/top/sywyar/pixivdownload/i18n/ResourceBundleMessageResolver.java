package top.sywyar.pixivdownload.i18n;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 面向可选插件、仅依赖 JDK 的消息解析器。
 * <p>
 * 解析插件自有资源包时优先使用插件类加载器；共享键再回退到宿主解析器。
 * <p>
 * 资源解析契约：resolver 只按 {@link LocaleBundlePolicy#resourceSuffixChain(Locale)}
 * 返回的 suffix 顺序精确加载文件（suffix 为空 = root 文件），不做 JDK 属性包的隐式
 * 默认语言回退，也不触发 JVM 系统默认语言。第一方代码必须显式传入 host catalog 构造的
 * 策略；旧构造器仅为第三方插件二进制兼容，走 {@link LegacyLocaleBundlePolicy}
 * （只保证旧版 root=zh-CN + {@code _en}=en-US 约定）。
 * 物理文件按解析器实例缓存，解析器实例与插件 ClassLoader 同生命周期，不残留全局引用。
 */
public final class ResourceBundleMessageResolver implements MessageResolver {

    private final MessageResolver fallback;
    private final ClassLoader classLoader;
    private final List<String> baseNames;
    private final LocaleBundlePolicy policy;
    private final ConcurrentHashMap<String, Map<String, String>> bundleCache = new ConcurrentHashMap<>();

    /**
     * 创建 {@code ResourceBundleMessageResolver} 实例。
     *
     * @param fallback 回退项
     * @param classLoader 类加载器
     * @param baseNames 基础名称列表
     */
    public ResourceBundleMessageResolver(MessageResolver fallback, ClassLoader classLoader, List<String> baseNames) {
        this(fallback, classLoader, baseNames, LegacyLocaleBundlePolicy.INSTANCE);
    }

    /**
     * 创建 {@code ResourceBundleMessageResolver} 实例。
     *
     * @param fallback 回退项
     * @param classLoader 类加载器
     * @param baseNames 基础名称列表
     * @param policy 策略
     */
    public ResourceBundleMessageResolver(MessageResolver fallback, ClassLoader classLoader, List<String> baseNames,
                                         LocaleBundlePolicy policy) {
        this.fallback = fallback;
        this.classLoader = classLoader == null ? ResourceBundleMessageResolver.class.getClassLoader() : classLoader;
        this.baseNames = baseNames == null ? List.of() : List.copyOf(baseNames);
        this.policy = policy == null ? LegacyLocaleBundlePolicy.INSTANCE : policy;
    }

    /**
     * 创建并返回 {@code ResourceBundleMessageResolver} 实例。
     *
     * @param fallback 回退项
     * @param classLoader 类加载器
     * @param baseNames 基础名称列表
     * @return 方法返回的 {@code ResourceBundleMessageResolver} 实例
     */
    public static ResourceBundleMessageResolver of(MessageResolver fallback, ClassLoader classLoader,
                                                   String... baseNames) {
        return new ResourceBundleMessageResolver(fallback, classLoader,
                baseNames == null ? List.of() : List.of(baseNames), LegacyLocaleBundlePolicy.INSTANCE);
    }

    /**
     * 创建并返回 {@code ResourceBundleMessageResolver} 实例。
     *
     * @param fallback 回退项
     * @param classLoader 类加载器
     * @param policy 策略
     * @param baseNames 基础名称列表
     * @return 方法返回的 {@code ResourceBundleMessageResolver} 实例
     */
    public static ResourceBundleMessageResolver of(MessageResolver fallback, ClassLoader classLoader,
                                                   LocaleBundlePolicy policy, String... baseNames) {
        return new ResourceBundleMessageResolver(fallback, classLoader,
                baseNames == null ? List.of() : List.of(baseNames), policy);
    }

    @Override
    public Locale currentLocale() {
        return fallback == null ? Locale.getDefault() : fallback.currentLocale();
    }

    @Override
    public Locale normalizeLocale(Locale locale) {
        if (fallback != null) {
            return fallback.normalizeLocale(locale);
        }
        return policy.normalize(locale == null ? Locale.getDefault() : locale);
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
        Locale effectiveLocale = policy.normalize(locale == null ? Locale.getDefault() : locale);
        for (String baseName : baseNames) {
            if (baseName == null || baseName.isBlank()) {
                continue;
            }
            for (String suffix : policy.resourceSuffixChain(effectiveLocale)) {
                String resourceName = resourceName(baseName, suffix);
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
        return getOrDefault(policy.fallbackLocale(), code, code, args);
    }

    /** 物理文件：suffix 为空 → {@code baseName.properties}，否则 {@code baseName_<suffix>.properties}。 */
    private static String resourceName(String baseName, String suffix) {
        String basePath = baseName.replace('.', '/');
        return suffix == null || suffix.isEmpty()
                ? basePath + ".properties"
                : basePath + "_" + suffix + ".properties";
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
