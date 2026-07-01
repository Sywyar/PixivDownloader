package top.sywyar.pixivdownload.plugin.api.gui;

/**
 * GUI configuration field control types exposed to plugins as a pure data contract.
 */
public enum GuiConfigFieldType {
    PATH_DIR,
    PATH_FILE,
    PORT,
    BOOL,
    INT,
    STRING,
    ENUM,
    PASSWORD
}
