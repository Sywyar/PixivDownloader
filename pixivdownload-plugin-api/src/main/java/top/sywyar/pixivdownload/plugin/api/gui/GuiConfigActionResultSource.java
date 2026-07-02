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
    /** HTTP status formatted as a short display text, such as HTTP 500. */
    HTTP_STATUS_TEXT,
    /** Value read from the JSON response body by dot-separated path. */
    JSON,
    /** Raw response body text. */
    RAW_BODY,
    /** Summary built from an action-declared response array. */
    SUMMARY
}
