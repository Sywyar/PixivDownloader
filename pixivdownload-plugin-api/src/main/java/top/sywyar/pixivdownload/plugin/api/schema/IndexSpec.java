package top.sywyar.pixivdownload.plugin.api.schema;

import java.util.List;

/**
 * 自有表索引的纯数据声明，由宿主 schema registry 校验并落地。
 *
 * @param name 显式索引名；{@link IndexOrigin#UNIQUE_CONSTRAINT} 来源时为 {@code null}
 */
public record IndexSpec(
        String name,
        IndexOrigin origin,
        boolean unique,
        List<String> columns
) {
    /**
     * 创建 {@code IndexSpec} 实例。
     *
     * @param name 名称
     * @param origin 来源
     * @param unique 唯一性
     * @param columns 列集合
     */
    public IndexSpec {
        columns = List.copyOf(columns);
    }
}
