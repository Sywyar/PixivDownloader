package top.sywyar.pixivdownload.gui.panel;

import top.sywyar.pixivdownload.plugin.api.gui.DesktopEnvironmentTransfer;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiHost;

import javax.swing.*;
import java.awt.*;
import java.nio.file.Path;
import java.util.*;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** 设置页配置迁移；文件操作在工作线程执行，主设置草稿不参与导出。 */
final class EnvironmentTransferDialog {
    private final Component parent;
    private final DesktopUiHost host;
    private final DesktopEnvironmentTransfer transfer;
    private final BooleanSupplier unsaved;

    EnvironmentTransferDialog(Component parent, DesktopUiHost host, BooleanSupplier unsaved) {
        this.parent = parent; this.host = host; this.transfer = host.environmentTransfer().orElseThrow(); this.unsaved = unsaved;
    }

    void open() {
        work(() -> new Overview(transfer.restoreState(), transfer.installation()), this::chooseOperation);
    }

    private record Overview(DesktopEnvironmentTransfer.RestoreState restore, DesktopEnvironmentTransfer.Installation installation) {}

    private void chooseOperation(Overview overview) {
        var state = overview.restore();
        Object[] choices = {host.message("transfer.export"), host.message("transfer.import"), host.message("desktop.ui.action.close")};
        var messages = new ArrayList<String>();
        if (state.status() != DesktopEnvironmentTransfer.Status.NONE)
            messages.add(host.message("transfer.status." + state.status().name().toLowerCase(Locale.ROOT)));
        if (overview.installation().kind() != DesktopEnvironmentTransfer.InstallationKind.UNSUPPORTED) {
            var installation = overview.installation();
            messages.add(host.message("transfer.installation." + installation.kind().name().toLowerCase(Locale.ROOT)));
            messages.add(host.message("transfer.installation-paths", installation.directory(), installation.registeredDirectory()));
        }
        messages.add(host.message("transfer.saved-only"));
        int chosen = JOptionPane.showOptionDialog(parent, messages.toArray(), host.message("transfer.title"),
                JOptionPane.DEFAULT_OPTION, JOptionPane.PLAIN_MESSAGE, null, choices, choices[0]);
        if (chosen == 0) exportConfiguration();
        else if (chosen == 1) importConfiguration();
    }

    private void exportConfiguration() {
        JFileChooser chooser = chooser();
        chooser.setSelectedFile(new java.io.File("PixivDownloader.pdbackup"));
        if (chooser.showSaveDialog(parent) != JFileChooser.APPROVE_OPTION) return;
        JCheckBox credentials = new JCheckBox(host.message("transfer.credentials"));
        JCheckBox databases = new JCheckBox(host.message("transfer.databases"));
        JCheckBox states = new JCheckBox(host.message("transfer.states"));
        JCheckBox resources = new JCheckBox(host.message("transfer.resources"));
        resources.addActionListener(event -> { if (resources.isSelected()) databases.setSelected(true); });
        databases.addActionListener(event -> { if (!databases.isSelected()) resources.setSelected(false); });
        JPasswordField password = new JPasswordField(28);
        JLabel label = new JLabel(host.message("transfer.password"));
        label.setLabelFor(password);
        if (JOptionPane.showConfirmDialog(parent, new Object[]{credentials, databases, states, resources, host.message("transfer.database-export-hint"), label, password, host.message("transfer.password-hint")},
                host.message("transfer.export"), JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) {
            password.setText("");
            return;
        }
        char[] secret = password.getPassword();
        password.setText("");
        boolean include = credentials.isSelected();
        boolean includeDatabases = databases.isSelected();
        boolean includeState = states.isSelected();
        boolean includeResources = resources.isSelected();
        Path destination = chooser.getSelectedFile().toPath();
        work(() -> {
            try { transfer.exportConfiguration(destination, secret, include, includeDatabases, includeState, includeResources); return true; }
            finally { Arrays.fill(secret, '\0'); }
        }, ignored -> notice("transfer.exported"));
    }

    private void importConfiguration() {
        if (unsaved.getAsBoolean()) { notice("transfer.unsaved"); return; }
        JFileChooser chooser = chooser();
        if (chooser.showOpenDialog(parent) != JFileChooser.APPROVE_OPTION) return;
        JPasswordField field = new JPasswordField(28);
        JLabel label = new JLabel(host.message("transfer.password"));
        label.setLabelFor(field);
        if (JOptionPane.showConfirmDialog(parent, new Object[]{label, field, host.message("transfer.password-hint")},
                host.message("transfer.import"), JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) {
            field.setText("");
            return;
        }
        char[] password = field.getPassword();
        field.setText("");
        Path path = chooser.getSelectedFile().toPath();
        work(() -> {
            try { return transfer.previewConfiguration(path, password); }
            catch (Exception failure) { Arrays.fill(password, '\0'); throw failure; }
        }, preview -> {
            try { confirm(preview, password); }
            finally { Arrays.fill(password, '\0'); }
        });
    }

    private void confirm(DesktopEnvironmentTransfer.Preview preview, char[] password) {
        JPanel content = new ConfigContentPanel();
        content.add(description("transfer.confirm-hint"));
        for (var plugin : preview.plugins()) {
            JTextField item = new JTextField(host.message("transfer.inventory-package", plugin.id(), plugin.sourceVersion(),
                    plugin.installed() ? plugin.currentVersion() : host.message("transfer.plugin-missing")));
            item.setEditable(false);
            item.setMaximumSize(new Dimension(Integer.MAX_VALUE, item.getPreferredSize().height));
            content.add(item);
        }
        if (!preview.repositories().isEmpty()) content.add(description("transfer.repositories-hint"));
        for (var repository : preview.repositories()) {
            JTextField item = new JTextField(repository.id() + ": " + repository.descriptorUrl() + " " + repository.manifestUrl());
            item.setEditable(false);
            item.setMaximumSize(new Dimension(Integer.MAX_VALUE, item.getPreferredSize().height));
            content.add(item);
        }
        JCheckBox databases = new JCheckBox(host.message("transfer.databases"));
        if (!preview.databases().isEmpty()) {
            content.add(databases);
            content.add(description("transfer.database-import-hint"));
            for (var store : preview.databases()) content.add(description("transfer.database-store", store.owner(), store.rows(),
                    host.message(store.replacesExisting() ? "transfer.database-replace" : "transfer.database-create")));
        }
        if (!preview.databaseFailure().isEmpty()) content.add(description("transfer.database-unavailable"));
        Map<Long, JTextField> databasePaths = new LinkedHashMap<>();
        if (!preview.databasePaths().isEmpty()) content.add(description("transfer.database-path-hint"));
        for (var path : preview.databasePaths()) {
            JPanel row = new JPanel(new BorderLayout(6, 4));
            JLabel label = new JLabel(host.message("transfer.database-path", path.id()));
            JTextField input = new JTextField(path.source());
            label.setLabelFor(input);
            input.setEnabled(false);
            databases.addActionListener(event -> input.setEnabled(databases.isSelected()));
            row.add(label, BorderLayout.NORTH);
            row.add(input, BorderLayout.CENTER);
            row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
            content.add(row);
            databasePaths.put(path.id(), input);
        }
        Map<DesktopEnvironmentTransfer.FieldKey, JCheckBox> files = new LinkedHashMap<>();
        for (var group : preview.files()) {
            JCheckBox choice = new JCheckBox(host.message("transfer.file-group", group.id().owner(), group.id().key(), group.files(), group.bytes()));
            choice.setEnabled(!group.requiresDatabase());
            if (group.requiresDatabase()) {
                choice.setToolTipText(host.message("transfer.files-require-database"));
                databases.addActionListener(event -> {
                    choice.setEnabled(databases.isSelected());
                    if (!databases.isSelected()) choice.setSelected(false);
                });
            }
            files.put(group.id(), choice);
            content.add(choice);
        }
        if (!preview.unavailableFiles().isEmpty()) content.add(description("transfer.unavailable-files", preview.unavailableFiles().size()));
        Map<DesktopEnvironmentTransfer.FieldKey, JCheckBox> choices = new LinkedHashMap<>();
        Map<DesktopEnvironmentTransfer.FieldKey, JTextField> paths = new LinkedHashMap<>();
        for (var change : preview.changes()) {
            String name = change.field().owner() + ": " + change.field().key();
            JCheckBox selected = new JCheckBox(name, true);
            choices.put(change.field(), selected);
            JPanel row = new JPanel(new BorderLayout(6, 4));
            row.add(selected, BorderLayout.NORTH);
            JTextField value = new JTextField(change.sensitive() ? host.message("transfer.secret-update") : change.after());
            value.setEditable(change.path());
            value.getAccessibleContext().setAccessibleName(name);
            row.add(value, BorderLayout.CENTER);
            if (change.path()) paths.put(change.field(), value);
            row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
            content.add(row);
        }
        if (!preview.unavailable().isEmpty()) {
            content.add(description("transfer.unavailable", preview.unavailable().size()));
        }
        boolean actionable = !choices.isEmpty() || !preview.databases().isEmpty() || !files.isEmpty();
        if (!actionable) content.add(description("transfer.no-changes"));
        for (Component child : content.getComponents()) ((JComponent) child).setAlignmentX(Component.LEFT_ALIGNMENT);
        JScrollPane scroll = new JScrollPane(content);
        Rectangle available = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        scroll.setPreferredSize(new Dimension(Math.min(680, available.width - 100), Math.min(460, available.height - 200)));
        if (!actionable) {
            JOptionPane.showMessageDialog(parent, scroll, host.message("transfer.preview"), JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        Object[] buttons = {host.message("transfer.apply"), host.message("desktop.ui.action.cancel")};
        int result = JOptionPane.showOptionDialog(parent, scroll, host.message("transfer.preview"), JOptionPane.DEFAULT_OPTION,
                JOptionPane.PLAIN_MESSAGE, null, buttons, buttons[1]);
        if (result != 0 || unsaved.getAsBoolean()) return;
        Set<DesktopEnvironmentTransfer.FieldKey> selected = new LinkedHashSet<>();
        choices.forEach((key, box) -> { if (box.isSelected()) selected.add(key); });
        Map<DesktopEnvironmentTransfer.FieldKey, String> mapped = new LinkedHashMap<>();
        paths.forEach((key, input) -> { if (selected.contains(key)) mapped.put(key, input.getText()); });
        char[] copy = password.clone();
        boolean includeDatabases = databases.isSelected();
        Map<Long, String> mappedDatabasePaths = new LinkedHashMap<>();
        if (includeDatabases) databasePaths.forEach((id, input) -> mappedDatabasePaths.put(id, input.getText()));
        Set<DesktopEnvironmentTransfer.FieldKey> selectedFiles = new LinkedHashSet<>();
        files.forEach((key, box) -> { if (box.isSelected()) selectedFiles.add(key); });
        work(() -> {
            try { transfer.importConfiguration(preview.token(), selected, mapped, copy, includeDatabases, selectedFiles, mappedDatabasePaths); return true; }
            finally { Arrays.fill(copy, '\0'); }
        }, ignored -> notice("transfer.restart-pending"));
    }

    private JFileChooser chooser() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("PixivDownloader (*.pdbackup)", "pdbackup"));
        return chooser;
    }
    private JLabel description(String key, Object... arguments) {
        return ScrollablePagePanel.secondaryLabel(host.message(key, arguments));
    }
    private void notice(String key) {
        JOptionPane.showMessageDialog(parent, host.message(key), host.message("transfer.title"), JOptionPane.INFORMATION_MESSAGE);
    }
    private <T> void work(Callable<T> operation, Consumer<T> completed) {
        JDialog progress = new JDialog(SwingUtilities.getWindowAncestor(parent), host.message("transfer.working"), Dialog.ModalityType.APPLICATION_MODAL);
        JProgressBar bar = new JProgressBar();
        bar.setIndeterminate(true);
        bar.getAccessibleContext().setAccessibleName(host.message("transfer.working"));
        progress.add(bar);
        progress.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        progress.setSize(360, 90);
        progress.setLocationRelativeTo(parent);
        new SwingWorker<T, Void>() {
            @Override protected T doInBackground() throws Exception { return operation.call(); }
            @Override protected void done() {
                progress.dispose();
                try { completed.accept(get()); }
                catch (Exception failed) {
                    JOptionPane.showMessageDialog(parent, host.message("transfer.failed"), host.message("transfer.title"), JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
        progress.setVisible(true);
    }
}
