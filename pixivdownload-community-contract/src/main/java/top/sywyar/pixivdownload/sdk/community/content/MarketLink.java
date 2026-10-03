package top.sywyar.pixivdownload.sdk.community.content;

import com.fasterxml.jackson.annotation.JsonInclude;
import top.sywyar.pixivdownload.sdk.community.format.CommunityJson;
import top.sywyar.pixivdownload.sdk.community.format.CommunityValues;
import top.sywyar.pixivdownload.sdk.community.format.ContractException;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** 作者提供的展示链接；用途不参与源码身份或信任判定。 */
public record MarketLink(String kind, String url,
                         @JsonInclude(JsonInclude.Include.NON_NULL) Map<String, String> label) {
    public static final int MAX_LINKS = 16;
    public static final int MAX_LABEL_POINTS = 128;
    public MarketLink { label = label == null ? null : Map.copyOf(label); }

    public static void validate(List<MarketLink> links) {
        if (links == null) return;
        if (links.size() > MAX_LINKS) throw CommunityJson.limit("/market/links", MAX_LINKS, "items");
        for (var link : links) {
            if (link == null || link.kind == null || !Set.of("repository", "documentation", "issues", "custom").contains(link.kind)) {
                throw new ContractException("SCHEMA_INVALID", "/market/links/kind");
            }
            CommunityValues.https(link.url, false, "/market/links/url");
            if ("custom".equals(link.kind)) {
                ContentLocales.validate(link.label, "/market/links/label");
                for (String value : link.label.values()) {
                    if (value == null || value.isBlank() || value.codePoints().anyMatch(Character::isISOControl)) {
                        throw new ContractException("SCHEMA_INVALID", "/market/links/label");
                    }
                    if (value.codePointCount(0, value.length()) > MAX_LABEL_POINTS) {
                        throw CommunityJson.limit("/market/links/label", MAX_LABEL_POINTS, "codepoints");
                    }
                }
            } else if (link.label != null) throw new ContractException("SCHEMA_INVALID", "/market/links/label");
        }
        CommunityValues.unique(links, link -> link.kind + "\n" + link.url, "/market/links");
    }
}
