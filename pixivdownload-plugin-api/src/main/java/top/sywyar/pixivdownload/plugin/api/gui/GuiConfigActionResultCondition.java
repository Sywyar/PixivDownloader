package top.sywyar.pixivdownload.plugin.api.gui;

/**
 * One condition in a GUI config action result rule.
 *
 * @param source where to read the value from
 * @param path optional dot-separated JSON path for {@link GuiConfigActionResultSource#JSON}
 * @param operator comparison operator
 * @param value comparison value for value-based operators
 */
public record GuiConfigActionResultCondition(
        GuiConfigActionResultSource source,
        String path,
        GuiConfigActionResultOperator operator,
        String value
) {

    public GuiConfigActionResultCondition {
        source = source == null ? GuiConfigActionResultSource.JSON : source;
        path = path == null ? "" : path.trim();
        operator = operator == null ? GuiConfigActionResultOperator.TRUE : operator;
        value = value == null ? "" : value;
    }

    public static GuiConfigActionResultCondition reachable(boolean expected) {
        return new GuiConfigActionResultCondition(
                GuiConfigActionResultSource.REACHABLE,
                "",
                expected ? GuiConfigActionResultOperator.TRUE : GuiConfigActionResultOperator.FALSE,
                "");
    }

    public static GuiConfigActionResultCondition http2xx(boolean expected) {
        return new GuiConfigActionResultCondition(
                GuiConfigActionResultSource.HTTP_2XX,
                "",
                expected ? GuiConfigActionResultOperator.TRUE : GuiConfigActionResultOperator.FALSE,
                "");
    }

    public static GuiConfigActionResultCondition jsonTrue(String path) {
        return new GuiConfigActionResultCondition(
                GuiConfigActionResultSource.JSON, path, GuiConfigActionResultOperator.TRUE, "");
    }

    public static GuiConfigActionResultCondition jsonFalse(String path) {
        return new GuiConfigActionResultCondition(
                GuiConfigActionResultSource.JSON, path, GuiConfigActionResultOperator.FALSE, "");
    }

    public static GuiConfigActionResultCondition jsonEquals(String path, String value) {
        return new GuiConfigActionResultCondition(
                GuiConfigActionResultSource.JSON, path, GuiConfigActionResultOperator.EQUALS, value);
    }

    public static GuiConfigActionResultCondition jsonGreaterThan(String path, int value) {
        return new GuiConfigActionResultCondition(
                GuiConfigActionResultSource.JSON,
                path,
                GuiConfigActionResultOperator.GREATER_THAN,
                Integer.toString(value));
    }

    public static GuiConfigActionResultCondition summaryContains(String value) {
        return new GuiConfigActionResultCondition(
                GuiConfigActionResultSource.SUMMARY, "", GuiConfigActionResultOperator.CONTAINS, value);
    }
}
