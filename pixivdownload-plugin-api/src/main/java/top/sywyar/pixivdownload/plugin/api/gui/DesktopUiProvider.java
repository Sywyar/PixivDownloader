package top.sywyar.pixivdownload.plugin.api.gui;

import top.sywyar.pixivdownload.plugin.api.gui.document.DesktopUiNode;

import java.util.Set;

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
     * Returns the stable declarative node kinds this provider can render, including every semantic variant
     * defined by each advertised kind.
     * Providers must advertise every kind required by the host document before launch.
     *
     * @return immutable supported node-kind set
     */
    Set<DesktopUiNode.Kind> supportedNodeKinds();

    /**
     * Starts the process-lifetime desktop UI.
     *
     * @param context immutable host startup context
     * @return the active UI session
     * @throws Exception when the UI cannot be started
     */
    DesktopUiSession launch(DesktopUiContext context) throws Exception;
}
