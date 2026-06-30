package top.sywyar.pixivdownload.plugin.api.gui;

/**
 * Closeable listener handle returned by a GUI theme contribution.
 */
@FunctionalInterface
public interface GuiThemeListenerSession extends AutoCloseable {

    /** Shared no-op session for themes without background listeners. */
    static GuiThemeListenerSession none() {
        return () -> {
        };
    }

    /**
     * Releases listener resources. Implementations must be idempotent.
     */
    @Override
    void close();
}
