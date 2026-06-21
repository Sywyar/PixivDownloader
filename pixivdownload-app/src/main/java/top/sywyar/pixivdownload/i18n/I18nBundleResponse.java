package top.sywyar.pixivdownload.i18n;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Map;

@Getter
@AllArgsConstructor
public class I18nBundleResponse {

    private final String namespace;
    private final String currentLang;
    private final String defaultLang;
    private final Map<String, String> messages;
}
