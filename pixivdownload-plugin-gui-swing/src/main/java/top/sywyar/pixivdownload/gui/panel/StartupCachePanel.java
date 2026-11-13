package top.sywyar.pixivdownload.gui.panel;

import top.sywyar.pixivdownload.plugin.api.gui.DesktopStartupCache;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiHost;

import javax.swing.*;
import java.awt.*;
import java.util.Locale;

/** 训练由宿主持有，面板移除只停止观察。 */
final class StartupCachePanel extends JPanel implements AutoCloseable {
    private final DesktopUiHost host;
    private final JTextArea status = text();
    private final JProgressBar progress = new JProgressBar();
    private final JButton start = new JButton(), cancel = new JButton();
    private final Timer timer = new Timer(500, event -> refresh());
    private DesktopStartupCache.Snapshot observed;

    StartupCachePanel(DesktopUiHost host) {
        this.host = host;
        setLayout(new BorderLayout(0, 10));
        setAlignmentX(Component.LEFT_ALIGNMENT);
        setBorder(BorderFactory.createTitledBorder(host.message("gui.tools.startup-cache.title")));
        JTextArea description = text();
        description.setText(host.message("gui.tools.startup-cache.description") + "\n"
                + host.message("gui.tools.startup-cache.impact"));
        add(description, BorderLayout.NORTH);
        JPanel state = new JPanel(new BorderLayout(0, 6));
        state.setOpaque(false);
        status.setName("startup-cache.status");
        state.add(status, BorderLayout.CENTER);
        progress.setIndeterminate(true);
        state.add(progress, BorderLayout.SOUTH);
        add(state, BorderLayout.CENTER);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT));
        actions.setOpaque(false);
        start.setName("startup-cache.start");
        cancel.setName("startup-cache.cancel");
        start.setText(host.message("gui.tools.startup-cache.start"));
        cancel.setText(host.message("gui.tools.startup-cache.cancel"));
        start.addActionListener(event -> { host.startupCache().ifPresent(DesktopStartupCache::start); refresh(); });
        cancel.addActionListener(event -> {
            var current = observed;
            if (current != null) host.startupCache().ifPresent(service -> service.cancel(current.executionId()));
            refresh();
        });
        actions.add(start); actions.add(cancel);
        add(actions, BorderLayout.SOUTH);
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
        if (!status.getText().equals(message)) status.setText(message);
        progress.setVisible(active);
        progress.getAccessibleContext().setAccessibleName(message);
        start.setEnabled(service.isPresent() && !active);
        cancel.setVisible(active);
        cancel.setEnabled(active && observed.state() != DesktopStartupCache.State.STOPPING);
    }

    private static String lower(Enum<?> value) { return value.name().toLowerCase(Locale.ROOT); }
    private static JTextArea text() {
        JTextArea value = new JTextArea();
        value.setEditable(false); value.setOpaque(false);
        value.setLineWrap(true); value.setWrapStyleWord(true);
        value.setFont(UIManager.getFont("Label.font"));
        return value;
    }
    @Override public void addNotify() { super.addNotify(); refresh(); timer.start(); }
    @Override public void removeNotify() { timer.stop(); super.removeNotify(); }
    @Override public void close() { timer.stop(); }
}
