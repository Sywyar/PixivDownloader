package top.sywyar.pixivdownload.gui.panel;

import lombok.extern.slf4j.Slf4j;
import top.sywyar.pixivdownload.gui.config.FieldRenderer;
import top.sywyar.pixivdownload.gui.i18n.GuiMessages;
import top.sywyar.pixivdownload.gui.i18n.PluginContributionText;
import top.sywyar.pixivdownload.gui.theme.GuiThemeManager;
import top.sywyar.pixivdownload.guiswing.SwingHost;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiContext;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiHost;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiProvider;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigEffect;
import top.sywyar.pixivdownload.plugin.api.gui.GuiThemeListenerSession;

import javax.swing.*;
import java.awt.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * “界面”配置页：集中管理语言、桌面 UI 提供者、主题与配置菜单层级偏好。
 *
 * <p>偏好直接写回 {@code config.yaml}，不经过普通配置字段网格。桌面 UI 提供者在完整进程重启后生效，
 * 其它三项即时生效。
 * 语言切换仍由主窗口回调重建本地化面板；主题继续由 {@link GuiThemeManager} 应用；
 * 菜单层级只通过中性的布尔回调通知 {@link ConfigPanel} 重新挂载既有叶子页面。</p>
 */
@Slf4j
final class InterfacePreferencesPanel extends JPanel {

    static final String EXPAND_ALL_CONFIG_KEY = "app.config-menu-expand-all";
    static final String GUI_PROVIDER_CONFIG_KEY = "app.gui-provider";
    static final String PREFERENCE_KEY_PROPERTY = "gui.interface.preference-key";

    private final Path configPath;
    private final Runnable onLocaleChanged;
    private final BooleanSupplier languageChangeBlocked;
    private final Consumer<Boolean> onExpandAllChanged;

    private final JComboBox<LocaleOption> languageCombo = new JComboBox<>();
    private final JComboBox<ProviderOption> providerCombo = new JComboBox<>();
    private final JComboBox<StatusPanelThemeOption> themeCombo = new JComboBox<>();
    private final JCheckBox expandAllCheckBox = new JCheckBox();

    private final java.awt.event.ActionListener languageActionListener = e -> applyLanguageSelection();
    private final java.awt.event.ActionListener providerActionListener = e -> applyProviderSelection();
    private final java.awt.event.ActionListener themeActionListener = e -> applyThemeSelection();
    private final Runnable themeChangeListener = this::syncThemeComboSelection;

    private LocaleOption currentAppliedLanguageOption;
    private ProviderOption currentProviderOption;
    private GuiThemeListenerSession themeListenerSession = GuiThemeListenerSession.none();

    InterfacePreferencesPanel(Path configPath,
                              Runnable onLocaleChanged,
                              BooleanSupplier languageChangeBlocked,
                              Consumer<Boolean> onExpandAllChanged) {
        this.configPath = configPath;
        this.onLocaleChanged = onLocaleChanged == null ? () -> { } : onLocaleChanged;
        this.languageChangeBlocked = languageChangeBlocked == null ? () -> false : languageChangeBlocked;
        this.onExpandAllChanged = onExpandAllChanged == null ? ignored -> { } : onExpandAllChanged;
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
        languageCombo.putClientProperty(PREFERENCE_KEY_PROPERTY, "app.language");
        languageCombo.addActionListener(languageActionListener);
    }

    private void configureThemeSelector() {
        StatusPanelThemeModel.refreshOptions(themeCombo,
                GuiMessages.currentLocale(),
                message("gui.interface.theme.option.unavailable"),
                message("gui.interface.theme.option.system-fallback"),
                GuiThemeManager.configuredThemeId());
        StatusPanelThemeModel.syncSelection(themeCombo);
        themeCombo.setToolTipText(message("gui.interface.theme.tooltip"));
        themeCombo.putClientProperty(PREFERENCE_KEY_PROPERTY, "app.theme");
        themeCombo.addActionListener(themeActionListener);
    }

    private void configureProviderSelector() {
        List<ProviderOption> options;
        try {
            options = providerOptions(SwingHost.context().currentPluginSources());
        } catch (RuntimeException e) {
            log.warn(logMessage("gui.config.log.read-failed", e.getMessage()));
            options = List.of();
        }
        options.forEach(providerCombo::addItem);

        String configured = readPersistedProviderId();
        ProviderOption selected = selectedProviderOption(options, configured);
        providerCombo.setSelectedItem(selected);
        currentProviderOption = selected;
        providerCombo.setEnabled(options.size() > 1);
        providerCombo.setToolTipText(message("gui.interface.provider.tooltip"));
        providerCombo.putClientProperty(PREFERENCE_KEY_PROPERTY, GUI_PROVIDER_CONFIG_KEY);
        providerCombo.addActionListener(providerActionListener);

        boolean configuredUnavailable = configured != null && !configured.isBlank()
                && options.stream().noneMatch(option -> option.id().equals(configured.trim()));
        if (configuredUnavailable && selected != null) {
            persistProviderPreference(selected.id());
        }
    }

    private void configureExpandAllCheckBox() {
        expandAllCheckBox.setToolTipText(message("gui.interface.config-menu-expand-all.tooltip"));
        expandAllCheckBox.putClientProperty(PREFERENCE_KEY_PROPERTY, EXPAND_ALL_CONFIG_KEY);
        expandAllCheckBox.setSelected(readExpandAllPreference());
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
        String persisted = readPersistedLanguageTag();
        if (persisted == null || persisted.isBlank()) {
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

    private String readPersistedLanguageTag() {
        if (configPath == null || !Files.exists(configPath)) {
            return null;
        }
        try {
            return SwingHost.host().applicationConfig().read("app.language");
        } catch (Exception e) {
            log.debug(logMessage("gui.interface.log.language.read-failed", e.getMessage()));
            return null;
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
        String persistValue = option.locale() == null ? "" : option.locale().toLanguageTag();
        boolean persisted = persistLanguagePreference(persistValue);

        if (option.locale() != null) {
            Locale.setDefault(option.locale());
        } else {
            SwingHost.host().detectSystemLocale();
        }
        GuiMessages.clearLocaleOverride();
        SwingUtilities.invokeLater(() -> GuiThemeManager.applyThemeId(GuiThemeManager.configuredThemeId()));

        if (!persisted && configPath != null) {
            log.warn(logMessage("gui.interface.log.language.persist-failed-warn", configPath));
            JOptionPane.showMessageDialog(this,
                    message("gui.interface.language.persist-failed.message"),
                    message("gui.dialog.error.title"), JOptionPane.WARNING_MESSAGE);
        }

        currentAppliedLanguageOption = option;
        SwingUtilities.invokeLater(onLocaleChanged);
    }

    private boolean persistLanguagePreference(String value) {
        if (configPath == null || !Files.exists(configPath)) {
            return false;
        }
        try {
            SwingHost.host().applicationConfig().write("app.language", value == null ? "" : value);
            return true;
        } catch (Exception e) {
            log.warn(logMessage("gui.interface.log.language.persist-failed", e.getMessage()));
            return false;
        }
    }

    private String readPersistedProviderId() {
        if (configPath == null || !Files.exists(configPath)) {
            return null;
        }
        try {
            return SwingHost.host().applicationConfig().read(GUI_PROVIDER_CONFIG_KEY);
        } catch (Exception e) {
            log.warn(logMessage("gui.config.log.read-failed", e.getMessage()));
            return null;
        }
    }

    private void applyProviderSelection() {
        ProviderOption selected = (ProviderOption) providerCombo.getSelectedItem();
        if (selected == null || selected.equals(currentProviderOption)) {
            return;
        }
        if (!persistProviderPreference(selected.id())) {
            providerCombo.removeActionListener(providerActionListener);
            providerCombo.setSelectedItem(currentProviderOption);
            providerCombo.addActionListener(providerActionListener);
            JOptionPane.showMessageDialog(this,
                    message("gui.interface.provider.persist-failed.message"),
                    message("gui.dialog.error.title"), JOptionPane.WARNING_MESSAGE);
            return;
        }
        currentProviderOption = selected;
        JOptionPane.showMessageDialog(this,
                message("gui.interface.provider.restart-required.message", selected.label()),
                message("gui.dialog.info.title"), JOptionPane.INFORMATION_MESSAGE);
    }

    private boolean persistProviderPreference(String providerId) {
        if (configPath == null || !Files.exists(configPath)) {
            return false;
        }
        try {
            SwingHost.host().applicationConfig().write(GUI_PROVIDER_CONFIG_KEY, providerId);
            return true;
        } catch (Exception e) {
            log.warn(logMessage("gui.config.log.save-failed", e.getMessage()));
            return false;
        }
    }

    static List<ProviderOption> providerOptions(List<DesktopUiContext.PluginSource> sources) {
        List<ProviderOption> options = new ArrayList<>();
        List<DesktopUiContext.PluginSource> safeSources = sources == null ? List.of() : sources;
        for (DesktopUiContext.PluginSource source : safeSources) {
            if (!(source.plugin() instanceof DesktopUiProvider provider)) {
                continue;
            }
            boolean defaultProvider;
            try {
                defaultProvider = provider.defaultProvider();
            } catch (RuntimeException ignored) {
                defaultProvider = false;
            }
            String label = source.id();
            try {
                String key = source.plugin().displayName();
                String resolved = PluginContributionText.resolve(source.plugin().i18n(), source.classLoader(),
                        source.plugin().displayNamespace(), key);
                if (resolved != null && !resolved.isBlank() && !resolved.equals(key)) {
                    label = resolved;
                }
            } catch (RuntimeException ignored) {
                // A broken display contribution must not hide an otherwise valid desktop provider.
            }
            options.add(new ProviderOption(source.id(), label, defaultProvider));
        }
        return List.copyOf(options);
    }

    static ProviderOption selectedProviderOption(List<ProviderOption> options, String configuredId) {
        String configured = configuredId == null ? "" : configuredId.trim();
        if (!configured.isEmpty()) {
            for (ProviderOption option : options) {
                if (option.id().equals(configured)) {
                    return option;
                }
            }
        }
        for (ProviderOption option : options) {
            if (option.defaultProvider()) {
                return option;
            }
        }
        return null;
    }

    private void applyThemeSelection() {
        StatusPanelThemeOption option = (StatusPanelThemeOption) themeCombo.getSelectedItem();
        if (option == null || option.unavailable()) {
            return;
        }
        String next = option.id();
        if (next.equals(GuiThemeManager.configuredThemeId())) {
            return;
        }

        boolean persisted = GuiThemeManager.persistThemeId(SwingHost.host().applicationConfig(), next);
        SwingUtilities.invokeLater(() -> GuiThemeManager.applyThemeId(next));

        if (!persisted && configPath != null) {
            log.warn(logMessage("gui.interface.log.theme.persist-failed-warn", configPath));
            JOptionPane.showMessageDialog(this,
                    message("gui.interface.theme.persist-failed.message"),
                    message("gui.dialog.error.title"), JOptionPane.WARNING_MESSAGE);
        }
    }

    private void syncThemeComboSelection() {
        themeCombo.removeActionListener(themeActionListener);
        StatusPanelThemeModel.syncSelection(themeCombo);
        themeCombo.addActionListener(themeActionListener);
    }

    private boolean readExpandAllPreference() {
        if (configPath == null || !Files.exists(configPath)) {
            return false;
        }
        try {
            return Boolean.parseBoolean(SwingHost.host().applicationConfig().read(EXPAND_ALL_CONFIG_KEY));
        } catch (Exception e) {
            log.warn(logMessage("gui.interface.log.config-menu-expand-all.read-failed", e.getMessage()));
            return false;
        }
    }

    private void applyExpandAllSelection() {
        boolean expanded = expandAllCheckBox.isSelected();
        boolean persisted = persistExpandAllPreference(expanded);
        onExpandAllChanged.accept(expanded);
        if (!persisted && configPath != null) {
            log.warn(logMessage("gui.interface.log.config-menu-expand-all.persist-failed-warn", configPath));
            JOptionPane.showMessageDialog(this,
                    message("gui.interface.config-menu-expand-all.persist-failed.message"),
                    message("gui.dialog.error.title"), JOptionPane.WARNING_MESSAGE);
        }
    }

    private boolean persistExpandAllPreference(boolean expanded) {
        if (configPath == null || !Files.exists(configPath)) {
            return false;
        }
        try {
            SwingHost.host().applicationConfig().write(EXPAND_ALL_CONFIG_KEY, Boolean.toString(expanded));
            return true;
        } catch (Exception e) {
            log.warn(logMessage("gui.interface.log.config-menu-expand-all.persist-failed", e.getMessage()));
            return false;
        }
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

    record ProviderOption(String id, String label, boolean defaultProvider) {
        ProviderOption {
            id = Objects.requireNonNull(id, "id");
            label = Objects.requireNonNull(label, "label");
        }

        @Override
        public String toString() {
            return label + " (" + id + ")";
        }
    }
}
