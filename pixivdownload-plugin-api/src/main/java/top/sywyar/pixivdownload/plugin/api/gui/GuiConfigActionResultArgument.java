package top.sywyar.pixivdownload.plugin.api.gui;

/**
 * 插入 GUI 配置动作结果提示的一项参数。
 *
 * @param source 读取值的数据源
 * @param path {@link GuiConfigActionResultSource#JSON} 使用的可选点分隔 JSON 路径；宿主拒绝类似凭证、
 *             原始错误和 HTML 的路径
 * @param defaultValue 数据源为空白或缺失时使用的值；宿主以有界纯文本渲染
 */
public record GuiConfigActionResultArgument(
        GuiConfigActionResultSource source,
        String path,
        String defaultValue
) {

    /**
     * 规范化数据源、路径和默认值。
     *
     * @param source 数据来源
     * @param path 路径
     * @param defaultValue 默认值
     */
    public GuiConfigActionResultArgument {
        source = source == null ? GuiConfigActionResultSource.JSON : source;
        path = path == null ? "" : path.trim();
        defaultValue = defaultValue == null ? "" : defaultValue;
    }

    /**
     * 创建从 JSON 路径读取值的参数。
     *
     * @param path 点分隔 JSON 路径
     * @return JSON 参数
     */
    public static GuiConfigActionResultArgument json(String path) {
        return new GuiConfigActionResultArgument(GuiConfigActionResultSource.JSON, path, "");
    }

    /**
     * 创建读取结果摘要的参数。
     *
     * @return 摘要参数
     */
    public static GuiConfigActionResultArgument summary() {
        return new GuiConfigActionResultArgument(GuiConfigActionResultSource.SUMMARY, "", "");
    }
}
