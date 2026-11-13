package top.sywyar.pixivdownload.plugin.api.web;

/**
 * 路由 owner 提供的程序权限；完整身份由宿主绑定的插件 ID 与局部 ID 组成。
 * @param id 稳定局部权限 ID，不接受通配符
 * @param labelKey 所属插件命名空间中的权限名称
 */
public record ProgramPermission(String id, String labelKey) {
    /** 校验局部权限及显示标签的稳定标识。 */
    public ProgramPermission {
        if (id == null || !id.matches("[a-z][a-z0-9.-]{0,95}"))
            throw new IllegalArgumentException("Invalid program permission id");
        if (labelKey == null || !labelKey.matches("[a-zA-Z][a-zA-Z0-9._-]{0,159}"))
            throw new IllegalArgumentException("Invalid program permission label");
    }
}
