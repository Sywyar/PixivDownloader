package top.sywyar.pixivdownload.i18n;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 非 Spring 场景（GUI 面板、日志解析、静态工具类）的 exact bundle 加载器。
 * <p>
 * 只加载应用 classpath 上的 bundle 物理文件（{@code baseName + resourceSuffix + .properties}），
 * 不合并 root、不依赖 JVM 默认语言；缺少物理文件时返回空 map，由调用方按
 * {@link LocaleCatalog#fallbackChain} 自行回退。结果缓存为不可变 map。
 * <p>
 * 仅供 {@code i18n} 包及其直接消费方（GUI 文案解析器）使用，不是对外 API。
 */
public final class StaticBundleLoader {

    private static final Logger log = LoggerFactory.getLogger(StaticBundleLoader.class);
    private static final char BOM = '\uFEFF';

    private static final Map<String, Map<String, String>> CACHE = new ConcurrentHashMap<>();

    private StaticBundleLoader() {
    }

    public static Map<String, String> exact(String baseName, LocaleDescriptor descriptor) {
        if (baseName == null || baseName.isBlank() || descriptor == null) {
            return Map.of();
        }
        String resource = resourceName(baseName, descriptor.resourceSuffix());
        return CACHE.computeIfAbsent(resource, StaticBundleLoader::load);
    }

    private static Map<String, String> load(String resource) {
        try (InputStream in = StaticBundleLoader.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                return Map.of();
            }
            try (InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                Properties properties = new Properties();
                properties.load(reader);
                Map<String, String> values = new LinkedHashMap<>();
                for (String key : properties.stringPropertyNames()) {
                    values.put(normalizeKey(key), properties.getProperty(key));
                }
                return Collections.unmodifiableMap(values);
            }
        } catch (IOException e) {
            log.warn("Cannot load i18n bundle resource {} (falling back to empty bundle).", resource);
            return Map.of();
        }
    }

    private static String resourceName(String baseName, String resourceSuffix) {
        String basePath = baseName.replace('.', '/');
        String suffix = resourceSuffix == null ? "" : resourceSuffix;
        return suffix.isEmpty() ? basePath + ".properties" : basePath + "_" + suffix + ".properties";
    }

    private static String normalizeKey(String key) {
        if (key != null && !key.isEmpty() && key.charAt(0) == BOM) {
            return key.substring(1);
        }
        return key;
    }
}
