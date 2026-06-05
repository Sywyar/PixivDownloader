package top.sywyar.pixivdownload.gui.panel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.gui.AutoStartManager;
import top.sywyar.pixivdownload.gui.DebugUnlockState;
import top.sywyar.pixivdownload.gui.GuiErrorDialog;
import top.sywyar.pixivdownload.gui.GuiTokenHolder;
import top.sywyar.pixivdownload.gui.config.*;
import top.sywyar.pixivdownload.ai.preset.AiPreset;
import top.sywyar.pixivdownload.ai.preset.AiPresetRegistry;
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
    private static final int PUSH_TEST_READ_TIMEOUT_MS = 30_000;
    private static final int PUSH_TEST_ALL_READ_TIMEOUT_MS = 10 * 60 * 1000;


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
    private final String aiGroup;
    private final String narrationTtsGroup;
    private final String notificationGroup;

    /** key → 渲染后的字段（含取值/赋值方法） */
    private final Map<String, FieldRenderer.RenderedField> renderedFields = new LinkedHashMap<>();

    /** 提示条（保存后显示） */
    private final JLabel noticeBar = new JLabel(" ");

    /** 调试模式解锁监听：彩蛋触发后在 EDT 上刷新可见性并提示。面板加入/移出窗口时随之注册/注销，避免泄漏。 */
    private final Runnable debugUnlockListener = () -> SwingUtilities.invokeLater(this::onDebugUnlocked);

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

    /** AI 服务商预设注册中心（不可变；GUI 加载 AI 分组时使用）。 */
    private final AiPresetRegistry aiPresetRegistry = new AiPresetRegistry();
    /** AI 服务商预设下拉。null 表示 AI 分组未渲染。 */
    private JComboBox<AiPreset> aiPresetCombo;
    /** "测试 AI 连接" 按钮，测试中暂时禁用。 */
    private JButton aiTestButton;
    /** 当前 AI 预设；非 custom 时锁定 base-url。 */
    private AiPreset currentAiPreset;
    /** 防止在程序性更新下拉时反向触发预设应用。 */
    private boolean updatingAiPresetCombo;

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
        this.aiGroup = ConfigFieldRegistry.groupAi();
        this.narrationTtsGroup = ConfigFieldRegistry.groupNarrationTts();
        this.notificationGroup = ConfigFieldRegistry.groupNotification();
        buildUi();
        loadCurrentValues();
        checkFieldDrift();
    }

    @Override
    public void addNotify() {
        super.addNotify();
        DebugUnlockState.addListener(debugUnlockListener);
    }

    @Override
    public void removeNotify() {
        DebugUnlockState.removeListener(debugUnlockListener);
        super.removeNotify();
    }

    /** 彩蛋解锁后刷新字段可见性，让「调试模式」复选框显示出来并提示用户。 */
    private void onDebugUnlocked() {
        updateEnabledStates();
        showNotice(message("gui.config.notice.debug-unlocked"));
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

    private JComponent buildGroupPanel(String group) {
        // 「通知」分组特殊：邮件 + 多通道推送合并，用服务下拉切换编辑，所有已启用的服务同时生效。
        if (notificationGroup.equals(group)) {
            return buildNotificationPanel();
        }
        // 「AI」分组特殊：文本模型（ai.*）+ TTS 模型（narration-tts.*）合并，用「模态」下拉切换编辑，两类配置同时生效。
        if (aiGroup.equals(group)) {
            return buildAiPanel();
        }

        JPanel content = new GroupContentPanel();
        content.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        List<ConfigFieldSpec> fields = allFields.stream()
                .filter(f -> group.equals(f.group()))
                .toList();

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
        content.add(Box.createVerticalGlue());

        JScrollPane sp = new JScrollPane(content);
        sp.setBorder(null);
        sp.getVerticalScrollBar().setUnitIncrement(16);
        // 任何分组在 init 阶段（字段锁定 / 预设回填 / 响应式重排）都可能让视口偏离 (0,0)；
        // 首次显示时统一强制回到顶部，避免切到该标签页时字段区域停在底部。
        resetScrollToTopOnFirstShow(sp);
        return sp;
    }

    /**
     * 让滚动面板在首次真正显示时把视口重置回顶部，随后摘除监听器不再干预用户滚动。
     * <p>
     * AI / 邮件等分组在 init 阶段锁定字段（如 ai.base-url / mail.host）会触发
     * {@code scrollRectToVisible}，使视口偏离 (0,0)；若不修正，首次切到该标签页 / 卡片时
     * 字段区域会直接停在底部而非从头显示。
     */
    private static void resetScrollToTopOnFirstShow(JScrollPane sp) {
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

    // ── 通知分组（邮件 + 多通道推送，服务下拉切换编辑）────────────────────────────────

    /** 通知服务下拉项：id 用于 CardLayout 切换与推送测试，displayKey 为 i18n 显示名。 */
    private record NotificationService(String id, String displayKey) {
    }

    private static List<NotificationService> notificationServices() {
        return List.of(
                new NotificationService("mail", "gui.config.notification.service.mail"),
                new NotificationService("bark", "gui.config.notification.service.bark"),
                new NotificationService("dingtalk", "gui.config.notification.service.dingtalk"),
                new NotificationService("telegram", "gui.config.notification.service.telegram"),
                new NotificationService("feishu", "gui.config.notification.service.feishu"),
                new NotificationService("wecom", "gui.config.notification.service.wecom"),
                new NotificationService("pushplus", "gui.config.notification.service.pushplus"),
                new NotificationService("serverchan", "gui.config.notification.service.serverchan"),
                new NotificationService("webhook", "gui.config.notification.service.webhook"));
    }

    /**
     * 通知分组面板：顶部提示 + 推送总开关 + 服务下拉；下方 CardLayout 显示所选服务的编辑卡片。
     * 所有已启用的服务同时发送；下拉仅切换当前编辑的服务。
     */
    private JComponent buildNotificationPanel() {
        JPanel root = new JPanel(new BorderLayout(0, 6));
        root.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        JPanel top = new JPanel();
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));

        JLabel hint = new JLabel(message("gui.config.notification.hint"));
        hint.setFont(hint.getFont().deriveFont(Font.PLAIN, 11f));
        hint.setForeground(new Color(0, 128, 96));
        hint.setAlignmentX(Component.LEFT_ALIGNMENT);
        top.add(hint);
        top.add(Box.createVerticalStrut(4));

        // 推送总开关（push.enabled）置顶，始终可见。
        ConfigFieldSpec pushEnabledSpec = findSpec("push.enabled");
        if (pushEnabledSpec != null) {
            FieldRenderer.RenderedField rf = FieldRenderer.render(pushEnabledSpec);
            registerRenderedField(pushEnabledSpec, rf);
            rf.panel().setAlignmentX(Component.LEFT_ALIGNMENT);
            top.add(rf.panel());
            top.add(Box.createVerticalStrut(2));
        }

        JComboBox<NotificationService> serviceCombo =
                new JComboBox<>(notificationServices().toArray(new NotificationService[0]));
        serviceCombo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                          boolean isSelected, boolean cellHasFocus) {
                JLabel label = (JLabel) super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value instanceof NotificationService s) {
                    label.setText(message(s.displayKey()));
                }
                return label;
            }
        });
        JPanel comboRow = FieldRenderer.fieldPanel(
                message("gui.config.notification.service.label") + message("gui.punctuation.colon"),
                serviceCombo, null, message("gui.config.notification.service.help"));
        comboRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        top.add(comboRow);

        CardLayout cardLayout = new CardLayout();
        JPanel cardHost = new JPanel(cardLayout);
        for (NotificationService s : notificationServices()) {
            cardHost.add(buildServiceCard(s), s.id());
        }
        serviceCombo.addActionListener(e -> {
            if (serviceCombo.getSelectedItem() instanceof NotificationService s) {
                cardLayout.show(cardHost, s.id());
            }
        });

        root.add(top, BorderLayout.NORTH);
        root.add(cardHost, BorderLayout.CENTER);
        return root;
    }

    /** 构建单个通知服务的编辑卡片（邮件含预设 + 测试；推送渠道含字段 + 单渠道测试）。 */
    private JComponent buildServiceCard(NotificationService service) {
        JPanel content = new GroupContentPanel();
        content.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        if ("mail".equals(service.id())) {
            JPanel preset = buildMailPresetPanel();
            preset.setAlignmentX(Component.LEFT_ALIGNMENT);
            content.add(preset);
            content.add(Box.createVerticalStrut(2));
            addFieldsTo(content, fieldsByPrefix("mail."));
            JPanel test = buildMailTestPanel();
            test.setAlignmentX(Component.LEFT_ALIGNMENT);
            content.add(test);
            content.add(Box.createVerticalStrut(2));
            JPanel testAll = buildMailTestAllPanel();
            testAll.setAlignmentX(Component.LEFT_ALIGNMENT);
            content.add(testAll);
            content.add(Box.createVerticalStrut(2));
        } else {
            addFieldsTo(content, fieldsByPrefix("push." + service.id() + "."));
            JPanel test = buildPushChannelTestPanel(service.id());
            test.setAlignmentX(Component.LEFT_ALIGNMENT);
            content.add(test);
            content.add(Box.createVerticalStrut(2));
            JPanel testAll = buildPushChannelTestAllPanel(service.id());
            testAll.setAlignmentX(Component.LEFT_ALIGNMENT);
            content.add(testAll);
            content.add(Box.createVerticalStrut(2));
        }
        content.add(Box.createVerticalGlue());

        JScrollPane sp = new JScrollPane(content);
        sp.setBorder(null);
        sp.getVerticalScrollBar().setUnitIncrement(16);
        // 邮件卡片含预设锁定，init 阶段会让视口偏离 (0,0)；首次显示该卡片时强制回到顶部。
        resetScrollToTopOnFirstShow(sp);
        return sp;
    }

    /** 渲染并注册一组字段到容器（供通知卡片复用，与 buildGroupPanel 的渲染循环一致）。 */
    private void addFieldsTo(JPanel content, List<ConfigFieldSpec> specs) {
        for (ConfigFieldSpec spec : specs) {
            FieldRenderer.RenderedField rf = FieldRenderer.render(spec);
            registerRenderedField(spec, rf);
            rf.panel().setAlignmentX(Component.LEFT_ALIGNMENT);
            content.add(rf.panel());
            content.add(Box.createVerticalStrut(2));
        }
    }

    private List<ConfigFieldSpec> fieldsByPrefix(String prefix) {
        return allFields.stream()
                .filter(f -> notificationGroup.equals(f.group()))
                .filter(f -> f.key().startsWith(prefix))
                .toList();
    }

    private List<ConfigFieldSpec> fieldsByGroup(String group) {
        return allFields.stream()
                .filter(f -> group.equals(f.group()))
                .toList();
    }

    private ConfigFieldSpec findSpec(String key) {
        return allFields.stream().filter(f -> key.equals(f.key())).findFirst().orElse(null);
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

    // ── AI 分组（文本模型 + TTS 模型，模态下拉切换编辑）────────────────────────────────

    /** AI 模态项：id 用于 CardLayout 切换，displayKey 为 i18n 显示名。 */
    private record AiModality(String id, String displayKey) {
    }

    private static List<AiModality> aiModalities() {
        return List.of(
                new AiModality("text", "gui.config.ai.modality.text"),
                new AiModality("tts", "gui.config.ai.modality.tts"));
    }

    /**
     * AI 分组面板：顶部「模态」下拉切换「文本模型」（{@code ai.*}）与「TTS 模型」（{@code narration-tts.*}）两张卡片。
     * 两类配置同时持久化、同时生效；下拉仅切换当前编辑的模态。
     */
    private JComponent buildAiPanel() {
        JPanel root = new JPanel(new BorderLayout(0, 6));
        root.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        JComboBox<AiModality> modalityCombo =
                new JComboBox<>(aiModalities().toArray(new AiModality[0]));
        modalityCombo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                          boolean isSelected, boolean cellHasFocus) {
                JLabel label = (JLabel) super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value instanceof AiModality m) {
                    label.setText(message(m.displayKey()));
                }
                return label;
            }
        });
        JPanel comboRow = FieldRenderer.fieldPanel(
                message("gui.config.ai.modality.label") + message("gui.punctuation.colon"),
                modalityCombo, null, message("gui.config.ai.modality.help"));

        CardLayout cardLayout = new CardLayout();
        JPanel cardHost = new JPanel(cardLayout);
        cardHost.add(buildAiTextCard(), "text");
        cardHost.add(buildAiTtsCard(), "tts");
        modalityCombo.addActionListener(e -> {
            if (modalityCombo.getSelectedItem() instanceof AiModality m) {
                cardLayout.show(cardHost, m.id());
            }
        });

        root.add(comboRow, BorderLayout.NORTH);
        root.add(cardHost, BorderLayout.CENTER);
        return root;
    }

    /** 「文本模型」卡片：服务商预设 + {@code ai.*} 字段 + 连接测试。 */
    private JComponent buildAiTextCard() {
        JPanel content = new GroupContentPanel();
        content.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        JPanel aiPresetPanel = buildAiPresetPanel();
        aiPresetPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(aiPresetPanel);
        content.add(Box.createVerticalStrut(2));

        addFieldsTo(content, fieldsByGroup(aiGroup));

        JPanel aiTestPanel = buildAiTestPanel();
        aiTestPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(aiTestPanel);
        content.add(Box.createVerticalStrut(2));
        content.add(Box.createVerticalGlue());

        JScrollPane sp = new JScrollPane(content);
        sp.setBorder(null);
        sp.getVerticalScrollBar().setUnitIncrement(16);
        // 预设锁定会在 init 阶段让视口偏离 (0,0)；首次显示该卡片时强制回到顶部。
        resetScrollToTopOnFirstShow(sp);
        return sp;
    }

    /** 「TTS 模型」卡片：{@code narration-tts.*} 字段（多角色听小说朗读的语音合成引擎）。 */
    private JComponent buildAiTtsCard() {
        JPanel content = new GroupContentPanel();
        content.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        addFieldsTo(content, fieldsByGroup(narrationTtsGroup));
        content.add(Box.createVerticalGlue());

        JScrollPane sp = new JScrollPane(content);
        sp.setBorder(null);
        sp.getVerticalScrollBar().setUnitIncrement(16);
        resetScrollToTopOnFirstShow(sp);
        return sp;
    }

    // ── AI 分组特殊控件 ──────────────────────────────────────────────────────────

    /**
     * "服务商预设" 下拉：选中预设后自动填入 base-url（锁定）/ 模型 / 是否走代理；选中 "自定义" 解锁。
     * 预设本身不入 config.yaml，由 base-url 反查推断。
     */
    private JPanel buildAiPresetPanel() {
        aiPresetCombo = new JComboBox<>(aiPresetRegistry.all().toArray(new AiPreset[0]));
        aiPresetCombo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                          boolean isSelected, boolean cellHasFocus) {
                JLabel label = (JLabel) super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value instanceof AiPreset preset) {
                    label.setText(message(preset.displayNameKey()));
                }
                return label;
            }
        });
        aiPresetCombo.addActionListener(e -> {
            if (updatingAiPresetCombo) {
                return;
            }
            Object selected = aiPresetCombo.getSelectedItem();
            if (selected instanceof AiPreset preset) {
                applyAiPreset(preset, true);
            }
        });
        return FieldRenderer.fieldPanel(
                message("gui.config.ai.preset.label") + message("gui.punctuation.colon"),
                aiPresetCombo,
                null,
                message("gui.config.ai.preset.help"));
    }

    /** "测试 AI 连接" 按钮行。 */
    private JPanel buildAiTestPanel() {
        aiTestButton = new JButton(message("gui.config.ai.test-button.label"));
        aiTestButton.addActionListener(e -> sendAiTest());
        return FieldRenderer.fieldPanel(
                message("gui.config.ai.test-button.label") + message("gui.punctuation.colon"),
                aiTestButton,
                null,
                message("gui.config.ai.test-button.help"));
    }

    /**
     * 应用选中的 AI 预设：base-url 写入并锁定；模型 / 是否走代理仅作为建议默认值回填（不锁定，用户可改）。
     *
     * @param userInitiated 由用户操作触发时为 true（会覆盖回填值）；初始化反查时为 false（只更新锁定状态）
     */
    private void applyAiPreset(AiPreset preset, boolean userInitiated) {
        currentAiPreset = preset;
        if (preset == null) {
            return;
        }
        if (userInitiated && !preset.isCustom()) {
            setRenderedFieldValue("ai.base-url", preset.baseUrl());
            setRenderedFieldValue("ai.model", preset.defaultModel());
            setRenderedFieldValue("ai.use-proxy", Boolean.toString(preset.defaultUseProxy()));
        }
        updateEnabledStates();
    }

    /**
     * 在 {@link #updateEnabledStates()} 末尾叠加预设锁定：选中非自定义预设时 base-url 保持禁用。
     */
    private void applyAiPresetLock() {
        if (aiPresetCombo == null || currentAiPreset == null || currentAiPreset.isCustom()) {
            return;
        }
        lockFieldByPreset("ai.base-url");
    }

    /** 启动后按已有 base-url 反查预设；未命中落到 custom。 */
    private void resolveAiPresetFromCurrentBaseUrl() {
        if (aiPresetCombo == null) {
            return;
        }
        FieldRenderer.RenderedField baseUrlField = renderedFields.get("ai.base-url");
        String baseUrl = baseUrlField == null ? "" : baseUrlField.getValue().get();
        AiPreset preset = aiPresetRegistry.findByBaseUrl(baseUrl).orElseGet(aiPresetRegistry::custom);
        updatingAiPresetCombo = true;
        try {
            aiPresetCombo.setSelectedItem(preset);
        } finally {
            updatingAiPresetCombo = false;
        }
        applyAiPreset(preset, false);
    }

    private void sendAiTest() {
        if (aiTestButton == null) {
            return;
        }
        aiTestButton.setEnabled(false);
        showNotice(message("gui.config.ai.test.notice.sending"));

        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("baseUrl", currentFieldValue("ai.base-url"));
        payload.put("apiKey", currentFieldValue("ai.api-key"));
        payload.put("model", currentFieldValue("ai.model"));
        payload.put("useProxy", Boolean.parseBoolean(currentFieldValue("ai.use-proxy")));

        SwingWorker<AiTestOutcome, Void> worker = new SwingWorker<>() {
            @Override
            protected AiTestOutcome doInBackground() {
                return postAiTest(payload);
            }

            @Override
            protected void done() {
                try {
                    AiTestOutcome outcome = get();
                    if (!outcome.reachable()) {
                        showNotice(message("gui.config.ai.test.notice.unreachable"));
                    } else if (outcome.success()) {
                        showNotice(message("gui.config.ai.test.notice.success",
                                outcome.reply() == null ? "" : outcome.reply()));
                    } else {
                        showNotice(message("gui.config.ai.test.notice.failed",
                                outcome.error() == null ? "" : outcome.error()));
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    showNotice(message("gui.config.ai.test.notice.unreachable"));
                } catch (ExecutionException ex) {
                    log.warn(logMessage("gui.config.ai.test.notice.failed", safeMessage(ex.getCause())),
                            ex.getCause());
                    showNotice(message("gui.config.ai.test.notice.failed", safeMessage(ex.getCause())));
                } finally {
                    aiTestButton.setEnabled(true);
                }
            }
        };
        worker.execute();
    }

    /**
     * 调用 {@code /api/gui/ai-test}；同时尝试 http / https，本地端点；连接不上返回 reachable=false。
     * 读超时放宽到 120s：大模型尤其是推理模型响应可能较慢。
     */
    private AiTestOutcome postAiTest(ObjectNode payload) {
        byte[] body;
        try {
            body = MAPPER.writeValueAsBytes(payload);
        } catch (Exception e) {
            return new AiTestOutcome(true, false, e.getMessage(), null);
        }
        String[] schemes = {"http", "https"};
        for (String scheme : schemes) {
            HttpURLConnection conn = null;
            try {
                URL url = new URI(scheme + "://localhost:" + serverPort + "/api/gui/ai-test").toURL();
                conn = (HttpURLConnection) url.openConnection();
                if (conn instanceof HttpsURLConnection https && TRUST_ALL_SSL != null) {
                    https.setSSLSocketFactory(TRUST_ALL_SSL.getSocketFactory());
                    https.setHostnameVerifier((host, session) -> true);
                }
                conn.setConnectTimeout(2000);
                conn.setReadTimeout(120_000);
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
                    String error = null;
                    String reply = null;
                    if (responseBody != null && !responseBody.isBlank()) {
                        var node = MAPPER.readTree(responseBody);
                        success = node.path("success").asBoolean(false);
                        error = node.path("error").isMissingNode() || node.path("error").isNull()
                                ? null : node.path("error").asText();
                        reply = node.path("reply").isMissingNode() || node.path("reply").isNull()
                                ? null : node.path("reply").asText();
                    }
                    return new AiTestOutcome(true, success, error, reply);
                }
                return new AiTestOutcome(true, false,
                        (responseBody == null || responseBody.isBlank())
                                ? ("HTTP " + status)
                                : responseBody, null);
            } catch (Exception ignored) {
                // try the other scheme
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
        }
        return new AiTestOutcome(false, false, null, null);
    }

    /** ai-test 异步结果。reachable=false 表示后端连接不上；success=true 仅当成功拿到模型回复。 */
    private record AiTestOutcome(boolean reachable, boolean success, String error, String reply) {
    }

    // ── 推送分组特殊控件 ──────────────────────────────────────────────────────────

    /** "测试推送" 按钮行：用当前表单值向所有已勾选启用的推送通道各发一条测试消息。 */
    /** 某个推送渠道卡片底部的「测试此渠道」按钮行。 */
    private JPanel buildPushChannelTestPanel(String channelId) {
        JButton button = new JButton(message("gui.config.push.test-current-button.label"));
        button.addActionListener(e -> sendPushChannelTest(channelId, button));
        return FieldRenderer.fieldPanel(
                message("gui.config.push.test-current-button.label") + message("gui.punctuation.colon"),
                button,
                null,
                message("gui.config.push.test-current-button.help"));
    }

    private JPanel buildPushChannelTestAllPanel(String channelId) {
        JButton button = new JButton(message("gui.config.push.test-all.button.label"));
        button.addActionListener(e -> sendPushChannelTestAll(channelId, button));
        return FieldRenderer.fieldPanel(
                message("gui.config.push.test-all.button.label") + message("gui.punctuation.colon"),
                button,
                null,
                message("gui.config.push.test-all.button.help"));
    }

    /** 用当前表单值向 {@code channelId} 一个渠道发送全部通知消息模板（无需先保存），便于预览各类通知呈现。 */
    private void sendPushChannelTestAll(String channelId, JButton button) {
        button.setEnabled(false);
        showNotice(message("gui.config.push.test-all.notice.sending"));

        ObjectNode payload = buildPushPayload(channelId);
        SwingWorker<PushTestOutcome, Void> worker = new SwingWorker<>() {
            @Override
            protected PushTestOutcome doInBackground() {
                return postPushTest(payload, "push-test-all");
            }

            @Override
            protected void done() {
                try {
                    PushTestOutcome outcome = get();
                    if (!outcome.reachable()) {
                        showNotice(message("gui.config.push.test.notice.unreachable"));
                    } else if (outcome.total() == 0
                            || (outcome.succeeded() == 0 && outcome.summary() != null
                                && outcome.summary().contains("SKIPPED"))) {
                        showNotice(message("gui.config.push.test-all.notice.skipped"));
                    } else if (outcome.success()) {
                        showNotice(message("gui.config.push.test-all.notice.success", outcome.total()));
                    } else {
                        showNotice(message("gui.config.push.test-all.notice.partial",
                                outcome.succeeded(), outcome.total(),
                                outcome.summary() == null ? "" : outcome.summary()));
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    showNotice(message("gui.config.push.test.notice.unreachable"));
                } catch (ExecutionException ex) {
                    log.warn(logMessage("gui.config.push.test-all.notice.partial",
                            0, 0, safeMessage(ex.getCause())), ex.getCause());
                    showNotice(message("gui.config.push.test.notice.unreachable"));
                } finally {
                    button.setEnabled(true);
                }
            }
        };
        worker.execute();
    }

    /** 用当前表单值仅测试 {@code channelId} 一个渠道（无需先保存）。 */
    private void sendPushChannelTest(String channelId, JButton button) {
        button.setEnabled(false);
        showNotice(message("gui.config.push.test.notice.sending"));

        ObjectNode payload = buildPushPayload(channelId);
        SwingWorker<PushTestOutcome, Void> worker = new SwingWorker<>() {
            @Override
            protected PushTestOutcome doInBackground() {
                return postPushTest(payload, "push-test");
            }

            @Override
            protected void done() {
                try {
                    PushTestOutcome outcome = get();
                    if (!outcome.reachable()) {
                        showNotice(message("gui.config.push.test.notice.unreachable"));
                    } else if (outcome.total() == 0) {
                        showNotice(message("gui.config.push.test.notice.none"));
                    } else if (outcome.success()) {
                        showNotice(message("gui.config.push.test.notice.current-success"));
                    } else if (outcome.summary() != null && outcome.summary().contains("SKIPPED")) {
                        showNotice(message("gui.config.push.test.notice.current-skipped"));
                    } else {
                        showNotice(message("gui.config.push.test.notice.current-failed",
                                outcome.summary() == null ? "" : outcome.summary()));
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    showNotice(message("gui.config.push.test.notice.unreachable"));
                } catch (ExecutionException ex) {
                    log.warn(logMessage("gui.config.push.test.notice.current-failed",
                            safeMessage(ex.getCause())), ex.getCause());
                    showNotice(message("gui.config.push.test.notice.unreachable"));
                } finally {
                    button.setEnabled(true);
                }
            }
        };
        worker.execute();
    }

    /**
     * 构造 push-test 请求体：每个渠道都带上当前表单值，但只有 {@code onlyChannelId} 的 enabled=true，
     * 从而只测试当前所选渠道（与各渠道自身的「启用」勾选无关）。
     */
    private ObjectNode buildPushPayload(String onlyChannelId) {
        ObjectNode payload = MAPPER.createObjectNode();
        ObjectNode bark = payload.putObject("bark");
        bark.put("enabled", "bark".equals(onlyChannelId));
        bark.put("server", currentFieldValue("push.bark.server"));
        bark.put("deviceKey", currentFieldValue("push.bark.device-key"));
        bark.put("sound", currentFieldValue("push.bark.sound"));
        bark.put("useProxy", Boolean.parseBoolean(currentFieldValue("push.bark.use-proxy")));
        ObjectNode dingtalk = payload.putObject("dingtalk");
        dingtalk.put("enabled", "dingtalk".equals(onlyChannelId));
        dingtalk.put("accessToken", currentFieldValue("push.dingtalk.access-token"));
        dingtalk.put("secret", currentFieldValue("push.dingtalk.secret"));
        dingtalk.put("useProxy", Boolean.parseBoolean(currentFieldValue("push.dingtalk.use-proxy")));
        ObjectNode telegram = payload.putObject("telegram");
        telegram.put("enabled", "telegram".equals(onlyChannelId));
        telegram.put("botToken", currentFieldValue("push.telegram.bot-token"));
        telegram.put("chatId", currentFieldValue("push.telegram.chat-id"));
        telegram.put("useProxy", Boolean.parseBoolean(currentFieldValue("push.telegram.use-proxy")));
        ObjectNode feishu = payload.putObject("feishu");
        feishu.put("enabled", "feishu".equals(onlyChannelId));
        feishu.put("webhookKey", currentFieldValue("push.feishu.webhook-key"));
        feishu.put("secret", currentFieldValue("push.feishu.secret"));
        feishu.put("useProxy", Boolean.parseBoolean(currentFieldValue("push.feishu.use-proxy")));
        ObjectNode wecom = payload.putObject("wecom");
        wecom.put("enabled", "wecom".equals(onlyChannelId));
        wecom.put("key", currentFieldValue("push.wecom.key"));
        wecom.put("useProxy", Boolean.parseBoolean(currentFieldValue("push.wecom.use-proxy")));
        ObjectNode pushplus = payload.putObject("pushplus");
        pushplus.put("enabled", "pushplus".equals(onlyChannelId));
        pushplus.put("token", currentFieldValue("push.pushplus.token"));
        pushplus.put("useProxy", Boolean.parseBoolean(currentFieldValue("push.pushplus.use-proxy")));
        ObjectNode serverchan = payload.putObject("serverchan");
        serverchan.put("enabled", "serverchan".equals(onlyChannelId));
        serverchan.put("sendKey", currentFieldValue("push.serverchan.send-key"));
        serverchan.put("useProxy", Boolean.parseBoolean(currentFieldValue("push.serverchan.use-proxy")));
        ObjectNode webhook = payload.putObject("webhook");
        webhook.put("enabled", "webhook".equals(onlyChannelId));
        webhook.put("url", currentFieldValue("push.webhook.url"));
        webhook.put("contentType", currentFieldValue("push.webhook.content-type"));
        webhook.put("bodyTemplate", currentFieldValue("push.webhook.body-template"));
        webhook.put("useProxy", Boolean.parseBoolean(currentFieldValue("push.webhook.use-proxy")));
        return payload;
    }

    /**
     * 调用 {@code /api/gui/<endpoint>}（{@code push-test} 单条测试 / {@code push-test-all} 全部模板）；
     * 同时尝试 http / https，本地端点；连接不上返回 reachable=false。单条测试沿用短读超时；全部模板会串行发送多条
     * webhook，读超时放宽到 10 分钟。
     */
    private PushTestOutcome postPushTest(ObjectNode payload, String endpoint) {
        byte[] body;
        try {
            body = MAPPER.writeValueAsBytes(payload);
        } catch (Exception e) {
            return new PushTestOutcome(true, false, 0, 0, e.getMessage());
        }
        String[] schemes = {"http", "https"};
        for (String scheme : schemes) {
            HttpURLConnection conn = null;
            try {
                URL url = new URI(scheme + "://localhost:" + serverPort + "/api/gui/" + endpoint).toURL();
                conn = (HttpURLConnection) url.openConnection();
                if (conn instanceof HttpsURLConnection https && TRUST_ALL_SSL != null) {
                    https.setSSLSocketFactory(TRUST_ALL_SSL.getSocketFactory());
                    https.setHostnameVerifier((host, session) -> true);
                }
                conn.setConnectTimeout(2000);
                conn.setReadTimeout(pushTestReadTimeout(endpoint));
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
                    String summary = null;
                    if (responseBody != null && !responseBody.isBlank()) {
                        var node = MAPPER.readTree(responseBody);
                        success = node.path("success").asBoolean(false);
                        total = node.path("total").asInt(0);
                        succeeded = node.path("succeeded").asInt(0);
                        var results = node.path("results");
                        if (results.isArray()) {
                            StringBuilder sb = new StringBuilder();
                            for (var item : results) {
                                if (!"OK".equals(item.path("status").asText(""))) {
                                    if (sb.length() > 0) sb.append("; ");
                                    sb.append(item.path("channel").asText("-"))
                                            .append(": ")
                                            .append(item.path("status").asText(""));
                                    String detail = item.path("detail").asText("");
                                    if (!detail.isBlank()) {
                                        sb.append(" (").append(detail).append(')');
                                    }
                                }
                            }
                            if (sb.length() > 0) summary = sb.toString();
                        }
                    }
                    return new PushTestOutcome(true, success, total, succeeded, summary);
                }
                return new PushTestOutcome(true, false, 0, 0,
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
        return new PushTestOutcome(false, false, 0, 0, null);
    }

    private static int pushTestReadTimeout(String endpoint) {
        return "push-test-all".equals(endpoint)
                ? PUSH_TEST_ALL_READ_TIMEOUT_MS
                : PUSH_TEST_READ_TIMEOUT_MS;
    }

    /** push-test 异步结果。reachable=false 表示后端连接不上；success=true 仅当全部通道都发送成功。 */
    private record PushTestOutcome(boolean reachable, boolean success, int total, int succeeded, String summary) {
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

            // 既有配置已开启调试模式时自动解锁复选框，便于用户在 GUI 中关闭它
            FieldRenderer.RenderedField debugField = renderedFields.get("debug.enabled");
            if (debugField != null && "true".equalsIgnoreCase(debugField.getValue().get())) {
                DebugUnlockState.unlock();
            }

            resolveMailPresetFromCurrentHost();
            resolveAiPresetFromCurrentBaseUrl();
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
        applyAiPresetLock();
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
        // debug.enabled 在未解锁时隐藏，但其值仍应原样保留（写空会清掉用户已有的调试开关）
        return isMaintenanceDayTimeKey(spec.key()) || "debug.enabled".equals(spec.key());
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
        resolveAiPresetFromCurrentBaseUrl();
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
