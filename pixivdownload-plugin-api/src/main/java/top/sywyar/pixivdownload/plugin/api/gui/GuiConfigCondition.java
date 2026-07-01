package top.sywyar.pixivdownload.plugin.api.gui;

/**
 * One pure data condition evaluated by the host against the current config snapshot.
 *
 * @param key      config key to inspect
 * @param operator comparison operator
 * @param value    comparison value used by {@link GuiConfigConditionOperator#EQUALS} and
 *                 {@link GuiConfigConditionOperator#NOT_EQUALS}
 */
public record GuiConfigCondition(String key, GuiConfigConditionOperator operator, String value) {

    public static GuiConfigCondition isTrue(String key) {
        return new GuiConfigCondition(key, GuiConfigConditionOperator.TRUE, null);
    }

    public static GuiConfigCondition isFalse(String key) {
        return new GuiConfigCondition(key, GuiConfigConditionOperator.FALSE, null);
    }

    public static GuiConfigCondition equalsTo(String key, String value) {
        return new GuiConfigCondition(key, GuiConfigConditionOperator.EQUALS, value);
    }

    public static GuiConfigCondition notEqualsTo(String key, String value) {
        return new GuiConfigCondition(key, GuiConfigConditionOperator.NOT_EQUALS, value);
    }

    public static GuiConfigCondition blank(String key) {
        return new GuiConfigCondition(key, GuiConfigConditionOperator.BLANK, null);
    }

    public static GuiConfigCondition notBlank(String key) {
        return new GuiConfigCondition(key, GuiConfigConditionOperator.NOT_BLANK, null);
    }
}
