package top.sywyar.pixivdownload.gui.panel;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import top.sywyar.pixivdownload.config.PluginCredentialStore;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.gui.AutoStartManager;
import top.sywyar.pixivdownload.gui.BackendLifecycleManager;
import top.sywyar.pixivdownload.gui.DebugUnlockState;
import top.sywyar.pixivdownload.gui.GuiErrorDialog;
import top.sywyar.pixivdownload.gui.config.*;
import top.sywyar.pixivdownload.gui.i18n.GuiMessages;
import top.sywyar.pixivdownload.gui.panel.configtab.ConfigSection;
import top.sywyar.pixivdownload.gui.panel.configtab.ConfigSectionContext;
import top.sywyar.pixivdownload.gui.panel.configtab.ConfigFieldRows;
import top.sywyar.pixivdownload.gui.panel.configtab.ConfigMenuTree;
import top.sywyar.pixivdownload.gui.panel.configtab.GuiConfigSectionResolver;
import top.sywyar.pixivdownload.gui.panel.configtab.GuiConfigTestClient;
import top.sywyar.pixivdownload.i18n.MessageBundles;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigGroups;

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
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/**
 * "配置" 标签页：Schema 驱动的字段渲染，按 group 分为子标签页。宿主字段保存到 config.yaml，
 * 普通插件字段保存到各自的 config/plugins/<id>.properties，插件凭证保存到 owner-scoped 专用存储。
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
    private final PluginCredentialStore credentialStore = new PluginCredentialStore();
    private final Map<String, PropertiesConfigFileEditor> pluginConfigEditors = new HashMap<>();
    private final String currentMode;
    /** Web 页 URL 构造器（scheme 按 SSL、主机名按域名推导），供「打开 Web 插件市场」入口复用。 */
    private final Function<String, String> webUrlProvider;
    private final Runnable onLocaleChanged;
    private final BooleanSupplier languageChangeBlocked;
    private final BooleanSupplier restartConfirmation;
    private final BooleanSupplier backendRestarter;

    /** 字段元数据快照（按当前 locale），构造时从 ConfigFieldRegistry 拉取一次。 */
    private final List<ConfigFieldSpec> allFields;
    private final List<GuiConfigSectionSpec> sectionContributions;
    private final List<ConfigGroupSpec> groupSpecs;
    private final String serverGroup;
    private final String multiModeGroup;
    private final String maintenanceGroup;

    /** 本地后端测试 / 热重载端点的统一 HTTP 客户端（供各 section 与热重载复用）。 */
    private final GuiConfigTestClient testClient;
    /** 特殊分组（自带控件 / 异步测试 / 预设联动）的可插拔实现。 */
    private List<ConfigSection> sections = List.of();

    /** key → 渲染后的字段（含取值/赋值方法） */
    private final Map<String, FieldRenderer.RenderedField> renderedFields = new LinkedHashMap<>();

    /** 提示条（保存后显示） */
    private final JLabel noticeBar = new JLabel(" ");

    /** 配置导航只遍历这棵显式树，避免把 section 内部的切换控件误当成菜单。 */
    private final JTabbedPane topTabs = new JTabbedPane(JTabbedPane.TOP);
    private final Map<JTabbedPane, List<ConfigMenuTree.Node<JComponent>>> renderedMenuNodes =
            new IdentityHashMap<>();
    private ConfigMenuTree<JComponent> configMenuTree = new ConfigMenuTree<>(List.of());
    private boolean expandAllConfigMenus;

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
        this(configPath, serverPort, webUrlProvider, fieldSnapshot, null, null);
    }

    public ConfigPanel(Path configPath, int serverPort, Function<String, String> webUrlProvider,
                       ConfigFieldSnapshot fieldSnapshot,
                       Runnable onLocaleChanged,
                       BooleanSupplier languageChangeBlocked) {
        this(configPath, serverPort, webUrlProvider, fieldSnapshot, onLocaleChanged, languageChangeBlocked,
                null, BackendLifecycleManager::restartAsync);
    }

    ConfigPanel(Path configPath, int serverPort, Function<String, String> webUrlProvider,
                ConfigFieldSnapshot fieldSnapshot,
                Runnable onLocaleChanged,
                BooleanSupplier languageChangeBlocked,
                BooleanSupplier restartConfirmation,
                BooleanSupplier backendRestarter) {
        this.configPath = configPath;
        this.serverPort = serverPort;
        this.webUrlProvider = webUrlProvider;
        this.onLocaleChanged = onLocaleChanged == null ? () -> { } : onLocaleChanged;
        this.languageChangeBlocked = languageChangeBlocked == null ? () -> false : languageChangeBlocked;
        this.restartConfirmation = restartConfirmation == null
                ? this::confirmBackendRestart
                : restartConfirmation;
        this.backendRestarter = backendRestarter == null
                ? BackendLifecycleManager::restartAsync
                : backendRestarter;
        this.editor = new ConfigFileEditor(configPath);
        this.currentMode = resolveCurrentMode();
        ConfigFieldSnapshot snapshot = fieldSnapshot == null ? ConfigFieldRegistry.snapshot() : fieldSnapshot;
        this.allFields = snapshot.fields();
        this.sectionContributions = snapshot.sections();
        this.groupSpecs = snapshot.groupSpecs();
        this.serverGroup = message("gui.config.group.server");
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

        List<ConfigSection> resolvedSections = new ArrayList<>();
        List<ScopedGroup> hostGroups = visibleGroups(ConfigScope.HOST).stream()
                .map(group -> new ScopedGroup(group, ConfigScope.HOST))
                .toList();
        List<ScopedGroup> pluginGroups = visibleGroups(ConfigScope.PLUGIN).stream()
                .map(group -> new ScopedGroup(group, ConfigScope.PLUGIN))
                .toList();
        Map<ConfigScope, Map<String, ConfigSection>> sectionsByScope =
                buildSectionsByScope(hostGroups, pluginGroups, resolvedSections);

        List<ConfigMenuTree.Node<JComponent>> menuRoots = new ArrayList<>();
        InterfacePreferencesPanel interfacePanel = new InterfacePreferencesPanel(
                configPath, onLocaleChanged, languageChangeBlocked, this::setExpandAllConfigMenus);
        expandAllConfigMenus = interfacePanel.isExpandAllSelected();
        menuRoots.add(ConfigMenuTree.leaf(
                "interface", message("gui.config.category.interface"), interfacePanel));

        Set<String> assignedHostGroupIds = new LinkedHashSet<>();
        for (TopCategory category : topCategories()) {
            List<ScopedGroup> groups = groupsForCategory(category, hostGroups, pluginGroups);
            if (groups.isEmpty()) {
                continue;
            }
            groups.stream()
                    .filter(group -> group.scope() == ConfigScope.HOST)
                    .map(group -> group.spec().id())
                    .forEach(assignedHostGroupIds::add);
            ConfigMenuTree.Node<JComponent> menuNode = category.pluginCategory()
                    ? buildPluginCategoryNode(category, groups, sectionsByScope)
                    : ConfigMenuTree.leaf(category.id(), category.label(),
                    buildCategoryPanel(category, groups, sectionsByScope));
            menuRoots.add(menuNode);
        }
        int fallbackIndex = 0;
        for (ScopedGroup group : hostGroups) {
            if (assignedHostGroupIds.contains(group.spec().id())) {
                continue;
            }
            String groupId = normalizeGroupId(group.spec().id());
            String fallbackId = "fallback:" + (groupId == null ? fallbackIndex : groupId);
            TopCategory fallback = new TopCategory(fallbackId, group.label(),
                    groupId == null ? Set.of() : Set.of(groupId), false, false);
            menuRoots.add(ConfigMenuTree.leaf(fallback.id(), fallback.label(),
                    buildCategoryPanel(fallback, List.of(group), sectionsByScope)));
            fallbackIndex++;
        }
        sections = List.copyOf(resolvedSections);
        configMenuTree = new ConfigMenuTree<>(menuRoots);
        renderConfigMenuTabs();
        add(topTabs, BorderLayout.CENTER);

        // 底部面板：提示条 + 按钮
        add(buildBottomPanel(), BorderLayout.SOUTH);
    }

    private Map<ConfigScope, Map<String, ConfigSection>> buildSectionsByScope(
            List<ScopedGroup> hostGroups,
            List<ScopedGroup> pluginGroups,
            List<ConfigSection> resolvedSections) {
        Map<ConfigScope, Map<String, ConfigSection>> sectionsByScope = new EnumMap<>(ConfigScope.class);
        sectionsByScope.put(ConfigScope.HOST,
                createSectionsByGroup(ConfigScope.HOST, hostGroups, resolvedSections));
        sectionsByScope.put(ConfigScope.PLUGIN,
                createSectionsByGroup(ConfigScope.PLUGIN, pluginGroups, resolvedSections));
        return sectionsByScope;
    }

    private Map<String, ConfigSection> createSectionsByGroup(ConfigScope scope,
                                                             List<ScopedGroup> groups,
                                                             List<ConfigSection> resolvedSections) {
        ConfigSectionContext scopedContext = new ScopedConfigSectionContext(this, scope);
        List<ConfigSection> scopeSections = GuiConfigSectionResolver.createSections(
                scopedContext, groups.stream().map(ScopedGroup::spec).toList(), scopedSections(scope),
                configPath, webUrlProvider,
                scope.includeHostTransitionAdapters());
        resolvedSections.addAll(scopeSections);

        Map<String, ConfigSection> sectionsByGroup = new LinkedHashMap<>();
        for (ConfigSection section : scopeSections) {
            sectionsByGroup.put(section.group(), section);
        }
        return sectionsByGroup;
    }

    private List<TopCategory> topCategories() {
        return List.of(
                new TopCategory("download", message("gui.config.group.download"),
                        Set.of(GuiConfigGroups.DOWNLOAD), false, false),
                new TopCategory("runtime-network", message("gui.config.category.runtime-network"),
                        Set.of(GuiConfigGroups.SERVER, GuiConfigGroups.PROXY,
                                GuiConfigGroups.HTTPS, GuiConfigGroups.UPDATE), false, true),
                new TopCategory("access-control", message("gui.config.category.access-control"),
                        Set.of(GuiConfigGroups.MULTI_MODE, GuiConfigGroups.GUEST_INVITE,
                                GuiConfigGroups.SECURITY), false, true),
                new TopCategory("automation-maintenance", message("gui.config.category.automation-maintenance"),
                        Set.of(GuiConfigGroups.SCHEDULE, GuiConfigGroups.MAINTENANCE), false, true),
                new TopCategory("plugins", message("gui.config.group.plugins"),
                        Set.of(GuiConfigGroups.PLUGINS), true, true)
        );
    }

    private List<ScopedGroup> groupsForCategory(TopCategory category,
                                                List<ScopedGroup> hostGroups,
                                                List<ScopedGroup> pluginGroups) {
        List<ScopedGroup> groups = new ArrayList<>();
        if (category.pluginCategory()) {
            hostGroups.stream()
                    .filter(group -> categoryContains(category, group))
                    .forEach(groups::add);
            groups.addAll(pluginGroups);
            return List.copyOf(groups);
        }
        hostGroups.stream()
                .filter(group -> categoryContains(category, group))
                .forEach(groups::add);
        return List.copyOf(groups);
    }

    private static boolean categoryContains(TopCategory category, ScopedGroup group) {
        String groupId = normalizeGroupId(group.spec().id());
        return groupId != null && category.hostGroupIds().contains(groupId);
    }

    private JComponent buildCategoryPanel(TopCategory category,
                                          List<ScopedGroup> groups,
                                          Map<ConfigScope, Map<String, ConfigSection>> sectionsByScope) {
        if (groups.size() == 1 && !category.showGroupHeadings()) {
            ScopedGroup group = groups.get(0);
            ConfigSection section = sectionsByScope.getOrDefault(group.scope(), Map.of()).get(group.label());
            return section != null ? section.build() : buildGroupPanel(group);
        }

        JPanel content = new GroupContentPanel();
        content.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        String lastHeading = null;
        for (ScopedGroup group : groups) {
            if (!Objects.equals(lastHeading, group.label())) {
                addGroupHeading(content, group.label());
                lastHeading = group.label();
            }
            ConfigSection section = sectionsByScope.getOrDefault(group.scope(), Map.of()).get(group.label());
            JComponent groupContent = section != null
                    ? unwrapSectionContent(section.build())
                    : buildGroupContent(group, false, false);
            removeTrailingVerticalGlue(groupContent);
            groupContent.setAlignmentX(Component.LEFT_ALIGNMENT);
            content.add(groupContent);
            content.add(Box.createVerticalStrut(8));
        }
        content.add(Box.createVerticalGlue());

        JScrollPane sp = new JScrollPane(content);
        sp.setBorder(null);
        sp.getVerticalScrollBar().setUnitIncrement(16);
        applyScrollTopReset(sp);
        return sp;
    }

    private ConfigMenuTree.Node<JComponent> buildPluginCategoryNode(
            TopCategory category,
            List<ScopedGroup> groups,
            Map<ConfigScope, Map<String, ConfigSection>> sectionsByScope) {
        List<ScopedGroup> marketGroups = groups.stream()
                .filter(group -> group.scope() == ConfigScope.HOST)
                .toList();
        List<ScopedGroup> pluginSettingGroups = groups.stream()
                .filter(group -> group.scope() == ConfigScope.PLUGIN)
                .toList();

        List<ConfigMenuTree.Node<JComponent>> children = new ArrayList<>();
        if (!marketGroups.isEmpty()) {
            TopCategory marketCategory = new TopCategory(
                    "plugins:market", message("gui.config.scope.plugin-market-settings"),
                    Set.of(), false, false);
            children.add(ConfigMenuTree.leaf(marketCategory.id(), marketCategory.label(),
                    buildCategoryPanel(marketCategory, marketGroups, sectionsByScope)));
        }
        TopCategory pluginSettingsCategory = new TopCategory(
                "plugins:settings", message("gui.config.scope.plugins"), Set.of(), false, true);
        ConfigMenuTree.Node<JComponent> pluginSettingsNode = pluginSettingGroups.isEmpty()
                ? ConfigMenuTree.leaf(pluginSettingsCategory.id(), pluginSettingsCategory.label(),
                buildEmptyScopePanel(ConfigScope.PLUGIN))
                : ConfigMenuTree.branch(pluginSettingsCategory.id(), pluginSettingsCategory.label(),
                buildPluginSettingNodes(pluginSettingGroups, sectionsByScope));
        children.add(pluginSettingsNode);
        return ConfigMenuTree.branch(category.id(), category.label(), children);
    }

    private List<ConfigMenuTree.Node<JComponent>> buildPluginSettingNodes(
            List<ScopedGroup> pluginSettingGroups,
            Map<ConfigScope, Map<String, ConfigSection>> sectionsByScope) {
        List<ConfigMenuTree.Node<JComponent>> nodes = new ArrayList<>();
        int groupIndex = 0;
        for (ScopedGroup group : pluginSettingGroups) {
            String normalizedId = normalizeGroupId(group.spec().id());
            String nodeId = "plugins:settings:"
                    + (normalizedId == null ? "group-" + groupIndex : normalizedId + ":" + groupIndex);
            TopCategory groupCategory = new TopCategory(
                    nodeId, group.label(), Set.of(), false, false);
            nodes.add(ConfigMenuTree.leaf(nodeId, group.label(),
                    buildCategoryPanel(groupCategory, List.of(group), sectionsByScope)));
            groupIndex++;
        }
        return List.copyOf(nodes);
    }

    private void setExpandAllConfigMenus(boolean expanded) {
        if (expandAllConfigMenus == expanded) {
            return;
        }
        expandAllConfigMenus = expanded;
        // 复用同一棵树和同一批叶子组件；延后到当前复选框事件结束后再重新挂载。
        SwingUtilities.invokeLater(this::renderConfigMenuTabs);
    }

    private void renderConfigMenuTabs() {
        String selectedLeafId = selectedMenuLeafId(topTabs);
        topTabs.removeAll();
        renderedMenuNodes.clear();
        topTabs.setTabLayoutPolicy(expandAllConfigMenus
                ? JTabbedPane.SCROLL_TAB_LAYOUT
                : JTabbedPane.WRAP_TAB_LAYOUT);

        List<ConfigMenuTree.Node<JComponent>> visibleRoots = configMenuTree.roots(expandAllConfigMenus);
        renderedMenuNodes.put(topTabs, visibleRoots);
        for (ConfigMenuTree.Node<JComponent> node : visibleRoots) {
            topTabs.addTab(node.label(), renderMenuNode(node));
        }
        if (selectedLeafId != null) {
            selectMenuLeaf(topTabs, selectedLeafId);
        }
        topTabs.revalidate();
        topTabs.repaint();
    }

    private JComponent renderMenuNode(ConfigMenuTree.Node<JComponent> node) {
        if (node instanceof ConfigMenuTree.Leaf<?> leaf) {
            return (JComponent) leaf.payload();
        }
        ConfigMenuTree.Branch<JComponent> branch = (ConfigMenuTree.Branch<JComponent>) node;
        JTabbedPane tabs = new JTabbedPane(JTabbedPane.TOP);
        tabs.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
        renderedMenuNodes.put(tabs, branch.children());
        for (ConfigMenuTree.Node<JComponent> child : branch.children()) {
            tabs.addTab(child.label(), renderMenuNode(child));
        }
        return tabs;
    }

    private String selectedMenuLeafId(JTabbedPane tabs) {
        List<ConfigMenuTree.Node<JComponent>> nodes = renderedMenuNodes.get(tabs);
        int selectedIndex = tabs.getSelectedIndex();
        if (nodes == null || selectedIndex < 0 || selectedIndex >= nodes.size()) {
            return null;
        }
        ConfigMenuTree.Node<JComponent> selected = nodes.get(selectedIndex);
        if (selected instanceof ConfigMenuTree.Leaf<?> leaf) {
            return leaf.id();
        }
        Component selectedComponent = tabs.getSelectedComponent();
        if (selectedComponent instanceof JTabbedPane nested && renderedMenuNodes.containsKey(nested)) {
            String nestedLeafId = selectedMenuLeafId(nested);
            if (nestedLeafId != null) {
                return nestedLeafId;
            }
        }
        ConfigMenuTree.Branch<JComponent> branch = (ConfigMenuTree.Branch<JComponent>) selected;
        return branch.leavesDepthFirst().stream()
                .findFirst()
                .map(ConfigMenuTree.Leaf::id)
                .orElse(null);
    }

    private boolean selectMenuLeaf(JTabbedPane tabs, String leafId) {
        List<ConfigMenuTree.Node<JComponent>> nodes = renderedMenuNodes.get(tabs);
        if (nodes == null) {
            return false;
        }
        for (int i = 0; i < nodes.size(); i++) {
            ConfigMenuTree.Node<JComponent> node = nodes.get(i);
            if (node instanceof ConfigMenuTree.Leaf<?> leaf) {
                if (leaf.id().equals(leafId)) {
                    tabs.setSelectedIndex(i);
                    return true;
                }
                continue;
            }
            ConfigMenuTree.Branch<JComponent> branch = (ConfigMenuTree.Branch<JComponent>) node;
            boolean containsLeaf = branch.leavesDepthFirst().stream()
                    .anyMatch(leaf -> leaf.id().equals(leafId));
            if (!containsLeaf) {
                continue;
            }
            tabs.setSelectedIndex(i);
            Component child = tabs.getComponentAt(i);
            return child instanceof JTabbedPane nested && selectMenuLeaf(nested, leafId);
        }
        return false;
    }

    private void addGroupHeading(JPanel content, String label) {
        JLabel heading = new JLabel(label);
        heading.setFont(heading.getFont().deriveFont(Font.BOLD, 13f));
        heading.setBorder(BorderFactory.createEmptyBorder(12, 2, 4, 2));
        heading.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(heading);
        JSeparator separator = new JSeparator(SwingConstants.HORIZONTAL);
        separator.setMaximumSize(new Dimension(Integer.MAX_VALUE, 1));
        separator.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(separator);
        content.add(Box.createVerticalStrut(4));
    }

    private List<ConfigGroupSpec> visibleGroups(ConfigScope scope) {
        Set<String> claimedFieldKeys = claimedFieldKeys(scope);
        return groupSpecs.stream()
                .filter(group -> !shouldHideGroup(group))
                .filter(group -> groupHasContent(group, scope, claimedFieldKeys))
                .toList();
    }

    private List<GuiConfigSectionSpec> scopedSections(ConfigScope scope) {
        return sectionContributions.stream()
                .filter(section -> scope.includesSection(section))
                .toList();
    }

    private JComponent buildEmptyScopePanel(ConfigScope scope) {
        JPanel content = new GroupContentPanel();
        content.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
        JLabel label = new JLabel(message(scope.emptyMessageKey()));
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(label);
        content.add(Box.createVerticalGlue());
        JScrollPane sp = new JScrollPane(content);
        sp.setBorder(null);
        return sp;
    }

    private boolean groupHasContent(ConfigGroupSpec groupSpec, ConfigScope scope, Set<String> claimedFieldKeys) {
        boolean hasField = allFields.stream()
                .filter(ConfigFieldSpec::contributesGroupVisibility)
                .filter(field -> !claimedFieldKeys.contains(field.key()))
                .anyMatch(field -> matchesGroup(field, groupSpec) && scope.includesField(field));
        boolean hasSection = sectionContributions.stream()
                .filter(GuiConfigSectionSpec::contributesGroupVisibility)
                .anyMatch(section -> matchesGroup(section, groupSpec) && scope.includesSection(section));
        boolean hasHostTransition = scope == ConfigScope.HOST
                && (GuiConfigGroups.PLUGINS.equals(groupSpec.id())
                || ConfigFieldRegistry.groupPlugins().equals(groupSpec.label()));
        return hasField || hasSection || hasHostTransition;
    }

    private static boolean matchesGroup(ConfigFieldSpec field, ConfigGroupSpec group) {
        if (field == null || group == null) {
            return false;
        }
        return matchesGroup(field.groupId(), field.group(), group);
    }

    private static boolean matchesGroup(GuiConfigSectionSpec section, ConfigGroupSpec group) {
        if (section == null || group == null) {
            return false;
        }
        return matchesGroup(section.groupId(), section.group(), group);
    }

    private static boolean matchesGroup(String itemGroupId, String itemGroupLabel, ConfigGroupSpec group) {
        String normalizedItemId = normalizeGroupId(itemGroupId);
        String normalizedGroupId = normalizeGroupId(group.id());
        if (normalizedItemId != null && normalizedGroupId != null) {
            return normalizedItemId.equals(normalizedGroupId);
        }
        return Objects.equals(itemGroupLabel, group.label());
    }

    private static boolean isGroup(ScopedGroup group, String groupId, String fallbackLabel) {
        if (group == null || group.spec() == null) {
            return false;
        }
        String normalizedGroupId = normalizeGroupId(group.spec().id());
        return Objects.equals(normalizedGroupId, normalizeGroupId(groupId))
                || Objects.equals(group.label(), fallbackLabel);
    }

    private static String normalizeGroupId(String groupId) {
        return groupId == null || groupId.isBlank() ? null : groupId.trim();
    }

    private Set<String> claimedFieldKeys(ConfigScope scope) {
        Set<String> claimed = new LinkedHashSet<>();
        scopedSections(scope).stream()
                .filter(section -> !shouldHideGroup(section.groupId(), section.group()))
                .flatMap(section -> section.fieldLayouts().stream())
                .map(GuiConfigFieldLayoutSpec::fieldKey)
                .filter(Objects::nonNull)
                .forEach(claimed::add);
        return claimed;
    }

    private JComponent buildGroupPanel(ScopedGroup group) {
        JPanel content = buildGroupContent(group, true, true);
        JScrollPane sp = new JScrollPane(content);
        sp.setBorder(null);
        sp.getVerticalScrollBar().setUnitIncrement(16);
        // 任何分组在 init 阶段（字段锁定 / 预设回填 / 响应式重排）都可能让视口偏离 (0,0)；
        // 首次显示时统一强制回到顶部，避免切到该标签页时字段区域停在底部。
        applyScrollTopReset(sp);
        return sp;
    }

    private JPanel buildGroupContent(ScopedGroup group, boolean includeBorder, boolean includeGlue) {
        JPanel content = new GroupContentPanel();
        if (includeBorder) {
            content.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        }
        Set<String> claimedFieldKeys = claimedFieldKeys(group.scope());
        boolean maintenance = isGroup(group, GuiConfigGroups.MAINTENANCE, maintenanceGroup);
        boolean server = isGroup(group, GuiConfigGroups.SERVER, serverGroup);

        List<ConfigFieldSpec> fields = allFields.stream()
                .filter(field -> matchesGroup(field, group.spec()))
                .filter(group.scope()::includesField)
                .filter(field -> !claimedFieldKeys.contains(field.key()))
                .toList();

        for (ConfigFieldSpec spec : fields) {
            if (maintenance && isMaintenanceDayEnabledKey(spec.key())) {
                continue;
            }
            FieldRenderer.RenderedField rf = ConfigFieldRows.render(spec);
            registerField(spec, rf);
            content.add(rf.panel());
            if (maintenance && "maintenance.enabled".equals(spec.key())) {
                JPanel weekdaysPanel = buildMaintenanceWeekdayPanel(fields);
                weekdaysPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
                content.add(weekdaysPanel);
                content.add(Box.createVerticalStrut(2));
            }
        }
        if (server) {
            JPanel autoStartPanel = buildAutoStartPanel();
            autoStartPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
            content.add(autoStartPanel);
            content.add(Box.createVerticalStrut(2));
        }
        if (includeGlue) {
            content.add(Box.createVerticalGlue());
        }
        return content;
    }

    /**
     * 让滚动面板在首次真正显示时把视口重置回顶部，随后摘除监听器不再干预用户滚动。
     * <p>
     * 预设或组合分组在 init 阶段锁定字段会触发
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

    private static JComponent unwrapSectionContent(JComponent component) {
        if (component instanceof JScrollPane scrollPane
                && scrollPane.getViewport().getView() instanceof JComponent content) {
            return content;
        }
        return component;
    }

    private static void removeTrailingVerticalGlue(JComponent component) {
        if (!(component instanceof JPanel panel)) {
            return;
        }
        while (panel.getComponentCount() > 0) {
            Component last = panel.getComponent(panel.getComponentCount() - 1);
            if (!(last instanceof Box.Filler filler)
                    || filler.getMaximumSize().height < Short.MAX_VALUE) {
                return;
            }
            panel.remove(panel.getComponentCount() - 1);
        }
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

    private boolean shouldHideGroup(ConfigGroupSpec group) {
        return SOLO_MODE.equalsIgnoreCase(currentMode)
                && (GuiConfigGroups.MULTI_MODE.equals(group.id()) || multiModeGroup.equals(group.label()));
    }

    private boolean shouldHideGroup(String groupId, String group) {
        return SOLO_MODE.equalsIgnoreCase(currentMode)
                && (GuiConfigGroups.MULTI_MODE.equals(normalizeGroupId(groupId)) || multiModeGroup.equals(group));
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
     * 从宿主 config.yaml、插件自有 properties 与插件凭证存储加载当前值并填充控件；key 不存在或被注释时回退到字段默认值，
     * 并将需要持久化的缺失 key 连同默认值自动补全到所属配置文件。未配置的插件凭证以 key 缺席表示。
     */
    private void loadCurrentValues() {
        try {
            Map<String, String> values = readStoredValues(allFields);
            Map<String, String> missing = new LinkedHashMap<>();

            for (ConfigFieldSpec spec : allFields) {
                FieldRenderer.RenderedField rf = renderedFields.get(spec.key());
                if (rf == null) continue;
                if (values.containsKey(spec.key())) {
                    rf.setValue().accept(displayValueForLoad(spec, values.get(spec.key())));
                } else {
                    // key 不存在或被注释掉：用默认值填充控件，并记录待补全
                    rf.setValue().accept(displayValueForLoad(spec, spec.defaultValue()));
                    if (requiresStoredKey(spec)) {
                        missing.put(spec.key(), spec.defaultValue());
                    }
                }
            }

            // 自动将缺失的 key 补全到所属配置文件
            if (!missing.isEmpty()) {
                try {
                    writeStoredValues(missing);
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
        // 验证最终会按原值保存的字段；隐藏且无需保留的字段仍可按空值写出。
        clearValidationErrors();
        List<String> errors = new ArrayList<>();
        FieldRenderer.RenderedField firstInvalidField = null;
        for (ConfigFieldSpec spec : allFields) {
            FieldRenderer.RenderedField rf = renderedFields.get(spec.key());
            if (rf == null) {
                continue;
            }
            boolean validate = rf.panel().isVisible() && rf.control().isEnabled()
                    || !rf.panel().isVisible() && shouldPreserveHiddenValue(spec);
            if (!validate) {
                continue;
            }
            String val = rf.getValue().get();
            String err = validateFieldValueForSave(spec, val);
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
            if (isShowing()) {
                JOptionPane.showMessageDialog(this,
                        message("gui.config.dialog.validation-failed.message", String.join("\n", errors)),
                        message("gui.config.dialog.validation-failed.title"),
                        JOptionPane.WARNING_MESSAGE);
            }
            if (firstInvalidField != null) {
                firstInvalidField.control().requestFocusInWindow();
                firstInvalidField.panel().scrollRectToVisible(new Rectangle(
                        0, 0, firstInvalidField.panel().getWidth(), firstInvalidField.panel().getHeight()));
            }
            return;
        }

        Map<String, String> before;
        try {
            before = readStoredValues(allFields);
        } catch (IOException e) {
            log.warn(logMessage("gui.config.log.read-failed", e.getMessage()));
            GuiErrorDialog.show(this,
                    message("gui.dialog.error.title"),
                    message("gui.config.dialog.read-failed.message", e.getMessage()));
            return;
        }

        // 收集所有值：核心隐藏字段保持既有语义；插件字段按 contribution 当前值保存，避免卡片切换清空其它插件配置。
        Map<String, String> values = new LinkedHashMap<>();
        for (ConfigFieldSpec spec : allFields) {
            FieldRenderer.RenderedField rf = renderedFields.get(spec.key());
            if (rf == null) continue;
            if (isPluginCredential(spec)) {
                String entered = rf.getValue().get();
                if (rf.credentialClearRequested()) {
                    values.put(spec.key(), "");
                } else if (entered != null && !entered.isBlank()) {
                    values.put(spec.key(), entered);
                }
                continue;
            }
            values.put(spec.key(), rf.panel().isVisible() || shouldPreserveHiddenValue(spec)
                    ? rf.getValue().get()
                    : "");
        }

        Set<String> changedKeys = changedKeys(before, values);
        Set<String> hotReloadKeys = changedFieldKeys(changedKeys, false);
        boolean hasHotReloadChanges = !hotReloadKeys.isEmpty();
        boolean hasRestartRequiredChanges = hasChangedField(changedKeys, true);

        // 下载根目录原本以符号根（跟随软件目录）方式记录时，改目录前必须先把旧记录固定为绝对路径，
        // 否则旧作品会随新配置解析到错误位置。用户取消或固定失败 → 整个保存中止。
        if (changedKeys.contains("download.root-folder")
                && !confirmAndPinSymbolicRoot(before.get("download.root-folder"),
                        values.get("download.root-folder"))) {
            return;
        }

        try {
            writeStoredValues(values);
            // 各 section 持久化自有的非字段网格状态（如插件市场仓库列表）；任一写入改动均为需重启项。
            boolean sectionRestartChange = false;
            for (ConfigSection s : sections) {
                if (s.onSave()) {
                    sectionRestartChange = true;
                }
            }
            boolean restartRequired = hasRestartRequiredChanges || sectionRestartChange;
            log.info(logMessage("gui.config.log.saved", configPath));
            if (restartRequired && restartConfirmation.getAsBoolean()) {
                if (requestBackendRestart()) {
                    showNotice(message("gui.config.notice.restarting"));
                } else {
                    showNotice(message("gui.message.backend-busy"));
                }
            } else if (hasHotReloadChanges) {
                showNotice(message("gui.config.notice.hot-reloading"));
                reloadHotConfigAsync(restartRequired, hotReloadKeys);
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

    private String validateFieldValueForSave(ConfigFieldSpec spec, String value) {
        try {
            ConfigFileEditor.requireSafeKey(spec.key());
            ConfigFileEditor.requireSafeValue(value);
        } catch (IOException e) {
            return message("gui.config.validation.storage-safe");
        }
        String typeError = validateFieldTypeValue(spec, value);
        if (typeError != null) {
            return typeError;
        }
        try {
            return spec.validator().validate(value == null ? "" : value);
        } catch (RuntimeException e) {
            log.warn(logMessage("gui.config.log.validation-failed",
                    spec.key() + ": " + safeMessage(e)), e);
            return message("gui.config.validation.storage-safe");
        }
    }

    private boolean requestBackendRestart() {
        try {
            return backendRestarter.getAsBoolean();
        } catch (RuntimeException e) {
            log.warn(logMessage("gui.status.log.restart-request.failed", safeMessage(e)), e);
            return false;
        }
    }

    private boolean confirmBackendRestart() {
        if (!isShowing()) {
            return false;
        }
        Object[] options = {
                message("gui.action.restart-service"),
                message("gui.action.restart-later")
        };
        int answer = JOptionPane.showOptionDialog(this,
                message("gui.config.dialog.restart-required.message"),
                message("gui.config.dialog.restart-required.title"),
                JOptionPane.DEFAULT_OPTION,
                JOptionPane.QUESTION_MESSAGE,
                null,
                options,
                options[0]);
        return answer == 0;
    }

    private static String validateFieldTypeValue(ConfigFieldSpec spec, String value) {
        String safe = value == null ? "" : value;
        return switch (spec.type()) {
            case BOOL -> "true".equalsIgnoreCase(safe) || "false".equalsIgnoreCase(safe)
                    ? null
                    : message("gui.config.validation.valid-boolean");
            case ENUM -> spec.enumValues().isEmpty() || spec.enumValues().contains(safe)
                    ? null
                    : message("gui.config.validation.valid-enum");
            case INT -> {
                try {
                    Integer.parseInt(safe);
                    yield null;
                } catch (NumberFormatException e) {
                    yield message("gui.config.validation.valid-int");
                }
            }
            case PORT -> {
                try {
                    int port = Integer.parseInt(safe);
                    yield port >= 1 && port <= 65535
                            ? null
                            : message("gui.config.validation.port-range");
                } catch (NumberFormatException e) {
                    yield message("gui.config.validation.valid-port");
                }
            }
            case PATH_DIR, PATH_FILE, STRING, PASSWORD -> null;
        };
    }

    private Map<String, String> readStoredValues(Collection<ConfigFieldSpec> specs) throws IOException {
        StoredSpecIndex index = storedSpecIndex(specs);
        Map<String, String> result = new LinkedHashMap<>();
        if (!index.coreKeys().isEmpty()) {
            result.putAll(editor.readAll(index.coreKeys()));
        }
        Map<String, String> legacyPluginValues = index.allPluginKeys().isEmpty()
                ? Map.of()
                : editor.readAll(index.allPluginKeys());
        for (Map.Entry<String, List<String>> entry : index.pluginKeys().entrySet()) {
            String pluginId = entry.getKey();
            Set<String> credentialKeys = new LinkedHashSet<>(
                    index.pluginCredentialKeys().getOrDefault(pluginId, List.of()));
            PropertiesConfigFileEditor pluginEditor = null;
            try {
                pluginEditor = pluginConfigEditor(pluginId);
            } catch (RuntimeException e) {
                log.warn(logMessage("gui.config.log.plugin-config-migration.failed",
                        pluginId, safeMessage(e)), e);
            }
            Map<String, String> pluginValues = pluginEditor == null
                    ? Map.of()
                    : pluginEditor.readAll(entry.getValue());
            pluginValues.forEach((key, value) -> {
                if (!credentialKeys.contains(key)) {
                    result.put(key, value);
                }
            });
            Map<String, String> migratedPluginValues = new LinkedHashMap<>();
            Set<String> removableYamlPluginKeys = new LinkedHashSet<>();
            for (String key : entry.getValue()) {
                if (credentialKeys.contains(key)) {
                    continue;
                }
                if (!legacyPluginValues.containsKey(key)) {
                    continue;
                }
                String legacyValue = legacyPluginValues.get(key);
                if (!pluginValues.containsKey(key)) {
                    result.put(key, legacyValue);
                    migratedPluginValues.put(key, legacyValue);
                    removableYamlPluginKeys.add(key);
                } else if (Objects.equals(pluginValues.get(key), legacyValue)) {
                    removableYamlPluginKeys.add(key);
                } else {
                    removableYamlPluginKeys.add(key);
                }
            }
            if (!migratedPluginValues.isEmpty() || !removableYamlPluginKeys.isEmpty()) {
                try {
                    migrateLegacyPluginValues(pluginId, migratedPluginValues, removableYamlPluginKeys);
                } catch (IOException | RuntimeException e) {
                    log.warn(logMessage("gui.config.log.plugin-config-migration.failed",
                            pluginId, safeMessage(e)), e);
                }
            }
            if (!credentialKeys.isEmpty()) {
                Map<String, String> storedCredentials = credentialStore.readAll(pluginId);
                Map<String, String> credentialUpdates = new LinkedHashMap<>();
                Set<String> removablePropertyKeys = new LinkedHashSet<>();
                Set<String> removableYamlCredentialKeys = new LinkedHashSet<>();
                for (String key : credentialKeys) {
                    String effective = storedCredentials.get(key);
                    if (effective == null && pluginValues.containsKey(key)) {
                        effective = pluginValues.get(key);
                    }
                    if (effective == null && legacyPluginValues.containsKey(key)) {
                        effective = legacyPluginValues.get(key);
                    }
                    if (effective != null) {
                        result.put(key, effective);
                    }
                    if (!storedCredentials.containsKey(key) && effective != null && !effective.isBlank()) {
                        credentialUpdates.put(key, effective);
                    }
                    if (pluginValues.containsKey(key)) {
                        removablePropertyKeys.add(key);
                    }
                    if (legacyPluginValues.containsKey(key)) {
                        removableYamlCredentialKeys.add(key);
                    }
                }
                if (!credentialUpdates.isEmpty() || !removablePropertyKeys.isEmpty()
                        || !removableYamlCredentialKeys.isEmpty()) {
                    try {
                        migrateLegacyPluginCredentials(pluginId, credentialUpdates,
                                removablePropertyKeys, removableYamlCredentialKeys);
                    } catch (IOException | RuntimeException e) {
                        log.warn(logMessage("gui.config.log.plugin-config-migration.failed",
                                pluginId, safeMessage(e)), e);
                    }
                }
            }
        }
        return result;
    }

    private void writeStoredValues(Map<String, String> values) throws IOException {
        StoredValuesSplit split = splitStoredValues(values);
        if (split.empty()) {
            return;
        }
        List<ConfigFileRollback> rollbacks = new ArrayList<>();
        try {
            boolean credentialChanges = split.credentialValues().values().stream().anyMatch(map -> !map.isEmpty());
            if (!split.coreValues().isEmpty() || credentialChanges) {
                ConfigFileEditor.FileSnapshot snapshot = editor.snapshot();
                rollbacks.add(new ConfigFileRollback("config.yaml", () -> editor.restore(snapshot)));
            }
            Set<String> snapshottedPluginOwners = new LinkedHashSet<>();
            for (Map.Entry<String, Map<String, String>> entry : split.pluginValues().entrySet()) {
                if (!entry.getValue().isEmpty()) {
                    PropertiesConfigFileEditor pluginEditor = pluginConfigEditor(entry.getKey());
                    PropertiesConfigFileEditor.FileSnapshot snapshot = pluginEditor.snapshot();
                    snapshottedPluginOwners.add(entry.getKey());
                    rollbacks.add(new ConfigFileRollback(
                            "config/plugins/" + entry.getKey() + ".properties",
                            () -> pluginEditor.restore(snapshot)));
                }
            }
            for (Map.Entry<String, Map<String, String>> entry : split.credentialValues().entrySet()) {
                if (entry.getValue().isEmpty()) {
                    continue;
                }
                String pluginId = entry.getKey();
                PluginCredentialStore.Snapshot credentialSnapshot = credentialStore.snapshot(pluginId);
                rollbacks.add(new ConfigFileRollback("plugin credentials: " + pluginId,
                        () -> credentialStore.restore(pluginId, credentialSnapshot)));
                if (snapshottedPluginOwners.add(pluginId)) {
                    PropertiesConfigFileEditor pluginEditor = pluginConfigEditor(pluginId);
                    PropertiesConfigFileEditor.FileSnapshot pluginSnapshot = pluginEditor.snapshot();
                    rollbacks.add(new ConfigFileRollback(
                            "config/plugins/" + pluginId + ".properties",
                            () -> pluginEditor.restore(pluginSnapshot)));
                }
            }

            if (!split.coreValues().isEmpty()) {
                editor.writeAll(split.coreValues());
            }
            for (Map.Entry<String, Map<String, String>> entry : split.pluginValues().entrySet()) {
                pluginConfigEditor(entry.getKey()).writeAll(entry.getValue());
            }
            Set<String> credentialYamlKeys = new LinkedHashSet<>();
            for (Map.Entry<String, Map<String, String>> entry : split.credentialValues().entrySet()) {
                if (entry.getValue().isEmpty()) {
                    continue;
                }
                credentialStore.update(entry.getKey(), entry.getValue());
                Set<String> keys = entry.getValue().keySet();
                PropertiesConfigFileEditor pluginEditor = pluginConfigEditor(entry.getKey());
                pluginEditor.removeAll(keys);
                if (!pluginEditor.readAll(keys).isEmpty()) {
                    throw new IOException("Plugin credential cleanup verification failed for owner: "
                            + entry.getKey());
                }
                credentialYamlKeys.addAll(keys);
            }
            if (!credentialYamlKeys.isEmpty()) {
                editor.removeAll(credentialYamlKeys);
                if (!editor.readAll(credentialYamlKeys).isEmpty()) {
                    throw new IOException("YAML credential cleanup verification failed");
                }
            }
        } catch (IOException | RuntimeException e) {
            IOException failure = asIOException(e);
            rollbackConfigWrites(rollbacks, failure);
            throw failure;
        }
    }

    private void migrateLegacyPluginValues(String pluginId,
                                           Map<String, String> pluginValues,
                                           Set<String> yamlKeysToRemove) throws IOException {
        if ((pluginValues == null || pluginValues.isEmpty())
                && (yamlKeysToRemove == null || yamlKeysToRemove.isEmpty())) {
            return;
        }
        Map<String, String> safePluginValues = ConfigFileEditor.validatedValues(pluginValues);
        Set<String> safeYamlKeys = ConfigFileEditor.validatedKeySet(yamlKeysToRemove);
        PropertiesConfigFileEditor pluginEditor = pluginConfigEditor(pluginId);
        List<ConfigFileRollback> rollbacks = new ArrayList<>();
        try {
            if (!safePluginValues.isEmpty()) {
                PropertiesConfigFileEditor.FileSnapshot pluginSnapshot = pluginEditor.snapshot();
                rollbacks.add(new ConfigFileRollback(
                        "config/plugins/" + pluginId + ".properties",
                        () -> pluginEditor.restore(pluginSnapshot)));
            }
            if (!safeYamlKeys.isEmpty()) {
                ConfigFileEditor.FileSnapshot yamlSnapshot = editor.snapshot();
                rollbacks.add(new ConfigFileRollback("config.yaml", () -> editor.restore(yamlSnapshot)));
            }

            if (!safePluginValues.isEmpty()) {
                pluginEditor.writeAll(safePluginValues);
                Map<String, String> reread = pluginEditor.readAll(safePluginValues.keySet());
                for (Map.Entry<String, String> entry : safePluginValues.entrySet()) {
                    if (!Objects.equals(entry.getValue(), reread.get(entry.getKey()))) {
                        throw new IOException("Plugin config migration verification failed for key: "
                                + entry.getKey());
                    }
                }
            }
            if (!safeYamlKeys.isEmpty()) {
                editor.removeAll(safeYamlKeys);
                Map<String, String> remaining = editor.readAll(safeYamlKeys);
                if (!remaining.isEmpty()) {
                    throw new IOException("Legacy config key removal verification failed: "
                            + String.join(", ", remaining.keySet()));
                }
            }
        } catch (IOException | RuntimeException e) {
            IOException failure = asIOException(e);
            rollbackConfigWrites(rollbacks, failure);
            throw failure;
        }
    }

    private void migrateLegacyPluginCredentials(String pluginId,
                                                Map<String, String> credentialUpdates,
                                                Set<String> propertyKeysToRemove,
                                                Set<String> yamlKeysToRemove) throws IOException {
        Map<String, String> safeUpdates = ConfigFileEditor.validatedValues(credentialUpdates);
        Set<String> safePropertyKeys = ConfigFileEditor.validatedKeySet(propertyKeysToRemove);
        Set<String> safeYamlKeys = ConfigFileEditor.validatedKeySet(yamlKeysToRemove);
        PropertiesConfigFileEditor pluginEditor = pluginConfigEditor(pluginId);
        List<ConfigFileRollback> rollbacks = new ArrayList<>();
        try {
            if (!safeUpdates.isEmpty()) {
                PluginCredentialStore.Snapshot snapshot = credentialStore.snapshot(pluginId);
                rollbacks.add(new ConfigFileRollback("plugin credentials: " + pluginId,
                        () -> credentialStore.restore(pluginId, snapshot)));
            }
            if (!safePropertyKeys.isEmpty()) {
                PropertiesConfigFileEditor.FileSnapshot snapshot = pluginEditor.snapshot();
                rollbacks.add(new ConfigFileRollback("config/plugins/" + pluginId + ".properties",
                        () -> pluginEditor.restore(snapshot)));
            }
            if (!safeYamlKeys.isEmpty()) {
                ConfigFileEditor.FileSnapshot snapshot = editor.snapshot();
                rollbacks.add(new ConfigFileRollback("config.yaml", () -> editor.restore(snapshot)));
            }

            if (!safeUpdates.isEmpty()) {
                credentialStore.update(pluginId, safeUpdates);
            }
            if (!safePropertyKeys.isEmpty()) {
                pluginEditor.removeAll(safePropertyKeys);
                if (!pluginEditor.readAll(safePropertyKeys).isEmpty()) {
                    throw new IOException("Legacy plugin credential removal verification failed for owner: "
                            + pluginId);
                }
            }
            if (!safeYamlKeys.isEmpty()) {
                editor.removeAll(safeYamlKeys);
                if (!editor.readAll(safeYamlKeys).isEmpty()) {
                    throw new IOException("Legacy YAML credential removal verification failed for owner: "
                            + pluginId);
                }
            }
        } catch (IOException | RuntimeException e) {
            IOException failure = asIOException(e);
            rollbackConfigWrites(rollbacks, failure);
            throw failure;
        }
    }

    private StoredValuesSplit splitStoredValues(Map<String, String> values) throws IOException {
        if (values == null || values.isEmpty()) {
            return new StoredValuesSplit(Map.of(), Map.of(), Map.of());
        }
        StoredSpecIndex index = storedSpecIndex(allFields);
        Map<String, String> coreValues = new LinkedHashMap<>();
        Map<String, Map<String, String>> pluginValues = new LinkedHashMap<>();
        Map<String, Map<String, String>> credentialValues = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            String key = ConfigFileEditor.requireSafeKey(entry.getKey());
            ConfigFieldSpec spec = index.specsByKey().get(key);
            if (spec == null) {
                throw new IOException("Unknown config field key: " + key);
            }
            String value = ConfigFileEditor.requireSafeValue(entry.getValue());
            if (isPluginCredential(spec)) {
                credentialValues.computeIfAbsent(spec.ownerPluginId(), ignored -> new LinkedHashMap<>())
                        .put(key, value);
            } else if (spec.pluginContributed()) {
                pluginValues.computeIfAbsent(spec.ownerPluginId(), ignored -> new LinkedHashMap<>())
                        .put(key, value);
            } else {
                coreValues.put(key, value);
            }
        }
        return new StoredValuesSplit(coreValues, pluginValues, credentialValues);
    }

    private StoredSpecIndex storedSpecIndex(Collection<ConfigFieldSpec> specs) throws IOException {
        List<String> coreKeys = new ArrayList<>();
        Map<String, List<String>> pluginKeys = new LinkedHashMap<>();
        Map<String, List<String>> pluginCredentialKeys = new LinkedHashMap<>();
        List<String> allPluginKeys = new ArrayList<>();
        Map<String, ConfigFieldSpec> specsByKey = new LinkedHashMap<>();
        if (specs == null) {
            return new StoredSpecIndex(List.of(), Map.of(), Map.of(), List.of(), Map.of());
        }
        for (ConfigFieldSpec spec : specs) {
            if (spec == null) {
                continue;
            }
            String key = ConfigFileEditor.requireSafeKey(spec.key());
            if (specsByKey.putIfAbsent(key, spec) != null) {
                throw new IOException("Duplicate config field key: " + key);
            }
            if (spec.pluginContributed()) {
                String ownerPluginId = ConfigFileEditor.requireSafeKey(spec.ownerPluginId());
                pluginKeys.computeIfAbsent(ownerPluginId, ignored -> new ArrayList<>()).add(key);
                if (isPluginCredential(spec)) {
                    pluginCredentialKeys.computeIfAbsent(ownerPluginId, ignored -> new ArrayList<>()).add(key);
                }
                allPluginKeys.add(key);
            } else {
                coreKeys.add(key);
            }
        }
        return new StoredSpecIndex(
                List.copyOf(coreKeys),
                copyPluginKeys(pluginKeys),
                copyPluginKeys(pluginCredentialKeys),
                List.copyOf(allPluginKeys),
                Map.copyOf(specsByKey));
    }

    private static Map<String, List<String>> copyPluginKeys(Map<String, List<String>> pluginKeys) {
        Map<String, List<String>> copy = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : pluginKeys.entrySet()) {
            copy.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        return Collections.unmodifiableMap(copy);
    }

    private void rollbackConfigWrites(List<ConfigFileRollback> rollbacks, IOException failure) {
        ListIterator<ConfigFileRollback> iterator = rollbacks.listIterator(rollbacks.size());
        while (iterator.hasPrevious()) {
            ConfigFileRollback rollback = iterator.previous();
            try {
                rollback.restore().restore();
            } catch (IOException rollbackError) {
                failure.addSuppressed(rollbackError);
                log.error(logMessage("gui.config.log.rollback-failed",
                        rollback.label(), rollbackError.getMessage()), rollbackError);
            }
        }
    }

    private static IOException asIOException(Exception e) {
        return e instanceof IOException io ? io : new IOException(e.getMessage(), e);
    }

    private PropertiesConfigFileEditor pluginConfigEditor(String pluginId) {
        return pluginConfigEditors.computeIfAbsent(pluginId, id ->
                new PropertiesConfigFileEditor(RuntimeFiles.resolvePluginConfigPath(id, "properties")));
    }

    private boolean hasChangedField(Set<String> changedKeys, boolean requiresRestart) {
        return !changedFieldKeys(changedKeys, requiresRestart).isEmpty();
    }

    private Set<String> changedFieldKeys(Set<String> changedKeys, boolean requiresRestart) {
        Set<String> keys = new LinkedHashSet<>();
        for (ConfigFieldSpec spec : allFields) {
            if (spec.requiresRestart() == requiresRestart && changedKeys.contains(spec.key())) {
                keys.add(spec.key());
            }
        }
        return keys;
    }

    private static String normalizeValue(String value) {
        return value == null ? "" : value.trim();
    }

    private static String displayValueForLoad(ConfigFieldSpec spec, String storedValue) {
        if (isPluginCredential(spec)) {
            return "";
        }
        String safe = storedValue == null ? "" : storedValue;
        if (safe.isBlank() && shouldUseDefaultForBlankStoredValue(spec)) {
            return spec.defaultValue();
        }
        return safe;
    }

    private static boolean shouldUseDefaultForBlankStoredValue(ConfigFieldSpec spec) {
        return switch (spec.type()) {
            case BOOL, ENUM, INT, PORT -> true;
            case PATH_DIR, PATH_FILE, STRING, PASSWORD -> false;
        };
    }

    private static boolean shouldPreserveHiddenValue(ConfigFieldSpec spec) {
        // debug.enabled 在未解锁时隐藏，但其值仍应原样保留（写空会清掉用户已有的调试开关）
        return spec.pluginContributed() || isMaintenanceDayTimeKey(spec.key()) || "debug.enabled".equals(spec.key());
    }

    private static boolean isPluginCredential(ConfigFieldSpec spec) {
        return spec != null && spec.pluginContributed() && spec.type() == FieldType.PASSWORD;
    }

    private static boolean requiresStoredKey(ConfigFieldSpec spec) {
        return !isPluginCredential(spec);
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

    private void reloadHotConfigAsync(boolean hasRestartRequiredChanges, Set<String> hotReloadKeys) {
        SwingWorker<Boolean, Void> worker = new SwingWorker<>() {
            @Override
            protected Boolean doInBackground() {
                try {
                    GuiConfigTestClient.Response response = testClient.postJson("config/reload",
                            MAPPER.writeValueAsBytes(Map.of("changedKeys", hotReloadKeys)), 5000);
                    return response.reachable() && response.is2xx();
                } catch (IOException e) {
                    log.warn(logMessage("gui.config.log.hot-reload-failed", e.getMessage()));
                    return false;
                }
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
     * 对比字段快照与所属配置存储中实际存在的 key，漂移时打日志警告。
     * 未配置的插件凭证以 key 缺席表示，不属于字段漂移。
     */
    private void checkFieldDrift() {
        try {
            Map<String, String> existing = readStoredValues(allFields);
            for (ConfigFieldSpec spec : allFields) {
                if (requiresStoredKey(spec) && !existing.containsKey(spec.key())) {
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

    private record ScopedGroup(ConfigGroupSpec spec, ConfigScope scope) {
        private String label() {
            return spec.label();
        }
    }

    private record TopCategory(String id,
                               String label,
                               Set<String> hostGroupIds,
                               boolean pluginCategory,
                               boolean showGroupHeadings) {
    }

    private record StoredSpecIndex(List<String> coreKeys,
                                   Map<String, List<String>> pluginKeys,
                                   Map<String, List<String>> pluginCredentialKeys,
                                   List<String> allPluginKeys,
                                   Map<String, ConfigFieldSpec> specsByKey) {
    }

    private record StoredValuesSplit(Map<String, String> coreValues,
                                     Map<String, Map<String, String>> pluginValues,
                                     Map<String, Map<String, String>> credentialValues) {
        private boolean empty() {
            return coreValues.isEmpty()
                    && pluginValues.values().stream().allMatch(Map::isEmpty)
                    && credentialValues.values().stream().allMatch(Map::isEmpty);
        }
    }

    private record ConfigFileRollback(String label, RestoreAction restore) {
    }

    @FunctionalInterface
    private interface RestoreAction {
        void restore() throws IOException;
    }

    private enum ConfigScope {
        HOST("", true) {
            @Override
            boolean includesField(ConfigFieldSpec field) {
                return field != null && !field.pluginContributed();
            }

            @Override
            boolean includesSection(GuiConfigSectionSpec section) {
                return section != null && ConfigFieldSpec.CORE_OWNER.equals(section.pluginId());
            }
        },
        PLUGIN("gui.config.scope.plugins.empty", false) {
            @Override
            boolean includesField(ConfigFieldSpec field) {
                return field != null && field.pluginContributed();
            }

            @Override
            boolean includesSection(GuiConfigSectionSpec section) {
                return section != null && !ConfigFieldSpec.CORE_OWNER.equals(section.pluginId());
            }
        };

        private final String emptyMessageKey;
        private final boolean includeHostTransitionAdapters;

        ConfigScope(String emptyMessageKey, boolean includeHostTransitionAdapters) {
            this.emptyMessageKey = emptyMessageKey;
            this.includeHostTransitionAdapters = includeHostTransitionAdapters;
        }

        abstract boolean includesField(ConfigFieldSpec field);

        abstract boolean includesSection(GuiConfigSectionSpec section);

        private String emptyMessageKey() {
            return emptyMessageKey;
        }

        private boolean includeHostTransitionAdapters() {
            return includeHostTransitionAdapters;
        }
    }

    private static final class ScopedConfigSectionContext implements ConfigSectionContext {
        private final ConfigSectionContext delegate;
        private final ConfigScope scope;

        private ScopedConfigSectionContext(ConfigSectionContext delegate, ConfigScope scope) {
            this.delegate = delegate;
            this.scope = scope;
        }

        @Override
        public List<ConfigFieldSpec> allFields() {
            return delegate.allFields().stream()
                    .filter(scope::includesField)
                    .toList();
        }

        @Override
        public ConfigFieldSpec findSpec(String key) {
            ConfigFieldSpec spec = delegate.findSpec(key);
            return scope.includesField(spec) ? spec : null;
        }

        @Override
        public void registerField(ConfigFieldSpec spec, FieldRenderer.RenderedField rf) {
            if (scope.includesField(spec)) {
                delegate.registerField(spec, rf);
            }
        }

        @Override
        public void addFields(JPanel content, List<ConfigFieldSpec> specs) {
            List<ConfigFieldSpec> scopedSpecs = specs == null ? List.of() : specs.stream()
                    .filter(scope::includesField)
                    .toList();
            delegate.addFields(content, scopedSpecs);
        }

        @Override
        public String currentFieldValue(String key) {
            return delegate.currentFieldValue(key);
        }

        @Override
        public void setFieldValue(String key, String value) {
            delegate.setFieldValue(key, value);
        }

        @Override
        public void lockField(String key) {
            ConfigFieldSpec spec = delegate.findSpec(key);
            if (scope.includesField(spec)) {
                delegate.lockField(key);
            }
        }

        @Override
        public JPanel newContentPanel() {
            return delegate.newContentPanel();
        }

        @Override
        public void resetScrollToTopOnFirstShow(JScrollPane sp) {
            delegate.resetScrollToTopOnFirstShow(sp);
        }

        @Override
        public JLabel effectLabel(boolean requiresRestart) {
            return delegate.effectLabel(requiresRestart);
        }

        @Override
        public JTextArea hiddenValidationError() {
            return delegate.hiddenValidationError();
        }

        @Override
        public void showNotice(String msg) {
            delegate.showNotice(msg);
        }

        @Override
        public void updateEnabledStates() {
            delegate.updateEnabledStates();
        }

        @Override
        public GuiConfigTestClient testClient() {
            return delegate.testClient();
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
            FieldRenderer.RenderedField rf = ConfigFieldRows.render(spec);
            registerField(spec, rf);
            content.add(rf.panel());
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

    public void requestCredentialClear(String key) {
        FieldRenderer.RenderedField rf = renderedFields.get(key);
        ConfigFieldSpec spec = findSpec(key);
        if (rf != null && isPluginCredential(spec)) {
            rf.requestCredentialClear();
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
