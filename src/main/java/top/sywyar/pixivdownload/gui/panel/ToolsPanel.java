package top.sywyar.pixivdownload.gui.panel;

import lombok.extern.slf4j.Slf4j;
import org.slf4j.LoggerFactory;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.gui.BackendLifecycleManager;
import top.sywyar.pixivdownload.gui.GuiErrorDialog;
import top.sywyar.pixivdownload.gui.ToolHtmlLogSession;
import top.sywyar.pixivdownload.gui.config.ConfigFileEditor;
import top.sywyar.pixivdownload.gui.i18n.GuiMessages;
import top.sywyar.pixivdownload.i18n.MessageBundles;
import top.sywyar.pixivdownload.imageclassifier.ImageClassifier;
import top.sywyar.pixivdownload.migration.JsonToSqliteMigration;
import top.sywyar.pixivdownload.tools.ArtworksBackFill;
import top.sywyar.pixivdownload.tools.FolderChecker;

import javax.swing.*;
import javax.swing.border.TitledBorder;
import javax.swing.text.InternationalFormatter;
import java.awt.*;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Format;
import java.text.NumberFormat;

/**
 * Tool shortcuts exposed from the main Swing GUI.
 */
@Slf4j
public class ToolsPanel extends JPanel {

    private static final Path BACKFILL_LOG_PATH = Path.of("log", "html", "artworks-backfill-latest.html");
    private static final Path MIGRATION_LOG_PATH = Path.of("log", "html", "json-to-sqlite-migration-latest.html");
    private static final String BACKFILL_COUNTING_STATUS = message("gui.tools.backfill.status.counting");
    private static final String MIGRATION_COUNTING_STATUS = message("gui.tools.migration.status.counting");

    private final Path configPath;

    private final JLabel backendStateLabel = new JLabel(message("gui.tools.backend-status", message("gui.tools.backend-status.detecting")));
    private final JLabel exclusiveToolLabel = new JLabel(message("gui.tools.exclusive-tool", message("gui.value.none")));
    private final JLabel backfillStatusLabel = secondaryLabel(message("gui.tools.backfill.status.idle"));
    private final JLabel migrationStatusLabel = secondaryLabel(message("gui.tools.migration.status.idle"));

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
    private final JCheckBox dryRunCheck = new JCheckBox(message("gui.tools.form.dry-run"));

    private final JTextField migrationDbPathField = new JTextField(34);
    private final JTextField migrationRootFolderField = new JTextField(34);

    private String exclusiveToolName;
    private boolean backfillRunning;
    private boolean migrationRunning;
    private volatile ToolHtmlLogSession currentBackfillLogSession;
    private volatile ToolHtmlLogSession currentMigrationLogSession;

    private final BackendLifecycleManager.Listener backendListener = this::handleBackendState;

    public ToolsPanel(Path configPath) {
        this.configPath = configPath;
        buildUi();
        configureBackfillNumberInputs();
        loadDefaults();
        BackendLifecycleManager.addListener(backendListener);
        refreshActionStates();
    }

    private void buildUi() {
        setLayout(new BorderLayout(0, 0));
        setBorder(BorderFactory.createEmptyBorder(16, 24, 16, 24));

        JPanel content = new JPanel();
        content.setOpaque(false);
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.add(buildOverviewCard());
        content.add(Box.createVerticalStrut(12));
        content.add(buildImageClassifierCard());
        content.add(Box.createVerticalStrut(12));
        content.add(buildFolderCheckerCard());
        content.add(Box.createVerticalStrut(12));
        content.add(buildBackfillCard());
        content.add(Box.createVerticalStrut(12));
        content.add(buildMigrationCard());
        content.add(Box.createVerticalGlue());

        JScrollPane scrollPane = new JScrollPane(content);
        scrollPane.setBorder(null);
        scrollPane.getVerticalScrollBar().setUnitIncrement(16);
        add(scrollPane, BorderLayout.CENTER);
    }

    private JComponent buildOverviewCard() {
        JPanel panel = createCard(message("gui.tools.card.overview.title"));
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        backendStateLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        exclusiveToolLabel.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel hint = secondaryLabel(message("gui.tools.card.overview.hint"));
        hint.setAlignmentX(Component.LEFT_ALIGNMENT);

        panel.add(backendStateLabel);
        panel.add(Box.createVerticalStrut(6));
        panel.add(exclusiveToolLabel);
        panel.add(Box.createVerticalStrut(10));
        panel.add(hint);
        return panel;
    }

    private JComponent buildImageClassifierCard() {
        JPanel panel = createCard(message("gui.tools.card.image-classifier.title"));
        panel.setLayout(new BorderLayout(0, 10));

        JLabel desc = secondaryLabel(message("gui.tools.card.image-classifier.description"));
        panel.add(desc, BorderLayout.CENTER);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        actions.setOpaque(false);
        imageClassifierButton.addActionListener(e -> openImageClassifier());
        actions.add(imageClassifierButton);
        panel.add(actions, BorderLayout.SOUTH);
        return panel;
    }

    private JComponent buildFolderCheckerCard() {
        JPanel panel = createCard(message("gui.tools.card.folder-checker.title"));
        panel.setLayout(new BorderLayout(0, 10));

        JLabel desc = secondaryLabel(message("gui.tools.card.folder-checker.description"));
        panel.add(desc, BorderLayout.CENTER);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        actions.setOpaque(false);
        folderCheckerButton.addActionListener(e -> openFolderChecker());
        actions.add(folderCheckerButton);
        panel.add(actions, BorderLayout.SOUTH);
        return panel;
    }

    private JComponent buildBackfillCard() {
        JPanel panel = createCard(message("gui.tools.card.backfill.title"));
        panel.setLayout(new BorderLayout(0, 12));

        JPanel form = new JPanel(new GridBagLayout());
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
        browseDbButton.addActionListener(e -> browseDatabase());
        form.add(browseDbButton, g);

        row++;
        g.gridy = row;
        g.gridx = 0;
        form.add(new JLabel(message("gui.tools.form.proxy")), g);
        JPanel proxyPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        proxyPanel.setOpaque(false);
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
        g.weightx = 0;
        form.add(delaySpinner, g);
        g.gridx = 2;
        form.add(new JLabel(message("gui.tools.form.limit-hint")), g);

        row++;
        g.gridy = row;
        g.gridx = 0;
        form.add(new JLabel(message("gui.tools.form.limit")), g);
        g.gridx = 1;
        form.add(limitSpinner, g);
        g.gridx = 2;
        form.add(dryRunCheck, g);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        actions.setOpaque(false);
        backfillRunButton.addActionListener(e -> startBackfill());
        backfillLogButton.addActionListener(e -> openBackfillLogPage());
        actions.add(backfillRunButton);
        actions.add(backfillLogButton);

        JPanel bottom = new JPanel();
        bottom.setOpaque(false);
        bottom.setLayout(new BoxLayout(bottom, BoxLayout.Y_AXIS));
        backfillStatusLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        actions.setAlignmentX(Component.LEFT_ALIGNMENT);
        bottom.add(backfillStatusLabel);
        bottom.add(Box.createVerticalStrut(8));
        bottom.add(actions);

        panel.add(form, BorderLayout.CENTER);
        panel.add(bottom, BorderLayout.SOUTH);
        return panel;
    }

    private JComponent buildMigrationCard() {
        JPanel panel = createCard(message("gui.tools.card.migration.title"));
        panel.setLayout(new BorderLayout(0, 12));

        JLabel desc = secondaryLabel(message("gui.tools.card.migration.description"));

        JPanel form = new JPanel(new GridBagLayout());
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
        form.add(migrationDbPathField, g);
        g.gridx = 2;
        g.weightx = 0;
        JButton browseDbButton = new JButton(message("gui.button.browse"));
        browseDbButton.addActionListener(e -> browseMigrationDatabase());
        form.add(browseDbButton, g);

        row++;
        g.gridy = row;
        g.gridx = 0;
        g.weightx = 0;
        form.add(new JLabel(message("gui.tools.form.root-folder")), g);
        g.gridx = 1;
        g.weightx = 1;
        form.add(migrationRootFolderField, g);
        g.gridx = 2;
        g.weightx = 0;
        JButton browseRootButton = new JButton(message("gui.button.browse"));
        browseRootButton.addActionListener(e -> browseMigrationRootFolder());
        form.add(browseRootButton, g);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        actions.setOpaque(false);
        migrationRunButton.addActionListener(e -> startMigration());
        migrationLogButton.addActionListener(e -> openMigrationLogPage());
        actions.add(migrationRunButton);
        actions.add(migrationLogButton);

        JPanel bottom = new JPanel();
        bottom.setOpaque(false);
        bottom.setLayout(new BoxLayout(bottom, BoxLayout.Y_AXIS));
        desc.setAlignmentX(Component.LEFT_ALIGNMENT);
        migrationStatusLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        actions.setAlignmentX(Component.LEFT_ALIGNMENT);
        bottom.add(desc);
        bottom.add(Box.createVerticalStrut(8));
        bottom.add(migrationStatusLabel);
        bottom.add(Box.createVerticalStrut(8));
        bottom.add(actions);

        panel.add(form, BorderLayout.CENTER);
        panel.add(bottom, BorderLayout.SOUTH);
        return panel;
    }

    private JPanel createCard(String title) {
        JPanel panel = new JPanel();
        panel.setOpaque(false);
        TitledBorder border = BorderFactory.createTitledBorder(title);
        border.setTitleJustification(TitledBorder.LEFT);
        panel.setBorder(BorderFactory.createCompoundBorder(
                border,
                BorderFactory.createEmptyBorder(10, 12, 12, 12)
        ));
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
        return panel;
    }

    private void loadDefaults() {
        String rootFolder = RuntimeFiles.readDownloadRootFromConfig(configPath, RuntimeFiles.DEFAULT_DOWNLOAD_ROOT);
        String resolvedDbPath = RuntimeFiles.resolveDatabasePath(rootFolder).toString();
        dbPathField.setText(resolvedDbPath);
        migrationDbPathField.setText(resolvedDbPath);
        migrationRootFolderField.setText(rootFolder);

        ArtworksBackFill.Options defaults = ArtworksBackFill.Options.defaults();
        proxyEnabledCheck.setSelected(defaults.useProxy());
        proxyHostField.setText(defaults.proxyHost());
        proxyPortSpinner.setValue(defaults.proxyPort());
        delaySpinner.setValue((int) defaults.delayMs());
        limitSpinner.setValue(defaults.limit());
        dryRunCheck.setSelected(defaults.dryRun());

        if (Files.isRegularFile(configPath)) {
            try {
                ConfigFileEditor editor = new ConfigFileEditor(configPath);
                proxyEnabledCheck.setSelected(Boolean.parseBoolean(defaultIfBlank(editor.read("proxy.enabled"), String.valueOf(defaults.useProxy()))));
                proxyHostField.setText(defaultIfBlank(editor.read("proxy.host"), defaults.proxyHost()));
                proxyPortSpinner.setValue(Integer.parseInt(defaultIfBlank(editor.read("proxy.port"), String.valueOf(defaults.proxyPort()))));
            } catch (Exception e) {
                log.debug(logMessage("gui.tools.log.proxy-defaults.failed", e.getMessage()));
            }
        }
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
        boolean accepted = BackendLifecycleManager.stopAsync(() -> {
            try {
                FolderChecker checker = new FolderChecker(JFrame.DISPOSE_ON_CLOSE, this::handleFolderCheckerClosed);
                checker.showWindow();
                setBackfillStatus(message("gui.tools.folder-checker.status.opened"));
            } catch (Exception e) {
                exclusiveToolName = null;
                refreshActionStates();
                setBackfillStatus(message("gui.tools.folder-checker.status.open-failed"));
                log.error(logMessage("gui.tools.log.folder-checker.open-failed"), e);
                BackendLifecycleManager.startAsync();
                GuiErrorDialog.show(this,
                        message("gui.dialog.error.title"),
                        message("gui.tools.dialog.folder-checker-open-failed.message", e.getMessage()));
            }
        });
        if (!accepted) {
            exclusiveToolName = null;
            refreshActionStates();
            JOptionPane.showMessageDialog(this,
                    message("gui.message.backend-busy"),
                    message("gui.dialog.please-wait.title"), JOptionPane.INFORMATION_MESSAGE);
        }
    }

    private void handleFolderCheckerClosed() {
        exclusiveToolName = null;
        refreshActionStates();
        setBackfillStatus(message("gui.tools.folder-checker.status.restoring"));
        if (!BackendLifecycleManager.startAsync(() ->
                setBackfillStatus(message("gui.tools.folder-checker.status.completed")))) {
            setBackfillStatus(message("gui.tools.folder-checker.status.closed"));
        }
    }

    private void startBackfill() {
        if (!beginExclusiveTool(message("gui.tools.card.backfill.title"))) {
            return;
        }

        ArtworksBackFill.Options options;
        try {
            options = buildBackfillOptions();
        } catch (Exception e) {
            exclusiveToolName = null;
            refreshActionStates();
            log.warn(logMessage("gui.tools.log.backfill.invalid-params", e.getMessage()));
            GuiErrorDialog.show(this,
                    message("gui.dialog.error.title"),
                    message("gui.tools.dialog.backfill.invalid-params.message", e.getMessage()));
            return;
        }

        backfillRunning = true;
        refreshActionStates();
        setBackfillStatus(message("gui.tools.backfill.status.preparing"));

        boolean accepted = BackendLifecycleManager.stopAsync(() -> prepareBackfillInBackground(options));
        if (!accepted) {
            backfillRunning = false;
            exclusiveToolName = null;
            closeBackfillLogSession();
            refreshActionStates();
            JOptionPane.showMessageDialog(this,
                    message("gui.message.backend-busy"),
                    message("gui.dialog.please-wait.title"), JOptionPane.INFORMATION_MESSAGE);
        }
    }

    private void prepareBackfillInBackground(ArtworksBackFill.Options options) {
        setBackfillStatus(BACKFILL_COUNTING_STATUS);

        Thread worker = new Thread(() -> {
            int totalCandidates;
            try {
                totalCandidates = ArtworksBackFill.countCandidates(options);
            } catch (Throwable error) {
                handleBackfillPreparationFailure(
                        message("gui.tools.backfill.status.count-failed"),
                        message("gui.tools.dialog.backfill.count-failed.prefix"),
                        error
                );
                return;
            }

            if (totalCandidates > 0) {
                setBackfillStatus(message("gui.tools.backfill.status.pending-found", totalCandidates));
            } else {
                setBackfillStatus(message("gui.tools.backfill.status.none-found"));
            }

            try {
                currentBackfillLogSession = ToolHtmlLogSession.open("artworks-backfill", ArtworksBackFill.class);
                SwingUtilities.invokeLater(this::refreshActionStates);
                currentBackfillLogSession.openLatestInBrowser();
            } catch (Exception error) {
                handleBackfillPreparationFailure(
                        message("gui.tools.backfill.status.log-open-failed"),
                        message("gui.tools.dialog.backfill.log-open-failed.prefix"),
                        error
                );
                return;
            }

            runBackfillInBackground(options);
        }, "tools-artworks-backfill-prepare");
        worker.setDaemon(true);
        worker.start();
    }

    private void handleBackfillPreparationFailure(String statusText, String dialogPrefix, Throwable failure) {
        log.error(logMessage("gui.tools.log.backfill.prepare-failed",
                failure == null ? logMessage("gui.log.no-detail") : failure.getMessage()), failure);
        closeBackfillLogSession();
        backfillRunning = false;
        exclusiveToolName = null;
        SwingUtilities.invokeLater(this::refreshActionStates);

        Runnable afterRestart = () -> SwingUtilities.invokeLater(() -> {
            setBackfillStatus(statusText);
            GuiErrorDialog.show(this,
                    message("gui.dialog.error.title"),
                    dialogPrefix + (failure.getMessage() == null
                            ? message("gui.dialog.error.no-detail") : failure.getMessage()));
        });
        if (!BackendLifecycleManager.startAsync(afterRestart)) {
            afterRestart.run();
        }
    }

    private void runBackfillInBackground(ArtworksBackFill.Options options) {
        setBackfillStatus(message("gui.tools.backfill.status.running"));

        Thread worker = new Thread(() -> {
            ArtworksBackFill.Summary summary = null;
            Throwable failure = null;
            var toolLogger = LoggerFactory.getLogger(ArtworksBackFill.class);

            try {
                toolLogger.info(logMessage("gui.tools.log.backfill.requested"));
                summary = ArtworksBackFill.run(options);
            } catch (Throwable error) {
                failure = error;
                toolLogger.error(logMessage("gui.tools.log.backfill.failed"), error);
            } finally {
                toolLogger.info(logMessage("gui.tools.log.backfill.finished"));
                closeBackfillLogSession();
                backfillRunning = false;
                exclusiveToolName = null;
                SwingUtilities.invokeLater(this::refreshActionStates);

                ArtworksBackFill.Summary finalSummary = summary;
                Throwable finalFailure = failure;
                if (!BackendLifecycleManager.startAsync(() ->
                        SwingUtilities.invokeLater(() -> finishBackfill(finalSummary, finalFailure)))) {
                    SwingUtilities.invokeLater(() -> finishBackfill(finalSummary, finalFailure));
                }
            }
        }, "tools-artworks-backfill");
        worker.setDaemon(true);
        worker.start();
    }

    private void finishBackfill(ArtworksBackFill.Summary summary, Throwable failure) {
        if (failure != null) {
            setBackfillStatus(message("gui.tools.backfill.status.failed"));
            GuiErrorDialog.show(this,
                    message("gui.dialog.error.title"),
                    message("gui.tools.dialog.backfill.failed.message",
                            failure.getMessage() == null
                                    ? message("gui.dialog.error.no-detail") : failure.getMessage()));
            return;
        }

        if (summary == null) {
            setBackfillStatus(message("gui.tools.backfill.status.finished"));
            return;
        }

        String result = summary.rateLimited()
                ? message("gui.tools.backfill.result.rate-limited")
                : message("gui.tools.backfill.result.completed");
        setBackfillStatus(result);
        JOptionPane.showMessageDialog(this,
                message("gui.tools.dialog.backfill.completed.message", result, summary.processed(), summary.totalCandidates()),
                message("gui.tools.dialog.backfill.completed.title"), JOptionPane.INFORMATION_MESSAGE);
    }

    private ArtworksBackFill.Options buildBackfillOptions() {
        String dbPath = dbPathField.getText().trim();
        if (dbPath.isBlank()) {
            throw new IllegalArgumentException(message("gui.tools.validation.database-path.required"));
        }

        return new ArtworksBackFill.Options(
                dbPath,
                proxyHostField.getText().trim(),
                ((Number) proxyPortSpinner.getValue()).intValue(),
                proxyEnabledCheck.isSelected(),
                ((Number) delaySpinner.getValue()).longValue(),
                ((Number) limitSpinner.getValue()).intValue(),
                dryRunCheck.isSelected()
        );
    }

    private void browseDatabase() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle(message("gui.tools.dialog.select-sqlite-database.title"));
        chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
        File current = new File(dbPathField.getText().trim());
        File parent = current.isDirectory() ? current : current.getParentFile();
        if (parent != null && parent.exists()) {
            chooser.setCurrentDirectory(parent);
        }
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            dbPathField.setText(chooser.getSelectedFile().getAbsolutePath());
        }
    }

    private void openBackfillLogPage() {
        try {
            if (currentBackfillLogSession != null) {
                currentBackfillLogSession.openLatestInBrowser();
                return;
            }
            if (!Files.exists(BACKFILL_LOG_PATH)) {
                JOptionPane.showMessageDialog(this,
                        message("gui.tools.dialog.backfill-log.missing"),
                        message("gui.dialog.info.title"), JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            Desktop.getDesktop().browse(BACKFILL_LOG_PATH.toUri());
        } catch (Exception e) {
            log.warn(logMessage("gui.tools.log.backfill.open-log-page-failed", e.getMessage()), e);
            GuiErrorDialog.show(this,
                    message("gui.dialog.error.title"),
                    message("gui.tools.dialog.backfill-log.open-failed", e.getMessage()));
        }
    }

    private void browseMigrationDatabase() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle(message("gui.tools.dialog.select-sqlite-database.title"));
        chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
        File current = new File(migrationDbPathField.getText().trim());
        File parent = current.isDirectory() ? current : current.getParentFile();
        if (parent != null && parent.exists()) {
            chooser.setCurrentDirectory(parent);
        }
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            migrationDbPathField.setText(chooser.getSelectedFile().getAbsolutePath());
        }
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

        JsonToSqliteMigration.Options options;
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

        boolean accepted = BackendLifecycleManager.stopAsync(() -> prepareMigrationInBackground(options));
        if (!accepted) {
            migrationRunning = false;
            exclusiveToolName = null;
            closeMigrationLogSession();
            refreshActionStates();
            JOptionPane.showMessageDialog(this,
                    message("gui.message.backend-busy"),
                    message("gui.dialog.please-wait.title"), JOptionPane.INFORMATION_MESSAGE);
        }
    }

    private void prepareMigrationInBackground(JsonToSqliteMigration.Options options) {
        setMigrationStatus(MIGRATION_COUNTING_STATUS);

        Thread worker = new Thread(() -> {
            int totalCandidates;
            try {
                totalCandidates = JsonToSqliteMigration.countCandidates(options);
            } catch (Throwable error) {
                handleMigrationPreparationFailure(
                        message("gui.tools.migration.status.count-failed"),
                        message("gui.tools.dialog.migration.count-failed.prefix"),
                        error
                );
                return;
            }

            if (totalCandidates > 0) {
                setMigrationStatus(message("gui.tools.migration.status.pending-found", totalCandidates));
            } else {
                setMigrationStatus(message("gui.tools.migration.status.none-found"));
            }

            try {
                currentMigrationLogSession = ToolHtmlLogSession.open("json-to-sqlite-migration", JsonToSqliteMigration.class);
                SwingUtilities.invokeLater(this::refreshActionStates);
                currentMigrationLogSession.openLatestInBrowser();
            } catch (Exception error) {
                handleMigrationPreparationFailure(
                        message("gui.tools.migration.status.log-open-failed"),
                        message("gui.tools.dialog.migration.log-open-failed.prefix"),
                        error
                );
                return;
            }

            runMigrationInBackground(options);
        }, "tools-json-to-sqlite-migration-prepare");
        worker.setDaemon(true);
        worker.start();
    }

    private void handleMigrationPreparationFailure(String statusText, String dialogPrefix, Throwable failure) {
        log.error(logMessage("gui.tools.log.migration.prepare-failed",
                failure == null ? logMessage("gui.log.no-detail") : failure.getMessage()), failure);
        closeMigrationLogSession();
        migrationRunning = false;
        exclusiveToolName = null;
        SwingUtilities.invokeLater(this::refreshActionStates);

        Runnable afterRestart = () -> SwingUtilities.invokeLater(() -> {
            setMigrationStatus(statusText);
            GuiErrorDialog.show(this,
                    message("gui.dialog.error.title"),
                    dialogPrefix + (failure.getMessage() == null
                            ? message("gui.dialog.error.no-detail") : failure.getMessage()));
        });
        if (!BackendLifecycleManager.startAsync(afterRestart)) {
            afterRestart.run();
        }
    }

    private void runMigrationInBackground(JsonToSqliteMigration.Options options) {
        setMigrationStatus(message("gui.tools.migration.status.running"));

        Thread worker = new Thread(() -> {
            JsonToSqliteMigration.Summary summary = null;
            Throwable failure = null;
            var toolLogger = LoggerFactory.getLogger(JsonToSqliteMigration.class);

            try {
                toolLogger.info(logMessage("gui.tools.log.migration.requested"));
                summary = JsonToSqliteMigration.run(options, toolLogger::info);
            } catch (Throwable error) {
                failure = error;
                toolLogger.error(logMessage("gui.tools.log.migration.failed"), error);
            } finally {
                toolLogger.info(logMessage("gui.tools.log.migration.finished"));
                closeMigrationLogSession();
                migrationRunning = false;
                exclusiveToolName = null;
                SwingUtilities.invokeLater(this::refreshActionStates);

                JsonToSqliteMigration.Summary finalSummary = summary;
                Throwable finalFailure = failure;
                if (!BackendLifecycleManager.startAsync(() ->
                        SwingUtilities.invokeLater(() -> finishMigration(finalSummary, finalFailure)))) {
                    SwingUtilities.invokeLater(() -> finishMigration(finalSummary, finalFailure));
                }
            }
        }, "tools-json-to-sqlite-migration");
        worker.setDaemon(true);
        worker.start();
    }

    private void finishMigration(JsonToSqliteMigration.Summary summary, Throwable failure) {
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

        String result = message("gui.tools.migration.result.completed");
        setMigrationStatus(result);
        JOptionPane.showMessageDialog(this,
                message("gui.tools.dialog.migration.completed.message",
                        result, summary.migrated(), summary.skipped(), summary.totalCandidates()),
                message("gui.tools.dialog.migration.completed.title"), JOptionPane.INFORMATION_MESSAGE);
    }

    private JsonToSqliteMigration.Options buildMigrationOptions() {
        String dbPath = migrationDbPathField.getText().trim();
        if (dbPath.isBlank()) {
            throw new IllegalArgumentException(message("gui.tools.validation.database-path.required"));
        }
        String rootFolder = migrationRootFolderField.getText().trim();
        if (rootFolder.isBlank()) {
            throw new IllegalArgumentException(message("gui.tools.validation.root-folder.required"));
        }
        return new JsonToSqliteMigration.Options(dbPath, rootFolder);
    }

    private void openMigrationLogPage() {
        try {
            if (currentMigrationLogSession != null) {
                currentMigrationLogSession.openLatestInBrowser();
                return;
            }
            if (!Files.exists(MIGRATION_LOG_PATH)) {
                JOptionPane.showMessageDialog(this,
                        message("gui.tools.dialog.migration-log.missing"),
                        message("gui.dialog.info.title"), JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            Desktop.getDesktop().browse(MIGRATION_LOG_PATH.toUri());
        } catch (Exception e) {
            log.warn(logMessage("gui.tools.log.migration.open-log-page-failed", e.getMessage()), e);
            GuiErrorDialog.show(this,
                    message("gui.dialog.error.title"),
                    message("gui.tools.dialog.migration-log.open-failed", e.getMessage()));
        }
    }

    private void handleBackendState(BackendLifecycleManager.Snapshot snapshot) {
        backendStateLabel.setText(message("gui.tools.backend-status", switch (snapshot.state()) {
            case RUNNING -> message("gui.backend.state.running");
            case STARTING -> message("gui.tools.backend-status.starting");
            case STOPPING -> message("gui.tools.backend-status.stopping");
            case STOPPED -> message("gui.backend.state.stopped");
            case FAILED -> message("gui.backend.state.failed");
        }));

        if (snapshot.state() == BackendLifecycleManager.State.FAILED && !backfillRunning && !migrationRunning && exclusiveToolName == null) {
            setBackfillStatus(message("gui.tools.backfill.status.backend-failed"));
            setMigrationStatus(message("gui.tools.migration.status.backend-failed"));
        }

        refreshActionStates();
    }

    private boolean beginExclusiveTool(String toolName) {
        if (!ensureNoExclusiveTool(toolName)) {
            return false;
        }
        BackendLifecycleManager.State state = BackendLifecycleManager.state();
        if (state == BackendLifecycleManager.State.STARTING || state == BackendLifecycleManager.State.STOPPING) {
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
        BackendLifecycleManager.State state = BackendLifecycleManager.state();
        boolean backendTransitioning = state == BackendLifecycleManager.State.STARTING
                || state == BackendLifecycleManager.State.STOPPING;
        boolean exclusiveBusy = exclusiveToolName != null || backfillRunning || migrationRunning;

        imageClassifierButton.setEnabled(!exclusiveBusy && !backendTransitioning);
        folderCheckerButton.setEnabled(!exclusiveBusy && !backendTransitioning);
        backfillRunButton.setEnabled(!exclusiveBusy && !backendTransitioning);
        backfillLogButton.setEnabled(currentBackfillLogSession != null || Files.exists(BACKFILL_LOG_PATH));
        migrationRunButton.setEnabled(!exclusiveBusy && !backendTransitioning);
        migrationLogButton.setEnabled(currentMigrationLogSession != null || Files.exists(MIGRATION_LOG_PATH));

        boolean backfillEditable = !backfillRunning && !backendTransitioning;
        dbPathField.setEnabled(backfillEditable);
        proxyEnabledCheck.setEnabled(backfillEditable);
        dryRunCheck.setEnabled(backfillEditable);
        delaySpinner.setEnabled(backfillEditable);
        limitSpinner.setEnabled(backfillEditable);
        updateProxyFieldState();

        boolean migrationEditable = !migrationRunning && !backendTransitioning;
        migrationDbPathField.setEnabled(migrationEditable);
        migrationRootFolderField.setEnabled(migrationEditable);

        exclusiveToolLabel.setText(message("gui.tools.exclusive-tool",
                exclusiveToolName == null ? message("gui.value.none") : exclusiveToolName));
    }

    private void updateProxyFieldState() {
        boolean enabled = proxyEnabledCheck.isSelected() && proxyEnabledCheck.isEnabled();
        proxyHostField.setEnabled(enabled);
        proxyPortSpinner.setEnabled(enabled);
    }

    private void closeBackfillLogSession() {
        if (currentBackfillLogSession == null) {
            return;
        }
        try {
            currentBackfillLogSession.close();
        } catch (Exception ignored) {
        } finally {
            currentBackfillLogSession = null;
        }
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

    private static JLabel secondaryLabel(String text) {
        JLabel label = new JLabel(text);
        label.setForeground(Color.GRAY);
        return label;
    }

    private void configureBackfillNumberInputs() {
        leftAlignSpinnerText(proxyPortSpinner);
        leftAlignSpinnerText(delaySpinner);
        leftAlignSpinnerText(limitSpinner);
    }

    private static void leftAlignSpinnerText(JSpinner spinner) {
        if (spinner.getEditor() instanceof JSpinner.DefaultEditor editor) {
            editor.getTextField().setHorizontalAlignment(JTextField.LEFT);
            JFormattedTextField.AbstractFormatter formatter = editor.getTextField().getFormatter();
            if (formatter instanceof InternationalFormatter intlFmt) {
                Format fmt = intlFmt.getFormat();
                if (fmt instanceof NumberFormat nf) {
                    nf.setGroupingUsed(false);
                    editor.getTextField().setValue(spinner.getValue());
                }
            }
        }
    }

    private static String defaultIfBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    public void dispose() {
        BackendLifecycleManager.removeListener(backendListener);
        closeBackfillLogSession();
        closeMigrationLogSession();
    }

    private static String message(String code, Object... args) {
        return GuiMessages.get(code, args);
    }

    private static String logMessage(String code, Object... args) {
        return MessageBundles.get(code, args);
    }
}
