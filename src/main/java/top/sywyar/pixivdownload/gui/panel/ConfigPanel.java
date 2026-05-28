package top.sywyar.pixivdownload.gui.panel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.gui.AutoStartManager;
import top.sywyar.pixivdownload.gui.GuiErrorDialog;
import top.sywyar.pixivdownload.gui.GuiTokenHolder;
import top.sywyar.pixivdownload.gui.config.*;
import top.sywyar.pixivdownload.gui.i18n.GuiMessages;
import top.sywyar.pixivdownload.i18n.MessageBundles;
import top.sywyar.pixivdownload.mail.preset.MailPreset;
import top.sywyar.pixivdownload.mail.preset.MailPresetRegistry;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.awt.*;
import java.awt.Desktop;
import java.awt.event.HierarchyEvent;
import java.awt.event.HierarchyListener;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
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
    private static final List<String> MAINTENANCE_WEEKDAYS = List.of(
            "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday");

    private final Path configPath;
    private final int serverPort;
    private final ConfigFileEditor editor;
    private final String currentMode;

    /** 字段元数据快照（按当前 locale），构造时从 ConfigFieldRegistry 拉取一次。 */
    private final List<ConfigFieldSpec> allFields;
    private final List<String> groups;
    private final String serverGroup;
    private final String multiModeGroup;
    private final String maintenanceGroup;
    private final String mailGroup;

    /** key → 渲染后的字段（含取值/赋值方法） */
    private final Map<String, FieldRenderer.RenderedField> renderedFields = new LinkedHashMap<>();

    /** 提示条（保存后显示） */
    private final JLabel noticeBar = new JLabel(" ");
    private JCheckBox autoStartCheckBox;
    private boolean autoStartSupported;
    private boolean updatingAutoStartCheckBox;

    /** SMTP 预设注册中心（不可变；GUI 加载邮件分组时使用）。 */
    private final MailPresetRegistry mailPresetRegistry = new MailPresetRegistry();
    /** 服务商预设下拉。null 表示 mail 分组未渲染（如未来某模式隐藏邮件组时）。 */
    private JComboBox<MailPreset> mailPresetCombo;
    /** "发送测试邮件" 按钮，发送中暂时禁用。 */
    private JButton mailTestButton;
    /** "发送所有邮件模板" 按钮，发送中暂时禁用。 */
    private JButton mailTestAllButton;
    /** 当前预设；非 custom 时锁定 host / port / security。 */
    private MailPreset currentMailPreset;
    /** 防止在程序性更新下拉时反向触发预设应用。 */
    private boolean updatingMailPresetCombo;

    public ConfigPanel(Path configPath, int serverPort) {
        this.configPath = configPath;
        this.serverPort = serverPort;
        this.editor = new ConfigFileEditor(configPath);
        this.currentMode = resolveCurrentMode();
        this.allFields = ConfigFieldRegistry.allFields();
        this.groups = ConfigFieldRegistry.groups();
        this.serverGroup = groups.isEmpty() ? "" : groups.get(0);
        this.multiModeGroup = ConfigFieldRegistry.groupMultiMode();
        this.maintenanceGroup = ConfigFieldRegistry.groupMaintenance();
        this.mailGroup = ConfigFieldRegistry.groupMail();
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

        // 服务商预设需要排在 mail.* 字段之前，先 append 即天然位于顶部
        if (mailGroup.equals(group)) {
            JPanel mailPresetPanel = buildMailPresetPanel();
            mailPresetPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
            content.add(mailPresetPanel);
            content.add(Box.createVerticalStrut(2));
        }

        for (ConfigFieldSpec spec : fields) {
            if (maintenanceGroup.equals(group) && isMaintenanceDayEnabledKey(spec.key())) {
                continue;
            }
            FieldRenderer.RenderedField rf = FieldRenderer.render(spec);
            registerRenderedField(spec, rf);
            rf.panel().setAlignmentX(Component.LEFT_ALIGNMENT);
            content.add(rf.panel());
            content.add(Box.createVerticalStrut(2));
            if (maintenanceGroup.equals(group) && "maintenance.enabled".equals(spec.key())) {
                JPanel weekdaysPanel = buildMaintenanceWeekdayPanel(fields);
                weekdaysPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
                content.add(weekdaysPanel);
                content.add(Box.createVerticalStrut(2));
            }
        }
        if (serverGroup.equals(group)) {
            JPanel autoStartPanel = buildAutoStartPanel();
            autoStartPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
            content.add(autoStartPanel);
            content.add(Box.createVerticalStrut(2));
        }
        if (mailGroup.equals(group)) {
            JPanel mailTestPanel = buildMailTestPanel();
            mailTestPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
            content.add(mailTestPanel);
            content.add(Box.createVerticalStrut(2));
            JPanel mailTestAllPanel = buildMailTestAllPanel();
            mailTestAllPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
            content.add(mailTestAllPanel);
            content.add(Box.createVerticalStrut(2));
        }
        content.add(Box.createVerticalGlue());

        JScrollPane sp = new JScrollPane(content);
        sp.setBorder(null);
        sp.getVerticalScrollBar().setUnitIncrement(16);
        // mail 分组在 init 阶段对预设 combo 的 setSelectedItem + 对 host/port/security 的
        // setEnabled(false) 会让视口偏离 (0,0)；首次显示时强制回到顶部。
        if (mailGroup.equals(group)) {
            sp.addHierarchyListener(new HierarchyListener() {
                @Override
                public void hierarchyChanged(HierarchyEvent e) {
                    if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0
                            && sp.isShowing()) {
                        SwingUtilities.invokeLater(() -> sp.getViewport().setViewPosition(new Point(0, 0)));
                        sp.removeHierarchyListener(this);
                    }
                }
            });
        }
        return sp;
    }

    private JPanel buildMaintenanceWeekdayPanel(List<ConfigFieldSpec> fields) {
        JPanel checkBoxRow = new JPanel(new GridLayout(1, MAINTENANCE_WEEKDAYS.size(), 8, 0));
        checkBoxRow.setOpaque(false);

        Map<ConfigFieldSpec, JCheckBox> checkBoxes = new LinkedHashMap<>();
        for (String weekday : MAINTENANCE_WEEKDAYS) {
            findField(fields, "maintenance." + weekday + ".enabled").ifPresent(spec -> {
                JCheckBox checkBox = new JCheckBox(spec.label());
                checkBox.setSelected("true".equalsIgnoreCase(spec.defaultValue()));
                checkBox.setToolTipText(spec.helpText());
                checkBox.setOpaque(false);
                checkBoxes.put(spec, checkBox);
                checkBoxRow.add(checkBox);
            });
        }

        JPanel panel = FieldRenderer.fieldPanel(
                message("gui.config.field.maintenance.weekdays.label") + message("gui.punctuation.colon"),
                checkBoxRow,
                buildEffectLabel(false),
                message("gui.config.field.maintenance.weekdays.help"));

        for (Map.Entry<ConfigFieldSpec, JCheckBox> entry : checkBoxes.entrySet()) {
            JCheckBox checkBox = entry.getValue();
            FieldRenderer.RenderedField rf = new FieldRenderer.RenderedField(
                    panel,
                    () -> Boolean.toString(checkBox.isSelected()),
                    value -> checkBox.setSelected("true".equalsIgnoreCase(value)),
                    checkBox,
                    createHiddenValidationError());
            registerRenderedField(entry.getKey(), rf);
        }

        return panel;
    }

    private static Optional<ConfigFieldSpec> findField(List<ConfigFieldSpec> fields, String key) {
        return fields.stream()
                .filter(field -> key.equals(field.key()))
                .findFirst();
    }

    private void registerRenderedField(ConfigFieldSpec spec, FieldRenderer.RenderedField rf) {
        renderedFields.put(spec.key(), rf);
        attachChangeListener(spec.key(), rf.control());
    }

    private static JLabel buildEffectLabel(boolean requiresRestart) {
        JLabel effect = new JLabel(message(requiresRestart
                ? "gui.label.restart-required"
                : "gui.label.hot-reload"));
        effect.setFont(effect.getFont().deriveFont(Font.PLAIN, 11f));
        effect.setForeground(requiresRestart
                ? new Color(180, 100, 0)
                : new Color(0, 128, 96));
        return effect;
    }

    private static JTextArea createHiddenValidationError() {
        JTextArea validationError = new JTextArea();
        validationError.setVisible(false);
        return validationError;
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

    // ── 邮件分组特殊控件 ──────────────────────────────────────────────────────────

    /**
     * "服务商预设" 下拉：选中预设后自动填入并锁定 host / port / security 三项；
     * 选中 "自定义" 解锁。预设本身不入 config.yaml，由 host 反查推断。
     */
    private JPanel buildMailPresetPanel() {
        mailPresetCombo = new JComboBox<>(mailPresetRegistry.all().toArray(new MailPreset[0]));
        mailPresetCombo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                          boolean isSelected, boolean cellHasFocus) {
                JLabel label = (JLabel) super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value instanceof MailPreset preset) {
                    label.setText(message(preset.displayNameKey()));
                }
                return label;
            }
        });
        mailPresetCombo.addActionListener(e -> {
            if (updatingMailPresetCombo) {
                return;
            }
            Object selected = mailPresetCombo.getSelectedItem();
            if (selected instanceof MailPreset preset) {
                applyMailPreset(preset, true);
            }
        });
        return FieldRenderer.fieldPanel(
                message("gui.config.mail.preset.label") + message("gui.punctuation.colon"),
                mailPresetCombo,
                null,
                message("gui.config.mail.preset.help"));
    }

    /** "发送测试邮件" 按钮行。 */
    private JPanel buildMailTestPanel() {
        mailTestButton = new JButton(message("gui.config.mail.test-button.label"));
        mailTestButton.addActionListener(e -> sendMailTest());
        return FieldRenderer.fieldPanel(
                message("gui.config.mail.test-button.label") + message("gui.punctuation.colon"),
                mailTestButton,
                null,
                message("gui.config.mail.test-button.help"));
    }

    /** "发送所有邮件模板" 按钮行：用示例数据遍历全部模板逐封发送。 */
    private JPanel buildMailTestAllPanel() {
        mailTestAllButton = new JButton(message("gui.config.mail.test-all.button.label"));
        mailTestAllButton.addActionListener(e -> sendAllMailTemplates());
        return FieldRenderer.fieldPanel(
                message("gui.config.mail.test-all.button.label") + message("gui.punctuation.colon"),
                mailTestAllButton,
                null,
                message("gui.config.mail.test-all.button.help"));
    }

    /**
     * 应用选中的预设：写入 host / port / security 字段，按是否自定义切换锁定状态。
     *
     * @param userInitiated 由用户操作触发时为 true（会覆盖三项值）；初始化反查时为 false（只更新锁定状态）
     */
    private void applyMailPreset(MailPreset preset, boolean userInitiated) {
        currentMailPreset = preset;
        if (preset == null) {
            return;
        }
        if (userInitiated && !preset.isCustom()) {
            setRenderedFieldValue("mail.host", preset.host());
            setRenderedFieldValue("mail.port", String.valueOf(preset.port()));
            setRenderedFieldValue("mail.security", preset.security().value());
        }
        updateEnabledStates();
    }

    private void setRenderedFieldValue(String key, String value) {
        FieldRenderer.RenderedField rf = renderedFields.get(key);
        if (rf != null) {
            rf.setValue().accept(value);
        }
    }

    /**
     * 在 {@link #updateEnabledStates()} 末尾叠加预设锁定：选中非自定义预设时，host / port / security
     * 即便满足 {@code enabledWhen(mail.enabled)} 也保持禁用。
     */
    private void applyMailPresetLock() {
        if (mailPresetCombo == null || currentMailPreset == null || currentMailPreset.isCustom()) {
            return;
        }
        lockFieldByPreset("mail.host");
        lockFieldByPreset("mail.port");
        lockFieldByPreset("mail.security");
    }

    private void lockFieldByPreset(String key) {
        FieldRenderer.RenderedField rf = renderedFields.get(key);
        if (rf == null || !rf.panel().isVisible()) {
            return;
        }
        setControlEnabled(rf.panel(), false);
    }

    /** 启动后按已有 host 反查预设；未命中落到 custom。 */
    private void resolveMailPresetFromCurrentHost() {
        if (mailPresetCombo == null) {
            return;
        }
        FieldRenderer.RenderedField hostField = renderedFields.get("mail.host");
        String host = hostField == null ? "" : hostField.getValue().get();
        MailPreset preset = mailPresetRegistry.findByHost(host).orElseGet(mailPresetRegistry::custom);
        updatingMailPresetCombo = true;
        try {
            mailPresetCombo.setSelectedItem(preset);
        } finally {
            updatingMailPresetCombo = false;
        }
        applyMailPreset(preset, false);
    }

    private void sendMailTest() {
        if (mailTestButton == null) {
            return;
        }
        mailTestButton.setEnabled(false);
        showNotice(message("gui.config.mail.test.notice.sending"));

        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("host", currentFieldValue("mail.host"));
        payload.put("port", parseIntOrZero(currentFieldValue("mail.port")));
        payload.put("security", currentFieldValue("mail.security"));
        payload.put("username", currentFieldValue("mail.username"));
        payload.put("password", currentFieldValue("mail.password"));
        payload.put("from", currentFieldValue("mail.from"));
        payload.put("to", currentFieldValue("mail.to"));
        payload.put("socksProxy", currentFieldValue("mail.socks-proxy"));
        payload.put("subjectPrefix", currentFieldValue("mail.subject-prefix"));

        SwingWorker<MailTestOutcome, Void> worker = new SwingWorker<>() {
            @Override
            protected MailTestOutcome doInBackground() {
                return postMailTest(payload);
            }

            @Override
            protected void done() {
                try {
                    MailTestOutcome outcome = get();
                    if (outcome.reachable()) {
                        if (outcome.success()) {
                            showNotice(message("gui.config.mail.test.notice.success"));
                        } else {
                            showNotice(message("gui.config.mail.test.notice.failed",
                                    outcome.error() == null ? "" : outcome.error()));
                        }
                    } else {
                        showNotice(message("gui.config.mail.test.notice.unreachable"));
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    showNotice(message("gui.config.mail.test.notice.unreachable"));
                } catch (ExecutionException ex) {
                    log.warn(logMessage("gui.config.mail.test.notice.failed", safeMessage(ex.getCause())),
                            ex.getCause());
                    showNotice(message("gui.config.mail.test.notice.failed", safeMessage(ex.getCause())));
                } finally {
                    mailTestButton.setEnabled(true);
                }
            }
        };
        worker.execute();
    }

    private String currentFieldValue(String key) {
        FieldRenderer.RenderedField rf = renderedFields.get(key);
        return rf == null ? "" : rf.getValue().get();
    }

    private static int parseIntOrZero(String s) {
        if (s == null || s.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** 同时尝试 http / https，本地端点；连接不上返回 reachable=false。 */
    private MailTestOutcome postMailTest(ObjectNode payload) {
        byte[] body;
        try {
            body = MAPPER.writeValueAsBytes(payload);
        } catch (Exception e) {
            return new MailTestOutcome(true, false, e.getMessage());
        }
        String[] schemes = {"http", "https"};
        for (String scheme : schemes) {
            HttpURLConnection conn = null;
            try {
                URL url = new URI(scheme + "://localhost:" + serverPort + "/api/gui/mail-test").toURL();
                conn = (HttpURLConnection) url.openConnection();
                if (conn instanceof HttpsURLConnection https && TRUST_ALL_SSL != null) {
                    https.setSSLSocketFactory(TRUST_ALL_SSL.getSocketFactory());
                    https.setHostnameVerifier((host, session) -> true);
                }
                conn.setConnectTimeout(2000);
                conn.setReadTimeout(30000);
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                conn.setRequestProperty("Accept-Language", GuiMessages.currentLocale().toLanguageTag());
                String guiToken = GuiTokenHolder.get();
                if (guiToken != null) {
                    conn.setRequestProperty(GuiTokenHolder.HEADER_NAME, guiToken);
                }
                conn.getOutputStream().write(body);
                int status = conn.getResponseCode();
                String responseBody = readResponseBody(conn, status);
                if (status >= 200 && status < 300) {
                    boolean success = true;
                    String error = null;
                    if (responseBody != null && !responseBody.isBlank()) {
                        var node = MAPPER.readTree(responseBody);
                        success = node.path("success").asBoolean(false);
                        error = node.path("error").isMissingNode() || node.path("error").isNull()
                                ? null : node.path("error").asText();
                    }
                    return new MailTestOutcome(true, success, error);
                }
                // 非 2xx 但已连通；把 body 内容作为错误摘要
                return new MailTestOutcome(true, false,
                        (responseBody == null || responseBody.isBlank())
                                ? ("HTTP " + status)
                                : responseBody);
            } catch (Exception ignored) {
                // try the other scheme
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
        }
        return new MailTestOutcome(false, false, null);
    }

    private static String readResponseBody(HttpURLConnection conn, int status) {
        try (var stream = (status >= 200 && status < 300) ? conn.getInputStream() : conn.getErrorStream()) {
            if (stream == null) {
                return "";
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                return sb.toString();
            }
        } catch (IOException e) {
            return "";
        }
    }

    /** mail-test 异步结果。reachable=false 表示后端连接不上；success=true 仅当后端发信成功。 */
    private record MailTestOutcome(boolean reachable, boolean success, String error) {
    }

    private void sendAllMailTemplates() {
        if (mailTestAllButton == null) {
            return;
        }
        mailTestAllButton.setEnabled(false);
        showNotice(message("gui.config.mail.test-all.notice.sending"));

        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("host", currentFieldValue("mail.host"));
        payload.put("port", parseIntOrZero(currentFieldValue("mail.port")));
        payload.put("security", currentFieldValue("mail.security"));
        payload.put("username", currentFieldValue("mail.username"));
        payload.put("password", currentFieldValue("mail.password"));
        payload.put("from", currentFieldValue("mail.from"));
        payload.put("to", currentFieldValue("mail.to"));
        payload.put("socksProxy", currentFieldValue("mail.socks-proxy"));
        payload.put("subjectPrefix", currentFieldValue("mail.subject-prefix"));

        SwingWorker<MailTestAllOutcome, Void> worker = new SwingWorker<>() {
            @Override
            protected MailTestAllOutcome doInBackground() {
                return postMailTestAll(payload);
            }

            @Override
            protected void done() {
                try {
                    MailTestAllOutcome outcome = get();
                    if (!outcome.reachable()) {
                        showNotice(message("gui.config.mail.test.notice.unreachable"));
                    } else if (outcome.success()) {
                        showNotice(message("gui.config.mail.test-all.notice.success",
                                String.valueOf(outcome.total())));
                    } else if (outcome.succeeded() > 0) {
                        showNotice(message("gui.config.mail.test-all.notice.partial",
                                String.valueOf(outcome.succeeded()),
                                String.valueOf(outcome.total()),
                                outcome.errorSummary() == null ? "" : outcome.errorSummary()));
                    } else {
                        showNotice(message("gui.config.mail.test.notice.failed",
                                outcome.errorSummary() == null ? "" : outcome.errorSummary()));
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    showNotice(message("gui.config.mail.test.notice.unreachable"));
                } catch (ExecutionException ex) {
                    log.warn(logMessage("gui.config.mail.test.notice.failed", safeMessage(ex.getCause())),
                            ex.getCause());
                    showNotice(message("gui.config.mail.test.notice.failed", safeMessage(ex.getCause())));
                } finally {
                    mailTestAllButton.setEnabled(true);
                }
            }
        };
        worker.execute();
    }

    /**
     * 调用 {@code /api/gui/mail-test-all}；同时尝试 http / https，本地端点；连接不上返回 reachable=false。
     * 读超时放宽到 180s：服务端会串行发送 4 封邮件，最坏情况下每封 ~40s（SMTP 连接 / 读 / 写 timeout 之和）。
     */
    private MailTestAllOutcome postMailTestAll(ObjectNode payload) {
        byte[] body;
        try {
            body = MAPPER.writeValueAsBytes(payload);
        } catch (Exception e) {
            return new MailTestAllOutcome(true, false, 0, 0, e.getMessage());
        }
        String[] schemes = {"http", "https"};
        for (String scheme : schemes) {
            HttpURLConnection conn = null;
            try {
                URL url = new URI(scheme + "://localhost:" + serverPort + "/api/gui/mail-test-all").toURL();
                conn = (HttpURLConnection) url.openConnection();
                if (conn instanceof HttpsURLConnection https && TRUST_ALL_SSL != null) {
                    https.setSSLSocketFactory(TRUST_ALL_SSL.getSocketFactory());
                    https.setHostnameVerifier((host, session) -> true);
                }
                conn.setConnectTimeout(2000);
                conn.setReadTimeout(180_000);
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                conn.setRequestProperty("Accept-Language", GuiMessages.currentLocale().toLanguageTag());
                String guiToken = GuiTokenHolder.get();
                if (guiToken != null) {
                    conn.setRequestProperty(GuiTokenHolder.HEADER_NAME, guiToken);
                }
                conn.getOutputStream().write(body);
                int status = conn.getResponseCode();
                String responseBody = readResponseBody(conn, status);
                if (status >= 200 && status < 300) {
                    boolean success = false;
                    int total = 0;
                    int succeeded = 0;
                    String errorSummary = null;
                    if (responseBody != null && !responseBody.isBlank()) {
                        var node = MAPPER.readTree(responseBody);
                        success = node.path("success").asBoolean(false);
                        total = node.path("total").asInt(0);
                        succeeded = node.path("succeeded").asInt(0);
                        var failures = node.path("failures");
                        if (failures.isArray() && !failures.isEmpty()) {
                            StringBuilder sb = new StringBuilder();
                            for (int i = 0; i < failures.size(); i++) {
                                var f = failures.get(i);
                                if (sb.length() > 0) sb.append("; ");
                                sb.append(f.path("templateId").asText("-"))
                                        .append(": ")
                                        .append(f.path("error").asText(""));
                            }
                            errorSummary = sb.toString();
                        }
                    }
                    return new MailTestAllOutcome(true, success, total, succeeded, errorSummary);
                }
                return new MailTestAllOutcome(true, false, 0, 0,
                        (responseBody == null || responseBody.isBlank())
                                ? ("HTTP " + status)
                                : responseBody);
            } catch (Exception ignored) {
                // try the other scheme
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
        }
        return new MailTestAllOutcome(false, false, 0, 0, null);
    }

    /** mail-test-all 异步结果。reachable=false 表示后端连接不上；success=true 仅当全部模板都发信成功。 */
    private record MailTestAllOutcome(boolean reachable, boolean success, int total, int succeeded,
                                      String errorSummary) {
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

            resolveMailPresetFromCurrentHost();
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
        applyMailPresetLock();
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
        clearValidationErrors();
        List<String> errors = new ArrayList<>();
        FieldRenderer.RenderedField firstInvalidField = null;
        for (ConfigFieldSpec spec : allFields) {
            FieldRenderer.RenderedField rf = renderedFields.get(spec.key());
            if (rf == null || !rf.panel().isVisible() || !rf.control().isEnabled()) continue;
            String val = rf.getValue().get();
            String err = spec.validator().validate(val);
            if (err != null) {
                String message = spec.label() + "：" + err;
                errors.add(message);
                rf.setValidationError(message);
                if (firstInvalidField == null) {
                    firstInvalidField = rf;
                }
            }
        }
        if (!errors.isEmpty()) {
            log.warn(logMessage("gui.config.log.validation-failed", String.join("; ", errors)));
            showNotice(message("gui.config.notice.validation-failed"));
            if (firstInvalidField != null) {
                firstInvalidField.control().requestFocusInWindow();
                firstInvalidField.panel().scrollRectToVisible(new Rectangle(
                        0, 0, firstInvalidField.panel().getWidth(), firstInvalidField.panel().getHeight()));
            }
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
            values.put(spec.key(), rf.panel().isVisible() || shouldPreserveHiddenValue(spec)
                    ? rf.getValue().get()
                    : "");
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

    private static boolean shouldPreserveHiddenValue(ConfigFieldSpec spec) {
        return isMaintenanceDayTimeKey(spec.key());
    }

    private static boolean isMaintenanceDayEnabledKey(String key) {
        return maintenanceDayKey(key, "enabled");
    }

    private static boolean isMaintenanceDayTimeKey(String key) {
        return maintenanceDayKey(key, "time");
    }

    private static boolean maintenanceDayKey(String key, String suffix) {
        for (String weekday : MAINTENANCE_WEEKDAYS) {
            if (("maintenance." + weekday + "." + suffix).equals(key)) {
                return true;
            }
        }
        return false;
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
                rf.setValidationError(null);
            }
        }
        resolveMailPresetFromCurrentHost();
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
    private void attachChangeListener(String key, JComponent control) {
        if (control instanceof JCheckBox cb) {
            cb.addItemListener(e -> handleFieldChanged(key));
        } else if (control instanceof JComboBox<?> combo) {
            combo.addActionListener(e -> handleFieldChanged(key));
        } else if (control instanceof JSpinner sp) {
            sp.addChangeListener(e -> handleFieldChanged(key));
        } else if (control instanceof JTextField tf) {
            tf.getDocument().addDocumentListener(new DocumentListener() {
                public void insertUpdate(DocumentEvent e) { handleFieldChanged(key); }
                public void removeUpdate(DocumentEvent e) { handleFieldChanged(key); }
                public void changedUpdate(DocumentEvent e) { handleFieldChanged(key); }
            });
        }
    }

    private void handleFieldChanged(String key) {
        FieldRenderer.RenderedField rf = renderedFields.get(key);
        if (rf != null) {
            rf.setValidationError(null);
        }
        updateEnabledStates();
    }

    private void clearValidationErrors() {
        for (FieldRenderer.RenderedField rf : renderedFields.values()) {
            rf.setValidationError(null);
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
            log.warn("Failed to create trust-all SSL context", e);
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
