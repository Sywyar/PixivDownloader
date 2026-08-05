package top.sywyar.pixivdownload.i18n;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
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
        WebI18nBundleRegistry.RegisteredBundle registered = bundleRegistry.resolve(namespace);
        if (registered == null) {
            throw LocalizedException.badRequest(
                    "i18n.namespace.unsupported",
                    "Unsupported i18n namespace: " + namespace,
                    namespace
            );
        }

        LocaleDescriptor effectiveLocale = catalog.resolve(locale);
        Map<String, String> messages = new LinkedHashMap<>(registered.load(effectiveLocale.toLocale()));

        return new I18nBundleResponse(
                namespace,
                effectiveLocale.tag(),
                catalog.defaultLocale().tag(),
                messages
        );
    }

    static String normalizeKey(String key) {
        if (key != null && !key.isEmpty() && key.charAt(0) == BOM) {
            return key.substring(1);
        }
        return key;
    }
}
