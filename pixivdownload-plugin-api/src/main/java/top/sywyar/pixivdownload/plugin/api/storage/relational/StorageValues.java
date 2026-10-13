package top.sywyar.pixivdownload.plugin.api.storage.relational;

/** 提供者写入边界共用的值规范化；不改动调用方对象或二进制内容。 */
public final class StorageValues {
    private StorageValues() {}

    /**
     * 文本仅移除 U+0000；空值、空串、其他字符及所有非文本值保持原义。
     * 普通写入和迁移工作副本使用同一规则，回读和完整性摘要不调用此方法。
     * @param value 已通过中性类型校验的值
     * @return 规范化后的文本或原非文本值
     */
    public static Object normalize(Object value) {
        return value instanceof String text ? text.replace("\0", "") : value;
    }
}
