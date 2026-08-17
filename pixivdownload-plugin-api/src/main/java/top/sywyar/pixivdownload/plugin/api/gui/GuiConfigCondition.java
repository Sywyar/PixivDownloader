package top.sywyar.pixivdownload.plugin.api.gui;

/**
 * 由宿主针对当前配置快照求值的纯数据条件。插件贡献的条件只能引用同一插件拥有的字段。
 *
 * @param key 要检查的配置 key
 * @param operator 比较运算符
 * @param value {@link GuiConfigConditionOperator#EQUALS} 和
 *              {@link GuiConfigConditionOperator#NOT_EQUALS} 使用的比较值
 */
public record GuiConfigCondition(String key, GuiConfigConditionOperator operator, String value) {

    /**
     * 创建要求字段为真的条件。
     *
     * @param key 配置 key
     * @return 真值条件
     */
    public static GuiConfigCondition isTrue(String key) {
        return new GuiConfigCondition(key, GuiConfigConditionOperator.TRUE, null);
    }

    /**
     * 创建要求字段为假的条件。
     *
     * @param key 配置 key
     * @return 假值条件
     */
    public static GuiConfigCondition isFalse(String key) {
        return new GuiConfigCondition(key, GuiConfigConditionOperator.FALSE, null);
    }

    /**
     * 创建要求字段等于指定值的条件。
     *
     * @param key 配置 key
     * @param value 预期值
     * @return 相等条件
     */
    public static GuiConfigCondition equalsTo(String key, String value) {
        return new GuiConfigCondition(key, GuiConfigConditionOperator.EQUALS, value);
    }

    /**
     * 创建要求字段不等于指定值的条件。
     *
     * @param key 配置 key
     * @param value 排除值
     * @return 不相等条件
     */
    public static GuiConfigCondition notEqualsTo(String key, String value) {
        return new GuiConfigCondition(key, GuiConfigConditionOperator.NOT_EQUALS, value);
    }

    /**
     * 创建要求字段为空白的条件。
     *
     * @param key 配置 key
     * @return 空白条件
     */
    public static GuiConfigCondition blank(String key) {
        return new GuiConfigCondition(key, GuiConfigConditionOperator.BLANK, null);
    }

    /**
     * 创建要求字段不为空白的条件。
     *
     * @param key 配置 key
     * @return 非空白条件
     */
    public static GuiConfigCondition notBlank(String key) {
        return new GuiConfigCondition(key, GuiConfigConditionOperator.NOT_BLANK, null);
    }
}
