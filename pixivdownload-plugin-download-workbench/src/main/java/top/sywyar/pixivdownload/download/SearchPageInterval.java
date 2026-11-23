package top.sywyar.pixivdownload.download;

import tools.jackson.databind.JsonNode;

/** 两个 HTTP 输入都使用以毫秒为单位的非负 32 位整数。 */
public final class SearchPageInterval {
    private SearchPageInterval() {}

    public static boolean isValid(JsonNode value) {
        return value.canConvertToExactIntegral() && value.canConvertToInt() && value.asInt() >= 0;
    }
}
