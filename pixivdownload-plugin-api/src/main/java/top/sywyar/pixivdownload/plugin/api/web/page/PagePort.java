package top.sywyar.pixivdownload.plugin.api.web.page;

import java.util.Objects;

/**
 * 实例间的纯值端口。两端的独立契约身份和 Schema 必须相同；兼容演进由契约包发布新身份。
 * Schema 使用 SDK 生成器的受控 JSON Schema 子集，声明中不包含执行代码。
 *
 * @param name 组件内唯一端口名
 * @param contractId 独立契约包定义的版本化身份
 * @param schema 该端口值的完整 JSON Schema
 */
public record PagePort(String name, String contractId, String schema) {
    /**
     * 创建不可变端口声明。
     * @param name 端口名
     * @param contractId 独立契约身份
     * @param schema 端口值 Schema
     */
    public PagePort {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(contractId, "contractId");
        Objects.requireNonNull(schema, "schema");
    }
}
