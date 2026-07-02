package top.sywyar.pixivdownload.plugin.api.gui;

/**
 * Data source used by declarative GUI config action result rules.
 */
public enum GuiConfigActionResultSource {
    /** Whether the GUI endpoint was reachable. */
    REACHABLE,
    /** Whether the HTTP status code is 2xx. */
    HTTP_2XX,
    /** HTTP status code as an integer. */
    HTTP_STATUS,
    /** Value read from the JSON response body by dot-separated path. */
    JSON,
    /** Summary built from an action-declared response array. */
    SUMMARY
}
