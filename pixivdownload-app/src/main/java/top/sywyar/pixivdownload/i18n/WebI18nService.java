package top.sywyar.pixivdownload.i18n;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class WebI18nService {

    private static final char BOM = '\uFEFF';

    private final WebI18nBundleRegistry bundleRegistry;

    public I18nBundleResponse loadBundle(String namespace, Locale locale) {
        WebI18nBundleRegistry.RegisteredBundle registered = bundleRegistry.resolve(namespace);
        if (registered == null) {
            throw LocalizedException.badRequest(
                    "i18n.namespace.unsupported",
                    "Unsupported i18n namespace: " + namespace,
                    namespace
            );
        }

        Locale effectiveLocale = AppLocale.normalize(locale);
        Map<String, String> messages = new LinkedHashMap<>(registered.load(effectiveLocale));

        return new I18nBundleResponse(
                namespace,
                effectiveLocale.toLanguageTag(),
                AppLocale.DEFAULT_LOCALE.toLanguageTag(),
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
