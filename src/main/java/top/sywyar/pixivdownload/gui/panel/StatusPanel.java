package top.sywyar.pixivdownload.gui.panel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import top.sywyar.pixivdownload.ffmpeg.FfmpegInstallation;
import top.sywyar.pixivdownload.ffmpeg.FfmpegInstaller;
import top.sywyar.pixivdownload.ffmpeg.FfmpegLocator;
import top.sywyar.pixivdownload.gui.BackendLifecycleManager;
import top.sywyar.pixivdownload.gui.config.ConfigFileEditor;
import top.sywyar.pixivdownload.gui.i18n.GuiMessages;
import top.sywyar.pixivdownload.i18n.AppLocale;
import top.sywyar.pixivdownload.i18n.MessageBundles;
import top.sywyar.pixivdownload.i18n.SystemLocaleDetector;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import javax.swing.*;
import javax.swing.border.TitledBorder;
import java.awt.*;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;

/**
 * “状态”页：展示服务状态，并提供桌面侧的快捷操作。
 */
@Slf4j
public class StatusPanel extends JPanel {

    private static final int POLL_INTERVAL_MS = 3000;
    private static final String BATCH_PAGE = "/pixiv-batch.html";
    private static final String MONITOR_PAGE = "/monitor.html";
    private static final String GALLERY_PAGE = "/pixiv-gallery.html";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final SSLContext TRUST_ALL_SSL = buildTrustAllSslContext();

    private static String message(String code, Object... args) {
        return GuiMessages.get(code, args);
    }

    private static String logMessage(String code, Object... args) {
        return MessageBundles.get(code, args);
    }

    private final JLabel portLabel = valueLabel("--");
    private final JLabel modeLabel = valueLabel("--");
    private final JLabel uptimeLabel = valueLabel("--");
    private final JLabel httpsLabel = valueLabel("--");
    private final JLabel statusBadge = new JLabel(message("gui.status.state.starting"));

    private final JLabel ffmpegBadge = new JLabel(message("gui.ffmpeg.badge.detecting"));
    private final JLabel ffmpegSourceLabel = secondaryLabel(message("gui.ffmpeg.hint.default"));
    private final JLabel ffmpegPathLabel = secondaryLabel(message("gui.ffmpeg.path.pending"));
    private final JButton ffmpegActionButton = new JButton(message("gui.ffmpeg.action.download"));
    private final JButton openFfmpegDirButton = new JButton(message("gui.ffmpeg.action.open-dir"));
    private final JProgressBar ffmpegProgress = new JProgressBar();

    private final JComboBox<LocaleOption> languageCombo = new JComboBox<>();

    private final int serverPort;
    private final String rootFolder;
    private final Path configPath;
    private final Runnable onLocaleChanged;

    private volatile String currentScheme = "http";
    private volatile String serverDomain = "localhost";
    private volatile String serverScheme = "http";
    private volatile boolean ffmpegInstalling;
    private Timer pollTimer;
    private final BackendLifecycleManager.Listener backendListener = this::applyBackendSnapshot;

    public StatusPanel(int serverPort, String rootFolder, Path configPath, Runnable onLocaleChanged) {
        this.serverPort = serverPort;
        this.rootFolder = rootFolder;
        this.configPath = configPath;
        this.onLocaleChanged = onLocaleChanged;
        buildUi();
        BackendLifecycleManager.addListener(backendListener);
        startPolling();
        refreshFfmpegState();
    }

    private void buildUi() {
        setLayout(new BorderLayout(0, 12));
        setBorder(BorderFactory.createEmptyBorder(16, 24, 16, 24));

        statusBadge.setFont(statusBadge.getFont().deriveFont(Font.BOLD, 12f));
        statusBadge.setForeground(new Color(180, 100, 0));
        JPanel badgeRow = new JPanel(new BorderLayout(12, 0));
        badgeRow.setOpaque(false);
        badgeRow.add(statusBadge, BorderLayout.WEST);
        badgeRow.add(buildLanguageSelector(), BorderLayout.EAST);

        JPanel content = new JPanel(new GridBagLayout());
        content.setOpaque(false);
        GridBagConstraints contentConstraints = new GridBagConstraints();
        contentConstraints.gridx = 0;
        contentConstraints.gridy = 0;
        contentConstraints.weightx = 1;
        contentConstraints.anchor = GridBagConstraints.WEST;
        contentConstraints.fill = GridBagConstraints.HORIZONTAL;

        content.add(badgeRow, contentConstraints);

        contentConstraints.gridy++;
        contentConstraints.insets = new Insets(10, 0, 0, 0);
        content.add(buildStatusGrid(), contentConstraints);

        contentConstraints.gridy++;
        contentConstraints.insets = new Insets(14, 0, 0, 0);
        content.add(buildFfmpegPanel(), contentConstraints);

        JPanel filler = new JPanel();
        filler.setOpaque(false);
        contentConstraints.gridy++;
        contentConstraints.insets = new Insets(0, 0, 0, 0);
        contentConstraints.weighty = 1;
        contentConstraints.fill = GridBagConstraints.BOTH;
        content.add(filler, contentConstraints);

        add(content, BorderLayout.CENTER);
        add(buildActionButtons(), BorderLayout.SOUTH);
    }

    private JComponent buildStatusGrid() {
        JPanel grid = new JPanel(new GridBagLayout());
        grid.setOpaque(false);
        GridBagConstraints g = new GridBagConstraints();
        g.insets = new Insets(6, 4, 6, 16);
        g.anchor = GridBagConstraints.WEST;

        int row = 0;
        addRow(grid, g, row++, message("gui.status.label.port"), portLabel);
        addRow(grid, g, row++, message("gui.status.label.mode"), modeLabel);
        addRow(grid, g, row++, message("gui.status.label.start-time"), uptimeLabel);
        addRow(grid, g, row++, message("gui.status.label.https"), httpsLabel);

        JLabel hint = secondaryLabel(message("gui.status.hint.web-console"));
        GridBagConstraints hc = new GridBagConstraints();
        hc.gridy = row++;
        hc.gridx = 0;
        hc.gridwidth = 2;
        hc.anchor = GridBagConstraints.WEST;
        hc.insets = new Insets(12, 4, 4, 4);
        grid.add(hint, hc);
        return grid;
    }

    private JComponent buildFfmpegPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 10));
        panel.setOpaque(false);
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);
        TitledBorder titledBorder = BorderFactory.createTitledBorder(message("gui.ffmpeg.panel.title"));
        titledBorder.setTitleJustification(TitledBorder.LEFT);
        titledBorder.setTitlePosition(TitledBorder.TOP);
        panel.setBorder(BorderFactory.createCompoundBorder(
                titledBorder,
                BorderFactory.createEmptyBorder(8, 12, 10, 12)
        ));

        JPanel text = new JPanel();
        text.setOpaque(false);
        text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
        text.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel intro = new JLabel(message("gui.ffmpeg.panel.intro"));
        intro.setAlignmentX(Component.LEFT_ALIGNMENT);

        ffmpegBadge.setFont(ffmpegBadge.getFont().deriveFont(Font.BOLD, 13f));
        ffmpegBadge.setAlignmentX(Component.LEFT_ALIGNMENT);
        ffmpegSourceLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        ffmpegPathLabel.setAlignmentX(Component.LEFT_ALIGNMENT);

        text.add(intro);
        text.add(Box.createVerticalStrut(10));
        text.add(ffmpegBadge);
        text.add(Box.createVerticalStrut(6));
        text.add(ffmpegSourceLabel);
        text.add(Box.createVerticalStrut(4));
        text.add(ffmpegPathLabel);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        actions.setOpaque(false);
        actions.setAlignmentX(Component.LEFT_ALIGNMENT);
        ffmpegActionButton.addActionListener(e -> triggerFfmpegInstall());
        openFfmpegDirButton.addActionListener(e -> openFfmpegDirectory());
        actions.add(ffmpegActionButton);
        actions.add(openFfmpegDirButton);

        ffmpegProgress.setVisible(false);
        ffmpegProgress.setStringPainted(true);
        ffmpegProgress.setAlignmentX(Component.LEFT_ALIGNMENT);

        panel.add(text, BorderLayout.NORTH);
        panel.add(actions, BorderLayout.CENTER);
        panel.add(ffmpegProgress, BorderLayout.SOUTH);
        return panel;
    }

    private JComponent buildActionButtons() {
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        buttons.setOpaque(false);

        JButton openBatch = webButton("gui.action.open-batch", BATCH_PAGE);
        JButton openMonitor = webButton("gui.action.open-monitor", MONITOR_PAGE);
        JButton openGallery = webButton("gui.action.open-gallery", GALLERY_PAGE);

        JButton openFolder = new JButton(message("gui.action.open-download-directory"));
        openFolder.addActionListener(e -> openDownloadFolder());

        JButton restart = new JButton(message("gui.action.restart-service"));
        restart.addActionListener(e -> restartService());

        buttons.add(openBatch);
        buttons.add(openMonitor);
        buttons.add(openGallery);
        buttons.add(openFolder);
        buttons.add(restart);
        return buttons;
    }

    private JButton webButton(String messageCode, String path) {
        JButton button = new JButton(message(messageCode));
        button.addActionListener(e -> openWebPage(path));
        return button;
    }

    private JComponent buildLanguageSelector() {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        row.setOpaque(false);

        JLabel label = new JLabel(message("gui.status.language.label"));
        label.setForeground(Color.GRAY);

        // null locale 表示"跟随系统"：写空值到 config.yaml，由 SystemLocaleDetector 兜底解析
        LocaleOption[] options = {
                new LocaleOption(null, message("gui.status.language.option.follow-system")),
                new LocaleOption(Locale.US, message("gui.status.language.option.en")),
                new LocaleOption(Locale.SIMPLIFIED_CHINESE, message("gui.status.language.option.zh-cn")),
        };
        for (LocaleOption option : options) {
            languageCombo.addItem(option);
        }
        selectInitialLanguageOption(options);
        languageCombo.setToolTipText(message("gui.status.language.tooltip"));
        languageCombo.addActionListener(e -> applyLanguageSelection());

        row.add(label);
        row.add(languageCombo);
        return row;
    }

    /**
     * 根据 config.yaml 中已持久化的 app.language 决定初始选中项：
     * 配置为空（或文件缺失）则选"跟随系统"；否则匹配对应 locale。
     */
    private void selectInitialLanguageOption(LocaleOption[] options) {
        String persisted = readPersistedLanguageTag();
        if (persisted == null || persisted.isBlank()) {
            languageCombo.setSelectedItem(options[0]);
            return;
        }
        Locale parsed = AppLocale.parse(persisted);
        if (parsed == null) {
            languageCombo.setSelectedItem(options[0]);
            return;
        }
        Locale normalized = AppLocale.normalize(parsed);
        for (LocaleOption option : options) {
            if (option.locale() != null && option.locale().equals(normalized)) {
                languageCombo.setSelectedItem(option);
                return;
            }
        }
        languageCombo.setSelectedItem(options[0]);
    }

    private String readPersistedLanguageTag() {
        if (configPath == null || !Files.exists(configPath)) {
            return null;
        }
        try {
            return new ConfigFileEditor(configPath).read("app.language");
        } catch (Exception e) {
            log.debug(logMessage("gui.status.log.language.persist-failed", e.getMessage()));
            return null;
        }
    }

    private void applyLanguageSelection() {
        LocaleOption option = (LocaleOption) languageCombo.getSelectedItem();
        if (option == null) {
            return;
        }

        // 持久化：跟随系统 = 写空字符串（与 AppLocale.parse(null) 行为一致）
        String persistValue = option.locale() == null ? "" : option.locale().toLanguageTag();
        boolean persisted = persistLanguagePreference(persistValue);

        // 推算本次会话应用的 locale。
        // 显式选择 → 直接使用；跟随系统 → 复用 SystemLocaleDetector 的解析链路。
        if (option.locale() != null) {
            Locale.setDefault(option.locale());
        } else {
            SystemLocaleDetector.detectAndApply();
        }
        GuiMessages.clearLocaleOverride();

        if (!persisted && configPath != null) {
            JOptionPane.showMessageDialog(this,
                    message("gui.status.language.persist-failed.message"),
                    message("gui.dialog.error.title"), JOptionPane.WARNING_MESSAGE);
        }

        if (onLocaleChanged != null) {
            // 回调将销毁本 Panel 并重建标签页，因此异步触发，避免在控件回调链中改变父容器
            SwingUtilities.invokeLater(onLocaleChanged);
        }
    }

    private boolean persistLanguagePreference(String value) {
        if (configPath == null || !Files.exists(configPath)) {
            return false;
        }
        try {
            new ConfigFileEditor(configPath).write("app.language", value == null ? "" : value);
            return true;
        } catch (Exception e) {
            log.warn(logMessage("gui.status.log.language.persist-failed", e.getMessage()));
            return false;
        }
    }

    private record LocaleOption(Locale locale, String label) {
        @Override
        public String toString() {
            return label;
        }
    }

    private static void addRow(JPanel grid, GridBagConstraints g, int row, String key, JLabel value) {
        g.gridy = row;
        g.gridx = 0;
        g.weightx = 0;
        g.fill = GridBagConstraints.NONE;
        JLabel keyLabel = new JLabel(key);
        keyLabel.setForeground(Color.GRAY);
        grid.add(keyLabel, g);

        g.gridx = 1;
        g.weightx = 1;
        g.fill = GridBagConstraints.HORIZONTAL;
        grid.add(value, g);
    }

    private static JLabel valueLabel(String text) {
        JLabel label = new JLabel(text);
        label.setFont(label.getFont().deriveFont(Font.BOLD));
        return label;
    }

    private static JLabel secondaryLabel(String text) {
        JLabel label = new JLabel(text);
        label.setForeground(Color.GRAY);
        return label;
    }

    private void startPolling() {
        pollTimer = new Timer(POLL_INTERVAL_MS, e -> fetchStatus());
        pollTimer.setInitialDelay(500);
        pollTimer.start();
    }

    private void fetchStatus() {
        Thread worker = new Thread(() -> {
            String[] schemes = "https".equals(currentScheme)
                    ? new String[]{"https", "http"}
                    : new String[]{"http", "https"};
            boolean success = false;

            for (String scheme : schemes) {
                try {
                    URL url = new URI(scheme + "://localhost:" + serverPort + "/api/gui/status").toURL();
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    if (conn instanceof HttpsURLConnection https && TRUST_ALL_SSL != null) {
                        https.setSSLSocketFactory(TRUST_ALL_SSL.getSocketFactory());
                        https.setHostnameVerifier((h, s) -> true);
                    }
                    conn.setConnectTimeout(2000);
                    conn.setReadTimeout(2000);
                    conn.setRequestMethod("GET");

                    if (conn.getResponseCode() == 200) {
                        success = true;
                        currentScheme = scheme;
                        try (InputStream is = conn.getInputStream()) {
                            JsonNode node = MAPPER.readTree(is);
                            SwingUtilities.invokeLater(() -> updateLabels(node));
                        }
                        return;
                    }
                } catch (Exception ignored) {
                }
            }
            if (!success) {
                SwingUtilities.invokeLater(() -> applyOfflineState(BackendLifecycleManager.snapshot()));
            }
        }, "gui-status-poll");
        worker.setDaemon(true);
        worker.start();
    }

    private void updateLabels(JsonNode node) {
        statusBadge.setText(message("gui.backend.state.running"));
        statusBadge.setForeground(new Color(0, 140, 0));

        portLabel.setText(textOf(node, "port"));
        modeLabel.setText(modeName(textOf(node, "mode")));
        uptimeLabel.setText(textOf(node, "startTime"));

        boolean https = node.path("httpsEnabled").asBoolean(false);
        httpsLabel.setText(https ? message("gui.status.https.enabled") : message("gui.status.https.disabled"));
        httpsLabel.setForeground(https ? new Color(0, 140, 0) : Color.GRAY);

        String domain = textOf(node, "domain");
        String scheme = textOf(node, "scheme");
        if (!"--".equals(domain)) {
            serverDomain = domain;
        }
        if (!"--".equals(scheme)) {
            serverScheme = scheme;
        }
    }

    private void applyBackendSnapshot(BackendLifecycleManager.Snapshot snapshot) {
        switch (snapshot.state()) {
            case STARTING -> {
                statusBadge.setText(message("gui.backend.state.starting"));
                statusBadge.setForeground(new Color(180, 100, 0));
            }
            case STOPPING -> {
                statusBadge.setText(message("gui.backend.state.stopping"));
                statusBadge.setForeground(new Color(180, 100, 0));
            }
            case STOPPED -> applyOfflineState(snapshot);
            case FAILED -> {
                statusBadge.setText(message("gui.backend.state.failed"));
                statusBadge.setForeground(new Color(180, 60, 60));
                clearStatusValues();
            }
            case RUNNING -> {
                statusBadge.setText(message("gui.status.state.connecting"));
                statusBadge.setForeground(new Color(180, 100, 0));
            }
        }
    }

    private void applyOfflineState(BackendLifecycleManager.Snapshot snapshot) {
        if (snapshot.state() == BackendLifecycleManager.State.RUNNING) {
            statusBadge.setText(message("gui.status.state.connection-failed"));
            statusBadge.setForeground(new Color(180, 60, 60));
        } else if (snapshot.state() == BackendLifecycleManager.State.STOPPED) {
            statusBadge.setText(message("gui.backend.state.stopped"));
            statusBadge.setForeground(Color.GRAY);
        } else if (snapshot.state() == BackendLifecycleManager.State.STOPPING) {
            statusBadge.setText(message("gui.backend.state.stopping"));
            statusBadge.setForeground(new Color(180, 100, 0));
        } else if (snapshot.state() == BackendLifecycleManager.State.STARTING) {
            statusBadge.setText(message("gui.backend.state.starting"));
            statusBadge.setForeground(new Color(180, 100, 0));
        } else {
            statusBadge.setText(message("gui.backend.state.failed"));
            statusBadge.setForeground(new Color(180, 60, 60));
        }
        clearStatusValues();
    }

    private void clearStatusValues() {
        portLabel.setText("--");
        modeLabel.setText("--");
        uptimeLabel.setText("--");
        httpsLabel.setText("--");
        httpsLabel.setForeground(Color.GRAY);
    }

    private static String textOf(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return (value == null || value.isNull()) ? "--" : value.asText();
    }

    private static String modeName(String mode) {
        return switch (mode) {
            case "solo" -> message("gui.mode.solo");
            case "multi" -> message("gui.mode.multi");
            default -> mode;
        };
    }

    private void refreshFfmpegState() {
        if (ffmpegInstalling) {
            return;
        }

        FfmpegInstallation installation = FfmpegLocator.locate().orElse(null);
        if (installation == null) {
            ffmpegBadge.setText(message("gui.ffmpeg.badge.missing"));
            ffmpegBadge.setForeground(new Color(180, 100, 0));
            ffmpegSourceLabel.setText(message("gui.ffmpeg.hint.missing"));
            ffmpegPathLabel.setText(message("gui.ffmpeg.path.managed", FfmpegLocator.managedToolsDir()));
            ffmpegPathLabel.setToolTipText(FfmpegLocator.managedToolsDir().toString());
            ffmpegActionButton.setText(FfmpegInstaller.supportsManagedDownload()
                    ? message("gui.ffmpeg.action.download")
                    : message("gui.ffmpeg.action.manual"));
            ffmpegActionButton.setEnabled(FfmpegInstaller.supportsManagedDownload());
            openFfmpegDirButton.setEnabled(true);
            return;
        }

        ffmpegBadge.setText(message("gui.ffmpeg.badge.ready"));
        ffmpegBadge.setForeground(new Color(0, 140, 0));
        String sourceLabel = message(installation.sourceMessageCode());
        String sourceMessage = installation.hasFfprobe()
                ? message("gui.ffmpeg.source.label", sourceLabel)
                : message("gui.ffmpeg.source.label.missing-ffprobe", sourceLabel);
        ffmpegSourceLabel.setText(sourceMessage);
        ffmpegPathLabel.setText(message("gui.ffmpeg.path.label", installation.ffmpegPath()));
        ffmpegPathLabel.setToolTipText(installation.ffmpegPath().toString());
        ffmpegActionButton.setText(switch (installation.source()) {
            case MANAGED -> message("gui.ffmpeg.action.redownload");
            case BUNDLED, SYSTEM -> message("gui.ffmpeg.action.download-to-managed");
        });
        ffmpegActionButton.setEnabled(FfmpegInstaller.supportsManagedDownload());
        openFfmpegDirButton.setEnabled(true);
    }

    private void triggerFfmpegInstall() {
        if (!FfmpegInstaller.supportsManagedDownload()) {
            JOptionPane.showMessageDialog(this,
                    message("gui.ffmpeg.dialog.unsupported.message"),
                    message("gui.dialog.info.title"), JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        if (ffmpegInstalling) {
            return;
        }

        int confirm = JOptionPane.showConfirmDialog(this,
                message("gui.ffmpeg.dialog.install.confirm.message"),
                message("gui.ffmpeg.dialog.install.title"), JOptionPane.YES_NO_OPTION);
        if (confirm != JOptionPane.YES_OPTION) {
            return;
        }

        ffmpegInstalling = true;
        ffmpegActionButton.setEnabled(false);
        openFfmpegDirButton.setEnabled(false);
        ffmpegBadge.setText(message("gui.ffmpeg.badge.installing"));
        ffmpegBadge.setForeground(new Color(180, 100, 0));
        ffmpegProgress.setValue(0);
        ffmpegProgress.setIndeterminate(true);
        ffmpegProgress.setString(message("gui.ffmpeg.progress.preparing"));
        ffmpegProgress.setVisible(true);

        SwingWorker<FfmpegInstallation, FfmpegProgress> worker = new SwingWorker<>() {
            @Override
            protected FfmpegInstallation doInBackground() throws Exception {
                return FfmpegInstaller.installManaged(loadProxySettings(),
                        (stage, current, total) -> publish(new FfmpegProgress(stage, current, total)));
            }

            @Override
            protected void process(List<FfmpegProgress> chunks) {
                if (!chunks.isEmpty()) {
                    applyFfmpegProgress(chunks.get(chunks.size() - 1));
                }
            }

            @Override
            protected void done() {
                ffmpegInstalling = false;
                ffmpegProgress.setVisible(false);
                try {
                    FfmpegInstallation installation = get();
                    refreshFfmpegState();
                    JOptionPane.showMessageDialog(StatusPanel.this,
                            message("gui.ffmpeg.dialog.install-success.message",
                                    message(installation.sourceMessageCode()), installation.ffmpegPath()),
                            message("gui.ffmpeg.dialog.install-success.title"), JOptionPane.INFORMATION_MESSAGE);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    handleFfmpegInstallFailure(e);
                } catch (ExecutionException e) {
                    handleFfmpegInstallFailure(e.getCause() == null ? e : e.getCause());
                }
            }
        };
        worker.execute();
    }

    private void applyFfmpegProgress(FfmpegProgress progress) {
        ffmpegProgress.setString(progress.stage());
        if (progress.total() > 0) {
            ffmpegProgress.setIndeterminate(false);
            ffmpegProgress.setMaximum(100);
            ffmpegProgress.setValue((int) Math.min(100L, progress.current() * 100L / progress.total()));
        } else {
            ffmpegProgress.setIndeterminate(true);
        }
    }

    private void handleFfmpegInstallFailure(Throwable error) {
        refreshFfmpegState();
        String detail = error == null ? message("gui.ffmpeg.error.unknown") : error.getMessage();
        if (detail == null || detail.isBlank()) {
            detail = error == null ? message("gui.ffmpeg.error.unknown") : error.getClass().getSimpleName();
        }
        JOptionPane.showMessageDialog(this,
                message("gui.ffmpeg.dialog.install-failed.message", detail),
                message("gui.ffmpeg.dialog.install-failed.title"), JOptionPane.ERROR_MESSAGE);
    }

    private FfmpegInstaller.ProxySettings loadProxySettings() {
        if (configPath == null || !Files.exists(configPath)) {
            return FfmpegInstaller.ProxySettings.disabled();
        }
        try {
            ConfigFileEditor editor = new ConfigFileEditor(configPath);
            boolean enabled = Boolean.parseBoolean(defaultIfBlank(editor.read("proxy.enabled"), "false"));
            if (!enabled) {
                return FfmpegInstaller.ProxySettings.disabled();
            }

            String host = defaultIfBlank(editor.read("proxy.host"), "");
            String portValue = defaultIfBlank(editor.read("proxy.port"), "0");
            int port = Integer.parseInt(portValue.trim());
            if (host.isBlank() || port <= 0) {
                return FfmpegInstaller.ProxySettings.disabled();
            }
            return new FfmpegInstaller.ProxySettings(true, host, port);
        } catch (Exception e) {
            log.warn(logMessage("gui.status.log.ffmpeg-proxy.read-failed", e.getMessage()));
            return FfmpegInstaller.ProxySettings.disabled();
        }
    }

    private static String defaultIfBlank(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value;
    }

    private void openWebPage(String path) {
        try {
            Desktop.getDesktop().browse(new URI(getWebUrl(path)));
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, message("gui.error.open-browser", e.getMessage()),
                    message("gui.dialog.error.title"), JOptionPane.ERROR_MESSAGE);
        }
    }

    private void openDownloadFolder() {
        File folder = new File(rootFolder);
        if (!folder.exists()) {
            JOptionPane.showMessageDialog(this, message("gui.status.dialog.download-folder-missing", folder.getAbsolutePath()),
                    message("gui.dialog.info.title"), JOptionPane.WARNING_MESSAGE);
            return;
        }
        try {
            Desktop.getDesktop().open(folder);
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, message("gui.error.open-folder", e.getMessage()),
                    message("gui.dialog.error.title"), JOptionPane.ERROR_MESSAGE);
        }
    }

    private void openFfmpegDirectory() {
        try {
            Path dir = FfmpegLocator.locate()
                    .map(installation -> {
                        Path homeDir = installation.homeDir();
                        if (homeDir != null && Files.isDirectory(homeDir)) {
                            return homeDir;
                        }
                        Path ffmpegPath = installation.ffmpegPath();
                        return ffmpegPath == null ? null : ffmpegPath.getParent();
                    })
                    .filter(path -> path != null)
                    .orElseGet(FfmpegLocator::managedToolsDir);

            Files.createDirectories(dir);
            Desktop.getDesktop().open(dir.toFile());
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, message("gui.ffmpeg.dialog.open-dir-failed.message", e.getMessage()),
                    message("gui.dialog.error.title"), JOptionPane.ERROR_MESSAGE);
        }
    }

    private void restartService() {
        int confirm = JOptionPane.showConfirmDialog(this,
                message("gui.status.dialog.restart.confirm.message"),
                message("gui.action.restart-service"), JOptionPane.YES_NO_OPTION);
        if (confirm != JOptionPane.YES_OPTION) {
            return;
        }

        Thread worker = new Thread(() -> {
            try {
                if (!BackendLifecycleManager.restartAsync()) {
                    SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(StatusPanel.this,
                            message("gui.message.backend-busy"),
                            message("gui.dialog.please-wait.title"), JOptionPane.INFORMATION_MESSAGE));
                    return;
                }
            } catch (Exception e) {
                log.warn(logMessage("gui.status.log.restart-request.failed", e.getMessage()));
            }
            SwingUtilities.invokeLater(() -> {
                statusBadge.setText(message("gui.status.state.restarting"));
                statusBadge.setForeground(new Color(180, 100, 0));
            });
        }, "gui-restart");
        worker.setDaemon(true);
        worker.start();
    }

    public String getWebUrl(String path) {
        String normalizedPath = path == null || path.isBlank() ? "/" : path;
        if (!normalizedPath.startsWith("/")) {
            normalizedPath = "/" + normalizedPath;
        }
        return serverScheme + "://" + serverDomain + ":" + serverPort + normalizedPath;
    }

    public String getBatchUrl() {
        return getWebUrl(BATCH_PAGE);
    }

    public String getMonitorUrl() {
        return getWebUrl(MONITOR_PAGE);
    }

    public String getGalleryUrl() {
        return getWebUrl(GALLERY_PAGE);
    }

    public void dispose() {
        if (pollTimer != null) {
            pollTimer.stop();
        }
        BackendLifecycleManager.removeListener(backendListener);
    }

    private static SSLContext buildTrustAllSslContext() {
        try {
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[]{
                    new X509TrustManager() {
                        public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                        public void checkClientTrusted(X509Certificate[] chain, String authType) {}
                        public void checkServerTrusted(X509Certificate[] chain, String authType) {}
                    }
            }, null);
            return ctx;
        } catch (Exception e) {
            log.warn(logMessage("gui.status.log.trust-all-ssl.failed", e.getMessage()));
            return null;
        }
    }

    private record FfmpegProgress(String stage, long current, long total) {}
}
