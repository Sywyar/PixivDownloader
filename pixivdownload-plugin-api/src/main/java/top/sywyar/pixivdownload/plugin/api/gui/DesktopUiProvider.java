package top.sywyar.pixivdownload.plugin.api.gui;

/** Process-lifetime desktop UI supplied by an external plugin. */
public interface DesktopUiProvider {
    /**
     * Returns the stable provider id used by {@code app.gui-provider}.
     *
     * @return stable provider id
     */
    String id();

    /**
     * Returns whether this provider is the default when no id is configured.
     *
     * @return whether this is the default provider
     */
    default boolean defaultProvider() { return false; }

    /**
     * Starts the process-lifetime desktop UI.
     *
     * @param context immutable host startup context
     * @return the active UI session
     * @throws Exception when the UI cannot be started
     */
    DesktopUiSession launch(DesktopUiContext context) throws Exception;
}
