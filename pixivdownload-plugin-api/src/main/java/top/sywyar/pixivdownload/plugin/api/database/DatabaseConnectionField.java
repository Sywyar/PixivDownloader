package top.sywyar.pixivdownload.plugin.api.database;

import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigFieldType;

/**
 * 提供者所需连接参数的纯值声明，复用配置界面的字段类型。
 * 参数绑定到 {@link DatabaseConnectionSettings}，不授予其它宿主配置的写入权限。
 * @param key 连接设置键：database.jdbc-url、database.username 或 database.password
 * @param type 文本参数使用 STRING，凭据必须使用 PASSWORD
 */
public record DatabaseConnectionField(String key, GuiConfigFieldType type) {
    /**
     * 拒绝未知参数及会暴露凭据的类型声明。
     * @param key 连接设置键
     * @param type 字段类型
     */
    public DatabaseConnectionField {
        boolean valid = switch (key == null ? "" : key) {
            case "database.jdbc-url", "database.username" -> type == GuiConfigFieldType.STRING;
            case "database.password" -> type == GuiConfigFieldType.PASSWORD;
            default -> false;
        };
        if (!valid) throw new IllegalArgumentException("DATABASE_CONNECTION_FIELD_INVALID");
    }
}
