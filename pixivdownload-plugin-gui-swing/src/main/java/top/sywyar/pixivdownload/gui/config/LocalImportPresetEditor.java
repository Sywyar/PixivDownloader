package top.sywyar.pixivdownload.gui.config;

import top.sywyar.pixivdownload.guiswing.SwingHost;
import top.sywyar.pixivdownload.core.work.filename.WorkTemplateVariables;
import top.sywyar.pixivdownload.plugin.api.gui.importing.LocalArtworkImports.Preset;
import top.sywyar.pixivdownload.plugin.api.gui.importing.LocalArtworkImports.VariableException;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.util.ArrayList;

/** 路径列表编辑器；工具入口独立保存，普通配置字段入口只修改草稿。 */
public final class LocalImportPresetEditor extends JButton {
    private String value = "[]";
    private boolean persistent;
    private Runnable afterSave;
    LocalImportPresetEditor(String initial) { setValue(initial); addActionListener(e -> { if (persistent) loadSaved(); else edit(); }); }
    /** 创建明确保存预设的工具入口。
     * @param afterSave 保存成功后刷新相关工具状态
     * @return 预设编辑按钮
     */
    public static JButton savedPresets(Runnable afterSave) {
        var editor = new LocalImportPresetEditor("[]");
        editor.persistent = true; editor.afterSave = afterSave;
        editor.setText(message("presets"));
        editor.setName("local-import.presets");
        return editor;
    }
    private void loadSaved() {
        setEnabled(false);
        new SwingWorker<String, Void>() {
            protected String doInBackground() throws Exception {
                return SwingHost.context().host().applicationConfig().readAll(java.util.List.of("local-import.presets"))
                        .getOrDefault("local-import.presets", "[]");
            }
            protected void done() {
                setEnabled(true);
                try { setValue(get()); edit(); }
                catch (Exception failure) {
                    org.slf4j.LoggerFactory.getLogger(LocalImportPresetEditor.class).error("Cannot load local import presets", failure);
                    top.sywyar.pixivdownload.gui.GuiErrorDialog.show(LocalImportPresetEditor.this,
                            message("presets"), message("presets-load-failed"));
                }
            }
        }.execute();
    }
    String value() { return value; }
    void setValue(String next) {
        int count = SwingHost.context().host().parseLocalImportPresets(next).size();
        String previous = value; value = next;
        setText(persistent ? message("presets") : message("presets-count", count));
        firePropertyChange("presetValue", previous, next);
    }
    private static String message(String key, Object... args) { return SwingHost.context().host().message("local-import." + key, args); }
    private void edit() {
        var host = SwingHost.context().host();
        var model = new DefaultTableModel(new Object[]{message("template")}, 0) {
            @Override public Class<?> getColumnClass(int column) { return String.class; }
        };
        host.parseLocalImportPresets(value).forEach(p -> model.addRow(new Object[]{p.template()}));
        JTable table = new JTable(model); table.setRowHeight(Math.max(26, table.getRowHeight()));
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.putClientProperty("terminateEditOnFocusLost", true);
        table.getAccessibleContext().setAccessibleName(message("presets"));
        var dialog = new JDialog(SwingUtilities.getWindowAncestor(this), message("presets"), Dialog.ModalityType.APPLICATION_MODAL);
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
        var scroll = new JScrollPane(table); panel.add(scroll, BorderLayout.CENTER);
        var header = new JPanel(new BorderLayout(8, 6));
        header.add(help(message(persistent ? "presets-save-direct-help" : "presets-save-help")), BorderLayout.NORTH);
        var rules = new JToggleButton(host.message("gui.config.directory.variables"));
        var explanation = new JPanel(new BorderLayout(0, 6));
        explanation.setOpaque(false);
        explanation.add(help(message("preset-template-help", WorkTemplateVariables.importTokens())), BorderLayout.NORTH);
        var kind = help(message("template-kind-help"));
        kind.setForeground(UIManager.getColor("Component.linkColor"));
        explanation.add(kind, BorderLayout.CENTER);
        explanation.setVisible(false);
        rules.addActionListener(e -> { explanation.setVisible(rules.isSelected()); panel.revalidate(); panel.repaint(); });
        header.add(rules, BorderLayout.WEST); header.add(explanation, BorderLayout.SOUTH);
        panel.add(header, BorderLayout.NORTH);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEADING));
        JButton add = new JButton(message("add")), remove = new JButton(message("remove"));
        remove.setEnabled(false);
        Runnable update = () -> { add.setEnabled(model.getRowCount() < 32); remove.setEnabled(table.getSelectedRow() >= 0); };
        table.getSelectionModel().addListSelectionListener(e -> update.run());
        add.addActionListener(e -> {
            if (model.getRowCount() >= 32) return;
            if (table.isEditing() && !table.getCellEditor().stopCellEditing()) return;
            model.addRow(new Object[]{""});
            int row = model.getRowCount() - 1;
            table.setRowSelectionInterval(row, row); table.scrollRectToVisible(table.getCellRect(row, 0, true));
            table.editCellAt(row, 0); table.getEditorComponent().requestFocusInWindow(); update.run();
        });
        remove.addActionListener(e -> {
            if (table.isEditing() && !table.getCellEditor().stopCellEditing()) return;
            int row = table.getSelectedRow();
            if (row < 0) return;
            model.removeRow(row);
            if (model.getRowCount() > 0) { row = Math.min(row, model.getRowCount() - 1); table.setRowSelectionInterval(row, row); }
            table.requestFocusInWindow(); update.run();
        });
        actions.add(add); actions.add(remove);
        var footer = new JPanel(new BorderLayout(8, 6));
        var error = help(""); error.setVisible(false); error.setForeground(UIManager.getColor("Component.error.focusedBorderColor"));
        footer.add(error, BorderLayout.NORTH); footer.add(actions, BorderLayout.WEST);
        var confirmation = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        var cancel = new JButton(message("cancel")); var apply = new JButton(message(persistent ? "save" : "apply"));
        cancel.addActionListener(e -> dialog.dispose());
        apply.addActionListener(e -> {
            if (table.isEditing() && !table.getCellEditor().stopCellEditing()) return;
            try {
                var entries = new ArrayList<Preset>();
                for (int row = 0; row < model.getRowCount(); row++) entries.add(new Preset(text(model.getValueAt(row, 0))));
                String encoded = host.formatLocalImportPresets(entries);
                if (!persistent) { setValue(encoded); dialog.dispose(); }
                else {
                    apply.setEnabled(false); cancel.setEnabled(false); table.setEnabled(false); add.setEnabled(false); remove.setEnabled(false);
                    dialog.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
                    new SwingWorker<Void, Void>() {
                        protected Void doInBackground() throws Exception { host.saveLocalImportPresets(encoded); return null; }
                        protected void done() {
                            try { get(); setValue(encoded); afterSave.run(); dialog.dispose(); }
                            catch (Exception failure) {
                                org.slf4j.LoggerFactory.getLogger(LocalImportPresetEditor.class).error("Cannot save local import presets", failure);
                                error.setText(message("presets-save-failed")); error.setVisible(true);
                            } finally {
                                apply.setEnabled(true); cancel.setEnabled(true); table.setEnabled(true); update.run();
                                dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
                                panel.revalidate(); panel.repaint();
                            }
                        }
                    }.execute();
                }
            } catch (RuntimeException invalid) {
                error.setText(invalid instanceof VariableException variable
                        ? message("error." + variable.getMessage(), "{" + variable.variable() + "}")
                        : message("error.INVALID_IMPORT_PRESET"));
                error.setVisible(true); panel.revalidate(); panel.repaint();
            }
        });
        confirmation.add(cancel); confirmation.add(apply); footer.add(confirmation, BorderLayout.EAST);
        panel.add(footer, BorderLayout.SOUTH);
        dialog.setContentPane(panel); dialog.setSize(760, 480); dialog.setMinimumSize(new Dimension(520, 320));
        dialog.setLocationRelativeTo(this);
        dialog.getRootPane().registerKeyboardAction(e -> { if (apply.isEnabled()) dialog.dispose(); }, KeyStroke.getKeyStroke("ESCAPE"), JComponent.WHEN_IN_FOCUSED_WINDOW);
        update.run(); dialog.setVisible(true); requestFocusInWindow();
    }
    private static JTextArea help(String text) {
        var area = new JTextArea(text);
        area.setEditable(false); area.setOpaque(false); area.setLineWrap(true); area.setWrapStyleWord(true);
        area.setFont(UIManager.getFont("Label.font"));
        return area;
    }
    private static String text(Object value) { return value == null ? "" : value.toString().strip(); }
}
