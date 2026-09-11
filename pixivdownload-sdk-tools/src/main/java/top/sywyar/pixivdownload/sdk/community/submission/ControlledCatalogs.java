package top.sywyar.pixivdownload.sdk.community.submission;

import com.fasterxml.jackson.databind.JsonNode;
import top.sywyar.pixivdownload.sdk.community.format.CommunityJson;
import top.sywyar.pixivdownload.sdk.community.format.ContractException;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.Set;

/** 与工具一起固定分发的目录；不在校验期间下载或修改目录。 */
public final class ControlledCatalogs {
    private static final JsonNode DATA = resource("catalogs.json");
    public static final String VERSION = DATA.get("catalogVersion").textValue();
    public static final Set<String> CATEGORIES = values(DATA, "categories");
    public static final Set<String> TAGS = values(DATA, "tags");
    public static final Set<String> RISK_SIGNALS = values(DATA, "riskSignals");
    private ControlledCatalogs() { }

    static JsonNode resource(String path) {
        try (var input = ControlledCatalogs.class.getResourceAsStream("/community/v1/" + path)) {
            if (input == null) throw new IllegalStateException("missing contract resource: " + path);
            byte[] bytes = input.readAllBytes();
            return CommunityJson.strictTree(bytes, bytes.length);
        } catch (IOException e) { throw new IllegalStateException(e); }
    }

    static Set<String> values(JsonNode node, String key) {
        var result = new LinkedHashSet<String>();
        for (JsonNode item : node.get(key)) result.add(item.textValue());
        return Set.copyOf(result);
    }

    public static void require(Set<String> allowed, String value, String field) {
        if (!allowed.contains(value)) throw ContractException.invalid("CATALOG_VALUE_UNKNOWN", field);
    }
}
