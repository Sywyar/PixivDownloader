package top.sywyar.pixivdownload.gui.panel.configtab;

import top.sywyar.pixivdownload.plugin.api.gui.DesktopDatabaseSettings;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiHost;

import javax.swing.*;
import java.awt.*;
import java.util.Map;
import java.util.function.Supplier;

/** 数据库表单的状态与异步检查，字段和统一保存入口仍由 ConfigPanel 拥有。 */
public final class DatabaseSettingsPanel extends JPanel {
    private final DesktopUiHost host;
    private final DesktopDatabaseSettings settings;
    private final Supplier<Map<String, String>> draft;
    private final JTextArea status = textArea();
    private final JTextArea result = textArea();
    private final JButton check;
    private final JButton refresh;
    private AutoCloseable backendSubscription;
    private boolean refreshPending;
    private final JTextArea migrationStatus = textArea();

    public DatabaseSettingsPanel(DesktopUiHost host, DesktopDatabaseSettings settings,
                                 Supplier<Map<String, String>> draft) {
        this.host = host;
        this.settings = settings;
        this.draft = draft;
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setOpaque(false);
        setAlignmentX(Component.LEFT_ALIGNMENT);
        JTextArea explanation = textArea();
        explanation.setText(host.message("gui.config.database.notice"));
        add(explanation);
        add(status);
        add(result);
        add(migrationStatus);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT));
        actions.setOpaque(false);
        check = new JButton(host.message("gui.config.database.check"));
        check.setName("database.check");
        check.addActionListener(event -> check());
        refresh = new JButton(host.message("gui.config.database.refresh"));
        refresh.addActionListener(event -> refreshState());
        JButton guide = new JButton(host.message("gui.config.database.guide"));
        guide.addActionListener(event -> new SwingWorker<Void, Void>() {
            @Override protected Void doInBackground() throws Exception {
                host.openExternalUri(settings.migrationGuide());
                return null;
            }
            @Override protected void done() {
                try { get(); } catch (Exception failure) { result.setText(host.message("desktop.ui.action.failed")); }
            }
        }.execute());
        actions.add(check);
        actions.add(refresh);
        actions.add(guide);
        add(actions);
        SwingUtilities.invokeLater(this::refreshState);
    }

    @Override
    public void addNotify() {
        super.addNotify();
        backendSubscription = host.subscribeBackend(snapshot -> {
            Runnable update = () -> {
                if (isDisplayable()) refreshState();
            };
            if (SwingUtilities.isEventDispatchThread()) update.run();
            else SwingUtilities.invokeLater(update);
        });
        refreshState();
    }

    @Override
    public void removeNotify() {
        AutoCloseable subscription = backendSubscription;
        backendSubscription = null;
        refreshPending = false;
        if (subscription != null) {
            try { subscription.close(); } catch (Exception ignored) {}
        }
        super.removeNotify();
    }

    public void refreshState() {
        if (!refresh.isEnabled()) {
            refreshPending = true;
            return;
        }
        refreshPending = false;
        refresh.setEnabled(false);
        new SwingWorker<DesktopDatabaseSettings.State, Void>() {
            private DesktopDatabaseSettings.MigrationState migration;
            @Override protected DesktopDatabaseSettings.State doInBackground() throws Exception {
                migration = settings.migrationState();
                return settings.state();
            }
            @Override protected void done() {
                refresh.setEnabled(true);
                try {
                    var state = get();
                    if (migration != null && !migration.status().isEmpty()) migrationStatus.setText(
                            host.message(statusKey("status", migration.status())) + "\n" + storeText(migration.stores())
                            + (migration.failure().isEmpty() ? "" : "\n" + host.message("gui.config.database.migration.failure", migration.failure())));
                    status.setText(host.message("gui.config.database.targets",
                            switch (state.activeTarget()) {
                                case "" -> host.message("gui.config.database.stopped");
                                case "DATABASE_UNAVAILABLE" -> host.message("gui.config.database.unavailable");
                                default -> state.activeTarget();
                            },
                            state.nextTarget().isEmpty() ? host.message("gui.config.database.invalid") : state.nextTarget())
                            + (state.overriddenKeys().isEmpty() ? "" : "\n" + host.message("gui.config.database.overridden", String.join(", ", state.overriddenKeys())))
                            + (state.restartRequired() ? "\n" + host.message("gui.config.notice.saved-process") : ""));
                } catch (Exception failure) { status.setText(host.message("gui.config.database.invalid")); }
                revalidate();
                if (refreshPending) refreshState();
            }
        }.execute();
    }

    public void edited() {
        result.setText("");
    }

    public void previewMigration(Map<String, String> submitted, java.util.function.BooleanSupplier unchanged,
                                 DesktopUiHost.IoOperation saveOthers) {
        if (!check.isEnabled()) return;
        check.setEnabled(false);
        result.setText(host.message("gui.config.database.checking"));
        new SwingWorker<DesktopDatabaseSettings.MigrationPlan, Void>() {
            @Override protected DesktopDatabaseSettings.MigrationPlan doInBackground() throws Exception {
                return settings.previewMigration(submitted);
            }
            @Override protected void done() {
                check.setEnabled(true);
                try {
                    var plan = get();
                    if (!unchanged.getAsBoolean()) { result.setText(host.message("gui.config.database.stale")); return; }
                    migrationStatus.setText(storeText(plan.stores()));
                    result.setText(host.message(plan.ready() ? "gui.config.database.migration.ready" : "gui.config.database.migration.failure", plan.failure()));
                    JTextArea detail = textArea();
                    detail.setText(storeText(plan.stores()) + "\n\n" + host.message(plan.ready()
                            ? "gui.config.database.migration.confirm-notice" : "gui.config.database.migration.failure", plan.failure()));
                    detail.setCaretPosition(0);
                    JScrollPane scroll = new JScrollPane(detail);
                    scroll.setPreferredSize(new Dimension(620, 360));
                    Object[] options = plan.ready() ? new Object[]{host.message("gui.config.database.migration.confirm"), host.message("desktop.ui.action.cancel")}
                            : new Object[]{host.message("desktop.ui.action.cancel")};
                    int choice = JOptionPane.showOptionDialog(DatabaseSettingsPanel.this, scroll,
                            host.message("gui.config.database.migrate"), JOptionPane.DEFAULT_OPTION,
                            JOptionPane.WARNING_MESSAGE, null, options, options[options.length - 1]);
                    if (choice == 0 && plan.ready()) {
                        if (!unchanged.getAsBoolean()) { result.setText(host.message("gui.config.database.stale")); return; }
                        settings.startMigration(plan.token(), saveOthers);
                        result.setText(host.message("gui.config.database.migration.status.pending"));
                    }
                } catch (Exception failure) { result.setText(host.message("gui.config.database.migration.failed")); }
                revalidate();
            }
        }.execute();
    }

    private String storeText(java.util.List<DesktopDatabaseSettings.MigrationStore> stores) {
        var text = new StringBuilder();
        for (var store : stores) {
            if (!text.isEmpty()) text.append("\n\n");
            text.append(host.message("gui.config.database.migration.store", store.owner(), store.source(), store.target(),
                    host.message(statusKey("phase", store.phase()))));
            if (!store.rebuildObjects().isEmpty()) text.append("\n").append(host.message(
                    "gui.config.database.migration.rebuild", String.join(", ", store.rebuildObjects())));
        }
        return text.toString();
    }
    private static String statusKey(String kind, String value) {
        return "gui.config.database.migration." + kind + "." + value.toLowerCase(java.util.Locale.ROOT).replace('_', '-');
    }

    private void check() {
        Map<String, String> submitted = Map.copyOf(draft.get());
        check.setEnabled(false);
        result.setText(host.message("gui.config.database.checking"));
        new SwingWorker<DesktopDatabaseSettings.CheckResult, Void>() {
            @Override protected DesktopDatabaseSettings.CheckResult doInBackground() throws Exception {
                return settings.check(submitted);
            }
            @Override protected void done() {
                check.setEnabled(true);
                try {
                    var checked = get();
                    result.setText(submitted.equals(draft.get())
                            ? host.message("gui.config.database.result." + checked.outcome().name().toLowerCase(java.util.Locale.ROOT),
                                    checked.target(), checked.sqlState())
                            : host.message("gui.config.database.stale"));
                } catch (Exception failure) { result.setText(host.message("gui.config.database.invalid")); }
                revalidate();
            }
        }.execute();
    }

    private static JTextArea textArea() {
        JTextArea area = new JTextArea();
        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setOpaque(false);
        area.setFont(UIManager.getFont("Label.font"));
        area.setAlignmentX(Component.LEFT_ALIGNMENT);
        return area;
    }
}
