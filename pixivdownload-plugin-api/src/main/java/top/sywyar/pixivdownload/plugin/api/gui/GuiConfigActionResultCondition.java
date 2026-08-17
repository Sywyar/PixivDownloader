package top.sywyar.pixivdownload.plugin.api.gui;

/**
 * GUI 配置动作结果规则中的一个条件。
 *
 * @param source 读取值的数据源
 * @param path {@link GuiConfigActionResultSource#JSON} 使用的可选点分隔 JSON 路径；宿主拒绝类似凭证、
 *             原始错误和 HTML 的路径
 * @param operator 比较运算符
 * @param value 值比较运算符使用的比较值
 */
public record GuiConfigActionResultCondition(
        GuiConfigActionResultSource source,
        String path,
        GuiConfigActionResultOperator operator,
        String value
) {

    /**
     * 规范化数据源、路径、运算符和比较值。
     *
     * @param source 数据来源
     * @param path 路径
     * @param operator 运算符
     * @param value 值
     */
    public GuiConfigActionResultCondition {
        source = source == null ? GuiConfigActionResultSource.JSON : source;
        path = path == null ? "" : path.trim();
        operator = operator == null ? GuiConfigActionResultOperator.TRUE : operator;
        value = value == null ? "" : value;
    }

    /**
     * 创建端点可达性条件。
     *
     * @param expected 预期是否可达
     * @return 可达性条件
     */
    public static GuiConfigActionResultCondition reachable(boolean expected) {
        return new GuiConfigActionResultCondition(
                GuiConfigActionResultSource.REACHABLE,
                "",
                expected ? GuiConfigActionResultOperator.TRUE : GuiConfigActionResultOperator.FALSE,
                "");
    }

    /**
     * 创建 HTTP 状态是否为 2xx 的条件。
     *
     * @param expected 预期是否为 2xx
     * @return HTTP 成功状态条件
     */
    public static GuiConfigActionResultCondition http2xx(boolean expected) {
        return new GuiConfigActionResultCondition(
                GuiConfigActionResultSource.HTTP_2XX,
                "",
                expected ? GuiConfigActionResultOperator.TRUE : GuiConfigActionResultOperator.FALSE,
                "");
    }

    /**
     * 创建要求 JSON 值为真的条件。
     *
     * @param path 点分隔 JSON 路径
     * @return JSON 真值条件
     */
    public static GuiConfigActionResultCondition jsonTrue(String path) {
        return new GuiConfigActionResultCondition(
                GuiConfigActionResultSource.JSON, path, GuiConfigActionResultOperator.TRUE, "");
    }

    /**
     * 创建要求 JSON 值为假的条件。
     *
     * @param path 点分隔 JSON 路径
     * @return JSON 假值条件
     */
    public static GuiConfigActionResultCondition jsonFalse(String path) {
        return new GuiConfigActionResultCondition(
                GuiConfigActionResultSource.JSON, path, GuiConfigActionResultOperator.FALSE, "");
    }

    /**
     * 创建要求 JSON 值等于指定值的条件。
     *
     * @param path 点分隔 JSON 路径
     * @param value 预期值
     * @return JSON 相等条件
     */
    public static GuiConfigActionResultCondition jsonEquals(String path, String value) {
        return new GuiConfigActionResultCondition(
                GuiConfigActionResultSource.JSON, path, GuiConfigActionResultOperator.EQUALS, value);
    }

    /**
     * 创建要求 JSON 数值大于指定整数的条件。
     *
     * @param path 点分隔 JSON 路径
     * @param value 比较整数
     * @return JSON 大于条件
     */
    public static GuiConfigActionResultCondition jsonGreaterThan(String path, int value) {
        return new GuiConfigActionResultCondition(
                GuiConfigActionResultSource.JSON,
                path,
                GuiConfigActionResultOperator.GREATER_THAN,
                Integer.toString(value));
    }

    /**
     * 创建要求结果摘要包含指定文本的条件。
     *
     * @param value 要查找的文本
     * @return 摘要包含条件
     */
    public static GuiConfigActionResultCondition summaryContains(String value) {
        return new GuiConfigActionResultCondition(
                GuiConfigActionResultSource.SUMMARY, "", GuiConfigActionResultOperator.CONTAINS, value);
    }
}
