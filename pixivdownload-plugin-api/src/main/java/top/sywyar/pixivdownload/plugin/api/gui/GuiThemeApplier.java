package top.sywyar.pixivdownload.plugin.api.gui;

/**
 * Applies a GUI theme. Implementations are invoked by
 * {@link GuiThemeContribution#applyOnEventDispatchThread()} after the caller has reached the AWT event dispatch
 * thread.
 */
@FunctionalInterface
public interface GuiThemeApplier {

    /**
     * Applies the theme to the current Swing UI state.
     *
     * @throws Exception when the theme cannot be applied
     */
    void apply() throws Exception;
}
