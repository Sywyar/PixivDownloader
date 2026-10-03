package top.sywyar.pixivdownload.sdk.community.content;

import top.sywyar.pixivdownload.sdk.community.format.CommunityJson;
import top.sywyar.pixivdownload.sdk.community.format.ContractException;

import java.util.HashSet;
import java.util.Locale;
import java.util.Map;

/** 文档和链接标签共用的语言标识校验。 */
public final class ContentLocales {
    public static final int MAX_LOCALES = 16;
    private ContentLocales() { }

    public static void validate(Map<String, ?> values, String field) {
        if (values == null || values.isEmpty()) throw new ContractException("SCHEMA_INVALID", field);
        if (values.size() > MAX_LOCALES) throw CommunityJson.limit(field, MAX_LOCALES, "locales");
        var seen = new HashSet<String>();
        for (String tag : values.keySet()) {
            try {
                var locale = new Locale.Builder().setLanguageTag(tag).build();
                if (locale.getLanguage().isEmpty()) throw new IllegalArgumentException();
                if (!seen.add(locale.toLanguageTag().toLowerCase(Locale.ROOT))) {
                    throw new ContractException("DUPLICATE_KEY", field);
                }
            } catch (IllegalArgumentException failure) {
                throw new ContractException("LOCALE_INVALID", field);
            }
        }
    }
}
