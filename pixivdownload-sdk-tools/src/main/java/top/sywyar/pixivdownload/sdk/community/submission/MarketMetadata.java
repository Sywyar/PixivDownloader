package top.sywyar.pixivdownload.sdk.community.submission;

import com.fasterxml.jackson.annotation.JsonInclude;
import top.sywyar.pixivdownload.sdk.community.content.MarketLink;
import top.sywyar.pixivdownload.sdk.community.content.MarketContent;
import top.sywyar.pixivdownload.sdk.community.format.CommunityValues;
import top.sywyar.pixivdownload.sdk.community.format.ContractException;
import top.sywyar.pixivdownload.sdk.community.project.CommunityPaths;

import java.util.Collections;
import java.util.HashSet;
import java.util.IllformedLocaleException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 市场介绍保留语言映射与截图顺序；缺图不构成投稿错误。 */
public record MarketMetadata(String defaultLocale, Map<String, String> displayName, Map<String, String> summary,
                             Map<String, String> description, String category, List<String> tags,
                             String homepageUrl, Image icon, List<Image> screenshots,
                             @JsonInclude(JsonInclude.Include.NON_NULL) List<MarketLink> links) {
    public MarketMetadata {
        displayName = freeze(displayName);
        summary = freeze(summary);
        description = description == null ? null : freeze(description);
        tags = List.copyOf(tags);
        screenshots = screenshots == null ? null : List.copyOf(screenshots);
        links = links == null ? null : List.copyOf(links);
    }

    public MarketMetadata(String defaultLocale, Map<String, String> displayName, Map<String, String> summary,
                          Map<String, String> description, String category, List<String> tags,
                          String homepageUrl, Image icon, List<Image> screenshots) {
        this(defaultLocale, displayName, summary, description, category, tags, homepageUrl, icon, screenshots, null);
    }

    public record Image(String path, Map<String, String> alt,
                        @JsonInclude(JsonInclude.Include.NON_NULL) MarketContent.Asset asset) {
        public Image { alt = freeze(alt); }
        public Image(String path, Map<String, String> alt) { this(path, alt, null); }
        public void validate() {
            CommunityPaths.relative(path, false); locales(alt);
            if (asset != null) asset.validate(true);
        }
    }

    public void validate() {
        locale(defaultLocale);
        locales(displayName);
        locales(summary);
        if (!displayName.containsKey(defaultLocale) || !summary.containsKey(defaultLocale)) {
            throw ContractException.invalid("LOCALE_DEFAULT_MISSING", "/market/defaultLocale");
        }
        if (description != null) locales(description);
        ControlledCatalogs.require(ControlledCatalogs.CATEGORIES, category, "/market/category");
        for (String tag : tags) ControlledCatalogs.require(ControlledCatalogs.TAGS, tag, "/market/tags");
        if (homepageUrl != null) CommunityValues.https(homepageUrl, false, "/market/homepageUrl");
        if (icon != null) icon.validate();
        if (screenshots != null) screenshots.forEach(Image::validate);
        MarketLink.validate(links);
    }

    /** 保持市场现有的目标语言、主语言、中文、英文、首项回退顺序。 */
    public static String text(Map<String, String> translations, String language, String fallback) {
        if (translations == null || translations.isEmpty()) return fallback;
        for (String candidate : List.of(language, language.split("-")[0], "zh", "en")) {
            if (translations.containsKey(candidate)) return translations.get(candidate);
        }
        return translations.values().iterator().next();
    }

    private static Map<String, String> freeze(Map<String, String> value) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(value));
    }

    private static void locales(Map<String, String> value) {
        var names = new HashSet<String>();
        for (String name : value.keySet()) {
            locale(name);
            if (!names.add(name.toLowerCase(Locale.ROOT))) throw ContractException.invalid("DUPLICATE_KEY", name);
        }
    }

    private static void locale(String value) {
        try { new Locale.Builder().setLanguageTag(value).build(); }
        catch (IllformedLocaleException e) { throw ContractException.invalid("LOCALE_INVALID", value); }
    }
}
