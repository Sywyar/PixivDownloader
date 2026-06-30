package top.sywyar.pixivdownload.plugin.api.gui;

/**
 * Callback used by a theme contribution to report an appearance change observed by its own listener.
 */
@FunctionalInterface
public interface GuiThemeChangeListener {

    /**
     * Called when the theme contribution observes a new appearance.
     *
     * @param appearance the current appearance, never {@code null}
     */
    void appearanceChanged(GuiThemeAppearance appearance);
}
