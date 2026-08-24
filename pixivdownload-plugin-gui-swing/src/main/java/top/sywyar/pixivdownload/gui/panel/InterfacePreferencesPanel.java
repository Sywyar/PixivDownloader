package top.sywyar.pixivdownload.gui.panel;

import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiHost;

import top.sywyar.pixivdownload.guiswing.SwingHost;

import lombok.extern.slf4j.Slf4j;
import top.sywyar.pixivdownload.gui.config.FieldRenderer;
import top.sywyar.pixivdownload.gui.i18n.GuiMessages;
import top.sywyar.pixivdownload.gui.theme.GuiThemeManager;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiPluginSnapshot;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiText;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigEffect;
import top.sywyar.pixivdownload.plugin.api.gui.GuiThemeContribution;
import top.sywyar.pixivdownload.plugin.api.gui.GuiThemeListenerSession;

import javax.swing.*;
import java.awt.*;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * “界面”配置页：集中管理语言、桌面 UI 提供者、主题与配置菜单层级偏好。
 *
 * <p>可预览的偏好即时更新 Swing，但只由配置页底部的统一保存入口写回 {@code config.yaml}。
 * 语言切换仍由主窗口回调重建本地化面板；主题继续由 {@link GuiThemeManager} 应用；
 * 菜单层级只通过中性的布尔回调通知 {@link ConfigPanel} 重新挂载既有叶子页面。</p>
 */
@Slf4j
final class InterfacePreferencesPanel extends JPanel {

    static final String LANGUAGE_CONFIG_KEY = "app.language";
    static final String GUI_PROVIDER_CONFIG_KEY = "app.gui-provider";
    static final String THEME_CONFIG_KEY = "app.theme";
    static final String EXPAND_ALL_CONFIG_KEY = "app.config-menu-expand-all";
    static final String PREFERENCE_KEY_PROPERTY = "gui.interface.preference-key";
    static final List<String> CONFIG_KEYS = List.of(
            LANGUAGE_CONFIG_KEY, GUI_PROVIDER_CONFIG_KEY, THEME_CONFIG_KEY, EXPAND_ALL_CONFIG_KEY);

    private final Runnable onLocaleChanged;
    private final BooleanSupplier languageChangeBlocked;
    private final Consumer<Boolean> onExpandAllChanged;
    private final Map<String, String> draft;

    private final JComboBox<LocaleOption> languageCombo = new JComboBox<>();
    private final JComboBox<ProviderOption> providerCombo = new JComboBox<>();
    private final JComboBox<StatusPanelThemeOption> themeCombo = new JComboBox<>();
    private final JCheckBox expandAllCheckBox = new JCheckBox();

    private final java.awt.event.ActionListener languageActionListener = e -> applyLanguageSelection();
    private final java.awt.event.ActionListener providerActionListener = e -> applyProviderSelection();
    private final java.awt.event.ActionListener themeActionListener = e -> applyThemeSelection();
    private final Runnable themeChangeListener = this::syncThemeComboSelection;

    private LocaleOption currentAppliedLanguageOption;
    private GuiThemeListenerSession themeListenerSession = GuiThemeListenerSession.none();

    InterfacePreferencesPanel(Path configPath,
                              Runnable onLocaleChanged,
                              BooleanSupplier languageChangeBlocked,
                              Consumer<Boolean> onExpandAllChanged) {
        this(configPath, onLocaleChanged, languageChangeBlocked, onExpandAllChanged, new LinkedHashMap<>());
    }

    InterfacePreferencesPanel(Path configPath,
                              Runnable onLocaleChanged,
                              BooleanSupplier languageChangeBlocked,
                              Consumer<Boolean> onExpandAllChanged,
                              Map<String, String> draft) {
        this.onLocaleChanged = onLocaleChanged == null ? () -> { } : onLocaleChanged;
        this.languageChangeBlocked = languageChangeBlocked == null ? () -> false : languageChangeBlocked;
        this.onExpandAllChanged = onExpandAllChanged == null ? ignored -> { } : onExpandAllChanged;
        this.draft = Objects.requireNonNull(draft, "draft");
        buildUi();
    }

    @Override
    public void addNotify() {
        super.addNotify();
        themeListenerSession.close();
        themeListenerSession = GuiThemeManager.addChangeListener(themeChangeListener);
    }

    @Override
    public void removeNotify() {
        themeListenerSession.close();
        themeListenerSession = GuiThemeListenerSession.none();
        super.removeNotify();
    }

    boolean isExpandAllSelected() {
        return expandAllCheckBox.isSelected();
    }

    private void buildUi() {
        setLayout(new BorderLayout());

        JPanel content = new PreferenceContentPanel();
        content.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        configureLanguageSelector();
        addPreferenceField(content,
                message("gui.interface.language.label"),
                languageCombo,
                GuiConfigEffect.HOT_RELOAD,
                message("gui.interface.language.help"));

        configureProviderSelector();
        addPreferenceField(content,
                message("gui.interface.provider.label"),
                providerCombo,
                GuiConfigEffect.PROCESS_RESTART,
                message("gui.interface.provider.help"));

        configureThemeSelector();
        addPreferenceField(content,
                message("gui.interface.theme.label"),
                themeCombo,
                GuiConfigEffect.HOT_RELOAD,
                message("gui.interface.theme.help"));

        configureExpandAllCheckBox();
        addPreferenceField(content,
                message("gui.interface.config-menu-expand-all.label"),
                expandAllCheckBox,
                GuiConfigEffect.HOT_RELOAD,
                message("gui.interface.config-menu-expand-all.help"));
        content.add(Box.createVerticalGlue());

        JScrollPane scrollPane = new JScrollPane(content);
        scrollPane.setBorder(null);
        scrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scrollPane.getVerticalScrollBar().setUnitIncrement(16);
        add(scrollPane, BorderLayout.CENTER);
    }

    private void configureLanguageSelector() {
        Font currentFont = languageCombo.getFont();
        languageCombo.setFont(new Font(Font.DIALOG, currentFont.getStyle(), currentFont.getSize()));
        List<LocaleOption> options = new ArrayList<>();
        options.add(new LocaleOption(null, message("gui.interface.language.option.follow-system")));
        for (DesktopUiHost.UiLocale descriptor : SwingHost.host().visibleLocales()) {
            options.add(new LocaleOption(descriptor.toLocale(), descriptor.nativeName()));
        }
        for (LocaleOption option : options) {
            languageCombo.addItem(option);
        }
        selectInitialLanguageOption(options);
        languageCombo.setToolTipText(message("gui.interface.language.tooltip"));
        languageCombo.putClientProperty(PREFERENCE_KEY_PROPERTY, LANGUAGE_CONFIG_KEY);
        languageCombo.addActionListener(languageActionListener);
    }

    private void configureProviderSelector() {
        List<ProviderOption> options = providerOptions(SwingHost.context().currentPluginSnapshots());
        options.forEach(providerCombo::addItem);
        ProviderOption selected = selectedProviderOption(options, preference(GUI_PROVIDER_CONFIG_KEY, "gui-swing"));
        providerCombo.setSelectedItem(selected);
        providerCombo.setEnabled(!options.isEmpty());
        providerCombo.setToolTipText(message("gui.interface.provider.help"));
        providerCombo.putClientProperty(PREFERENCE_KEY_PROPERTY, GUI_PROVIDER_CONFIG_KEY);
        if (selected != null) {
            draft.put(GUI_PROVIDER_CONFIG_KEY, selected.id());
        }
        providerCombo.addActionListener(providerActionListener);
    }

    private void configureThemeSelector() {
        refreshThemeOptions(preference(THEME_CONFIG_KEY, GuiThemeManager.configuredThemeId()), false);
        themeCombo.setToolTipText(message("gui.interface.theme.tooltip"));
        themeCombo.putClientProperty(PREFERENCE_KEY_PROPERTY, THEME_CONFIG_KEY);
    }

    private void configureExpandAllCheckBox() {
        expandAllCheckBox.setToolTipText(message("gui.interface.config-menu-expand-all.tooltip"));
        expandAllCheckBox.putClientProperty(PREFERENCE_KEY_PROPERTY, EXPAND_ALL_CONFIG_KEY);
        expandAllCheckBox.setSelected(Boolean.parseBoolean(preference(EXPAND_ALL_CONFIG_KEY, "false")));
        expandAllCheckBox.addActionListener(e -> applyExpandAllSelection());
    }

    private static void addPreferenceField(JPanel content,
                                           String labelText,
                                           JComponent control,
                                           GuiConfigEffect effect,
                                           String helpText) {
        JPanel field = FieldRenderer.fieldPanelWithEffect(
                labelText + message("gui.punctuation.colon"), control, effect, helpText);
        field.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(field);
        content.add(Box.createVerticalStrut(2));
    }

    private void selectInitialLanguageOption(List<LocaleOption> options) {
        String persisted = preference(LANGUAGE_CONFIG_KEY, "follow-system");
        if (persisted.isBlank() || "follow-system".equalsIgnoreCase(persisted)) {
            selectLanguageOption(options.get(0));
            return;
        }
        DesktopUiHost.UiLocale matched = SwingHost.host().matchLocale(persisted).orElse(null);
        if (matched == null) {
            selectLanguageOption(options.get(0));
            return;
        }
        Locale normalized = matched.toLocale();
        for (LocaleOption option : options) {
            if (option.locale() != null && option.locale().equals(normalized)) {
                selectLanguageOption(option);
                return;
            }
        }
        selectLanguageOption(options.get(0));
    }

    private void selectLanguageOption(LocaleOption option) {
        languageCombo.setSelectedItem(option);
        currentAppliedLanguageOption = option;
    }

    private String preference(String key, String fallback) {
        if (draft.containsKey(key)) {
            return draft.getOrDefault(key, fallback);
        }
        try {
            String value = SwingHost.host().applicationConfig().read(key);
            return value == null ? fallback : value;
        } catch (Exception e) {
            log.debug(logMessage("gui.interface.log.language.read-failed", e.getMessage()));
            return fallback;
        }
    }

    private void applyLanguageSelection() {
        if (languageChangeBlocked.getAsBoolean()) {
            languageCombo.removeActionListener(languageActionListener);
            if (currentAppliedLanguageOption != null) {
                languageCombo.setSelectedItem(currentAppliedLanguageOption);
            }
            languageCombo.addActionListener(languageActionListener);
            JOptionPane.showMessageDialog(this,
                    message("gui.update.dialog.language-blocked.message"),
                    message("gui.dialog.please-wait.title"), JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        LocaleOption option = (LocaleOption) languageCombo.getSelectedItem();
        if (option == null || Objects.equals(option, currentAppliedLanguageOption)) {
            return;
        }
        draft.put(LANGUAGE_CONFIG_KEY,
                option.locale() == null ? "follow-system" : option.locale().toLanguageTag());

        if (option.locale() != null) {
            Locale.setDefault(option.locale());
        } else {
            SwingHost.host().detectSystemLocale();
        }
        GuiMessages.clearLocaleOverride();
        SwingUtilities.invokeLater(() -> GuiThemeManager.applyThemeId(GuiThemeManager.configuredThemeId()));

        currentAppliedLanguageOption = option;
        SwingUtilities.invokeLater(onLocaleChanged);
    }

    private void applyProviderSelection() {
        ProviderOption option = (ProviderOption) providerCombo.getSelectedItem();
        if (option == null) {
            return;
        }
        draft.put(GUI_PROVIDER_CONFIG_KEY, option.id());
        refreshThemeOptions(preference(THEME_CONFIG_KEY, "system"), true);
    }

    private void applyThemeSelection() {
        StatusPanelThemeOption option = (StatusPanelThemeOption) themeCombo.getSelectedItem();
        if (option == null || option.unavailable()) {
            return;
        }
        String next = option.id();
        draft.put(THEME_CONFIG_KEY, next);
        SwingUtilities.invokeLater(() -> GuiThemeManager.applyThemeId(next));
    }

    private void syncThemeComboSelection() {
        themeCombo.removeActionListener(themeActionListener);
        StatusPanelThemeModel.selectThemeOption(themeCombo,
                preference(THEME_CONFIG_KEY, GuiThemeManager.configuredThemeId()));
        themeCombo.addActionListener(themeActionListener);
    }

    private void applyExpandAllSelection() {
        boolean expanded = expandAllCheckBox.isSelected();
        draft.put(EXPAND_ALL_CONFIG_KEY, Boolean.toString(expanded));
        onExpandAllChanged.accept(expanded);
    }

    Map<String, String> pendingValues() {
        LocaleOption locale = (LocaleOption) languageCombo.getSelectedItem();
        ProviderOption provider = (ProviderOption) providerCombo.getSelectedItem();
        StatusPanelThemeOption theme = (StatusPanelThemeOption) themeCombo.getSelectedItem();
        return Map.of(
                LANGUAGE_CONFIG_KEY,
                locale == null || locale.locale() == null ? "follow-system" : locale.locale().toLanguageTag(),
                GUI_PROVIDER_CONFIG_KEY,
                provider == null ? "gui-swing" : provider.id(),
                THEME_CONFIG_KEY,
                theme == null || theme.unavailable() ? "system" : theme.id(),
                EXPAND_ALL_CONFIG_KEY,
                Boolean.toString(expandAllCheckBox.isSelected()));
    }

    private void refreshThemeOptions(String selectedId, boolean applyFallback) {
        ProviderOption provider = (ProviderOption) providerCombo.getSelectedItem();
        List<StatusPanelThemeOption> options = themeOptions(
                SwingHost.context().currentPluginSnapshots(), provider == null ? null : provider.id());
        String selected = options.stream().anyMatch(option -> option.id().equals(selectedId))
                ? selectedId
                : "system";
        themeCombo.removeActionListener(themeActionListener);
        themeCombo.removeAllItems();
        options.forEach(themeCombo::addItem);
        StatusPanelThemeModel.selectThemeOption(themeCombo, selected);
        themeCombo.addActionListener(themeActionListener);
        draft.put(THEME_CONFIG_KEY, selected);
        if (applyFallback && !Objects.equals(selectedId, selected)) {
            SwingUtilities.invokeLater(() -> GuiThemeManager.applyThemeId(selected));
        }
    }

    static List<ProviderOption> providerOptions(List<DesktopUiPluginSnapshot> snapshots) {
        return providerOptions(snapshots, SwingHost.context()::resolveText);
    }

    static List<ProviderOption> providerOptions(List<DesktopUiPluginSnapshot> snapshots,
                                                Function<DesktopUiText, String> resolver) {
        if (snapshots == null || snapshots.isEmpty()) {
            return List.of();
        }
        List<ProviderOption> options = new ArrayList<>();
        snapshots.stream()
                .filter(Objects::nonNull)
                .filter(DesktopUiPluginSnapshot::desktopUiProvider)
                .sorted(Comparator.comparing(DesktopUiPluginSnapshot::id))
                .forEach(snapshot -> {
                    try {
                        String label = resolver.apply(snapshot.displayName());
                        options.add(new ProviderOption(snapshot.id(),
                                label == null || label.isBlank() ? snapshot.id() : label));
                    } catch (RuntimeException failure) {
                        log.warn("Ignored invalid desktop UI provider '{}': {}",
                                snapshot.id(), failure.toString());
                    }
                });
        return List.copyOf(options);
    }

    static ProviderOption selectedProviderOption(List<ProviderOption> options, String configuredId) {
        if (options == null || options.isEmpty()) {
            return null;
        }
        String configured = configuredId == null ? "" : configuredId.trim();
        return options.stream().filter(option -> option.id().equals(configured)).findFirst()
                .or(() -> options.stream().filter(option -> option.id().equals("gui-swing")).findFirst())
                .orElse(options.get(0));
    }

    static List<StatusPanelThemeOption> themeOptions(List<DesktopUiPluginSnapshot> snapshots,
                                                      String providerId) {
        Map<String, StatusPanelThemeOption> options = new LinkedHashMap<>();
        options.put("system", new StatusPanelThemeOption(
                "system", message("gui.interface.theme.option.system"), false));
        options.put("light", new StatusPanelThemeOption(
                "light", message("gui.interface.theme.option.light"), false));
        options.put("dark", new StatusPanelThemeOption(
                "dark", message("gui.interface.theme.option.dark"), false));
        if (snapshots == null || providerId == null) {
            return List.copyOf(options.values());
        }
        DesktopUiPluginSnapshot provider = snapshots.stream()
                .filter(Objects::nonNull)
                .filter(DesktopUiPluginSnapshot::desktopUiProvider)
                .filter(snapshot -> providerId.equals(snapshot.id()))
                .findFirst()
                .orElse(null);
        if (provider == null) {
            return List.copyOf(options.values());
        }
        for (GuiThemeContribution contribution : provider.themes()) {
            if (contribution == null || options.containsKey(contribution.themeId())) {
                continue;
            }
            try {
                options.put(contribution.themeId(), new StatusPanelThemeOption(
                        contribution.themeId(), contribution.displayName(GuiMessages.currentLocale()), false));
            } catch (RuntimeException failure) {
                log.warn("Ignored invalid desktop UI theme '{}' from provider '{}': {}",
                        contribution.themeId(), providerId, failure.toString());
            }
        }
        return List.copyOf(options.values());
    }

    private static String message(String code, Object... args) {
        return GuiMessages.get(code, args);
    }

    private static String logMessage(String code, Object... args) {
        return SwingHost.host().message(code, args);
    }

    private static final class PreferenceContentPanel extends JPanel implements Scrollable {

        private PreferenceContentPanel() {
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

    private record LocaleOption(Locale locale, String label) {
        @Override
        public String toString() {
            return label;
        }
    }

    record ProviderOption(String id, String label) {
        @Override
        public String toString() {
            return label;
        }
    }
}
