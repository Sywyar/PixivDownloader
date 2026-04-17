package top.sywyar.pixivdownload.gui.panel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import top.sywyar.pixivdownload.ffmpeg.FfmpegInstallation;
import top.sywyar.pixivdownload.ffmpeg.FfmpegInstaller;
import top.sywyar.pixivdownload.ffmpeg.FfmpegLocator;
import top.sywyar.pixivdownload.gui.config.ConfigFileEditor;

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
import java.util.concurrent.ExecutionException;

/**
 * “状态”页：展示服务状态，并提供桌面侧的快捷操作。
 */
@Slf4j
public class StatusPanel extends JPanel {

    private static final int POLL_INTERVAL_MS = 3000;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final SSLContext TRUST_ALL_SSL = buildTrustAllSslContext();

    private final JLabel portLabel = valueLabel("--");
    private final JLabel modeLabel = valueLabel("--");
    private final JLabel uptimeLabel = valueLabel("--");
    private final JLabel httpsLabel = valueLabel("--");
    private final JLabel statusBadge = new JLabel("正在启动...");

    private final JLabel ffmpegBadge = new JLabel("正在检测 FFmpeg...");
    private final JLabel ffmpegSourceLabel = secondaryLabel("动图转 WebP 需要 FFmpeg，普通图片下载不受影响。");
    private final JLabel ffmpegPathLabel = secondaryLabel("用户目录安装位置将显示在这里。");
    private final JButton ffmpegActionButton = new JButton("下载 FFmpeg");
    private final JButton openFfmpegDirButton = new JButton("打开 FFmpeg 目录");
    private final JProgressBar ffmpegProgress = new JProgressBar();

    private final int serverPort;
    private final String rootFolder;
    private final Path configPath;

    private volatile String currentScheme = "http";
    private volatile String serverDomain = "localhost";
    private volatile String serverScheme = "http";
    private volatile boolean ffmpegInstalling;
    private Timer pollTimer;

    public StatusPanel(int serverPort, String rootFolder, Path configPath) {
        this.serverPort = serverPort;
        this.rootFolder = rootFolder;
        this.configPath = configPath;
        buildUi();
        startPolling();
        refreshFfmpegState();
    }

    private void buildUi() {
        setLayout(new BorderLayout(0, 12));
        setBorder(BorderFactory.createEmptyBorder(16, 24, 16, 24));

        statusBadge.setFont(statusBadge.getFont().deriveFont(Font.BOLD, 12f));
        statusBadge.setForeground(new Color(180, 100, 0));
        JPanel badgeRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        badgeRow.setOpaque(false);
        badgeRow.add(statusBadge);

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
        addRow(grid, g, row++, "运行端口", portLabel);
        addRow(grid, g, row++, "运行模式", modeLabel);
        addRow(grid, g, row++, "启动时间", uptimeLabel);
        addRow(grid, g, row++, "HTTPS", httpsLabel);

        JLabel hint = secondaryLabel("下载队列、历史记录等详细信息请打开 Web 控制台查看。");
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
        TitledBorder titledBorder = BorderFactory.createTitledBorder("FFmpeg / 动图适配");
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

        JLabel intro = new JLabel("<html><b>Ugoira 动图转 WebP 依赖 FFmpeg。</b><br/>"
                + "在线便携版默认不内置 FFmpeg，MSI 也可以在安装时跳过此组件；需要时可在这里补齐。</html>");
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

        JButton openMonitor = new JButton("打开 Web 控制台");
        openMonitor.addActionListener(e -> openMonitor());

        JButton openFolder = new JButton("打开下载目录");
        openFolder.addActionListener(e -> openDownloadFolder());

        JButton restart = new JButton("重启服务");
        restart.addActionListener(e -> restartService());

        buttons.add(openMonitor);
        buttons.add(openFolder);
        buttons.add(restart);
        return buttons;
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
        }, "gui-status-poll");
        worker.setDaemon(true);
        worker.start();
    }

    private void updateLabels(JsonNode node) {
        statusBadge.setText("运行中");
        statusBadge.setForeground(new Color(0, 140, 0));

        portLabel.setText(textOf(node, "port"));
        modeLabel.setText(modeName(textOf(node, "mode")));
        uptimeLabel.setText(textOf(node, "startTime"));

        boolean https = node.path("httpsEnabled").asBoolean(false);
        httpsLabel.setText(https ? "启用" : "未启用");
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

    private static String textOf(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return (value == null || value.isNull()) ? "--" : value.asText();
    }

    private static String modeName(String mode) {
        return switch (mode) {
            case "solo" -> "solo（自用模式）";
            case "multi" -> "multi（多人模式）";
            default -> mode;
        };
    }

    private void refreshFfmpegState() {
        if (ffmpegInstalling) {
            return;
        }

        FfmpegInstallation installation = FfmpegLocator.locate().orElse(null);
        if (installation == null) {
            ffmpegBadge.setText("未检测到 FFmpeg");
            ffmpegBadge.setForeground(new Color(180, 100, 0));
            ffmpegSourceLabel.setText("普通图片下载不受影响；需要处理 Ugoira 动图时，再点击右侧按钮即可。");
            ffmpegPathLabel.setText("用户目录安装位置：" + FfmpegLocator.managedToolsDir());
            ffmpegPathLabel.setToolTipText(FfmpegLocator.managedToolsDir().toString());
            ffmpegActionButton.setText(FfmpegInstaller.supportsManagedDownload() ? "下载 FFmpeg" : "请手动安装 FFmpeg");
            ffmpegActionButton.setEnabled(FfmpegInstaller.supportsManagedDownload());
            openFfmpegDirButton.setEnabled(true);
            return;
        }

        ffmpegBadge.setText("FFmpeg 已就绪");
        ffmpegBadge.setForeground(new Color(0, 140, 0));
        String sourceMessage = "来源：" + installation.sourceLabel();
        if (!installation.hasFfprobe()) {
            sourceMessage += "（未检测到 ffprobe）";
        }
        ffmpegSourceLabel.setText(sourceMessage);
        ffmpegPathLabel.setText("位置：" + installation.ffmpegPath());
        ffmpegPathLabel.setToolTipText(installation.ffmpegPath().toString());
        ffmpegActionButton.setText(switch (installation.source()) {
            case MANAGED -> "重新下载 FFmpeg";
            case BUNDLED, SYSTEM -> "下载到用户目录";
        });
        ffmpegActionButton.setEnabled(FfmpegInstaller.supportsManagedDownload());
        openFfmpegDirButton.setEnabled(true);
    }

    private void triggerFfmpegInstall() {
        if (!FfmpegInstaller.supportsManagedDownload()) {
            JOptionPane.showMessageDialog(this,
                    "当前系统暂不支持自动下载 FFmpeg，请手动安装到 PATH 后再使用动图转 WebP。",
                    "提示", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        if (ffmpegInstalling) {
            return;
        }

        int confirm = JOptionPane.showConfirmDialog(this,
                "将下载 FFmpeg 到用户目录，用于 Ugoira 动图转 WebP。普通图片下载不会受到影响。\n\n是否继续？",
                "下载 FFmpeg", JOptionPane.YES_NO_OPTION);
        if (confirm != JOptionPane.YES_OPTION) {
            return;
        }

        ffmpegInstalling = true;
        ffmpegActionButton.setEnabled(false);
        openFfmpegDirButton.setEnabled(false);
        ffmpegBadge.setText("正在安装 FFmpeg...");
        ffmpegBadge.setForeground(new Color(180, 100, 0));
        ffmpegProgress.setValue(0);
        ffmpegProgress.setIndeterminate(true);
        ffmpegProgress.setString("准备中...");
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
                            "FFmpeg 已安装完成。\n\n当前来源：" + installation.sourceLabel() + "\n位置：" + installation.ffmpegPath(),
                            "下载完成", JOptionPane.INFORMATION_MESSAGE);
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
        String message = error == null ? "未知错误" : error.getMessage();
        if (message == null || message.isBlank()) {
            message = error == null ? "未知错误" : error.getClass().getSimpleName();
        }
        JOptionPane.showMessageDialog(this,
                "FFmpeg 下载失败：\n" + message + "\n\n可检查 config.yaml 中的代理配置后重试。",
                "下载失败", JOptionPane.ERROR_MESSAGE);
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
            log.warn("读取 FFmpeg 下载代理配置失败: {}", e.getMessage());
            return FfmpegInstaller.ProxySettings.disabled();
        }
    }

    private static String defaultIfBlank(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value;
    }

    private void openMonitor() {
        try {
            Desktop.getDesktop().browse(new URI(serverScheme + "://" + serverDomain + ":" + serverPort + "/monitor.html"));
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "无法打开浏览器：" + e.getMessage(),
                    "错误", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void openDownloadFolder() {
        File folder = new File(rootFolder);
        if (!folder.exists()) {
            JOptionPane.showMessageDialog(this, "下载目录不存在：" + folder.getAbsolutePath(),
                    "提示", JOptionPane.WARNING_MESSAGE);
            return;
        }
        try {
            Desktop.getDesktop().open(folder);
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "无法打开目录：" + e.getMessage(),
                    "错误", JOptionPane.ERROR_MESSAGE);
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
            JOptionPane.showMessageDialog(this, "无法打开 FFmpeg 目录：" + e.getMessage(),
                    "错误", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void restartService() {
        int confirm = JOptionPane.showConfirmDialog(this,
                "确认重启服务？重启期间将短暂无法访问。",
                "重启服务", JOptionPane.YES_NO_OPTION);
        if (confirm != JOptionPane.YES_OPTION) {
            return;
        }

        Thread worker = new Thread(() -> {
            try {
                URL url = new URI(currentScheme + "://localhost:" + serverPort + "/api/gui/restart").toURL();
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                if (conn instanceof HttpsURLConnection https && TRUST_ALL_SSL != null) {
                    https.setSSLSocketFactory(TRUST_ALL_SSL.getSocketFactory());
                    https.setHostnameVerifier((h, s) -> true);
                }
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(3000);
                conn.setReadTimeout(3000);
                conn.getResponseCode();
            } catch (Exception e) {
                log.warn("重启请求失败: {}", e.getMessage());
            }
            SwingUtilities.invokeLater(() -> {
                statusBadge.setText("正在重启...");
                statusBadge.setForeground(new Color(180, 100, 0));
            });
        }, "gui-restart");
        worker.setDaemon(true);
        worker.start();
    }

    public String getMonitorUrl() {
        return serverScheme + "://" + serverDomain + ":" + serverPort + "/monitor.html";
    }

    public void dispose() {
        if (pollTimer != null) {
            pollTimer.stop();
        }
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
            log.warn("无法创建 trust-all SSLContext，HTTPS 轮询可能失败: {}", e.getMessage());
            return null;
        }
    }

    private record FfmpegProgress(String stage, long current, long total) {}
}
