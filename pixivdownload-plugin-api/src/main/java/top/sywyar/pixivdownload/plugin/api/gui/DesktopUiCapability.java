package top.sywyar.pixivdownload.plugin.api.gui;

/** Stable semantic desktop-renderer capabilities negotiated independently from node kinds. */
public enum DesktopUiCapability {
    /** Split panes can be resized by the user. */
    SPLIT_USER_RESIZABLE,
    /** Tree branches can be expanded and collapsed. */
    TREE_EXPAND_COLLAPSE,
    /** Tables provide bounded-height scrolling suitable for large row sets. */
    TABLE_LARGE_DATA_SCROLL,
    /** Numeric input preserves numeric validation semantics. */
    INPUT_NUMERIC,
    /** Calendar-date input preserves date semantics. */
    INPUT_TEMPORAL_DATE,
    /** Time-of-day input preserves time semantics. */
    INPUT_TEMPORAL_TIME,
    /** Date-time input preserves combined date and time semantics. */
    INPUT_TEMPORAL_DATE_TIME,
    /** File inputs provide a native file picker. */
    INPUT_PATH_FILE,
    /** Directory inputs provide a native directory picker. */
    INPUT_PATH_DIRECTORY,
    /** Selection controls preserve every selected id. */
    SELECTION_MULTIPLE
}
