package top.sywyar.pixivdownload.gui.panel;

import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiHost;

import top.sywyar.pixivdownload.guiswing.SwingHost;

import lombok.extern.slf4j.Slf4j;
import top.sywyar.pixivdownload.gui.SwingBackendLifecycle;
import top.sywyar.pixivdownload.gui.ExclusiveToolHolder;
import top.sywyar.pixivdownload.gui.GuiErrorDialog;
import top.sywyar.pixivdownload.gui.i18n.GuiMessages;
import top.sywyar.pixivdownload.gui.theme.GuiInputStyleNormalizer;
import top.sywyar.pixivdownload.gui.imageclassifier.ImageClassifier;
import top.sywyar.pixivdownload.gui.tools.FolderChecker;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Tool shortcuts exposed from the main Swing GUI.
 */
@Slf4j
public class ToolsPanel extends JPanel {

    private static final Path MIGRATION_LOG_PATH = Path.of("log", "html", "json-to-sqlite-migration-latest.html");

    private final Path configPath;
    private final java.util.function.Consumer<DesktopUiHost.BackfillTask> automaticBackfillNotice;
    private JComponent backfillCard;
    private DesktopUiHost.BackfillOptions inheritedBackfillOptions;
    private String inheritedRootFolder;
    private boolean logPageOpening;
    private final MediaToolsPanel mediaTools = new MediaToolsPanel(SwingHost.context());
    private final FileImportToolsPanel fileImports = new FileImportToolsPanel(SwingHost.context());
    private final LocalArtworkImportPanel localImports = new LocalArtworkImportPanel(SwingHost.context());
    private final StartupCachePanel startupCache = new StartupCachePanel(SwingHost.host());

    private final JLabel backendStateLabel = new JLabel(message("gui.tools.backend-status", message("gui.tools.backend-status.detecting")));
    private final JLabel exclusiveToolLabel = SwingPage.text(SwingPage.Style.SECONDARY,
            message("gui.tools.exclusive-tool", message("gui.value.none")));
    private final SwingPage.Dot backendDot = new SwingPage.Dot(SwingPage::secondaryColor);
    private final SwingPage.Block mediaSection = new SwingPage.Block();
    private final JLabel backfillStatusLabel = SwingPage.text(SwingPage.Style.BODY, message("gui.tools.backfill.status.idle"));
    private final JLabel migrationStatusLabel = SwingPage.text(SwingPage.Style.BODY, message("gui.tools.migration.status.idle"));

    private final JButton imageClassifierButton = new JButton(message("gui.tools.action.open-image-classifier"));
    private final JButton folderCheckerButton = new JButton(message("gui.tools.action.open-folder-checker"));
    private final JButton backfillRunButton = new JButton(message("gui.tools.action.start-backfill"));
    private final JButton backfillLogButton = new JButton(message("gui.tools.action.open-log-page"));
    private final JButton migrationRunButton = new JButton(message("gui.tools.action.start-migration"));
    private final JButton migrationLogButton = new JButton(message("gui.tools.action.open-migration-log-page"));

    private final JTextField dbPathField = new JTextField(34);
    private final JCheckBox proxyEnabledCheck = new JCheckBox(message("gui.tools.form.use-proxy"));
    private final JTextField proxyHostField = new JTextField(16);
    private final JSpinner proxyPortSpinner = new JSpinner(new SpinnerNumberModel(7890, 1, 65535, 1));
    private final JSpinner delaySpinner = new JSpinner(new SpinnerNumberModel(800, 0, 60_000, 100));
    private final JSpinner limitSpinner = new JSpinner(new SpinnerNumberModel(0, 0, Integer.MAX_VALUE, 100));
    private final JCheckBox alwaysNetworkCheck = new JCheckBox(message("gui.tools.form.always-network"));
    private final JCheckBox dryRunCheck = new JCheckBox(message("gui.tools.form.dry-run"));

    private final JTextField migrationDbPathField = new JTextField(34);
    private final JTextField migrationRootFolderField = new JTextField(34);

    private String exclusiveToolName;
    private boolean backfillRunning;
    private boolean migrationRunning;
    private DesktopUiHost.BackfillTask backfillTask;
    private boolean disposed;
    private DesktopUiHost.BackendState previousBackendState;
    private JPanel backfillForm;
    private final JProgressBar backfillProgress = new JProgressBar();
    private final JLabel backfillCounts = SwingPage.text(SwingPage.Style.SECONDARY, "");
    private final JLabel backfillCurrent = SwingPage.text(SwingPage.Style.SECONDARY, "");
    private final JLabel backfillFields = SwingPage.text(SwingPage.Style.SECONDARY, "");
    private final JButton backfillStopButton = new JButton(message("gui.tools.backfill.action.stop"));
    private final JButton backfillEditButton = new JButton(message("gui.tools.backfill.action.edit"));
    private final JCheckBox backfillDetails = new JCheckBox(message("gui.tools.backfill.action.details"));
    private final Timer backfillTimer = new Timer(500, event -> refreshBackfill());
    private volatile DesktopUiHost.ToolLogSession currentMigrationLogSession;

    private final SwingBackendLifecycle.Listener backendListener = this::handleBackendState;

    public ToolsPanel(Path configPath) {
        this(configPath, task -> { });
    }

    public ToolsPanel(Path configPath, java.util.function.Consumer<DesktopUiHost.BackfillTask> automaticBackfillNotice) {
        this.configPath = configPath;
        this.automaticBackfillNotice = automaticBackfillNotice;
        buildUi();
        GuiInputStyleNormalizer.apply(this);
        loadDefaults();
        SwingBackendLifecycle.addListener(backendListener);
        backfillTimer.start();
        refreshBackfill();
        refreshActionStates();
    }

    private void buildUi() {
        setLayout(new BorderLayout(0, 0));

        JPanel content = SwingPage.page();
        backendStateLabel.setIcon(backendDot);
        backendStateLabel.setIconTextGap(8);
        content.add(SwingPage.header(message("gui.tab.tools"), message("gui.tools.page.subtitle"),
                SwingPage.inline(backendStateLabel, (JComponent) Box.createHorizontalStrut(10), exclusiveToolLabel)));

        content.add(SwingPage.section(message("gui.tools.section.quick"), null));
        SwingPage.RowList quickTools = new SwingPage.RowList();
        quickTools.add(toolRow(imageClassifierButton, "gui.tools.card.image-classifier",
                "gui.tools.action.open-image-classifier", this::openImageClassifier));
        quickTools.add(toolRow(folderCheckerButton, "gui.tools.card.folder-checker",
                "gui.tools.action.open-folder-checker", this::openFolderChecker));
        quickTools.add(fileImports);
        quickTools.add(localImports);
        content.add(quickTools);
        content.add(Box.createVerticalStrut(30));

        content.add(SwingPage.section(message("gui.tools.section.maintenance"), message("gui.tools.card.overview.hint")));
        SwingPage.RowList maintenance = new SwingPage.RowList();
        backfillCard = buildBackfillCard();
        maintenance.add(backfillCard);
        maintenance.add(buildMigrationCard());
        maintenance.add(startupCache);
        content.add(maintenance);

        mediaSection.add(Box.createVerticalStrut(30));
        mediaSection.add(SwingPage.section(message("gui.tools.section.media"), null));
        mediaSection.add(mediaTools);
        mediaTools.addContainerListener(new java.awt.event.ContainerListener() {
            @Override public void componentAdded(java.awt.event.ContainerEvent event) { updateMediaSection(); }
            @Override public void componentRemoved(java.awt.event.ContainerEvent event) { updateMediaSection(); }
        });
        updateMediaSection();
        content.add(mediaSection);
        addHierarchyListener(event -> {
            if ((event.getChangeFlags() & java.awt.event.HierarchyEvent.SHOWING_CHANGED) != 0 && isShowing()) mediaTools.refreshTools();
        });
        content.add(Box.createVerticalGlue());

        add(SwingPage.scroll(content), BorderLayout.CENTER);
    }

    private void updateMediaSection() {
        mediaSection.setVisible(mediaTools.getComponentCount() > 0);
    }

    /** 快捷工具行：标题与说明在左，打开按钮在右；按钮的无障碍名称保留完整动作。 */
    private JComponent toolRow(JButton button, String cardKey, String actionKey, Runnable action) {
        button.setText(SwingHost.host().message("desktop.ui.action.open"));
        button.setToolTipText(message(actionKey));
        button.getAccessibleContext().setAccessibleName(message(actionKey));
        button.addActionListener(event -> action.run());
        return SwingPage.row(message(cardKey + ".title"), message(cardKey + ".description"), button);
    }

    private JComponent buildBackfillCard() {
        SwingPage.Row panel = SwingPage.row(message("gui.tools.card.backfill.title"), null,
                SwingPage.primary(backfillRunButton), backfillStopButton);

        JPanel form = new JPanel(new GridBagLayout());
        backfillForm = form;
        form.setOpaque(false);
        GridBagConstraints g = new GridBagConstraints();
        g.insets = new Insets(4, 0, 4, 8);
        g.anchor = GridBagConstraints.WEST;
        g.fill = GridBagConstraints.HORIZONTAL;
        g.weightx = 0;

        int row = 0;
        g.gridx = 0;
        g.gridy = row;
        form.add(new JLabel(message("gui.tools.form.database-path")), g);
        g.gridx = 1;
        g.weightx = 1;
        form.add(dbPathField, g);
        g.gridx = 2;
        g.weightx = 0;
        JButton browseDbButton = new JButton(message("gui.button.browse"));
        browseDbButton.setEnabled(SwingHost.host().supportsDatabaseFileSelection());
        browseDbButton.addActionListener(e -> browseDatabase(dbPathField));
        form.add(browseDbButton, g);

        row++;
        g.gridy = row;
        g.gridx = 0;
        form.add(new JLabel(message("gui.tools.form.proxy")), g);
        JPanel proxyPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        proxyPanel.setOpaque(false);
        proxyPanel.setBorder(BorderFactory.createEmptyBorder(0, -8, 0, 0));
        proxyEnabledCheck.setOpaque(false);
        proxyEnabledCheck.addActionListener(e -> updateProxyFieldState());
        proxyPanel.add(proxyEnabledCheck);
        proxyPanel.add(new JLabel(message("gui.tools.form.proxy-host")));
        proxyPanel.add(proxyHostField);
        proxyPanel.add(new JLabel(message("gui.tools.form.proxy-port")));
        proxyPanel.add(proxyPortSpinner);
        g.gridx = 1;
        g.gridwidth = 2;
        g.weightx = 1;
        form.add(proxyPanel, g);
        g.gridwidth = 1;

        row++;
        g.gridy = row;
        g.gridx = 0;
        g.weightx = 0;
        form.add(new JLabel(message("gui.tools.form.delay-ms")), g);
        g.gridx = 1;
        g.gridwidth = 2;
        form.add(leading(delaySpinner), g);
        g.gridwidth = 1;

        row++;
        g.gridy = row;
        g.gridx = 0;
        form.add(new JLabel(message("gui.tools.form.limit")), g);
        g.gridx = 1;
        g.gridwidth = 2;
        JLabel limitHint = SwingPage.text(SwingPage.Style.SECONDARY, message("gui.tools.form.limit-hint"));
        form.add(leading(limitSpinner, limitHint), g);
        g.gridy = ++row;
        g.gridx = 1;
        dryRunCheck.setOpaque(false);
        form.add(dryRunCheck, g);
        g.gridy = ++row;
        alwaysNetworkCheck.setOpaque(false);
        alwaysNetworkCheck.setToolTipText(message("gui.tools.form.always-network-hint"));
        form.add(alwaysNetworkCheck, g);
        g.gridwidth = 1;

        backfillStopButton.addActionListener(event -> stopBackfill());
        backfillRunButton.addActionListener(e -> startBackfill());
        backfillLogButton.addActionListener(e -> openBackfillLogPage());
        SwingPage.plain(backfillLogButton);
        SwingPage.plain(backfillEditButton);
        backfillEditButton.setIcon(UIManager.getIcon("Tree.collapsedIcon"));
        backfillEditButton.addActionListener(event -> setBackfillOptionsVisible(!backfillForm.isVisible()));
        backfillForm.setVisible(false);

        backfillStatusLabel.setText(message("gui.tools.backfill.status.idle"));
        backfillProgress.setStringPainted(true);
        backfillProgress.setVisible(false);
        backfillDetails.setOpaque(false);
        backfillDetails.addActionListener(event -> {
            backfillFields.setVisible(backfillDetails.isSelected());
            revalidate();
        });
        backfillDetails.setVisible(false);
        backfillFields.setVisible(false);

        panel.texts.add(Box.createVerticalStrut(4));
        panel.texts.add(backfillStatusLabel);
        panel.texts.add(Box.createVerticalStrut(6));
        panel.texts.add(SwingPage.capped(backfillProgress, SwingPage.FORM_WIDTH));
        panel.texts.add(backfillCounts);
        panel.texts.add(backfillCurrent);
        panel.texts.add(Box.createVerticalStrut(4));
        panel.texts.add(SwingPage.links(backfillEditButton, backfillLogButton, backfillDetails));
        panel.texts.add(backfillFields);
        panel.texts.add(SwingPage.capped(form, SwingPage.FORM_WIDTH + 80));
        return panel;
    }

    private void setBackfillOptionsVisible(boolean visible) {
        backfillForm.setVisible(visible);
        backfillEditButton.setIcon(UIManager.getIcon(visible ? "Tree.expandedIcon" : "Tree.collapsedIcon"));
        backfillForm.revalidate();
        revalidate();
        repaint();
    }

    private static JPanel leading(JComponent... components) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        row.setOpaque(false);
        row.setBorder(BorderFactory.createEmptyBorder(0, -8, 0, 0));
        for (JComponent component : components) row.add(component);
        return row;
    }

    private JComponent buildMigrationCard() {
        SwingPage.Row panel = SwingPage.row(message("gui.tools.card.migration.title"),
                message("gui.tools.card.migration.description"), SwingPage.primary(migrationRunButton));

        JButton browseDbButton = new JButton(message("gui.button.browse"));
        browseDbButton.setEnabled(SwingHost.host().supportsDatabaseFileSelection());
        browseDbButton.addActionListener(e -> browseDatabase(migrationDbPathField));
        JButton browseRootButton = new JButton(message("gui.button.browse"));
        browseRootButton.addActionListener(e -> browseMigrationRootFolder());
        SwingPage.Form form = new SwingPage.Form(SwingPage.FORM_WIDTH + 80)
                .field(message("gui.tools.form.database-path"), migrationDbPathField, browseDbButton)
                .field(message("gui.tools.form.root-folder"), migrationRootFolderField, browseRootButton);

        migrationRunButton.addActionListener(e -> startMigration());
        migrationLogButton.addActionListener(e -> openMigrationLogPage());
        SwingPage.plain(migrationLogButton);

        panel.texts.add(Box.createVerticalStrut(8));
        panel.texts.add(SwingPage.capped(form, SwingPage.FORM_WIDTH + 80));
        panel.texts.add(Box.createVerticalStrut(4));
        panel.texts.add(migrationStatusLabel);
        panel.texts.add(Box.createVerticalStrut(2));
        panel.texts.add(SwingPage.links(migrationLogButton));
        return panel;
    }

    private void loadDefaults() {
        try {
            DesktopUiHost.BackfillOptions defaults = SwingHost.host().defaultBackfillOptions();
            String rootFolder = SwingHost.host().readDownloadRootFromConfig(configPath, SwingHost.context().rootFolder());
            dbPathField.setText(defaults.dbPath());
            migrationDbPathField.setText(defaults.dbPath());
            migrationRootFolderField.setText(rootFolder);
            inheritedRootFolder = rootFolder;
            inheritBackfillProxy(defaults);
            delaySpinner.setValue((int) defaults.delayMs());
            limitSpinner.setValue(defaults.limit());
            dryRunCheck.setSelected(defaults.dryRun());
            alwaysNetworkCheck.setSelected(defaults.alwaysNetwork());
        } catch (RuntimeException failure) {
            log.warn(logMessage("gui.tools.log.proxy-defaults.failed", failure.getMessage()), failure);
            setBackfillStatus(SwingHost.host().message("desktop.ui.tools.operation-failed"));
        }

        updateProxyFieldState();
    }

    public void refreshConfiguration() {
        try {
            inheritBackfillProxy(SwingHost.host().defaultBackfillOptions());
            String root = SwingHost.host().readDownloadRootFromConfig(configPath, SwingHost.context().rootFolder());
            if (migrationRootFolderField.getText().equals(inheritedRootFolder)) migrationRootFolderField.setText(root);
            inheritedRootFolder = root;
        } catch (RuntimeException failure) {
            log.warn(logMessage("gui.tools.log.proxy-defaults.failed", failure.getMessage()), failure);
            setBackfillStatus(SwingHost.host().message("desktop.ui.tools.operation-failed"));
        }
    }

    private void inheritBackfillProxy(DesktopUiHost.BackfillOptions current) {
        var previous = inheritedBackfillOptions;
        if (previous == null || proxyEnabledCheck.isSelected() == previous.useProxy())
            proxyEnabledCheck.setSelected(current.useProxy());
        if (previous == null || proxyHostField.getText().equals(previous.proxyHost()))
            proxyHostField.setText(current.proxyHost());
        if (previous == null || ((Number) proxyPortSpinner.getValue()).intValue() == previous.proxyPort())
            proxyPortSpinner.setValue(current.proxyPort());
        inheritedBackfillOptions = current;
        updateProxyFieldState();
    }

    private void openImageClassifier() {
        if (!ensureNoExclusiveTool(message("gui.tools.card.image-classifier.title"))) {
            return;
        }
        SwingUtilities.invokeLater(() -> {
            ImageClassifier classifier = new ImageClassifier(false);
            classifier.setVisible(true);
        });
    }

    private void openFolderChecker() {
        if (!beginExclusiveTool(message("gui.tools.card.folder-checker.title"))) {
            return;
        }

        setBackfillStatus(message("gui.tools.folder-checker.status.preparing"));
        boolean restart = SwingBackendLifecycle.state() == DesktopUiHost.BackendState.RUNNING;
        boolean accepted = SwingBackendLifecycle.stopAsync(() -> {
            try {
                FolderChecker checker = new FolderChecker(JFrame.DISPOSE_ON_CLOSE, () -> handleFolderCheckerClosed(restart));
                checker.showWindow();
                setBackfillStatus(message("gui.tools.folder-checker.status.opened"));
            } catch (Exception e) {
                exclusiveToolName = null;
                refreshActionStates();
                setBackfillStatus(message("gui.tools.folder-checker.status.open-failed"));
                log.error(logMessage("gui.tools.log.folder-checker.open-failed"), e);
                if (restart) SwingBackendLifecycle.startAsync();
                GuiErrorDialog.show(this,
                        message("gui.dialog.error.title"),
                        message("gui.tools.dialog.folder-checker-open-failed.message", e.getMessage()));
            }
        }, failure -> handleToolStopFailure(false, failure));
        if (!accepted) handleToolStopFailure(false, null);
    }

    private void handleToolStopFailure(boolean migration, Throwable failure) {
        exclusiveToolName = null;
        String detail = failure == null ? message("gui.message.backend-busy")
                : SwingHost.host().message("desktop.ui.tools.stop-failed", java.util.Objects.toString(failure.getMessage(), failure.getClass().getSimpleName()));
        if (migration) {
            migrationRunning = false;
            closeMigrationLogSession();
            setMigrationStatus(detail);
        } else {
            setBackfillStatus(detail);
        }
        refreshActionStates();
    }

    private void handleFolderCheckerClosed(boolean restart) {
        exclusiveToolName = null;
        refreshActionStates();
        if (!restart) {
            setBackfillStatus(message("gui.tools.folder-checker.status.closed"));
            return;
        }
        setBackfillStatus(message("gui.tools.folder-checker.status.restoring"));
        if (!SwingBackendLifecycle.startAsync(() ->
                setBackfillStatus(message("gui.tools.folder-checker.status.completed")))) {
            setBackfillStatus(message("gui.tools.folder-checker.status.closed"));
        }
    }

    private void startBackfill() {
        if (!beginExclusiveTool(message("gui.tools.card.backfill.title"))) return;
        DesktopUiHost.BackfillOptions options;
        try { options = buildBackfillOptions(); }
        catch (Exception failure) {
            exclusiveToolName = null;
            refreshActionStates();
            GuiErrorDialog.show(this, message("gui.dialog.error.title"), failure.getMessage());
            return;
        }
        backfillRunning = true;
        refreshActionStates();
        backfillStatusLabel.setText(message("gui.tools.backfill.status.preparing"));
        new SwingWorker<Void, Void>() {
            @Override protected Void doInBackground() throws Exception {
                SwingHost.host().startBackfill(options);
                return null;
            }
            @Override protected void done() {
                if (disposed) return;
                exclusiveToolName = null;
                backfillRunning = false;
                try { get(); }
                catch (Exception failure) {
                    log.error(logMessage("gui.tools.log.backfill.failed"), failure);
                    backfillStatusLabel.setText(SwingHost.host().message("desktop.ui.tools.operation-failed"));
                }
                refreshBackfill();
                refreshActionStates();
            }
        }.execute();
    }

    private void stopBackfill() {
        var task = backfillTask;
        if (task == null || !task.active()) return;
        backfillStopButton.setEnabled(false);
        new SwingWorker<Void, Void>() {
            @Override protected Void doInBackground() {
                SwingHost.host().cancelBackfill(task.executionId());
                return null;
            }
            @Override protected void done() {
                if (disposed) return;
                try { get(); }
                catch (Exception failure) { log.warn(logMessage("desktop.ui.tools.operation-failed"), failure); }
                refreshBackfill();
                refreshActionStates();
            }
        }.execute();
    }

    private void refreshBackfill() {
        if (disposed) return;
        var task = SwingHost.host().backfillTask().orElse(null);
        automaticBackfillNotice.accept(task);
        if (task == null) return;
        backfillRunning = task.active();
        if (task.equals(backfillTask)) return;
        backfillTask = task;
        backfillDetails.setVisible(task.summary() != null);
        String key = task.state() == DesktopUiHost.BackfillState.RUNNING
                ? "gui.tools.backfill.phase." + task.phase().name().toLowerCase(java.util.Locale.ROOT)
                : "gui.tools.backfill.state." + task.state().name().toLowerCase(java.util.Locale.ROOT);
        backfillStatusLabel.setText(message(key));
        backfillProgress.setVisible(task.active());
        backfillProgress.setIndeterminate(task.total() == null);
        backfillProgress.setMaximum(task.total() == null ? 1 : Math.max(1, task.total()));
        backfillProgress.setValue(task.processed());
        backfillProgress.setString(task.total() == null ? message(key) : task.processed() + " / " + task.total());
        backfillCounts.setText(message(task.dryRun() ? "gui.tools.backfill.task.preview" : "gui.tools.backfill.task.counts",
                task.processed(), task.total() == null ? message("gui.tools.backfill.task.unknown-total") : task.total(),
                task.changed(), task.remaining(), task.unavailable()));
        String origin = message(task.automatic() ? "gui.tools.backfill.task.automatic" : "gui.tools.backfill.task.manual");
        backfillCurrent.setText(task.workId() == null ? origin : origin + " · " + task.workType() + ":" + task.workId());
        var summary = task.summary();
        backfillFields.setText(summary == null ? "" : message("gui.tools.backfill.task.fields", summary.filledAuthor(),
                summary.filledR18(), summary.filledAi(), summary.filledDescription(), summary.filledTags(),
                summary.filledSeries(), summary.filledUploadTime()));
        refreshActionStates();
        revalidate();
        repaint();
    }

    public void showBackfill() {
        refreshBackfill();
        backfillCard.scrollRectToVisible(new Rectangle(0, 0, backfillCard.getWidth(), backfillCard.getHeight()));
        if (backfillTask != null && backfillTask.active()) backfillStopButton.requestFocusInWindow();
        else backfillEditButton.requestFocusInWindow();
    }

    private DesktopUiHost.BackfillOptions buildBackfillOptions() {
        inheritBackfillProxy(SwingHost.host().defaultBackfillOptions());
        String dbPath = dbPathField.getText().trim();
        if (dbPath.isBlank()) {
            throw new IllegalArgumentException(message("gui.tools.validation.database-path.required"));
        }

        return new DesktopUiHost.BackfillOptions(
                dbPath,
                proxyHostField.getText().trim(),
                ((Number) proxyPortSpinner.getValue()).intValue(),
                proxyEnabledCheck.isSelected(),
                ((Number) delaySpinner.getValue()).longValue(),
                ((Number) limitSpinner.getValue()).intValue(),
                dryRunCheck.isSelected(),
                alwaysNetworkCheck.isSelected()
        );
    }

    private void browseDatabase(JTextField pathField) {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle(message("gui.tools.dialog.select-sqlite-database.title"));
        chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
        File current = new File(pathField.getText().trim());
        File parent = current.isDirectory() ? current : current.getParentFile();
        if (parent != null && parent.exists()) {
            chooser.setCurrentDirectory(parent);
        }
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            pathField.setText(chooser.getSelectedFile().getAbsolutePath());
        }
    }

    private void openBackfillLogPage() {
        var task = backfillTask;
        if (task != null && task.logFileName() != null) openToolLogFile(task.logFileName());
    }

    private void browseMigrationRootFolder() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle(message("gui.tools.dialog.select-root-folder.title"));
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        File current = new File(migrationRootFolderField.getText().trim());
        if (current.exists()) {
            chooser.setCurrentDirectory(current.isDirectory() ? current : current.getParentFile());
        }
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            migrationRootFolderField.setText(chooser.getSelectedFile().getAbsolutePath());
        }
    }

    private void startMigration() {
        if (!beginExclusiveTool(message("gui.tools.card.migration.title"))) {
            return;
        }

        DesktopUiHost.MigrationOptions options;
        try {
            options = buildMigrationOptions();
        } catch (Exception e) {
            exclusiveToolName = null;
            refreshActionStates();
            log.warn(logMessage("gui.tools.log.migration.invalid-params", e.getMessage()));
            GuiErrorDialog.show(this,
                    message("gui.dialog.error.title"),
                    message("gui.tools.dialog.migration.invalid-params.message", e.getMessage()));
            return;
        }

        migrationRunning = true;
        refreshActionStates();
        setMigrationStatus(message("gui.tools.migration.status.preparing"));

        boolean restart = SwingBackendLifecycle.state() == DesktopUiHost.BackendState.RUNNING;
        boolean accepted = SwingBackendLifecycle.stopAsync(() -> prepareMigrationInBackground(options, restart),
                failure -> handleToolStopFailure(true, failure));
        if (!accepted) handleToolStopFailure(true, null);
    }

    private void prepareMigrationInBackground(DesktopUiHost.MigrationOptions options, boolean restart) {
        setMigrationStatus(message("gui.tools.migration.status.counting"));

        Thread worker = new Thread(() -> {
            int totalCandidates;
            try {
                totalCandidates = SwingHost.host().countMigrationCandidates(options);
            } catch (Throwable error) {
                handleMigrationPreparationFailure(
                        message("gui.tools.migration.status.count-failed"),
                        message("gui.tools.dialog.migration.count-failed.prefix"),
                        error, restart
                );
                return;
            }

            if (totalCandidates > 0) {
                setMigrationStatus(message("gui.tools.migration.status.pending-found", totalCandidates));
            } else {
                setMigrationStatus(message("gui.tools.migration.status.none-found"));
            }

            try {
                currentMigrationLogSession = SwingHost.host().openToolLog("json-to-sqlite-migration");
                SwingUtilities.invokeLater(this::refreshActionStates);
            } catch (Exception error) {
                handleMigrationPreparationFailure(
                        message("gui.tools.migration.status.log-open-failed"),
                        message("gui.tools.dialog.migration.log-open-failed.prefix"),
                        error, restart
                );
                return;
            }

            openLogAfterPreparation(currentMigrationLogSession);
            runMigrationInBackground(options, restart);
        }, "tools-json-to-sqlite-migration-prepare");
        worker.setDaemon(true);
        worker.start();
    }

    private void handleMigrationPreparationFailure(String statusText, String dialogPrefix, Throwable failure, boolean restart) {
        log.error(logMessage("gui.tools.log.migration.prepare-failed",
                failure == null ? logMessage("gui.log.no-detail") : failure.getMessage()), failure);
        completeMigration(restart, () -> {
            setMigrationStatus(statusText);
            GuiErrorDialog.show(this,
                    message("gui.dialog.error.title"),
                    dialogPrefix + (failure.getMessage() == null
                            ? message("gui.dialog.error.no-detail") : failure.getMessage()));
        });
    }

    private void completeMigration(boolean restart, Runnable showResult) {
        closeMigrationLogSession();
        SwingUtilities.invokeLater(() -> {
            try {
                if (restart) SwingBackendLifecycle.startAsync();
            } finally {
                migrationRunning = false;
                exclusiveToolName = null;
                refreshActionStates();
                showResult.run();
            }
        });
    }

    private void runMigrationInBackground(DesktopUiHost.MigrationOptions options, boolean restart) {
        setMigrationStatus(message("gui.tools.migration.status.running"));
        long startedAt = System.currentTimeMillis();

        Thread worker = new Thread(() -> {
            DesktopUiHost.MigrationSummary summary = null;
            Throwable failure = null;

            try {
                log.info(logMessage("gui.tools.log.migration.requested"));
                summary = SwingHost.host().runMigration(options, log::info);
            } catch (Throwable error) {
                failure = error;
                log.error(logMessage("gui.tools.log.migration.failed"), error);
            } finally {
                try {
                    SwingHost.host().recordToolHistory(
                            DesktopUiHost.ToolId.JSON_TO_SQLITE_MIGRATION,
                            failure != null || summary == null ? DesktopUiHost.ToolOutcome.FAILED : summary.outcome(),
                            startedAt,
                            summary == null ? null : summary.totalCandidates(),
                            summary == null ? null : summary.migrated(),
                            summary == null ? null : summary.failed(),
                            currentMigrationLogSession == null ? null : currentMigrationLogSession.sessionPath()
                    );
                } catch (RuntimeException historyFailure) {
                    log.warn(logMessage("gui.tools.log.migration.failed"), historyFailure);
                } finally {
                    log.info(logMessage("gui.tools.log.migration.finished"));
                    DesktopUiHost.MigrationSummary finalSummary = summary;
                    Throwable finalFailure = failure;
                    completeMigration(restart, () -> finishMigration(finalSummary, finalFailure));
                }
            }
        }, "tools-json-to-sqlite-migration");
        worker.setDaemon(true);
        worker.start();
    }

    private void finishMigration(DesktopUiHost.MigrationSummary summary, Throwable failure) {
        if (failure != null) {
            setMigrationStatus(message("gui.tools.migration.status.failed"));
            GuiErrorDialog.show(this,
                    message("gui.dialog.error.title"),
                    message("gui.tools.dialog.migration.failed.message",
                            failure.getMessage() == null
                                    ? message("gui.dialog.error.no-detail") : failure.getMessage()));
            return;
        }

        if (summary == null) {
            setMigrationStatus(message("gui.tools.migration.status.finished"));
            return;
        }

        if (summary.historyFileMissing()) {
            setMigrationStatus(message("gui.tools.migration.status.history-missing"));
            return;
        }

        var feedback = migrationFeedback(summary);
        setMigrationStatus(feedback.status());
        JOptionPane.showMessageDialog(
                this,
                feedback.details(),
                feedback.status(),
                feedback.messageType()
        );
    }

    static MigrationFeedback migrationFeedback(DesktopUiHost.MigrationSummary summary) {
        String result = message(switch (summary.outcome()) {
            case PARTIALLY_FAILED -> "gui.tools.migration.result.partial";
            case FAILED -> "gui.tools.migration.result.failed";
            default -> "gui.tools.migration.result.completed";
        });
        return new MigrationFeedback(
                result,
                message(
                        "gui.tools.dialog.migration.completed.message",
                        result,
                        summary.migrated(),
                        summary.skipped(),
                        summary.totalCandidates(),
                        summary.failed()
                ),
                switch (summary.outcome()) {
                    case PARTIALLY_FAILED -> JOptionPane.WARNING_MESSAGE;
                    case FAILED -> JOptionPane.ERROR_MESSAGE;
                    default -> JOptionPane.INFORMATION_MESSAGE;
                }
        );
    }

    record MigrationFeedback(String status, String details, int messageType) {}

    private DesktopUiHost.MigrationOptions buildMigrationOptions() {
        String dbPath = migrationDbPathField.getText().trim();
        if (dbPath.isBlank()) {
            throw new IllegalArgumentException(message("gui.tools.validation.database-path.required"));
        }
        String rootFolder = migrationRootFolderField.getText().trim();
        if (rootFolder.isBlank()) {
            throw new IllegalArgumentException(message("gui.tools.validation.root-folder.required"));
        }
        return new DesktopUiHost.MigrationOptions(dbPath, rootFolder);
    }

    private void openMigrationLogPage() {
        openToolLogPage("json-to-sqlite-migration");
    }

    private void openToolLogPage(String stem) {
        openToolLogFile(stem + "-latest.html");
    }

    private void openToolLogFile(String fileName) {
        if (logPageOpening) return;
        logPageOpening = true;
        new SwingWorker<Void, Void>() {
            @Override protected Void doInBackground() throws Exception {
                var host = SwingHost.host();
                Path logPath = host.findToolLog(fileName).orElseThrow(
                        () -> new java.io.IOException("Tool log is no longer available"));
                host.openExternalUri(logPath.toUri());
                return null;
            }
            @Override protected void done() {
                logPageOpening = false;
                try { get(); }
                catch (Exception failure) {
                    log.warn(logMessage("desktop.ui.tools.log-open-failed"), failure);
                    GuiErrorDialog.show(ToolsPanel.this, message("gui.dialog.error.title"),
                            SwingHost.host().message("desktop.ui.tools.log-open-failed"));
                }
            }
        }.execute();
    }

    private void openLogAfterPreparation(DesktopUiHost.ToolLogSession session) {
        try { session.openLatestInBrowser(); }
        catch (Exception failure) {
            log.warn(logMessage("desktop.ui.tools.log-open-failed"), failure);
        }
    }

    private void handleBackendState(DesktopUiHost.BackendSnapshot snapshot) {
        mediaTools.refreshTools();
        fileImports.refreshTools();
        localImports.refreshTools();
        backendDot.color(switch (snapshot.state()) {
            case RUNNING -> SwingPage::successColor;
            case STARTING, STOPPING -> SwingPage::warningColor;
            case FAILED -> SwingPage::errorColor;
            case STOPPED -> SwingPage::secondaryColor;
        });
        backendStateLabel.repaint();
        backendStateLabel.setText(message("gui.tools.backend-status", switch (snapshot.state()) {
            case RUNNING -> message("gui.backend.state.running");
            case STARTING -> message("gui.tools.backend-status.starting");
            case STOPPING -> message("gui.tools.backend-status.stopping");
            case STOPPED -> message("gui.backend.state.stopped");
            case FAILED -> message("gui.backend.state.failed");
        }));

        if (snapshot.state() == DesktopUiHost.BackendState.FAILED && previousBackendState != DesktopUiHost.BackendState.STOPPING
                && !backfillRunning && !migrationRunning && exclusiveToolName == null) {
            setBackfillStatus(message("gui.tools.backfill.status.backend-failed"));
            setMigrationStatus(message("gui.tools.migration.status.backend-failed"));
        }

        previousBackendState = snapshot.state();
        refreshActionStates();
    }

    private boolean beginExclusiveTool(String toolName) {
        if (!ensureNoExclusiveTool(toolName)) {
            return false;
        }
        DesktopUiHost.BackendState state = SwingBackendLifecycle.state();
        if (state == DesktopUiHost.BackendState.STARTING || state == DesktopUiHost.BackendState.STOPPING) {
            JOptionPane.showMessageDialog(this,
                    message("gui.message.backend-busy"),
                    message("gui.dialog.please-wait.title"), JOptionPane.INFORMATION_MESSAGE);
            return false;
        }
        exclusiveToolName = toolName;
        refreshActionStates();
        return true;
    }

    private boolean ensureNoExclusiveTool(String requestedTool) {
        if (exclusiveToolName != null) {
            JOptionPane.showMessageDialog(this,
                    message("gui.tools.dialog.exclusive-busy.message", exclusiveToolName, requestedTool),
                    message("gui.tools.dialog.exclusive-busy.title"), JOptionPane.INFORMATION_MESSAGE);
            return false;
        }
        return true;
    }

    private void refreshActionStates() {
        DesktopUiHost.BackendState state = SwingBackendLifecycle.state();
        boolean backendTransitioning = state == DesktopUiHost.BackendState.STARTING
                || state == DesktopUiHost.BackendState.STOPPING;
        boolean exclusiveBusy = exclusiveToolName != null || backfillRunning || migrationRunning;

        imageClassifierButton.setEnabled(!exclusiveBusy && !backendTransitioning);
        folderCheckerButton.setEnabled(!exclusiveBusy && !backendTransitioning);
        backfillRunButton.setEnabled(!exclusiveBusy && state == DesktopUiHost.BackendState.RUNNING);
        backfillLogButton.setEnabled(backfillTask != null && backfillTask.logFileName() != null);
        backfillStopButton.setVisible(backfillRunning);
        backfillStopButton.setEnabled(backfillTask != null && backfillTask.state() == DesktopUiHost.BackfillState.RUNNING);
        migrationRunButton.setEnabled(!exclusiveBusy && !backendTransitioning);
        migrationLogButton.setEnabled(currentMigrationLogSession != null || Files.exists(MIGRATION_LOG_PATH));

        boolean backfillEditable = !backfillRunning && !backendTransitioning;
        dbPathField.setEnabled(backfillEditable && SwingHost.host().supportsDatabaseFileSelection());
        proxyEnabledCheck.setEnabled(backfillEditable);
        dryRunCheck.setEnabled(backfillEditable);
        alwaysNetworkCheck.setEnabled(backfillEditable);
        delaySpinner.setEnabled(backfillEditable);
        limitSpinner.setEnabled(backfillEditable);
        updateProxyFieldState();

        boolean migrationEditable = !migrationRunning && !backendTransitioning;
        migrationDbPathField.setEnabled(migrationEditable && SwingHost.host().supportsDatabaseFileSelection());
        migrationRootFolderField.setEnabled(migrationEditable);

        exclusiveToolLabel.setText(message("gui.tools.exclusive-tool",
                backfillRunning ? message("gui.tools.card.backfill.title")
                        : exclusiveToolName == null ? message("gui.value.none") : exclusiveToolName));

        // 把独占工具名镜像到进程内持有者，供状态页把「已停止」细化为「使用某工具导致后端停止」。
        // 每次工具状态变更都会走到这里，故此处是与状态页同步的单一事实源。
        ExclusiveToolHolder.set(exclusiveToolName);
    }

    private void updateProxyFieldState() {
        boolean enabled = proxyEnabledCheck.isSelected() && proxyEnabledCheck.isEnabled();
        proxyHostField.setEnabled(enabled);
        proxyPortSpinner.setEnabled(enabled);
    }

    private void closeMigrationLogSession() {
        if (currentMigrationLogSession == null) {
            return;
        }
        try {
            currentMigrationLogSession.close();
        } catch (Exception ignored) {
        } finally {
            currentMigrationLogSession = null;
        }
    }

    private void setBackfillStatus(String text) {
        SwingUtilities.invokeLater(() -> backfillStatusLabel.setText(text));
    }

    private void setMigrationStatus(String text) {
        SwingUtilities.invokeLater(() -> migrationStatusLabel.setText(text));
    }

    public void dispose() {
        mediaTools.close();
        fileImports.close();
        localImports.close();
        startupCache.close();
        SwingBackendLifecycle.removeListener(backendListener);
        disposed = true;
        backfillTimer.stop();
        closeMigrationLogSession();
    }

    private static String message(String code, Object... args) {
        return GuiMessages.get(code, args);
    }

    private static String logMessage(String code, Object... args) {
        return SwingHost.host().message(code, args);
    }
}
