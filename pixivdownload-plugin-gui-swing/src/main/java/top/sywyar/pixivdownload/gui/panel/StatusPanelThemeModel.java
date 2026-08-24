package top.sywyar.pixivdownload.gui.panel;

import top.sywyar.pixivdownload.gui.theme.GuiThemeManager;

import javax.swing.JComboBox;
import java.util.Locale;

final class StatusPanelThemeModel {

    private StatusPanelThemeModel() {
    }

    static String selectedThemeId(JComboBox<StatusPanelThemeOption> combo, String fallback) {
        Object selected = combo.getSelectedItem();
        return selected instanceof StatusPanelThemeOption option ? option.id() : fallback;
    }

    static void refreshOptions(JComboBox<StatusPanelThemeOption> combo,
                               Locale locale,
                               String unavailableLabel,
                               String fallbackLabel,
                               String selectedId) {
        combo.removeAllItems();
        for (GuiThemeManager.ThemeChoice choice : GuiThemeManager.choices(locale, unavailableLabel, fallbackLabel)) {
            combo.addItem(new StatusPanelThemeOption(choice.id(), choice.displayName(), choice.unavailable()));
        }
        selectThemeOption(combo, selectedId);
    }

    static void syncSelection(JComboBox<StatusPanelThemeOption> combo) {
        String configured = GuiThemeManager.configuredThemeId();
        if (!selectThemeOption(combo, configured)) {
            selectThemeOption(combo, GuiThemeManager.activeThemeId());
        }
    }

    static boolean selectThemeOption(JComboBox<StatusPanelThemeOption> combo, String themeId) {
        for (int i = 0; i < combo.getItemCount(); i++) {
            StatusPanelThemeOption option = combo.getItemAt(i);
            if (option.id().equals(themeId)) {
                if (combo.getSelectedItem() != option) {
                    combo.setSelectedItem(option);
                }
                return true;
            }
        }
        return false;
    }
}

record StatusPanelThemeOption(String id, String label, boolean unavailable) {
    @Override
    public String toString() {
        return label;
    }
}
