package top.sywyar.pixivdownload.gui.config;

/**
 * 配置字段的 UI 控件类型。
 * commentWhenEmpty: 值为空时是否在 config.yaml 中注释掉该行（不写入 Spring）
 */
public enum FieldType {
    PATH_DIR(true),
    PATH_FILE(true),
    PORT(false),
    BOOL(false),
    INT(false),
    STRING(false),
    ENUM(false),
    PASSWORD(true);

    public final boolean commentWhenEmpty;

    FieldType(boolean commentWhenEmpty) {
        this.commentWhenEmpty = commentWhenEmpty;
    }
}
