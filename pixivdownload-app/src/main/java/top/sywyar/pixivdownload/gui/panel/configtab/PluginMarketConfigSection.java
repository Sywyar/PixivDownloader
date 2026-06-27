package top.sywyar.pixivdownload.gui.panel.configtab;

import lombok.extern.slf4j.Slf4j;
import top.sywyar.pixivdownload.gui.GuiErrorDialog;
import top.sywyar.pixivdownload.gui.config.ConfigFieldRegistry;
import top.sywyar.pixivdownload.gui.config.ConfigFieldSpec;
import top.sywyar.pixivdownload.gui.config.PluginRepositoryConfigEditor;
import top.sywyar.pixivdownload.gui.config.RepositoryConfigEntry;
import top.sywyar.pixivdownload.gui.config.RepositoryConfigValidator;
import top.sywyar.pixivdownload.gui.i18n.GuiMessages;
import top.sywyar.pixivdownload.i18n.MessageBundles;
import top.sywyar.pixivdownload.plugin.catalog.repository.RepositoryProxyPolicy;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.Function;

/**
 * 「插件」分组标签页：在内置插件开关 + 受信 catalog 标量字段之外，提供<b>自定义仓库列表编辑器</b>（增 / 改 / 删 /
 * 上移 / 下移）与「打开 Web 插件市场」入口。Swing 只做<b>配置与打开入口</b>，<b>不</b>在桌面端复刻完整市场浏览 / 安装页。
 *
 * <p>仓库列表是列表型配置、无法靠 {@code ConfigFieldSpec} 字段网格渲染，故本 section 用 {@link PluginRepositoryConfigEditor}
 * 结构化读写 {@code plugin-catalog.repositories}（{@link #onValuesLoaded()} 读、{@link #onSave()} 写）。标量字段（插件开关、
 * catalog 主开关 / 官方仓库开关 / 全局超时 / 大小默认）仍由宿主 {@code ConfigPanel} 的字段网格统一加载 / 保存。
 *
 * <p>校验、URL / 协议、保留 id、重复 id、超时 / 大小、代理策略均为<b>前置提示</b>；后端
 * {@code PluginRepositoryRegistry} 与安装器的 HTTPS / SSRF / 重定向 / 大小 / sha256 / 签名校验仍为权威，本页不放宽。
 */
@Slf4j
public final class PluginMarketConfigSection implements ConfigSection {

    /** 市场页路径（核心壳的 admin-only Web 市场页；经共享 webUrlProvider 打开，不新增任何 GUI 写端点）。 */
    static final String MARKET_PAGE = "/plugin-market.html";
    private static final List<String> GLOBAL_DEFAULT_KEYS = List.of(
            "plugin-catalog.connect-timeout-ms", "plugin-catalog.read-timeout-ms",
            "plugin-catalog.max-manifest-bytes", "plugin-catalog.max-package-bytes");

    private final ConfigSectionContext ctx;
    private final Function<String, String> webUrlProvider;
    private final PluginRepositoryConfigEditor repoEditor;
    private final String group = ConfigFieldRegistry.groupPlugins();

    private final List<RepositoryConfigEntry> entries = new ArrayList<>();
    private final RepoTableModel tableModel = new RepoTableModel();
    /**
     * 仓库列表是否已<b>成功</b>从磁盘读出。读取失败时为 {@code false}：此时 {@link #entries} 的「空」是读取失败的产物，
     * <b>不是</b>用户「清空」意图，{@link #onSave()} 据此拒绝写回、绝不把空表覆盖磁盘上的原仓库块（防止读取失败后破坏配置）。
     */
    private boolean repositoriesLoaded;
    private JTable table;
    private JButton editButton;
    private JButton deleteButton;
    private JButton upButton;
    private JButton downButton;
    private JButton openMarketButton;

    public PluginMarketConfigSection(ConfigSectionContext ctx, Path configPath, Function<String, String> webUrlProvider) {
        this.ctx = ctx;
        this.webUrlProvider = webUrlProvider;
        this.repoEditor = new PluginRepositoryConfigEditor(configPath);
    }

    @Override
    public String group() {
        return group;
    }

    // ── 构建 ────────────────────────────────────────────────────────────────────

    @Override
    public JComponent build() {
        JPanel content = ctx.newContentPanel();
        content.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        List<ConfigFieldSpec> groupFields = ctx.allFields().stream()
                .filter(f -> group.equals(f.group()))
                .toList();
        List<ConfigFieldSpec> toggleFields = groupFields.stream()
                .filter(f -> f.key().startsWith("plugins."))
                .toList();
        List<ConfigFieldSpec> catalogToggles = groupFields.stream()
                .filter(f -> f.key().startsWith("plugin-catalog.") && !GLOBAL_DEFAULT_KEYS.contains(f.key()))
                .toList();
        List<ConfigFieldSpec> catalogGlobals = groupFields.stream()
                .filter(f -> GLOBAL_DEFAULT_KEYS.contains(f.key()))
                .toList();

        ctx.addFields(content, toggleFields);

        addHeading(content, "gui.config.market.section.heading");
        ctx.addFields(content, catalogToggles);

        addHeading(content, "gui.config.market.globals.heading");
        ctx.addFields(content, catalogGlobals);

        addHeading(content, "gui.config.market.repos.heading");
        addLeftAligned(content, riskBanner());
        addLeftAligned(content, buildTablePanel());
        addLeftAligned(content, buildButtonRow());

        addHeading(content, "gui.config.market.open.heading");
        addLeftAligned(content, buildOpenMarketRow());

        content.add(Box.createVerticalGlue());

        JScrollPane sp = new JScrollPane(content);
        sp.setBorder(null);
        sp.getVerticalScrollBar().setUnitIncrement(16);
        ctx.resetScrollToTopOnFirstShow(sp);
        return sp;
    }

    private void addHeading(JPanel content, String code) {
        JLabel heading = new JLabel(message(code));
        heading.setFont(heading.getFont().deriveFont(Font.BOLD, 13f));
        heading.setBorder(BorderFactory.createEmptyBorder(12, 2, 4, 2));
        addLeftAligned(content, heading);
        JSeparator separator = new JSeparator(SwingConstants.HORIZONTAL);
        separator.setMaximumSize(new Dimension(Integer.MAX_VALUE, 1));
        addLeftAligned(content, separator);
        content.add(Box.createVerticalStrut(4));
    }

    private static void addLeftAligned(JPanel content, JComponent component) {
        component.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(component);
        content.add(Box.createVerticalStrut(2));
    }

    /** 自定义仓库 + proxy-trusted 的显著风险提示横幅（始终可见）。 */
    private JComponent riskBanner() {
        JTextArea banner = new JTextArea(message("gui.config.market.repo.risk"));
        banner.setEditable(false);
        banner.setFocusable(false);
        banner.setLineWrap(true);
        banner.setWrapStyleWord(true);
        banner.setFont(UIManager.getFont("Label.font"));
        banner.setForeground(new Color(150, 80, 0));
        banner.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(200, 165, 70)),
                BorderFactory.createEmptyBorder(8, 12, 8, 12)));
        banner.setMaximumSize(new Dimension(Integer.MAX_VALUE, banner.getPreferredSize().height + 24));
        return banner;
    }

    private JComponent buildTablePanel() {
        table = new JTable(tableModel);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setRowHeight(table.getRowHeight() + 6);
        table.getTableHeader().setReorderingAllowed(false);
        table.getSelectionModel().addListSelectionListener(e -> updateButtonStates());
        table.getColumnModel().getColumn(0).setPreferredWidth(120);
        table.getColumnModel().getColumn(1).setPreferredWidth(60);
        table.getColumnModel().getColumn(2).setPreferredWidth(320);
        table.getColumnModel().getColumn(3).setPreferredWidth(120);

        JScrollPane scroll = new JScrollPane(table);
        scroll.setPreferredSize(new Dimension(640, 150));
        scroll.setMaximumSize(new Dimension(Integer.MAX_VALUE, 180));
        scroll.setAlignmentX(Component.LEFT_ALIGNMENT);
        return scroll;
    }

    private JComponent buildButtonRow() {
        JButton addButton = new JButton(message("gui.config.market.action.add"));
        addButton.addActionListener(e -> addEntry());
        editButton = new JButton(message("gui.config.market.action.edit"));
        editButton.addActionListener(e -> editEntry());
        deleteButton = new JButton(message("gui.config.market.action.delete"));
        deleteButton.addActionListener(e -> deleteEntry());
        upButton = new JButton(message("gui.config.market.action.up"));
        upButton.addActionListener(e -> moveEntry(-1));
        downButton = new JButton(message("gui.config.market.action.down"));
        downButton.addActionListener(e -> moveEntry(1));

        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        row.setOpaque(false);
        row.add(addButton);
        row.add(editButton);
        row.add(deleteButton);
        row.add(upButton);
        row.add(downButton);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
        updateButtonStates();
        return row;
    }

    private JComponent buildOpenMarketRow() {
        openMarketButton = new JButton(message("gui.config.market.action.open-web"));
        openMarketButton.addActionListener(e -> openMarketPage());

        JTextArea hint = new JTextArea(message("gui.config.market.open.hint"));
        hint.setEditable(false);
        hint.setFocusable(false);
        hint.setOpaque(false);
        hint.setLineWrap(true);
        hint.setWrapStyleWord(true);
        hint.setForeground(Color.GRAY);
        hint.setFont(UIManager.getFont("Label.font").deriveFont(Font.PLAIN, 11f));
        hint.setMaximumSize(new Dimension(Integer.MAX_VALUE, hint.getPreferredSize().height + 24));

        JPanel row = new JPanel();
        row.setOpaque(false);
        row.setLayout(new BoxLayout(row, BoxLayout.Y_AXIS));
        JPanel buttonRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        buttonRow.setOpaque(false);
        buttonRow.add(openMarketButton);
        buttonRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        hint.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.add(buttonRow);
        row.add(Box.createVerticalStrut(4));
        row.add(hint);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
        return row;
    }

    // ── 生命周期回调 ─────────────────────────────────────────────────────────────

    @Override
    public void onValuesLoaded() {
        IOException error = reloadRepositories();
        if (error != null) {
            log.warn(logMessage("gui.config.market.log.read-failed", safeMessage(error)));
            GuiErrorDialog.show(table, message("gui.dialog.error.title"),
                    message("gui.config.market.repo.read-failed", safeMessage(error)));
        }
        if (table != null) {
            tableModel.fireTableDataChanged();
            updateButtonStates();
        }
    }

    /**
     * Headless：把仓库列表读入 {@link #entries} 并更新 {@link #repositoriesLoaded} 加载状态。读取失败返回异常（成功返回
     * {@code null}）。失败时 {@code entries} 置空仅用于展示「读取失败」，由 {@link #repositoriesLoaded} 守住写回。
     */
    IOException reloadRepositories() {
        entries.clear();
        try {
            entries.addAll(repoEditor.read());
            repositoriesLoaded = true;
            return null;
        } catch (IOException e) {
            repositoriesLoaded = false;
            return e;
        }
    }

    @Override
    public boolean onSave() throws IOException {
        if (!repositoriesLoaded) {
            // 仓库列表尚未成功读出（读取失败且未重读成功）：空表是读取失败的产物、非用户「清空」意图，
            // 绝不写回以免抹掉磁盘上的原仓库块。抛出由宿主 ConfigPanel 统一弹错 + 记日志（标量字段照常已保存）。
            throw new IOException("plugin repositories not loaded; refusing to overwrite the repositories block to avoid data loss");
        }
        List<RepositoryConfigEntry> onDisk;
        try {
            onDisk = repoEditor.read();
        } catch (IOException e) {
            // 加载时可读、保存前重读却失败：同样拒绝写回（成功重读前不写），不冒险覆盖。
            throw new IOException("plugin repositories re-read failed before save; refusing to overwrite: " + safeMessage(e), e);
        }
        if (entries.equals(onDisk)) {
            return false;
        }
        repoEditor.write(entries);
        return true;
    }

    /** 仓库列表是否已成功从磁盘读出（供测试断言读取失败后的写回守卫状态）。 */
    boolean repositoriesLoaded() {
        return repositoriesLoaded;
    }

    @Override
    public void afterEnabledStates() {
        if (openMarketButton == null) {
            return;
        }
        // 仅当 plugin-market 插件启用时市场页才存在；catalog 主开关关闭仍可打开页看「未开启」诊断，故不据它禁用。
        boolean marketPluginEnabled = isMarketEntryEnabled(
                ctx.currentFieldValue("plugins.plugin-market.enabled"));
        openMarketButton.setEnabled(marketPluginEnabled);
        openMarketButton.setToolTipText(marketPluginEnabled
                ? message("gui.config.market.action.open-web.help")
                : message("gui.config.market.open-web.disabled"));
    }

    // ── 仓库列表操作 ─────────────────────────────────────────────────────────────

    private void addEntry() {
        RepositoryConfigEntry created = RepositoryFormDialog.show(table, null, otherEntries(-1));
        if (created != null) {
            entries.add(created);
            tableModel.fireTableDataChanged();
            selectRow(entries.size() - 1);
        }
    }

    private void editEntry() {
        int row = table.getSelectedRow();
        if (row < 0) {
            return;
        }
        RepositoryConfigEntry edited = RepositoryFormDialog.show(table, entries.get(row), otherEntries(row));
        if (edited != null) {
            entries.set(row, edited);
            tableModel.fireTableDataChanged();
            selectRow(row);
        }
    }

    private void deleteEntry() {
        int row = table.getSelectedRow();
        if (row < 0) {
            return;
        }
        int confirm = JOptionPane.showConfirmDialog(table,
                message("gui.config.market.repo.delete.confirm", entries.get(row).id()),
                message("gui.config.market.repo.delete.title"), JOptionPane.YES_NO_OPTION);
        if (confirm == JOptionPane.YES_OPTION) {
            entries.remove(row);
            tableModel.fireTableDataChanged();
            updateButtonStates();
        }
    }

    private void moveEntry(int delta) {
        int row = table.getSelectedRow();
        int target = row + delta;
        if (row < 0 || target < 0 || target >= entries.size()) {
            return;
        }
        RepositoryConfigEntry moved = entries.remove(row);
        entries.add(target, moved);
        tableModel.fireTableDataChanged();
        selectRow(target);
    }

    private List<RepositoryConfigEntry> otherEntries(int excludeIndex) {
        List<RepositoryConfigEntry> others = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            if (i != excludeIndex) {
                others.add(entries.get(i));
            }
        }
        return others;
    }

    private void selectRow(int row) {
        if (row >= 0 && row < entries.size()) {
            table.setRowSelectionInterval(row, row);
        }
        updateButtonStates();
    }

    private void updateButtonStates() {
        int row = table == null ? -1 : table.getSelectedRow();
        boolean hasSelection = row >= 0;
        if (editButton != null) {
            editButton.setEnabled(hasSelection);
            deleteButton.setEnabled(hasSelection);
            upButton.setEnabled(hasSelection && row > 0);
            downButton.setEnabled(hasSelection && row < entries.size() - 1);
        }
    }

    private void openMarketPage() {
        try {
            Desktop.getDesktop().browse(new URI(webUrlProvider.apply(MARKET_PAGE)));
        } catch (Exception e) {
            log.warn(logMessage("gui.status.log.open-browser-failed", MARKET_PAGE, safeMessage(e)), e);
            GuiErrorDialog.show(openMarketButton, message("gui.dialog.error.title"),
                    message("gui.error.open-browser", safeMessage(e)));
        }
    }

    // ── 表格模型 ────────────────────────────────────────────────────────────────

    private final class RepoTableModel extends AbstractTableModel {
        private final String[] columns = {
                message("gui.config.market.table.col.id"),
                message("gui.config.market.table.col.enabled"),
                message("gui.config.market.table.col.url"),
                message("gui.config.market.table.col.proxy")};

        @Override
        public int getRowCount() {
            return entries.size();
        }

        @Override
        public int getColumnCount() {
            return columns.length;
        }

        @Override
        public String getColumnName(int column) {
            return columns[column];
        }

        @Override
        public boolean isCellEditable(int row, int column) {
            return false;
        }

        @Override
        public Object getValueAt(int row, int column) {
            RepositoryConfigEntry entry = entries.get(row);
            return switch (column) {
                case 0 -> entry.id();
                case 1 -> message(entry.enabled() ? "gui.config.market.table.yes" : "gui.config.market.table.no");
                case 2 -> entry.manifestUrl();
                case 3 -> proxyPolicyLabel(entry.proxyPolicy());
                default -> "";
            };
        }
    }

    /**
     * 「打开 Web 插件市场」入口是否可用：仅当 {@code plugins.plugin-market.enabled} 未被显式置为 {@code false} 时可用
     * （插件默认启用、缺省视为启用）。禁用时按钮置灰并给出「页已停用」提示，<b>不</b>打开一个必然 404 的坏入口。
     */
    static boolean isMarketEntryEnabled(String pluginToggleValue) {
        return !"false".equalsIgnoreCase(pluginToggleValue == null ? "" : pluginToggleValue.trim());
    }

    static String proxyPolicyLabel(String configId) {
        for (RepositoryProxyPolicy policy : RepositoryProxyPolicy.values()) {
            if (policy.configId().equalsIgnoreCase(configId)) {
                return GuiMessages.get("gui.config.market.repo.proxy." + policy.configId());
            }
        }
        return configId;
    }

    /**
     * 把仓库的代理策略串映射为下拉项：已知策略（大小写不敏感）→ 对应 {@link RepositoryProxyPolicy}；空 →
     * {@link RepositoryProxyPolicy#DEFAULT}；<b>非空且不可识别 → 原样字符串</b>（在下拉里展示原值并保留，<b>绝不</b>
     * 静默映射为 {@code direct-strict}）。编辑已有仓库时未知策略据此被保留、不被悄悄降级。
     */
    static Object proxyComboSelection(String configId) {
        String raw = configId == null ? "" : configId.trim();
        if (raw.isEmpty()) {
            return RepositoryProxyPolicy.DEFAULT;
        }
        for (RepositoryProxyPolicy policy : RepositoryProxyPolicy.values()) {
            if (policy.configId().equalsIgnoreCase(raw)) {
                return policy;
            }
        }
        return raw;
    }

    /**
     * 把下拉项转回要持久化的策略串：{@link RepositoryProxyPolicy} → 其 {@code configId}；<b>未知字符串原样返回</b>
     * （交由 {@link RepositoryConfigValidator#validateProxyPolicy} 以 {@code proxy-policy-unknown} 阻止提交、不被悄悄改写）。
     */
    static String persistedProxyPolicy(Object comboSelection) {
        if (comboSelection instanceof RepositoryProxyPolicy policy) {
            return policy.configId();
        }
        return comboSelection == null ? RepositoryProxyPolicy.DEFAULT.configId() : comboSelection.toString();
    }

    // ── 编辑对话框 ──────────────────────────────────────────────────────────────

    /** 新增 / 编辑单个自定义仓库的模态对话框；OK 时前置校验，全部通过才返回条目，取消返回 null。 */
    private static final class RepositoryFormDialog extends JDialog {

        private final List<RepositoryConfigEntry> others;
        private final RepositoryConfigEntry existing;
        private RepositoryConfigEntry result;

        private final JTextField idField = new JTextField(24);
        private final JTextField urlField = new JTextField(24);
        private final JCheckBox enabledBox = new JCheckBox();
        // Object 元素：除固定枚举外，编辑已有仓库时还可承载一个未知策略原值（字符串），以便展示并保留、不静默降级。
        private final JComboBox<Object> proxyCombo =
                new JComboBox<>(RepositoryProxyPolicy.values());
        private final JTextField connectField = new JTextField(10);
        private final JTextField readField = new JTextField(10);
        private final JTextField manifestBytesField = new JTextField(10);
        private final JTextField packageBytesField = new JTextField(10);
        private final JCheckBox allowRedirectsBox = new JCheckBox(
                message("gui.config.market.repo.custom.allow-redirects"));
        private final JCheckBox strictHttpsBox = new JCheckBox(
                message("gui.config.market.repo.custom.strict-https"), true);
        private final JCheckBox allowNonPublicBox = new JCheckBox(
                message("gui.config.market.repo.custom.allow-non-public"));
        private final JCheckBox useProxyBox = new JCheckBox(
                message("gui.config.market.repo.custom.use-proxy"));
        private final JPanel customOptions = new JPanel();
        private JLabel customOptionsLabel;
        private final JTextArea policyRisk = new JTextArea();
        private final JLabel errorLabel = new JLabel(" ");

        static RepositoryConfigEntry show(Component parent, RepositoryConfigEntry existing,
                                          List<RepositoryConfigEntry> others) {
            Window owner = parent == null ? null : SwingUtilities.getWindowAncestor(parent);
            RepositoryFormDialog dialog = new RepositoryFormDialog(owner, existing, others);
            dialog.setVisible(true);
            return dialog.result;
        }

        private RepositoryFormDialog(Window owner, RepositoryConfigEntry existing,
                                     List<RepositoryConfigEntry> others) {
            super(owner, message(existing == null
                    ? "gui.config.market.repo.dialog.add.title"
                    : "gui.config.market.repo.dialog.edit.title"), ModalityType.APPLICATION_MODAL);
            this.existing = existing;
            this.others = others;
            buildForm();
            populate(existing);
            pack();
            setMinimumSize(new Dimension(Math.max(520, getWidth()), getHeight()));
            setLocationRelativeTo(owner);
        }

        private void buildForm() {
            JPanel form = new JPanel(new GridBagLayout());
            form.setBorder(BorderFactory.createEmptyBorder(16, 16, 8, 16));
            GridBagConstraints gbc = new GridBagConstraints();
            gbc.insets = new Insets(4, 4, 4, 4);
            gbc.anchor = GridBagConstraints.WEST;
            gbc.fill = GridBagConstraints.HORIZONTAL;
            int[] rowIndex = {0};

            addFormRow(form, gbc, rowIndex, "gui.config.market.repo.field.id", idField);
            addFormRow(form, gbc, rowIndex, "gui.config.market.repo.field.url", urlField);
            addFormRow(form, gbc, rowIndex, "gui.config.market.repo.field.enabled", enabledBox);

            proxyCombo.setRenderer(new DefaultListCellRenderer() {
                @Override
                public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                              boolean isSelected, boolean cellHasFocus) {
                    JLabel label = (JLabel) super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                    if (value instanceof RepositoryProxyPolicy policy) {
                        label.setText(message("gui.config.market.repo.proxy." + policy.configId()));
                    } else if (value != null) {
                        // 未知（来自旧 / 手写配置的）策略原值：展示原值 + 「不受支持」标注，提示用户改选受支持策略。
                        label.setText(message("gui.config.market.repo.proxy.unknown-display", value));
                    }
                    return label;
                }
            });
            proxyCombo.addActionListener(e -> refreshPolicyOptions());
            addFormRow(form, gbc, rowIndex, "gui.config.market.repo.field.proxy", proxyCombo);

            customOptions.setOpaque(false);
            customOptions.setLayout(new BoxLayout(customOptions, BoxLayout.Y_AXIS));
            customOptions.add(allowRedirectsBox);
            customOptions.add(strictHttpsBox);
            customOptions.add(allowNonPublicBox);
            customOptions.add(useProxyBox);
            customOptionsLabel = addFormRow(
                    form, gbc, rowIndex, "gui.config.market.repo.field.custom-options", customOptions);

            policyRisk.setEditable(false);
            policyRisk.setFocusable(false);
            policyRisk.setOpaque(false);
            policyRisk.setLineWrap(true);
            policyRisk.setWrapStyleWord(true);
            policyRisk.setForeground(new Color(150, 80, 0));
            policyRisk.setFont(UIManager.getFont("Label.font").deriveFont(Font.PLAIN, 11f));
            gbc.gridx = 1;
            gbc.gridy = rowIndex[0]++;
            form.add(policyRisk, gbc);

            addFormRow(form, gbc, rowIndex, "gui.config.market.repo.field.connect-timeout", connectField);
            addFormRow(form, gbc, rowIndex, "gui.config.market.repo.field.read-timeout", readField);
            addFormRow(form, gbc, rowIndex, "gui.config.market.repo.field.max-manifest", manifestBytesField);
            addFormRow(form, gbc, rowIndex, "gui.config.market.repo.field.max-package", packageBytesField);

            JLabel overrideHint = new JLabel(message("gui.config.market.repo.override.hint"));
            overrideHint.setForeground(Color.GRAY);
            overrideHint.setFont(overrideHint.getFont().deriveFont(Font.PLAIN, 11f));
            gbc.gridx = 1;
            gbc.gridy = rowIndex[0]++;
            form.add(overrideHint, gbc);

            errorLabel.setForeground(new Color(180, 40, 40));
            gbc.gridx = 0;
            gbc.gridy = rowIndex[0]++;
            gbc.gridwidth = 2;
            form.add(errorLabel, gbc);

            JButton ok = new JButton(message("gui.config.market.repo.dialog.ok"));
            ok.addActionListener(e -> onOk());
            JButton cancel = new JButton(message("gui.config.market.repo.dialog.cancel"));
            cancel.addActionListener(e -> dispose());
            JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 4));
            buttons.add(cancel);
            buttons.add(ok);

            getContentPane().setLayout(new BorderLayout());
            getContentPane().add(form, BorderLayout.CENTER);
            getContentPane().add(buttons, BorderLayout.SOUTH);
            getRootPane().setDefaultButton(ok);
        }

        private JLabel addFormRow(JPanel form, GridBagConstraints gbc, int[] rowIndex,
                                  String labelKey, JComponent field) {
            JLabel label = new JLabel(message(labelKey) + message("gui.punctuation.colon"));
            gbc.gridx = 0;
            gbc.gridy = rowIndex[0];
            gbc.weightx = 0;
            form.add(label, gbc);
            gbc.gridx = 1;
            gbc.weightx = 1;
            form.add(field, gbc);
            rowIndex[0]++;
            return label;
        }

        private void populate(RepositoryConfigEntry entry) {
            if (entry == null) {
                enabledBox.setSelected(true);
                proxyCombo.setSelectedItem(RepositoryProxyPolicy.DIRECT_STRICT);
            } else {
                idField.setText(entry.id());
                urlField.setText(entry.manifestUrl());
                enabledBox.setSelected(entry.enabled());
                selectPolicy(entry.proxyPolicy());
                allowRedirectsBox.setSelected(entry.allowRedirects());
                strictHttpsBox.setSelected(entry.strictHttps());
                allowNonPublicBox.setSelected(entry.allowNonPublicAddresses());
                useProxyBox.setSelected(entry.useProxy());
                connectField.setText(overrideText(entry.connectTimeoutMs()));
                readField.setText(overrideText(entry.readTimeoutMs()));
                manifestBytesField.setText(overrideText(entry.maxManifestBytes()));
                packageBytesField.setText(overrideText(entry.maxPackageBytes()));
            }
            refreshPolicyOptions();
        }

        private void refreshPolicyOptions() {
            Object selection = proxyCombo.getSelectedItem();
            boolean custom = selection == RepositoryProxyPolicy.CUSTOM;
            boolean showRisk = custom || selection == RepositoryProxyPolicy.PROXY_TRUSTED;
            customOptionsLabel.setVisible(custom);
            customOptions.setVisible(custom);
            policyRisk.setVisible(showRisk);
            policyRisk.setText(custom
                    ? message("gui.config.market.repo.custom.risk")
                    : message("gui.config.market.repo.proxy-trusted.risk"));
            // 对话框已 pack 定型，显隐高级选项 / 风险提示后需重新布局并适配高度，否则新增行可能被裁掉。
            revalidate();
            repaint();
            if (isShowing()) {
                pack();
                setMinimumSize(new Dimension(Math.max(520, getWidth()), getHeight()));
            }
        }

        private void onOk() {
            String id = idField.getText().trim();
            String url = urlField.getText().trim();
            // 下拉项可能是固定枚举，也可能是被保留的未知策略原值；后者会在前置校验里以 proxy-policy-unknown 阻止提交，
            // 既不静默降级为 direct-strict、也不在用户编辑其它字段时被悄悄改写。
            String policyId = persistedProxyPolicy(proxyCombo.getSelectedItem());

            String error = firstError(id, url, policyId);
            if (error != null) {
                errorLabel.setText(message(error));
                return;
            }
            result = new RepositoryConfigEntry(id,
                    existing == null ? "" : existing.displayNameKey(),
                    url, enabledBox.isSelected(), policyId,
                    allowRedirectsBox.isSelected(), strictHttpsBox.isSelected(),
                    allowNonPublicBox.isSelected(), useProxyBox.isSelected(),
                    RepositoryConfigValidator.parseOverride(connectField.getText()),
                    RepositoryConfigValidator.parseOverride(readField.getText()),
                    RepositoryConfigValidator.parseOverride(manifestBytesField.getText()),
                    RepositoryConfigValidator.parseOverride(packageBytesField.getText()),
                    existing == null ? new LinkedHashMap<>() : existing.extraFields());
            dispose();
        }

        private String firstError(String id, String url, String policyId) {
            String idError = RepositoryConfigValidator.validateId(id, others);
            if (idError != null) {
                return idError;
            }
            boolean strictHttps = !RepositoryProxyPolicy.CUSTOM.configId().equalsIgnoreCase(policyId)
                    || strictHttpsBox.isSelected();
            String urlError = RepositoryConfigValidator.validateManifestUrl(url, strictHttps);
            if (urlError != null) {
                return urlError;
            }
            String proxyError = RepositoryConfigValidator.validateProxyPolicy(policyId);
            if (proxyError != null) {
                return proxyError;
            }
            String connectError = RepositoryConfigValidator.validateTimeoutOverride(connectField.getText());
            if (connectError != null) {
                return connectError;
            }
            String readError = RepositoryConfigValidator.validateTimeoutOverride(readField.getText());
            if (readError != null) {
                return readError;
            }
            String manifestError = RepositoryConfigValidator.validateSizeOverride(manifestBytesField.getText());
            if (manifestError != null) {
                return manifestError;
            }
            return RepositoryConfigValidator.validateSizeOverride(packageBytesField.getText());
        }

        /**
         * 把已有仓库的代理策略落到下拉：已知策略直接选中；<b>未知策略</b>作为可见特殊项插入下拉首位并选中——展示原值、
         * 保留，OK 时由前置校验以 {@code proxy-policy-unknown} 阻止，<b>不</b>静默映射为 {@code direct-strict}。
         */
        private void selectPolicy(String configId) {
            Object selection = proxyComboSelection(configId);
            if (!(selection instanceof RepositoryProxyPolicy)) {
                proxyCombo.insertItemAt(selection, 0);
            }
            proxyCombo.setSelectedItem(selection);
        }

        private static String overrideText(long value) {
            return value > 0 ? Long.toString(value) : "";
        }
    }

    // ── 工具 ────────────────────────────────────────────────────────────────────

    private static String safeMessage(Throwable t) {
        if (t == null) {
            return "unknown";
        }
        return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
    }

    private static String message(String code, Object... args) {
        return GuiMessages.get(code, args);
    }

    private static String logMessage(String code, Object... args) {
        return MessageBundles.get(code, args);
    }
}
