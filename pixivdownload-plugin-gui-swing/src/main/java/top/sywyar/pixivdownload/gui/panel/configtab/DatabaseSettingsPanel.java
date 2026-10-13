package top.sywyar.pixivdownload.gui.panel.configtab;

import top.sywyar.pixivdownload.gui.panel.ScrollablePagePanel;
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
    private final JPanel connectionDetails = verticalPanel();
    private final JPanel resultDetails = verticalPanel();
    private final JButton check;
    private final JButton refresh;
    private AutoCloseable backendSubscription;
    private boolean refreshPending;
    private final JTextArea migrationStatus = textArea();
    private final JPanel migrationHistory = verticalPanel();

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
        add(connectionDetails);
        add(result);
        add(resultDetails);
        add(migrationStatus);
        add(migrationHistory);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT));
        actions.setOpaque(false);
        check = new JButton(host.message("gui.config.database.check"));
        check.setName("database.check");
        check.addActionListener(event -> check());
        refresh = new JButton(host.message("gui.config.database.refresh"));
        refresh.setName("database.refresh");
        refresh.addActionListener(event -> { edited(); refreshState(); });
        actions.add(check);
        actions.add(refresh);
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
                    migrationStatus.setText("");
                    migrationHistory.removeAll();
                    if (migration != null && !migration.status().isEmpty()) {
                        migrationStatus.setText(host.message(statusKey("status", migration.status()))
                                + (migration.failure().isEmpty() ? "" : "\n" + failureText(migration.failure())));
                        JPanel history = storeList(migration.stores(), false);
                        addFailureDetails(history, migration.failure());
                        migrationHistory.add(migration.status().equals("SUCCEEDED") && migration.failure().isEmpty()
                                ? disclosure("gui.config.database.history", history) : history);
                    }
                    String active = switch (state.activeTarget()) {
                                case "" -> host.message("gui.config.database.stopped");
                                case "DATABASE_UNAVAILABLE" -> host.message("gui.config.database.unavailable");
                                default -> targetLabel(state.activeTarget());
                            };
                    boolean showNext = !state.activeTarget().equals(state.nextTarget());
                    status.setText(host.message("gui.config.database.current", active)
                            + (showNext ? "\n" + host.message("gui.config.database.next", state.nextTarget().isEmpty()
                                    ? host.message("gui.config.database.invalid") : targetLabel(state.nextTarget())) : "")
                            + (state.overriddenKeys().isEmpty() ? "" : "\n" + host.message("gui.config.database.overridden", String.join(", ", state.overriddenKeys())))
                            + (state.restartRequired() ? "\n" + host.message("gui.config.notice.saved-process") : ""));
                    connectionDetails.removeAll();
                    JPanel targets = verticalPanel();
                    if (!state.activeTarget().isEmpty() && !state.activeTarget().equals("DATABASE_UNAVAILABLE"))
                        addText(targets, host.message("gui.config.database.current", state.activeTarget()));
                    if (showNext && !state.nextTarget().isEmpty())
                        addText(targets, host.message("gui.config.database.next", state.nextTarget()));
                    if (targets.getComponentCount() > 0) connectionDetails.add(disclosure("gui.config.database.details", targets));
                } catch (Exception failure) { status.setText(host.message("gui.config.database.invalid")); }
                revalidate();
                if (refreshPending) refreshState();
            }
        }.execute();
    }

    public void edited() {
        result.setText("");
        resultDetails.removeAll();
    }

    public void previewMigration(Map<String, String> submitted, java.util.function.BooleanSupplier unchanged,
                                 DesktopUiHost.IoOperation saveOthers) {
        if (!check.isEnabled()) return;
        check.setEnabled(false);
        resultDetails.removeAll();
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
                    result.setText(plan.ready() ? "" : failureText(plan.failure()));
                    JPanel detail = new ScrollablePagePanel();
                    addText(detail, host.message("gui.config.database.migration.summary", plan.stores().size()));
                    plan.stores().stream().filter(store -> store.owner().equals("main")).findFirst().ifPresent(store -> {
                        addText(detail, host.message("gui.config.database.migration.source", store.source()));
                        addText(detail, host.message("gui.config.database.migration.target", store.target()));
                    });
                    if (plan.stores().stream().anyMatch(store -> store.phase().equals("REPLACE_TARGET")))
                        addText(detail, host.message("gui.config.database.migration.replacement-notice"));
                    detail.add(storeList(plan.stores(), true));
                    addText(detail, plan.ready() ? host.message("gui.config.database.migration.confirm-notice") : failureText(plan.failure()));
                    addFailureDetails(detail, plan.failure());
                    JScrollPane scroll = new JScrollPane(detail);
                    scroll.setPreferredSize(new Dimension(620, 360));
                    boolean connectionOnly = !plan.stores().isEmpty() && plan.stores().stream()
                            .allMatch(store -> store.phase().equals("SAME_TARGET") || store.phase().equals("CONNECTION_REPAIR"));
                    Object[] options = plan.ready() ? new Object[]{host.message(connectionOnly
                            ? "gui.config.database.migration.confirm-connection" : "gui.config.database.migration.confirm"), host.message("desktop.ui.action.cancel")}
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

    private JPanel storeList(java.util.List<DesktopDatabaseSettings.MigrationStore> stores, boolean preview) {
        JPanel list = verticalPanel();
        for (var store : stores) {
            JPanel row = verticalPanel();
            row.setBorder(BorderFactory.createEmptyBorder(8, 0, 8, 0));
            JTextArea title = addText(row, store.owner().equals("main") ? host.message("gui.config.database.migration.main")
                    : host.message("gui.config.database.migration.plugin", store.owner().replaceFirst("^private:", "")));
            title.setFont(title.getFont().deriveFont(Font.BOLD));
            addText(row, host.message(statusKey("phase", preview && store.phase().equals("COMMITTED") ? "REUSE" : store.phase())));
            JPanel details = verticalPanel();
            addText(details, host.message("gui.config.database.migration.source", store.source()));
            addText(details, host.message("gui.config.database.migration.target", store.target()));
            if (!store.rebuildObjects().isEmpty()) addText(details, host.message(
                    "gui.config.database.migration.rebuild", String.join(", ", store.rebuildObjects())));
            row.add(disclosure("gui.config.database.migration.details", details));
            list.add(row);
        }
        return list;
    }

    private String failureText(String code) {
        return host.message(DesktopDatabaseSettings.failureMessageKey(code));
    }

    private void addFailureDetails(JPanel parent, String code) {
        if (code == null || code.isEmpty()) return;
        JPanel details = verticalPanel();
        addText(details, host.message("gui.config.database.diagnostic-code", code));
        parent.add(disclosure("gui.config.database.migration.details", details));
    }

    private JPanel disclosure(String key, JPanel content) {
        JPanel panel = verticalPanel();
        JToggleButton toggle = new JToggleButton(host.message(key));
        toggle.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.setVisible(false);
        toggle.addActionListener(event -> {
            content.setVisible(toggle.isSelected());
            panel.revalidate();
            panel.repaint();
        });
        panel.add(toggle);
        panel.add(content);
        return panel;
    }

    private static String targetLabel(String target) {
        int separator = target.indexOf(':');
        return separator > 0 ? target.substring(0, separator) : target;
    }

    private static JPanel verticalPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setOpaque(false);
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);
        return panel;
    }

    private static JTextArea addText(JPanel parent, String text) {
        JTextArea area = textArea();
        area.setText(text);
        parent.add(area);
        return area;
    }
    private static String statusKey(String kind, String value) {
        return "gui.config.database.migration." + kind + "." + value.toLowerCase(java.util.Locale.ROOT).replace('_', '-');
    }

    private void check() {
        Map<String, String> submitted = Map.copyOf(draft.get());
        check.setEnabled(false);
        resultDetails.removeAll();
        result.setText(host.message("gui.config.database.checking"));
        new SwingWorker<DesktopDatabaseSettings.CheckResult, Void>() {
            @Override protected DesktopDatabaseSettings.CheckResult doInBackground() throws Exception {
                return settings.check(submitted);
            }
            @Override protected void done() {
                check.setEnabled(true);
                try {
                    var checked = get();
                    if (!submitted.equals(draft.get())) result.setText(host.message("gui.config.database.stale"));
                    else {
                        result.setText(checked.outcome() == DesktopDatabaseSettings.Outcome.FAILED
                                ? failureText(checked.sqlState())
                                : host.message("gui.config.database.result." + checked.outcome().name().toLowerCase(java.util.Locale.ROOT),
                                        targetLabel(checked.target()), ""));
                        if (checked.outcome() == DesktopDatabaseSettings.Outcome.FAILED) addFailureDetails(resultDetails, checked.sqlState());
                    }
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
