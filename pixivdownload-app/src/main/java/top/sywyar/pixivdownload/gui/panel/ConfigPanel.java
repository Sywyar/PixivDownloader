package top.sywyar.pixivdownload.gui.panel;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.gui.AutoStartManager;
import top.sywyar.pixivdownload.gui.DebugUnlockState;
import top.sywyar.pixivdownload.gui.GuiErrorDialog;
import top.sywyar.pixivdownload.gui.config.*;
import top.sywyar.pixivdownload.gui.i18n.GuiMessages;
import top.sywyar.pixivdownload.gui.panel.configtab.ConfigSection;
import top.sywyar.pixivdownload.gui.panel.configtab.ConfigSectionContext;
import top.sywyar.pixivdownload.gui.panel.configtab.GuiConfigTestClient;
import top.sywyar.pixivdownload.gui.panel.configtab.PluginMarketConfigSection;
import top.sywyar.pixivdownload.i18n.MessageBundles;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.Desktop;
import java.awt.event.HierarchyEvent;
import java.awt.event.HierarchyListener;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;

/**
 * "配置" 标签页：Schema 驱动的字段渲染，按 group 分为子标签页。保存时调用 ConfigFileEditor 行内替换，
 * 保留注释和格式。
 * <p>
 * 普通分组「字段平铺」由 {@code ConfigFieldRegistry} 的 {@link ConfigFieldSpec} 声明式渲染；带自定义控件 /
 * 异步测试的特殊分组拆成 {@code gui.panel.configtab} 下的 {@link ConfigSection} 实现，
 * 本类作为 {@link ConfigSectionContext} 向它们提供共享的字段注册表、取值 / 赋值、提示与测试客户端，
 * 而加载 / 保存 / 校验 / 可见性重算仍集中在这里。
 */
@Slf4j
public class ConfigPanel extends JPanel implements ConfigSectionContext {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String DEFAULT_ROOT_FOLDER = RuntimeFiles.DEFAULT_DOWNLOAD_ROOT;
    private static final String SOLO_MODE = "solo";
    private static final List<String> MAINTENANCE_WEEKDAYS = List.of(
            "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday");

    private final Path configPath;
    private final int serverPort;
    private final ConfigFileEditor editor;
    private final String currentMode;
    /** Web 页 URL 构造器（scheme 按 SSL、主机名按域名推导），供「打开 Web 插件市场」入口复用。 */
    private final Function<String, String> webUrlProvider;

    /** 字段元数据快照（按当前 locale），构造时从 ConfigFieldRegistry 拉取一次。 */
    private final List<ConfigFieldSpec> allFields;
    private final List<String> groups;
    private final String serverGroup;
    private final String multiModeGroup;
    private final String maintenanceGroup;

    /** 本地后端测试 / 热重载端点的统一 HTTP 客户端（供各 section 与热重载复用）。 */
    private final GuiConfigTestClient testClient;
    /** 特殊分组（自带控件 / 异步测试 / 预设联动）的可插拔实现。 */
    private List<ConfigSection> sections = List.of();
    /** group 名 → 负责该分组的 section（普通分组无对应项，走 buildGroupPanel）。 */
    private Map<String, ConfigSection> sectionsByGroup = Map.of();

    /** key → 渲染后的字段（含取值/赋值方法） */
    private final Map<String, FieldRenderer.RenderedField> renderedFields = new LinkedHashMap<>();

    /** 提示条（保存后显示） */
    private final JLabel noticeBar = new JLabel(" ");

    /** 调试模式解锁监听：彩蛋触发后在 EDT 上刷新可见性并提示。面板加入/移出窗口时随之注册/注销，避免泄漏。 */
    private final Runnable debugUnlockListener = () -> SwingUtilities.invokeLater(this::onDebugUnlocked);

    private JCheckBox autoStartCheckBox;
    private boolean autoStartSupported;
    private boolean updatingAutoStartCheckBox;

    public ConfigPanel(Path configPath, int serverPort, Function<String, String> webUrlProvider) {
        this(configPath, serverPort, webUrlProvider, ConfigFieldRegistry.snapshot());
    }

    public ConfigPanel(Path configPath, int serverPort, Function<String, String> webUrlProvider,
                       ConfigFieldSnapshot fieldSnapshot) {
        this.configPath = configPath;
        this.serverPort = serverPort;
        this.webUrlProvider = webUrlProvider;
        this.editor = new ConfigFileEditor(configPath);
        this.currentMode = resolveCurrentMode();
        ConfigFieldSnapshot snapshot = fieldSnapshot == null ? ConfigFieldRegistry.snapshot() : fieldSnapshot;
        this.allFields = snapshot.fields();
        this.groups = snapshot.groups();
        this.serverGroup = groups.isEmpty() ? "" : groups.get(0);
        this.multiModeGroup = ConfigFieldRegistry.groupMultiMode();
        this.maintenanceGroup = ConfigFieldRegistry.groupMaintenance();
        this.testClient = new GuiConfigTestClient(serverPort);
        logContributionDiagnostics(snapshot.diagnostics());
        buildUi();
        loadCurrentValues();
        checkFieldDrift();
    }

    private void logContributionDiagnostics(List<GuiConfigContributionDiagnostic> diagnostics) {
        for (GuiConfigContributionDiagnostic diagnostic : diagnostics) {
            log.warn(logMessage("gui.config.log.plugin-field-diagnostic",
                    diagnostic.pluginId(),
                    diagnostic.key() == null ? "-" : diagnostic.key(),
                    diagnostic.message()));
        }
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

    /**
     * 从磁盘重新加载配置值并刷新控件，用于外部改写了 config.yaml 之后
     * （如「迁移下载目录」同步了 {@code download.root-folder}）让配置页立即反映新值。
     * 必须在 EDT 上调用。
     */
    public void reloadFromDisk() {
        loadCurrentValues();
        updateEnabledStates();
    }

    // ── UI 构建 ──────────────────────────────────────────────────────────────────

    private void buildUi() {
        setLayout(new BorderLayout(0, 0));

        // 特殊分组（自带控件 / 异步测试 / 预设联动 / 列表编辑器）的可插拔实现；普通分组仍走 buildGroupPanel 声明式渲染。
        sections = List.of(
                new PluginMarketConfigSection(this, configPath, webUrlProvider));
        sectionsByGroup = new LinkedHashMap<>();
        for (ConfigSection section : sections) {
            sectionsByGroup.put(section.group(), section);
        }

        // 子标签页（按 group）
        JTabbedPane tabs = new JTabbedPane(JTabbedPane.TOP);
        for (String group : groups) {
            if (shouldHideGroup(group)) {
                continue;
            }
            ConfigSection section = sectionsByGroup.get(group);
            tabs.addTab(group, section != null ? section.build() : buildGroupPanel(group));
        }
        add(tabs, BorderLayout.CENTER);

        // 底部面板：提示条 + 按钮
        add(buildBottomPanel(), BorderLayout.SOUTH);
    }

    private JComponent buildGroupPanel(String group) {
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
            registerField(spec, rf);
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
        applyScrollTopReset(sp);
        return sp;
    }

    /**
     * 让滚动面板在首次真正显示时把视口重置回顶部，随后摘除监听器不再干预用户滚动。
     * <p>
     * AI / 邮件等分组在 init 阶段锁定字段（如 ai.base-url / mail.host）会触发
     * {@code scrollRectToVisible}，使视口偏离 (0,0)；若不修正，首次切到该标签页 / 卡片时
     * 字段区域会直接停在底部而非从头显示。
     */
    private static void applyScrollTopReset(JScrollPane sp) {
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
            registerField(entry.getKey(), rf);
        }

        return panel;
    }

    private static Optional<ConfigFieldSpec> findField(List<ConfigFieldSpec> fields, String key) {
        return fields.stream()
                .filter(field -> key.equals(field.key()))
                .findFirst();
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

            for (ConfigSection s : sections) {
                s.onValuesLoaded();
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
    @Override
    public void updateEnabledStates() {
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
        for (ConfigSection s : sections) {
            s.afterEnabledStates();
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

        // 下载根目录原本以符号根（跟随软件目录）方式记录时，改目录前必须先把旧记录固定为绝对路径，
        // 否则旧作品会随新配置解析到错误位置。用户取消或固定失败 → 整个保存中止。
        if (changedKeys.contains("download.root-folder")
                && !confirmAndPinSymbolicRoot(before.get("download.root-folder"),
                        values.get("download.root-folder"))) {
            return;
        }

        try {
            editor.writeAll(values);
            // 各 section 持久化自有的非字段网格状态（如插件市场仓库列表）；任一写入改动均为需重启项。
            boolean sectionRestartChange = false;
            for (ConfigSection s : sections) {
                if (s.onSave()) {
                    sectionRestartChange = true;
                }
            }
            boolean restartRequired = hasRestartRequiredChanges || sectionRestartChange;
            log.info(logMessage("gui.config.log.saved", configPath));
            if (hasHotReloadChanges) {
                showNotice(message("gui.config.notice.hot-reloading"));
                reloadHotConfigAsync(restartRequired);
            } else {
                showNotice(message(restartRequired
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

    /**
     * 下载根目录变更前的符号根处理：原配置为相对路径（旧记录以 {@code {0}} 跟随软件目录）且数据库确有
     * {@code {0}} 引用时，弹窗告知「改目录会把旧作品固定为绝对路径、失去随软件搬迁的能力」；
     * 确定 → 调后端 pin 端点把 {@code {0}} 固定为当前解析路径后放行保存；取消 / 固定失败 → 返回 false 中止保存。
     * 后端不可达时放行保存（无法转换，孤儿 {@code {0}} 由启动检查兜底提示修复）。
     */
    private boolean confirmAndPinSymbolicRoot(String oldValue, String newValue) {
        String oldRoot = RuntimeFiles.normalizeRootFolder(oldValue);
        try {
            if (Path.of(oldRoot).isAbsolute()) {
                return true; // 旧配置已是绝对路径，符号根未启用
            }
            // 解析结果未变（如「pixiv-download」与「./pixiv-download」）→ 与 {0} 无关
            Path oldAbs = Path.of(oldRoot).toAbsolutePath().normalize();
            Path newAbs = Path.of(RuntimeFiles.normalizeRootFolder(newValue)).toAbsolutePath().normalize();
            if (oldAbs.equals(newAbs)) {
                return true;
            }
        } catch (Exception e) {
            return true; // 路径无法解析时不拦截，交由后端校验
        }

        GuiConfigTestClient.Response resp = testClient.getJson("path-prefixes", 10_000);
        if (!resp.reachable() || !resp.is2xx() || resp.body() == null || resp.body().isEmpty()) {
            log.warn(logMessage("gui.config.log.symbolic-pin.status-unavailable"));
            return true; // 后端不可达：先保存，孤儿记录由下次启动检查提示修复
        }
        boolean referenced;
        String currentResolved = null;
        try {
            var node = MAPPER.readTree(resp.body());
            referenced = node.path("symbolicReferenced").asBoolean(false);
            for (var p : node.path("prefixes")) {
                if (p.path("symbolic").asBoolean(false)) {
                    currentResolved = p.path("path").asText(null);
                    break;
                }
            }
        } catch (Exception e) {
            log.warn(logMessage("gui.config.log.symbolic-pin.status-unavailable"));
            return true;
        }
        if (!referenced || currentResolved == null || currentResolved.isEmpty()) {
            return true; // 没有 {0} 记录，改根目录无后顾之忧
        }

        int answer = JOptionPane.showConfirmDialog(this,
                message("gui.config.symbolic-pin.message", currentResolved),
                message("gui.config.symbolic-pin.title"),
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
        if (answer != JOptionPane.OK_OPTION) {
            showNotice(message("gui.config.symbolic-pin.cancelled"));
            return false;
        }

        try {
            byte[] body = MAPPER.writeValueAsBytes(Map.of("path", currentResolved));
            GuiConfigTestClient.Response pin = testClient.postJson("path-prefixes/pin", body, 15_000);
            if (pin.reachable() && pin.is2xx() && pin.body() != null
                    && MAPPER.readTree(pin.body()).path("success").asBoolean(false)) {
                return true;
            }
            log.warn(logMessage("gui.config.log.symbolic-pin.failed",
                    pin.reachable() ? String.valueOf(pin.status()) : "unreachable"));
        } catch (Exception e) {
            log.warn(logMessage("gui.config.log.symbolic-pin.failed", e.getMessage()));
        }
        GuiErrorDialog.show(this, message("gui.dialog.error.title"),
                message("gui.config.symbolic-pin.failed"));
        return false;
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
                return testClient.post("config/reload", 5000);
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
        for (ConfigSection s : sections) {
            s.onValuesLoaded();
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
     * 对比字段快照与 config.yaml 中实际存在的 key，漂移时打日志警告。
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

    private void showNoticeInternal(String msg) {
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

    // ── ConfigSectionContext 实现 ─────────────────────────────────────────────────

    @Override
    public List<ConfigFieldSpec> allFields() {
        return allFields;
    }

    @Override
    public ConfigFieldSpec findSpec(String key) {
        return allFields.stream().filter(f -> key.equals(f.key())).findFirst().orElse(null);
    }

    @Override
    public void registerField(ConfigFieldSpec spec, FieldRenderer.RenderedField rf) {
        renderedFields.put(spec.key(), rf);
        attachChangeListener(spec.key(), rf.control());
    }

    @Override
    public void addFields(JPanel content, List<ConfigFieldSpec> specs) {
        for (ConfigFieldSpec spec : specs) {
            FieldRenderer.RenderedField rf = FieldRenderer.render(spec);
            registerField(spec, rf);
            rf.panel().setAlignmentX(Component.LEFT_ALIGNMENT);
            content.add(rf.panel());
            content.add(Box.createVerticalStrut(2));
        }
    }

    @Override
    public String currentFieldValue(String key) {
        FieldRenderer.RenderedField rf = renderedFields.get(key);
        return rf == null ? "" : rf.getValue().get();
    }

    @Override
    public void setFieldValue(String key, String value) {
        FieldRenderer.RenderedField rf = renderedFields.get(key);
        if (rf != null) {
            rf.setValue().accept(value);
        }
    }

    @Override
    public void lockField(String key) {
        FieldRenderer.RenderedField rf = renderedFields.get(key);
        if (rf == null || !rf.panel().isVisible()) {
            return;
        }
        setControlEnabled(rf.panel(), false);
    }

    @Override
    public JPanel newContentPanel() {
        return new GroupContentPanel();
    }

    @Override
    public void resetScrollToTopOnFirstShow(JScrollPane sp) {
        applyScrollTopReset(sp);
    }

    @Override
    public JLabel effectLabel(boolean requiresRestart) {
        return buildEffectLabel(requiresRestart);
    }

    @Override
    public JTextArea hiddenValidationError() {
        return createHiddenValidationError();
    }

    @Override
    public void showNotice(String msg) {
        showNoticeInternal(msg);
    }

    @Override
    public GuiConfigTestClient testClient() {
        return testClient;
    }
}
