package top.sywyar.pixivdownload.plugin.api.gui;

/**
 * Built-in layout hints for rich GUI configuration sections.
 */
public enum GuiConfigSectionLayout {
    /** Render fields in declaration order as one vertical form. */
    FIELD_LIST,
    /** Render fields grouped by card id behind a section-local switcher. */
    CARD_SWITCHER,
    /** Render compact boolean-like controls in a grid while preserving section order. */
    COMPACT_GRID
}
