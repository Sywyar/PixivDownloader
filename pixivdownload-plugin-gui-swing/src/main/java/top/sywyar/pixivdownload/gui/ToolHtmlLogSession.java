package top.sywyar.pixivdownload.gui;

import top.sywyar.pixivdownload.guiswing.SwingHost;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiHost;

import java.nio.file.Path;

public final class ToolHtmlLogSession implements AutoCloseable {
    private final DesktopUiHost.ToolLogSession delegate;
    private ToolHtmlLogSession(DesktopUiHost.ToolLogSession delegate) { this.delegate = delegate; }
    public static ToolHtmlLogSession open(String stem, Class<?> ignoredLoggerType) throws Exception {
        return new ToolHtmlLogSession(SwingHost.host().openToolLog(stem));
    }
    public Path latestPath() { return delegate.latestPath(); }
    public Path sessionPath() { return delegate.sessionPath(); }
    public void openLatestInBrowser() throws Exception { delegate.openLatestInBrowser(); }
    @Override public void close() { delegate.close(); }
}
