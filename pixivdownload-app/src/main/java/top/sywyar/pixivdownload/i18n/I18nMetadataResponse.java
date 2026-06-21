package top.sywyar.pixivdownload.i18n;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

@Getter
@AllArgsConstructor
public class I18nMetadataResponse {

    private final String currentLang;
    private final String defaultLang;
    private final String languageCookieName;
    private final String languageParamName;
    private final List<LocaleOptionResponse> supportedLocales;
    private final List<String> supportedNamespaces;
}
