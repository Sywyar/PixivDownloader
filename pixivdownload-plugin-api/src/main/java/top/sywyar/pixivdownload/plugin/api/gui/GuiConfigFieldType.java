package top.sywyar.pixivdownload.plugin.api.gui;

/** 以纯数据契约向插件开放的 GUI 配置字段控件类型。 */
public enum GuiConfigFieldType {
    /** 目录路径选择。 */
    PATH_DIR,
    /** 文件路径选择。 */
    PATH_FILE,
    /** 网络端口。 */
    PORT,
    /** 布尔开关。 */
    BOOL,
    /** 整数输入。 */
    INT,
    /** 普通字符串输入。 */
    STRING,
    /** 本地时间输入（HH:mm）。 */
    TIME,
    /** 受控枚举选择。 */
    ENUM,
    /** 敏感密码输入。 */
    PASSWORD
}
