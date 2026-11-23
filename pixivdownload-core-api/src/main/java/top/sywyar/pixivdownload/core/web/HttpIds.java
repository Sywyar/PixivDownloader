package top.sywyar.pixivdownload.core.web;

import java.util.LinkedHashSet;
import java.util.Set;

/** HTTP 查询和路径参数中的正整数 ID，保留 CSV 的顺序。 */
public final class HttpIds {
    private HttpIds() {}

    /**
     * 解析仅含 ASCII 数字的正整数 ID。
     *
     * @param in 参数在请求中的位置
     * @param field 参数名称
     * @param value 要解析的十进制文本
     * @return 解析后的正整数
     * @throws HttpParameterException 文本缺失、格式不符或数值超出正长整数范围时抛出
     */
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

    /**
     * 按输入顺序解析查询参数中的 ID，去重并忽略每个 ID 两端的空白。
     *
     * @param field 查询参数名
     * @param csv 逗号分隔的 ID，未提供时为 {@code null}
     * @return 有序 ID 集合，未提供时为空
     * @throws HttpParameterException 任一项为空或不是有效正整数时抛出
     */
    public static Set<Long> queryIds(String field, String csv) {
        if (csv == null) return Set.of();
        Set<Long> ids = new LinkedHashSet<>();
        for (String item : csv.split(",", -1)) ids.add(positive("query", field, item.trim()));
        return ids;
    }

    /**
     * 合并列表和单项查询参数，保留首次出现的顺序。
     *
     * @param field 列表参数名
     * @param csv 逗号分隔的 ID，可为 {@code null}
     * @param singleField 单项参数名
     * @param single 追加到列表末尾的 ID，可为 {@code null}
     * @return 去重后的 ID 集合
     * @throws HttpParameterException 单项或列表中的 ID 无效时抛出
     */
    public static Set<Long> queryIds(String field, String csv, String singleField, Long single) {
        if (single == null) return queryIds(field, csv);
        if (single <= 0) throw new HttpParameterException("query", singleField, "invalid_value");
        if (csv == null) return Set.of(single);
        Set<Long> ids = queryIds(field, csv);
        ids.add(single);
        return ids;
    }
}
