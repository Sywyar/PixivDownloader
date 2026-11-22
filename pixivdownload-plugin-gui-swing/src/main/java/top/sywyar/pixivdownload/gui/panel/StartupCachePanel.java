package top.sywyar.pixivdownload.gui.panel;

import top.sywyar.pixivdownload.plugin.api.gui.DesktopStartupCache;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiHost;

import javax.swing.*;
import java.awt.*;
import java.util.Locale;

/** 训练由宿主持有，面板移除只停止观察。 */
final class StartupCachePanel extends JPanel implements AutoCloseable {
    private final DesktopUiHost host;
    private final JLabel status = SwingPage.text(SwingPage.Style.BODY, "");
    private final JProgressBar progress = new JProgressBar();
    private final JButton start = new JButton(), cancel = new JButton();
    private final Timer timer = new Timer(500, event -> refresh());
    private DesktopStartupCache.Snapshot observed;
    private String shown;

    StartupCachePanel(DesktopUiHost host) {
        this.host = host;
        setLayout(new BorderLayout());
        setOpaque(false);
        setAlignmentX(Component.LEFT_ALIGNMENT);
        status.setName("startup-cache.status");
        start.setName("startup-cache.start");
        cancel.setName("startup-cache.cancel");
        start.setText(host.message("gui.tools.startup-cache.start"));
        cancel.setText(host.message("gui.tools.startup-cache.cancel"));
        SwingPage.Row row = SwingPage.row(host.message("gui.tools.startup-cache.title"),
                host.message("gui.tools.startup-cache.description") + "\n" + host.message("gui.tools.startup-cache.impact"),
                SwingPage.primary(start), cancel);
        row.texts.add(Box.createVerticalStrut(8));
        row.texts.add(status);
        row.texts.add(Box.createVerticalStrut(6));
        progress.setIndeterminate(true);
        row.texts.add(SwingPage.capped(progress, SwingPage.FORM_WIDTH));
        add(row, BorderLayout.CENTER);
        start.addActionListener(event -> { host.startupCache().ifPresent(DesktopStartupCache::start); refresh(); });
        cancel.addActionListener(event -> {
            var current = observed;
            if (current != null) host.startupCache().ifPresent(service -> service.cancel(current.executionId()));
            refresh();
        });
        refresh();
    }

    void refresh() {
        var service = host.startupCache();
        observed = service.map(DesktopStartupCache::snapshot).orElse(null);
        boolean active = observed != null && observed.active();
        String key = observed == null ? "unavailable"
                : observed.state() == DesktopStartupCache.State.FAILED ? "failure." + lower(observed.failure())
                : observed.state() == DesktopStartupCache.State.RUNNING ? "phase." + lower(observed.phase())
                : "state." + lower(observed.state());
        String message = host.message("gui.tools.startup-cache." + key);
        if (!message.equals(shown)) {
            shown = message;
            status.setText(message);
        }
        progress.setVisible(active);
        progress.getAccessibleContext().setAccessibleName(message);
        start.setEnabled(service.isPresent() && !active);
        cancel.setVisible(active);
        cancel.setEnabled(active && observed.state() != DesktopStartupCache.State.STOPPING);
    }

    @Override public Dimension getMaximumSize() {
        return new Dimension(SwingPage.PAGE_WIDTH, getPreferredSize().height);
    }

    private static String lower(Enum<?> value) { return value.name().toLowerCase(Locale.ROOT); }
    @Override public void addNotify() { super.addNotify(); refresh(); timer.start(); }
    @Override public void removeNotify() { timer.stop(); super.removeNotify(); }
    @Override public void close() { timer.stop(); }
}
