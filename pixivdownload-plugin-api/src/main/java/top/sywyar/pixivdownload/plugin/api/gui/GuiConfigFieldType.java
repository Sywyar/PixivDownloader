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
    /** 非空枚举多选，以逗号分隔的标量保存；选项值不得包含逗号。 */
    MULTI_ENUM,
    /** 敏感密码输入。 */
    PASSWORD;

    /** 校验多选标量，拒绝空选择、重复值和未声明的值。 */
    public static boolean validMultiSelection(String value, java.util.List<String> options) {
        if (value == null || value.isBlank() || options.isEmpty()
                || options.stream().anyMatch(option -> option == null || option.isBlank() || option.contains(","))) {
            return false;
        }
        var selected = java.util.Arrays.asList(value.split(",", -1));
        return new java.util.HashSet<>(selected).size() == selected.size()
                && options.containsAll(selected);
    }
}
