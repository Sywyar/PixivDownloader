package top.sywyar.pixivdownload.gui.panel;

import javax.swing.JComboBox;

final class StatusPanelThemeModel {

    private StatusPanelThemeModel() {
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
