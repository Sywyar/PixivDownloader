package top.sywyar.pixivdownload.gui.panel;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.gui.AutoStartManager;
import top.sywyar.pixivdownload.gui.GuiErrorDialog;
import top.sywyar.pixivdownload.gui.GuiTokenHolder;
import top.sywyar.pixivdownload.gui.config.*;
import top.sywyar.pixivdownload.gui.i18n.GuiMessages;
import top.sywyar.pixivdownload.i18n.MessageBundles;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.awt.*;
import java.awt.Desktop;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.List;
import java.util.concurrent.ExecutionException;

/**
 * "配置" 标签页：Schema 驱动的字段渲染，按 group 分为子标签页。
 * 保存时调用 ConfigFileEditor 行内替换，保留注释和格式。
 */
@Slf4j
public class ConfigPanel extends JPanel {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String DEFAULT_ROOT_FOLDER = RuntimeFiles.DEFAULT_DOWNLOAD_ROOT;
    private static final String SOLO_MODE = "solo";
    private static final SSLContext TRUST_ALL_SSL = buildTrustAllSslContext();

    private final Path configPath;
    private final int serverPort;
    private final ConfigFileEditor editor;
    private final String currentMode;

    /** 字段元数据快照（按当前 locale），构造时从 ConfigFieldRegistry 拉取一次。 */
    private final List<ConfigFieldSpec> allFields;
    private final List<String> groups;
    private final String serverGroup;
    private final String multiModeGroup;

    /** key → 渲染后的字段（含取值/赋值方法） */
    private final Map<String, FieldRenderer.RenderedField> renderedFields = new LinkedHashMap<>();

    /** 提示条（保存后显示） */
    private final JLabel noticeBar = new JLabel(" ");
    private JCheckBox autoStartCheckBox;
    private boolean autoStartSupported;
    private boolean updatingAutoStartCheckBox;

    public ConfigPanel(Path configPath, int serverPort) {
        this.configPath = configPath;
        this.serverPort = serverPort;
        this.editor = new ConfigFileEditor(configPath);
        this.currentMode = resolveCurrentMode();
        this.allFields = ConfigFieldRegistry.allFields();
        this.groups = ConfigFieldRegistry.groups();
        this.serverGroup = groups.isEmpty() ? "" : groups.get(0);
        this.multiModeGroup = ConfigFieldRegistry.groupMultiMode();
        buildUi();
        loadCurrentValues();
        checkFieldDrift();
    }

    // ── UI 构建 ──────────────────────────────────────────────────────────────────

    private void buildUi() {
        setLayout(new BorderLayout(0, 0));

        // 子标签页（按 group）
        JTabbedPane tabs = new JTabbedPane(JTabbedPane.TOP);
        for (String group : groups) {
            if (shouldHideGroup(group)) {
                continue;
            }
            tabs.addTab(group, buildGroupPanel(group));
        }
        add(tabs, BorderLayout.CENTER);

        // 底部面板：提示条 + 按钮
        add(buildBottomPanel(), BorderLayout.SOUTH);
    }

    private JScrollPane buildGroupPanel(String group) {
        JPanel content = new GroupContentPanel();
        content.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        List<ConfigFieldSpec> fields = allFields.stream()
                .filter(f -> group.equals(f.group()))
                .toList();

        for (ConfigFieldSpec spec : fields) {
            FieldRenderer.RenderedField rf = FieldRenderer.render(spec);
            renderedFields.put(spec.key(), rf);
            attachChangeListener(rf.control());
            rf.panel().setAlignmentX(Component.LEFT_ALIGNMENT);
            content.add(rf.panel());
            content.add(Box.createVerticalStrut(2));
        }
        if (serverGroup.equals(group)) {
            JPanel autoStartPanel = buildAutoStartPanel();
            autoStartPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
            content.add(autoStartPanel);
            content.add(Box.createVerticalStrut(2));
        }
        content.add(Box.createVerticalGlue());

        JScrollPane sp = new JScrollPane(content);
        sp.setBorder(null);
        sp.getVerticalScrollBar().setUnitIncrement(16);
        return sp;
    }

    private JPanel buildAutoStartPanel() {
        autoStartSupported = AutoStartManager.isSupported();
        autoStartCheckBox = new JCheckBox();
        autoStartCheckBox.setSelected(AutoStartManager.isEnabled());
        autoStartCheckBox.setEnabled(autoStartSupported);
        String helpText = message(autoStartSupported
                ? "gui.config.field.autostart.help"
                : "gui.config.field.autostart.unsupported.help");
        autoStartCheckBox.setToolTipText(helpText);
        autoStartCheckBox.addActionListener(e -> handleAutoStartToggle());

        JPanel panel = FieldRenderer.fieldPanel(
                message("gui.config.field.autostart.label") + message("gui.punctuation.colon"),
                autoStartCheckBox,
                null,
                helpText);

        if (!autoStartSupported) {
            setControlEnabled(panel, false);
        }
        return panel;
    }

    private boolean shouldHideGroup(String group) {
        return SOLO_MODE.equalsIgnoreCase(currentMode) && multiModeGroup.equals(group);
    }

    private String resolveCurrentMode() {
        String rootFolder = DEFAULT_ROOT_FOLDER;
        try {
            String configuredRoot = editor.read("download.root-folder");
            if (configuredRoot != null && !configuredRoot.isBlank()) {
                rootFolder = configuredRoot.trim();
            }
        } catch (IOException e) {
            log.debug(logMessage("gui.config.log.download-root.read-failed", e.getMessage()));
        }

        Path setupConfigPath = RuntimeFiles.resolveSetupConfigPath(rootFolder);
        if (!Files.isRegularFile(setupConfigPath)) {
            return null;
        }

        try {
            return MAPPER.readTree(setupConfigPath.toFile()).path("mode").asText(null);
        } catch (IOException e) {
            log.warn(logMessage("gui.config.log.mode.read-failed", e.getMessage()));
            return null;
        }
    }

    private JPanel buildBottomPanel() {
        // 提示条
        noticeBar.setOpaque(true);
        noticeBar.setBackground(new Color(255, 243, 205));
        noticeBar.setForeground(new Color(133, 100, 4));
        noticeBar.setBorder(BorderFactory.createEmptyBorder(4, 12, 4, 12));
        noticeBar.setVisible(false);

        // 按钮行
        JButton save = new JButton(message("gui.button.save"));
        save.addActionListener(e -> saveConfig());

        JButton reset = new JButton(message("gui.button.reset-defaults"));
        reset.addActionListener(e -> resetToDefaults());

        JButton openFile = new JButton(message("gui.button.open-config"));
        openFile.addActionListener(e -> openConfigFile());

        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 4));
        btnRow.add(openFile);
        btnRow.add(reset);
        btnRow.add(save);

        JPanel bottom = new JPanel(new BorderLayout());
        bottom.add(noticeBar, BorderLayout.NORTH);
        bottom.add(btnRow, BorderLayout.CENTER);
        return bottom;
    }

    // ── 数据加载 ──────────────────────────────────────────────────────────────────

    /**
     * 从 config.yaml 加载当前值并填充控件；key 不存在或被注释时回退到字段默认值，
     * 并将所有缺失的 key 连同默认值自动补全到 config.yaml（与 AppConfigGenerator 效果一致）。
     */
    private void loadCurrentValues() {
        if (!configPath.toFile().exists()) return;
        try {
            Map<String, String> values = editor.readAll(renderedFields.keySet());
            Map<String, String> missing = new LinkedHashMap<>();

            for (ConfigFieldSpec spec : allFields) {
                FieldRenderer.RenderedField rf = renderedFields.get(spec.key());
                if (rf == null) continue;
                if (values.containsKey(spec.key())) {
                    rf.setValue().accept(values.get(spec.key()));
                } else {
                    // key 不存在或被注释掉：用默认值填充控件，并记录待补全
                    rf.setValue().accept(spec.defaultValue());
                    missing.put(spec.key(), spec.defaultValue());
                }
            }

            // 自动将缺失的 key 补全到 config.yaml
            if (!missing.isEmpty()) {
                try {
                    editor.writeAll(missing);
                    log.info(logMessage("gui.config.log.missing-keys.completed",
                            missing.size(), String.join(", ", missing.keySet())));
                } catch (IOException ex) {
                    log.warn(logMessage("gui.config.log.missing-keys.failed", ex.getMessage()));
                }
            }

            updateEnabledStates();
        } catch (IOException e) {
            log.warn(logMessage("gui.config.log.read-failed", e.getMessage()));
            GuiErrorDialog.show(this,
                    message("gui.dialog.error.title"),
                    message("gui.config.dialog.read-failed.message", e.getMessage()));
        }
    }

    /**
     * 根据 visibleWhen / enabledWhen 谓词显示/隐藏、启用/禁用控件。
     * 在每次值变更时触发，以反映字段间依赖。
     */
    private void updateEnabledStates() {
        ConfigSnapshot snap = buildSnapshot();
        boolean layoutChanged = false;
        for (ConfigFieldSpec spec : allFields) {
            FieldRenderer.RenderedField rf = renderedFields.get(spec.key());
            if (rf == null) continue;
            boolean visible = spec.visibleWhen().test(snap);
            if (rf.panel().isVisible() != visible) {
                rf.panel().setVisible(visible);
                layoutChanged = true;
            }
            if (visible) {
                setControlEnabled(rf.panel(), spec.enabledWhen().test(snap));
            }
        }
        if (layoutChanged) {
            revalidate();
            repaint();
        }
    }

    private void setControlEnabled(Container c, boolean enabled) {
        for (Component child : c.getComponents()) {
            child.setEnabled(enabled);
            if (child instanceof Container container) {
                setControlEnabled(container, enabled);
            }
        }
    }

    private ConfigSnapshot buildSnapshot() {
        Map<String, String> snap = new HashMap<>();
        for (Map.Entry<String, FieldRenderer.RenderedField> e : renderedFields.entrySet()) {
            snap.put(e.getKey(), e.getValue().getValue().get());
        }
        return new ConfigSnapshot(snap);
    }

    // ── 保存 ─────────────────────────────────────────────────────────────────────

    private void saveConfig() {
        // 验证（仅验证可见且已启用的字段）
        List<String> errors = new ArrayList<>();
        for (ConfigFieldSpec spec : allFields) {
            FieldRenderer.RenderedField rf = renderedFields.get(spec.key());
            if (rf == null || !rf.panel().isVisible() || !rf.control().isEnabled()) continue;
            String val = rf.getValue().get();
            String err = spec.validator().validate(val);
            if (err != null) {
                errors.add(spec.label() + "：" + err);
            }
        }
        if (!errors.isEmpty()) {
            log.warn(logMessage("gui.config.log.validation-failed", String.join("; ", errors)));
            GuiErrorDialog.show(this,
                    message("gui.config.dialog.validation-failed.title"),
                    message("gui.config.dialog.validation-failed.message", String.join("\n", errors)));
            return;
        }

        Map<String, String> before;
        try {
            before = editor.readAll(allFields.stream().map(ConfigFieldSpec::key).toList());
        } catch (IOException e) {
            log.warn(logMessage("gui.config.log.read-failed", e.getMessage()));
            GuiErrorDialog.show(this,
                    message("gui.dialog.error.title"),
                    message("gui.config.dialog.read-failed.message", e.getMessage()));
            return;
        }

        // 收集所有值：隐藏字段写入空值，Spring Boot 对空值不加载对应证书
        Map<String, String> values = new LinkedHashMap<>();
        for (ConfigFieldSpec spec : allFields) {
            FieldRenderer.RenderedField rf = renderedFields.get(spec.key());
            if (rf == null) continue;
            values.put(spec.key(), rf.panel().isVisible() ? rf.getValue().get() : "");
        }

        Set<String> changedKeys = changedKeys(before, values);
        boolean hasHotReloadChanges = hasChangedField(changedKeys, false);
        boolean hasRestartRequiredChanges = hasChangedField(changedKeys, true);

        try {
            editor.writeAll(values);
            log.info(logMessage("gui.config.log.saved", configPath));
            if (hasHotReloadChanges) {
                showNotice(message("gui.config.notice.hot-reloading"));
                reloadHotConfigAsync(hasRestartRequiredChanges);
            } else {
                showNotice(message(hasRestartRequiredChanges
                        ? "gui.config.notice.saved"
                        : "gui.config.notice.saved-no-change"));
            }
        } catch (IOException e) {
            log.error(logMessage("gui.config.log.save-failed", e.getMessage()));
            GuiErrorDialog.show(this,
                    message("gui.dialog.error.title"),
                    message("gui.config.dialog.save-failed.message", e.getMessage()));
        }
    }

    private Set<String> changedKeys(Map<String, String> before, Map<String, String> after) {
        Set<String> changed = new LinkedHashSet<>();
        for (ConfigFieldSpec spec : allFields) {
            if (!after.containsKey(spec.key())) {
                continue;
            }
            String oldValue = before.containsKey(spec.key()) ? before.get(spec.key()) : spec.defaultValue();
            String newValue = after.get(spec.key());
            if (!Objects.equals(normalizeValue(oldValue), normalizeValue(newValue))) {
                changed.add(spec.key());
            }
        }
        return changed;
    }

    private boolean hasChangedField(Set<String> changedKeys, boolean requiresRestart) {
        for (ConfigFieldSpec spec : allFields) {
            if (spec.requiresRestart() == requiresRestart && changedKeys.contains(spec.key())) {
                return true;
            }
        }
        return false;
    }

    private static String normalizeValue(String value) {
        return value == null ? "" : value.trim();
    }

    private void reloadHotConfigAsync(boolean hasRestartRequiredChanges) {
        SwingWorker<Boolean, Void> worker = new SwingWorker<>() {
            @Override
            protected Boolean doInBackground() {
                return postHotReload();
            }

            @Override
            protected void done() {
                try {
                    if (Boolean.TRUE.equals(get())) {
                        showNotice(message(hasRestartRequiredChanges
                                ? "gui.config.notice.saved-mixed"
                                : "gui.config.notice.saved-hot"));
                    } else {
                        showNotice(message(hasRestartRequiredChanges
                                ? "gui.config.notice.saved-hot-failed-mixed"
                                : "gui.config.notice.saved-hot-failed"));
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    showNotice(message("gui.config.notice.saved-hot-failed"));
                } catch (ExecutionException e) {
                    log.warn(logMessage("gui.config.log.hot-reload-failed", safeMessage(e.getCause())));
                    showNotice(message(hasRestartRequiredChanges
                            ? "gui.config.notice.saved-hot-failed-mixed"
                            : "gui.config.notice.saved-hot-failed"));
                }
            }
        };
        worker.execute();
    }

    private void resetToDefaults() {
        int confirm = JOptionPane.showConfirmDialog(this,
                message("gui.config.dialog.reset-confirm.message"),
                message("gui.config.dialog.reset-confirm.title"), JOptionPane.YES_NO_OPTION);
        if (confirm != JOptionPane.YES_OPTION) return;

        for (ConfigFieldSpec spec : allFields) {
            FieldRenderer.RenderedField rf = renderedFields.get(spec.key());
            if (rf != null) {
                rf.setValue().accept(spec.defaultValue());
            }
        }
        updateEnabledStates();
    }

    private void openConfigFile() {
        try {
            Desktop.getDesktop().open(configPath.toFile());
        } catch (Exception e) {
            log.warn(logMessage("gui.config.log.open-file-failed", configPath, e.getMessage()), e);
            GuiErrorDialog.show(this,
                    message("gui.dialog.error.title"),
                    message("gui.error.open-file", e.getMessage()));
        }
    }

    // ── 字段漂移检查 ──────────────────────────────────────────────────────────────

    /**
     * 对比 ALL_FIELDS 与 config.yaml 中实际存在的 key，漂移时打日志警告。
     */
    private void checkFieldDrift() {
        if (!configPath.toFile().exists()) return;
        try {
            Map<String, String> existing = editor.readAll(
                    allFields.stream()
                            .map(ConfigFieldSpec::key).toList());
            for (ConfigFieldSpec spec : allFields) {
                if (!existing.containsKey(spec.key())) {
                    log.warn(logMessage("gui.config.log.field-drift", spec.key()));
                }
            }
        } catch (IOException e) {
            log.warn(logMessage("gui.config.log.field-drift-check.failed", e.getMessage()));
        }
    }

    // ── 工具 ─────────────────────────────────────────────────────────────────────

    /**
     * 为控件添加变更监听，任何值变化都触发一次 enabledWhen 重算。
     * 这样 ENUM/BOOL 类控件改变后，依赖它的字段会立即启用/禁用。
     */
    private void attachChangeListener(JComponent control) {
        if (control instanceof JCheckBox cb) {
            cb.addItemListener(e -> updateEnabledStates());
        } else if (control instanceof JComboBox<?> combo) {
            combo.addActionListener(e -> updateEnabledStates());
        } else if (control instanceof JSpinner sp) {
            sp.addChangeListener(e -> updateEnabledStates());
        } else if (control instanceof JTextField tf) {
            tf.getDocument().addDocumentListener(new DocumentListener() {
                public void insertUpdate(DocumentEvent e) { updateEnabledStates(); }
                public void removeUpdate(DocumentEvent e) { updateEnabledStates(); }
                public void changedUpdate(DocumentEvent e) { updateEnabledStates(); }
            });
        }
    }

    private void handleAutoStartToggle() {
        if (updatingAutoStartCheckBox || autoStartCheckBox == null || !autoStartCheckBox.isEnabled()) {
            return;
        }

        boolean targetEnabled = autoStartCheckBox.isSelected();
        autoStartCheckBox.setEnabled(false);

        SwingWorker<Void, Void> worker = new SwingWorker<>() {
            @Override
            protected Void doInBackground() throws Exception {
                AutoStartManager.setEnabled(targetEnabled);
                return null;
            }

            @Override
            protected void done() {
                try {
                    get();
                    showNotice(message(targetEnabled
                            ? "gui.config.autostart.notice.enabled"
                            : "gui.config.autostart.notice.disabled"));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    handleAutoStartFailure(targetEnabled, e);
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause() == null ? e : e.getCause();
                    handleAutoStartFailure(targetEnabled, cause);
                } finally {
                    autoStartCheckBox.setEnabled(autoStartSupported);
                }
            }
        };
        worker.execute();
    }

    private void handleAutoStartFailure(boolean targetEnabled, Throwable cause) {
        updatingAutoStartCheckBox = true;
        try {
            autoStartCheckBox.setSelected(!targetEnabled);
        } finally {
            updatingAutoStartCheckBox = false;
        }
        log.error(logMessage("gui.config.log.autostart.apply-failed",
                targetEnabled, safeMessage(cause)), cause);
        GuiErrorDialog.show(ConfigPanel.this,
                message("gui.dialog.error.title"),
                message("gui.config.autostart.dialog.apply-failed.message", safeMessage(cause)));
    }

    private boolean postHotReload() {
        String[] schemes = {"http", "https"};
        for (String scheme : schemes) {
            HttpURLConnection conn = null;
            try {
                URL url = new URI(scheme + "://localhost:" + serverPort + "/api/gui/config/reload").toURL();
                conn = (HttpURLConnection) url.openConnection();
                if (conn instanceof HttpsURLConnection https && TRUST_ALL_SSL != null) {
                    https.setSSLSocketFactory(TRUST_ALL_SSL.getSocketFactory());
                    https.setHostnameVerifier((host, session) -> true);
                }
                conn.setConnectTimeout(2000);
                conn.setReadTimeout(5000);
                conn.setRequestMethod("POST");
                String guiToken = GuiTokenHolder.get();
                if (guiToken != null) {
                    conn.setRequestProperty(GuiTokenHolder.HEADER_NAME, guiToken);
                }
                int status = conn.getResponseCode();
                if (status >= 200 && status < 300) {
                    return true;
                }
            } catch (Exception ignored) {
                // Try the other scheme; the backend may currently be HTTP or HTTPS.
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
        }
        return false;
    }

    private void showNotice(String msg) {
        noticeBar.setText("  " + msg);
        noticeBar.setVisible(true);
        // 10 秒后自动隐藏
        javax.swing.Timer t = new javax.swing.Timer(10_000, e -> noticeBar.setVisible(false));
        t.setRepeats(false);
        t.start();
    }

    private static String safeMessage(Throwable t) {
        if (t == null) {
            return "unknown";
        }
        return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
    }

    private static SSLContext buildTrustAllSslContext() {
        try {
            TrustManager[] trustAll = new TrustManager[]{
                    new X509TrustManager() {
                        public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                        public void checkClientTrusted(X509Certificate[] certs, String authType) {}
                        public void checkServerTrusted(X509Certificate[] certs, String authType) {}
                    }
            };
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, trustAll, new java.security.SecureRandom());
            return context;
        } catch (Exception e) {
            return null;
        }
    }

    private static final class GroupContentPanel extends JPanel implements Scrollable {
        GroupContentPanel() {
            setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        }

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
            return 16;
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
            return Math.max(16, visibleRect.height - 16);
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return false;
        }
    }

    private static String message(String code, Object... args) {
        return GuiMessages.get(code, args);
    }

    private static String logMessage(String code, Object... args) {
        return MessageBundles.get(code, args);
    }
}
