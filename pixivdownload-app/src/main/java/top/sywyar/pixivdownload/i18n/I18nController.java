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
    private final LocaleCatalog catalog;

    @GetMapping("/meta")
    public I18nMetadataResponse metadata(Locale locale) {
        LocaleDescriptor currentLocale = catalog.resolve(locale);
        List<LocaleOptionResponse> locales = catalog.visibleLocales().stream()
                .map(item -> new LocaleOptionResponse(
                        item.tag(),
                        item.aliases(),
                        item.nativeName(),
                        item.nativeName(),
                        item.direction(),
                        item.status().name()
                ))
                .toList();

        return new I18nMetadataResponse(
                currentLocale.tag(),
                catalog.sourceLocale().tag(),
                catalog.defaultLocale().tag(),
                catalog.fallbackLocale().tag(),
                catalog.languageCookieName(),
                catalog.languageParameterName(),
                locales,
                bundleRegistry.supportedNamespaces()
        );
    }

    @GetMapping("/messages/{namespace}")
    public I18nBundleResponse messages(@PathVariable String namespace, Locale locale) {
        return webI18nService.loadBundle(namespace, locale);
    }
}
