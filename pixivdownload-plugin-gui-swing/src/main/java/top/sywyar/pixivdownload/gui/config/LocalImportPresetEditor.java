package top.sywyar.pixivdownload.gui.config;

import top.sywyar.pixivdownload.guiswing.SwingHost;
import top.sywyar.pixivdownload.plugin.api.gui.importing.LocalArtworkImports.Preset;
import top.sywyar.pixivdownload.plugin.api.gui.importing.LocalArtworkImports.VariableException;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.util.ArrayList;

/** 设置草稿中的本地导入预设编辑器，确认弹窗只修改草稿。 */
final class LocalImportPresetEditor extends JButton {
    private String value = "[]";
    LocalImportPresetEditor(String initial) { setValue(initial); addActionListener(e -> edit()); }
    String value() { return value; }
    void setValue(String next) {
        int count = SwingHost.context().host().parseLocalImportPresets(next).size();
        String previous = value; value = next;
        setText(message("presets-count", count));
        firePropertyChange("presetValue", previous, next);
    }
    private static String message(String key, Object... args) { return SwingHost.context().host().message("local-import." + key, args); }
    private void edit() {
        var host = SwingHost.context().host();
        var model = new DefaultTableModel(new Object[]{message("enabled"), message("name"), message("template")}, 0) {
            @Override public Class<?> getColumnClass(int column) { return column == 0 ? Boolean.class : String.class; }
        };
        host.parseLocalImportPresets(value).forEach(p -> model.addRow(new Object[]{p.enabled(), p.name(), p.template()}));
        JTable table = new JTable(model); table.setRowHeight(Math.max(26, table.getRowHeight()));
        table.getColumnModel().getColumn(0).setMaxWidth(80); table.getColumnModel().getColumn(1).setPreferredWidth(140); table.getColumnModel().getColumn(2).setPreferredWidth(460);
        table.getAccessibleContext().setAccessibleName(message("presets"));
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        var scroll = new JScrollPane(table); scroll.setPreferredSize(new Dimension(680, 260)); panel.add(scroll, BorderLayout.CENTER);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEADING)); JButton add = new JButton(message("add")), remove = new JButton(message("remove"));
        add.addActionListener(e -> { if (model.getRowCount() < 32) model.addRow(new Object[]{true, "", ""}); });
        remove.addActionListener(e -> { if (table.getSelectedRow() >= 0) model.removeRow(table.getSelectedRow()); });
        actions.add(add); actions.add(remove); panel.add(actions, BorderLayout.SOUTH);
        panel.add(new JLabel(message("presets-save-help")), BorderLayout.NORTH);
        while (JOptionPane.showConfirmDialog(this, panel, message("presets"), JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION) {
            if (table.isEditing() && !table.getCellEditor().stopCellEditing()) continue;
            try {
                var entries = new ArrayList<Preset>();
                for (int row = 0; row < model.getRowCount(); row++) entries.add(new Preset(
                        text(model.getValueAt(row, 1)), text(model.getValueAt(row, 2)), Boolean.TRUE.equals(model.getValueAt(row, 0))));
                setValue(host.formatLocalImportPresets(entries)); return;
            } catch (RuntimeException invalid) {
                String error = invalid instanceof VariableException variable
                        ? message("error." + variable.getMessage(), "{" + variable.variable() + "}")
                        : message("error.INVALID_IMPORT_PRESET");
                JOptionPane.showMessageDialog(this, error, message("presets"), JOptionPane.ERROR_MESSAGE);
            }
        }
    }
    private static String text(Object value) { return value == null ? "" : value.toString().strip(); }
}
