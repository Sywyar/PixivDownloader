package top.sywyar.pixivdownload.plugin.api.gui;

/**
 * Opens a closeable listener session for a GUI theme contribution.
 */
@FunctionalInterface
public interface GuiThemeListenerFactory {

    /**
     * Starts listening for changes relevant to this theme.
     *
     * @param listener callback receiving appearance changes
     * @return a closeable session; use {@link GuiThemeListenerSession#none()} when no listener is needed
     */
    GuiThemeListenerSession open(GuiThemeChangeListener listener);
}
