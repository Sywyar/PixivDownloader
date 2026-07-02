package top.sywyar.pixivdownload.plugin.api.gui;

/**
 * One argument inserted into a GUI config action result notice.
 *
 * @param source where to read the value from
 * @param path optional dot-separated JSON path for {@link GuiConfigActionResultSource#JSON}
 * @param defaultValue value used when the source is blank or missing
 */
public record GuiConfigActionResultArgument(
        GuiConfigActionResultSource source,
        String path,
        String defaultValue
) {

    public GuiConfigActionResultArgument {
        source = source == null ? GuiConfigActionResultSource.JSON : source;
        path = path == null ? "" : path.trim();
        defaultValue = defaultValue == null ? "" : defaultValue;
    }

    public static GuiConfigActionResultArgument json(String path) {
        return new GuiConfigActionResultArgument(GuiConfigActionResultSource.JSON, path, "");
    }

    public static GuiConfigActionResultArgument summary() {
        return new GuiConfigActionResultArgument(GuiConfigActionResultSource.SUMMARY, "", "");
    }
}
