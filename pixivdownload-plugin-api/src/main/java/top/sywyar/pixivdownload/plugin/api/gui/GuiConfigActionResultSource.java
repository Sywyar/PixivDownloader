package top.sywyar.pixivdownload.plugin.api.gui;

/**
 * Data source used by declarative GUI config action result rules. Response-derived display values are limited to
 * bounded structured scalars admitted by the host; raw response bodies, credential-like keys, raw errors and HTML
 * are not projection sources.
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
    /** Bounded scalar read from an admitted non-sensitive JSON response path. */
    JSON,
    /** Bounded plain-text summary built from admitted scalar fields in an action-declared response array. */
    SUMMARY
}
