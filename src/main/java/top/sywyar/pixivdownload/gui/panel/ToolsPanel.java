package top.sywyar.pixivdownload.gui.panel;

import lombok.extern.slf4j.Slf4j;
import org.slf4j.LoggerFactory;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.gui.BackendLifecycleManager;
import top.sywyar.pixivdownload.gui.ToolHtmlLogSession;
import top.sywyar.pixivdownload.gui.config.ConfigFileEditor;
import top.sywyar.pixivdownload.imageclassifier.ImageClassifier;
import top.sywyar.pixivdownload.tools.ArtworksBackFill;
import top.sywyar.pixivdownload.tools.FolderChecker;

import javax.swing.*;
import javax.swing.border.TitledBorder;
import java.awt.*;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Tool shortcuts exposed from the main Swing GUI.
 */
@Slf4j
public class ToolsPanel extends JPanel {

    private static final Path BACKFILL_LOG_PATH = Path.of("log", "html", "artworks-backfill-latest.html");
    private static final String BACKFILL_COUNTING_STATUS = "后端服务器已关闭...正在查询回填条数，查询完毕后打开实时日志文件";

    private final Path configPath;

    private final JLabel backendStateLabel = new JLabel("后端状态：检测中");
    private final JLabel exclusiveToolLabel = new JLabel("独占工具：无");
    private final JLabel backfillStatusLabel = secondaryLabel("回填工具未运行。");

    private final JButton imageClassifierButton = new JButton("打开图片分类工具");
    private final JButton folderCheckerButton = new JButton("打开目录有效检查工具");
    private final JButton backfillRunButton = new JButton("开始数据回填");
    private final JButton backfillLogButton = new JButton("打开日志页面");

    private final JTextField dbPathField = new JTextField(34);
    private final JCheckBox proxyEnabledCheck = new JCheckBox("使用代理");
    private final JTextField proxyHostField = new JTextField(16);
    private final JSpinner proxyPortSpinner = new JSpinner(new SpinnerNumberModel(7890, 1, 65535, 1));
    private final JSpinner delaySpinner = new JSpinner(new SpinnerNumberModel(800, 0, 60_000, 100));
    private final JSpinner limitSpinner = new JSpinner(new SpinnerNumberModel(0, 0, Integer.MAX_VALUE, 100));
    private final JCheckBox dryRunCheck = new JCheckBox("仅试运行，不写数据库");

    private String exclusiveToolName;
    private boolean backfillRunning;
    private volatile ToolHtmlLogSession currentBackfillLogSession;

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
        content.add(Box.createVerticalGlue());

        JScrollPane scrollPane = new JScrollPane(content);
        scrollPane.setBorder(null);
        scrollPane.getVerticalScrollBar().setUnitIncrement(16);
        add(scrollPane, BorderLayout.CENTER);
    }

    private JComponent buildOverviewCard() {
        JPanel panel = createCard("工具说明");
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        backendStateLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        exclusiveToolLabel.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel hint = secondaryLabel("<html>目录有效检查工具和数据库数据回填工具会在运行前暂时停止 Spring Boot 后端，避免占用 SQLite。<br>目录检查窗口关闭后会自动恢复后端；数据回填完成后也会自动恢复。</html>");
        hint.setAlignmentX(Component.LEFT_ALIGNMENT);

        panel.add(backendStateLabel);
        panel.add(Box.createVerticalStrut(6));
        panel.add(exclusiveToolLabel);
        panel.add(Box.createVerticalStrut(10));
        panel.add(hint);
        return panel;
    }

    private JComponent buildImageClassifierCard() {
        JPanel panel = createCard("图片分类工具");
        panel.setLayout(new BorderLayout(0, 10));

        JLabel desc = secondaryLabel("<html>打开独立的图片分类窗口。这个工具不会主动停止后端，仍可继续使用现有的接口联动。</html>");
        panel.add(desc, BorderLayout.CENTER);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        actions.setOpaque(false);
        imageClassifierButton.addActionListener(e -> openImageClassifier());
        actions.add(imageClassifierButton);
        panel.add(actions, BorderLayout.SOUTH);
        return panel;
    }

    private JComponent buildFolderCheckerCard() {
        JPanel panel = createCard("数据库目录有效检查工具");
        panel.setLayout(new BorderLayout(0, 10));

        JLabel desc = secondaryLabel("<html>检查数据库中的目录字段是否仍然可访问。运行前会先停止后端，关闭该工具窗口后自动重新启动后端。</html>");
        panel.add(desc, BorderLayout.CENTER);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        actions.setOpaque(false);
        folderCheckerButton.addActionListener(e -> openFolderChecker());
        actions.add(folderCheckerButton);
        panel.add(actions, BorderLayout.SOUTH);
        return panel;
    }

    private JComponent buildBackfillCard() {
        JPanel panel = createCard("数据库数据回填工具");
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
        form.add(new JLabel("数据库路径"), g);
        g.gridx = 1;
        g.weightx = 1;
        form.add(dbPathField, g);
        g.gridx = 2;
        g.weightx = 0;
        JButton browseDbButton = new JButton("浏览...");
        browseDbButton.addActionListener(e -> browseDatabase());
        form.add(browseDbButton, g);

        row++;
        g.gridy = row;
        g.gridx = 0;
        form.add(new JLabel("代理"), g);
        JPanel proxyPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        proxyPanel.setOpaque(false);
        proxyEnabledCheck.addActionListener(e -> updateProxyFieldState());
        proxyPanel.add(proxyEnabledCheck);
        proxyPanel.add(new JLabel("Host"));
        proxyPanel.add(proxyHostField);
        proxyPanel.add(new JLabel("Port"));
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
        form.add(new JLabel("请求延迟(ms)"), g);
        g.gridx = 1;
        g.weightx = 0;
        form.add(delaySpinner, g);
        g.gridx = 2;
        form.add(new JLabel("0 表示不限条数"), g);

        row++;
        g.gridy = row;
        g.gridx = 0;
        form.add(new JLabel("处理条数"), g);
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
        dbPathField.setText(RuntimeFiles.resolveDatabasePath(rootFolder).toString());

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
                log.debug("Failed to load proxy defaults for tools panel: {}", e.getMessage());
            }
        }
        updateProxyFieldState();
    }

    private void openImageClassifier() {
        if (!ensureNoExclusiveTool("图片分类工具")) {
            return;
        }
        SwingUtilities.invokeLater(() -> {
            ImageClassifier classifier = new ImageClassifier(false);
            classifier.setVisible(true);
        });
    }

    private void openFolderChecker() {
        if (!beginExclusiveTool("数据库目录有效检查工具")) {
            return;
        }

        setBackfillStatus("正在停止后端，准备打开目录检查工具...");
        boolean accepted = BackendLifecycleManager.stopAsync(() -> {
            try {
                FolderChecker checker = new FolderChecker(JFrame.DISPOSE_ON_CLOSE, this::handleFolderCheckerClosed);
                checker.showWindow();
                setBackfillStatus("目录检查工具已打开，关闭窗口后会自动恢复后端。");
            } catch (Exception e) {
                exclusiveToolName = null;
                refreshActionStates();
                setBackfillStatus("目录检查工具打开失败。");
                log.error("Failed to open folder checker", e);
                BackendLifecycleManager.startAsync();
                JOptionPane.showMessageDialog(this,
                        "无法打开目录有效检查工具：" + e.getMessage(),
                        "错误", JOptionPane.ERROR_MESSAGE);
            }
        });
        if (!accepted) {
            exclusiveToolName = null;
            refreshActionStates();
            JOptionPane.showMessageDialog(this,
                    "后端当前正在启动或停止，请稍后重试。",
                    "请稍后", JOptionPane.INFORMATION_MESSAGE);
        }
    }

    private void handleFolderCheckerClosed() {
        exclusiveToolName = null;
        refreshActionStates();
        setBackfillStatus("目录检查工具已关闭，正在恢复后端...");
        if (!BackendLifecycleManager.startAsync(() ->
                setBackfillStatus("目录检查工具已完成，后端已恢复。"))) {
            setBackfillStatus("目录检查工具已关闭。");
        }
    }

    private void startBackfill() {
        if (!beginExclusiveTool("数据库数据回填工具")) {
            return;
        }

        ArtworksBackFill.Options options;
        try {
            options = buildBackfillOptions();
        } catch (Exception e) {
            exclusiveToolName = null;
            refreshActionStates();
            JOptionPane.showMessageDialog(this,
                    "回填参数无效：" + e.getMessage(),
                    "错误", JOptionPane.ERROR_MESSAGE);
            return;
        }

        backfillRunning = true;
        refreshActionStates();
        setBackfillStatus("正在停止后端，准备执行数据回填...");

        boolean accepted = BackendLifecycleManager.stopAsync(() -> prepareBackfillInBackground(options));
        if (!accepted) {
            backfillRunning = false;
            exclusiveToolName = null;
            closeBackfillLogSession();
            refreshActionStates();
            JOptionPane.showMessageDialog(this,
                    "后端当前正在启动或停止，请稍后重试。",
                    "请稍后", JOptionPane.INFORMATION_MESSAGE);
        }
    }

    private void prepareBackfillInBackground(ArtworksBackFill.Options options) {
        setBackfillStatus(BACKFILL_COUNTING_STATUS);

        Thread worker = new Thread(() -> {
            int totalCandidates;
            try {
                totalCandidates = ArtworksBackFill.countCandidates(options);
            } catch (Throwable error) {
                handleBackfillPreparationFailure("回填条数查询失败，后端已尝试恢复。", "无法查询回填条数：", error);
                return;
            }

            if (totalCandidates > 0) {
                setBackfillStatus("已查询到 " + totalCandidates + " 条待回填记录，正在打开实时日志并执行数据回填...");
            } else {
                setBackfillStatus("查询完成，未发现需要回填的记录，正在打开实时日志文件...");
            }

            try {
                currentBackfillLogSession = ToolHtmlLogSession.open("artworks-backfill", ArtworksBackFill.class);
                SwingUtilities.invokeLater(this::refreshActionStates);
                currentBackfillLogSession.openLatestInBrowser();
            } catch (Exception error) {
                handleBackfillPreparationFailure("回填日志打开失败，后端已尝试恢复。", "无法创建或打开回填日志页面：", error);
                return;
            }

            runBackfillInBackground(options);
        }, "tools-artworks-backfill-prepare");
        worker.setDaemon(true);
        worker.start();
    }

    private void handleBackfillPreparationFailure(String statusText, String dialogPrefix, Throwable failure) {
        closeBackfillLogSession();
        backfillRunning = false;
        exclusiveToolName = null;
        SwingUtilities.invokeLater(this::refreshActionStates);

        Runnable afterRestart = () -> SwingUtilities.invokeLater(() -> {
            setBackfillStatus(statusText);
            JOptionPane.showMessageDialog(this,
                    dialogPrefix + failure.getMessage(),
                    "错误", JOptionPane.ERROR_MESSAGE);
        });
        if (!BackendLifecycleManager.startAsync(afterRestart)) {
            afterRestart.run();
        }
    }

    private void runBackfillInBackground(ArtworksBackFill.Options options) {
        setBackfillStatus("后端服务器已关闭，正在执行数据回填...");

        Thread worker = new Thread(() -> {
            ArtworksBackFill.Summary summary = null;
            Throwable failure = null;
            var toolLogger = LoggerFactory.getLogger(ArtworksBackFill.class);

            try {
                toolLogger.info("Backfill requested from ToolsPanel.");
                summary = ArtworksBackFill.run(options);
            } catch (Throwable error) {
                failure = error;
                toolLogger.error("Backfill failed", error);
            } finally {
                toolLogger.info("Backfill finished, requesting backend restart.");
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
            setBackfillStatus("数据回填失败，后端已尝试恢复。");
            JOptionPane.showMessageDialog(this,
                    "数据库数据回填失败：" + failure.getMessage(),
                    "错误", JOptionPane.ERROR_MESSAGE);
            return;
        }

        if (summary == null) {
            setBackfillStatus("数据回填已结束。");
            return;
        }

        String result = summary.rateLimited()
                ? "数据回填因限流提前结束，后端已恢复。"
                : "数据回填完成，后端已恢复。";
        setBackfillStatus(result);
        JOptionPane.showMessageDialog(this,
                result + "\n已处理：" + summary.processed() + " / " + summary.totalCandidates(),
                "回填完成", JOptionPane.INFORMATION_MESSAGE);
    }

    private ArtworksBackFill.Options buildBackfillOptions() {
        String dbPath = dbPathField.getText().trim();
        if (dbPath.isBlank()) {
            throw new IllegalArgumentException("数据库路径不能为空");
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
        chooser.setDialogTitle("选择 SQLite 数据库");
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
                        "暂无回填日志页面，请先运行一次数据库数据回填工具。",
                        "提示", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            Desktop.getDesktop().browse(BACKFILL_LOG_PATH.toUri());
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this,
                    "无法打开回填日志页面：" + e.getMessage(),
                    "错误", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void handleBackendState(BackendLifecycleManager.Snapshot snapshot) {
        backendStateLabel.setText("后端状态：" + switch (snapshot.state()) {
            case RUNNING -> "运行中";
            case STARTING -> "启动中";
            case STOPPING -> "停止中";
            case STOPPED -> "已停止";
            case FAILED -> "启动失败";
        });

        if (snapshot.state() == BackendLifecycleManager.State.FAILED && !backfillRunning && exclusiveToolName == null) {
            setBackfillStatus("后端启动失败，请查看日志。");
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
                    "后端当前正在启动或停止，请稍后重试。",
                    "请稍后", JOptionPane.INFORMATION_MESSAGE);
            return false;
        }
        exclusiveToolName = toolName;
        refreshActionStates();
        return true;
    }

    private boolean ensureNoExclusiveTool(String requestedTool) {
        if (exclusiveToolName != null) {
            JOptionPane.showMessageDialog(this,
                    "当前已有工具在独占运行：" + exclusiveToolName + "\n请先完成后再打开 " + requestedTool + "。",
                    "工具忙", JOptionPane.INFORMATION_MESSAGE);
            return false;
        }
        return true;
    }

    private void refreshActionStates() {
        BackendLifecycleManager.State state = BackendLifecycleManager.state();
        boolean backendTransitioning = state == BackendLifecycleManager.State.STARTING
                || state == BackendLifecycleManager.State.STOPPING;
        boolean exclusiveBusy = exclusiveToolName != null || backfillRunning;

        imageClassifierButton.setEnabled(!exclusiveBusy && !backendTransitioning);
        folderCheckerButton.setEnabled(!exclusiveBusy && !backendTransitioning);
        backfillRunButton.setEnabled(!exclusiveBusy && !backendTransitioning);
        backfillLogButton.setEnabled(currentBackfillLogSession != null || Files.exists(BACKFILL_LOG_PATH));

        boolean editable = !backfillRunning && !backendTransitioning;
        dbPathField.setEnabled(editable);
        proxyEnabledCheck.setEnabled(editable);
        dryRunCheck.setEnabled(editable);
        delaySpinner.setEnabled(editable);
        limitSpinner.setEnabled(editable);
        updateProxyFieldState();

        exclusiveToolLabel.setText("独占工具：" + (exclusiveToolName == null ? "无" : exclusiveToolName));
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

    private void setBackfillStatus(String text) {
        SwingUtilities.invokeLater(() -> backfillStatusLabel.setText(text));
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
        }
    }

    private static String defaultIfBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    public void dispose() {
        BackendLifecycleManager.removeListener(backendListener);
        closeBackfillLogSession();
    }
}
