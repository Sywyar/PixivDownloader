package top.sywyar.pixivdownload.i18n;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.TreeSet;

@Service
@RequiredArgsConstructor
public class WebI18nService {

    /**
     * 禁用 {@link ResourceBundle} 默认的"回退到 JVM 默认 locale"行为。
     * 例如 JVM 默认 locale 是 en-US 时，请求 zh-CN 的资源会先尝试
     * {@code common_zh_CN} / {@code common_zh}，找不到后会回退到默认 locale
     * 的 {@code common_en}（英文），永远到不了作为根 bundle 的 {@code common.properties}（中文）。
     * 用 {@link java.util.ResourceBundle.Control#getNoFallbackControl} 禁止这一步，
     * 让请求 locale 找不到对应文件时直接落到根 bundle。
     */
    private static final ResourceBundle.Control NO_FALLBACK_CONTROL =
            ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES);

    private final WebI18nBundleRegistry bundleRegistry;

    public I18nBundleResponse loadBundle(String namespace, Locale locale) {
        String baseName = bundleRegistry.resolveBaseName(namespace);
        if (baseName == null) {
            throw LocalizedException.badRequest(
                    "i18n.namespace.unsupported",
                    "Unsupported i18n namespace: " + namespace,
                    namespace
            );
        }

        Locale effectiveLocale = AppLocale.normalize(locale);
        ResourceBundle bundle = ResourceBundle.getBundle(baseName, effectiveLocale, NO_FALLBACK_CONTROL);

        Map<String, String> messages = new LinkedHashMap<>();
        for (String key : new TreeSet<>(bundle.keySet())) {
            messages.put(key, bundle.getString(key));
        }

        return new I18nBundleResponse(
                namespace,
                effectiveLocale.toLanguageTag(),
                AppLocale.DEFAULT_LOCALE.toLanguageTag(),
                messages
        );
    }
}
