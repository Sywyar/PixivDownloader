package top.sywyar.pixivdownload.gui.config;

/**
 * 配置字段的 UI 控件类型。
 * config.yaml 中的配置项永远不注释，commentWhenEmpty 恒为 false。
 */
public enum FieldType {
    PATH_DIR,
    PATH_FILE,
    PORT,
    BOOL,
    INT,
    STRING,
    ENUM,
    PASSWORD;

    /** 始终为 false：config.yaml 中不允许出现被注释的配置行。 */
    public final boolean commentWhenEmpty = false;
}
