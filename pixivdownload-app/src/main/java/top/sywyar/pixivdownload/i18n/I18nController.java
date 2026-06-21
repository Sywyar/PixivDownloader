package top.sywyar.pixivdownload.i18n;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;

@RestController
@RequestMapping("/api/i18n")
@RequiredArgsConstructor
public class I18nController {

    private final WebI18nBundleRegistry bundleRegistry;
    private final WebI18nService webI18nService;

    @GetMapping("/meta")
    public I18nMetadataResponse metadata(Locale locale) {
        Locale currentLocale = AppLocale.normalize(locale);
        List<LocaleOptionResponse> locales = AppLocale.SUPPORTED_LOCALES.stream()
                .map(item -> new LocaleOptionResponse(
                        item.toLanguageTag(),
                        AppLocale.displayName(item, currentLocale)
                ))
                .toList();

        return new I18nMetadataResponse(
                currentLocale.toLanguageTag(),
                AppLocale.DEFAULT_LOCALE.toLanguageTag(),
                AppLocale.LANGUAGE_COOKIE_NAME,
                AppLocale.LANGUAGE_PARAM_NAME,
                locales,
                bundleRegistry.supportedNamespaces()
        );
    }

    @GetMapping("/messages/{namespace}")
    public I18nBundleResponse messages(@PathVariable String namespace, Locale locale) {
        return webI18nService.loadBundle(namespace, locale);
    }
}
