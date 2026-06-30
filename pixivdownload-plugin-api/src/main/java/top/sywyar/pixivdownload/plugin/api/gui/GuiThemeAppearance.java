package top.sywyar.pixivdownload.plugin.api.gui;

/**
 * GUI theme brightness classification reported by a theme contribution.
 */
public enum GuiThemeAppearance {
    /** Light appearance. */
    LIGHT,
    /** Dark appearance. */
    DARK,
    /** The theme follows the operating system appearance. */
    SYSTEM,
    /** The contribution cannot report a stable brightness classification. */
    UNKNOWN
}
