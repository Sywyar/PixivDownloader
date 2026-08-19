package top.sywyar.pixivdownload.plugin.api.gui;

import top.sywyar.pixivdownload.plugin.api.gui.document.DesktopUiDocument;
import top.sywyar.pixivdownload.plugin.api.gui.document.DesktopUiNode;

/**
 * Host-owned, toolkit-neutral desktop UI state.
 * Renderers read immutable documents and return typed events; unknown or stale events are ignored by the host.
 */
public interface DesktopUiModel {
    /**
     * Returns the current complete desktop UI document.
     *
     * @return immutable desktop UI document
     */
    DesktopUiDocument document();

    /**
     * Returns the monotonic revision changed whenever {@link #document()} must be rendered again.
     *
     * @return current document revision
     */
    long revision();

    /**
     * Dispatches one renderer event to the host-owned model.
     *
     * @param event typed renderer event
     */
    void dispatch(DesktopUiNode.Event event);
}
