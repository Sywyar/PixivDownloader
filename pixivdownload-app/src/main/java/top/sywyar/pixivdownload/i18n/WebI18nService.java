package top.sywyar.pixivdownload.i18n;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Collection;
import java.util.Locale;
import java.util.Map;

@Service
public class WebI18nService {

    private static final char BOM = '\uFEFF';

    private final WebI18nBundleRegistry bundleRegistry;
    private final LocaleCatalog catalog;

    public WebI18nService(WebI18nBundleRegistry bundleRegistry) {
        this(bundleRegistry, LocaleCatalog.defaultCatalog());
    }

    @Autowired
    public WebI18nService(WebI18nBundleRegistry bundleRegistry, LocaleCatalog catalog) {
        this.bundleRegistry = bundleRegistry;
        this.catalog = catalog == null ? LocaleCatalog.defaultCatalog() : catalog;
    }

    public I18nBundleResponse loadBundle(String namespace, Locale locale) {
        WebI18nBundleRegistry.RegisteredBundle registered = requireBundle(namespace);
        LocaleDescriptor effectiveLocale = catalog.resolve(locale);
        Map<String, String> messages = new LinkedHashMap<>(registered.load(effectiveLocale.toLocale()));

        return new I18nBundleResponse(
                namespace,
                effectiveLocale.tag(),
                catalog.defaultLocale().tag(),
                messages
        );
    }

    /** 在同一 namespace 快照中读取指定字段，保持完整词典接口的语言回退和异常语义。 */
    public Map<String, String> loadMessages(String namespace, Locale locale, Collection<String> keys) {
        return requireBundle(namespace).loadMessages(catalog.resolve(locale).toLocale(), keys);
    }

    private WebI18nBundleRegistry.RegisteredBundle requireBundle(String namespace) {
        WebI18nBundleRegistry.RegisteredBundle registered = bundleRegistry.resolve(namespace);
        if (registered == null) {
            throw LocalizedException.badRequest(
                    "i18n.namespace.unsupported",
                    "Unsupported i18n namespace: " + namespace,
                    namespace
            );
        }

        return registered;
    }

    static String normalizeKey(String key) {
        if (key != null && !key.isEmpty() && key.charAt(0) == BOM) {
            return key.substring(1);
        }
        return key;
    }
}
