package top.sywyar.pixivdownload.core.web;

import java.util.LinkedHashSet;
import java.util.Set;

/** HTTP 查询和路径参数中的正整数 ID，保留 CSV 的顺序。 */
public final class HttpIds {
    private HttpIds() {}

    public static long positive(String in, String field, String value) {
        if (value == null || value.isEmpty()
                || !value.chars().allMatch(c -> c >= '0' && c <= '9'))
            throw new HttpParameterException(in, field, "invalid_value");
        try {
            long id = Long.parseLong(value);
            if (id > 0) return id;
        } catch (NumberFormatException ignored) {
            // 超过 long 上限的十进制数不是有效 ID。
        }
        throw new HttpParameterException(in, field, "invalid_value");
    }

    public static Set<Long> queryIds(String field, String csv) {
        if (csv == null) return Set.of();
        Set<Long> ids = new LinkedHashSet<>();
        for (String item : csv.split(",", -1)) ids.add(positive("query", field, item.trim()));
        return ids;
    }

    public static Set<Long> queryIds(String field, String csv, String singleField, Long single) {
        if (single == null) return queryIds(field, csv);
        if (single <= 0) throw new HttpParameterException("query", singleField, "invalid_value");
        if (csv == null) return Set.of(single);
        Set<Long> ids = queryIds(field, csv);
        ids.add(single);
        return ids;
    }
}
