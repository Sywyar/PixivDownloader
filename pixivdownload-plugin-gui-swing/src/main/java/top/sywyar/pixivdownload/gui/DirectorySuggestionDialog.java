package top.sywyar.pixivdownload.gui;

import top.sywyar.pixivdownload.guiswing.SwingHost;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiHost;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiText;
import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.List;
import java.util.Map;

/** The Swing provider owns directory confirmation and focus; persistence stays with the host. */
public final class DirectorySuggestionDialog {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(DirectorySuggestionDialog.class);
    private final JComponent parent;
    private JDialog dialog;
    private DesktopUiHost.GuiValue current;
    private String lastPresented = "";
    private boolean saving;
    private boolean disposed;

    public DirectorySuggestionDialog(JComponent parent) { this.parent = parent; }

    public void refresh(DesktopUiHost.GuiValue snapshot) {
        if (disposed) return;
        var candidates = snapshot.path("directories");
        if (current != null) {
            boolean present = false;
            for (var candidate : candidates) if (identity(candidate).equals(identity(current))) present = true;
            if (!present && !saving) close();
        }
        if (dialog != null || !parent.isDisplayable()) return;
        for (Window window : Window.getWindows()) {
            if (window instanceof Dialog other && other.isVisible() && other.isModal()) return;
        }
        for (var candidate : candidates) {
            if (identity(candidate).equals(lastPresented)) continue;
            show(candidate);
            break;
        }
    }

    private void show(DesktopUiHost.GuiValue value) {
        current = value;
        lastPresented = identity(value);
        Window window = SwingUtilities.getWindowAncestor(parent);
        if (window instanceof MainFrame frame) frame.showDirectorySettings();
        dialog = new JDialog(window, SwingHost.host().message("gui.directory-suggestion.title"), Dialog.ModalityType.DOCUMENT_MODAL);
        dialog.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        JPanel body = new JPanel(new BorderLayout(12, 12));
        body.setBorder(BorderFactory.createEmptyBorder(20, 20, 16, 20));
        var name = value.path("displayName");
        String pluginName = SwingHost.context().resolveText(new DesktopUiText(name.path("namespace").asText(),
                name.path("key").asText(), name.path("fallback").asText(), List.of()));
        JLabel help = paragraph(pluginName + "\n\n" + SwingHost.host().message("gui.directory-suggestion.help") + "\n" + SwingHost.host().message("gui.directory-suggestion.readonly"));
        body.add(help, BorderLayout.NORTH);
        JTextField path = new JTextField(value.path("suggestion").path("directory").asText(), 36);
        path.getAccessibleContext().setAccessibleName(SwingHost.host().message("gui.directory-suggestion.directory"));
        JPanel field = new JPanel(new BorderLayout(8, 8));
        JLabel label = new JLabel(SwingHost.host().message("gui.directory-suggestion.directory"));
        label.setLabelFor(path);
        field.add(label, BorderLayout.NORTH);
        field.add(path, BorderLayout.CENTER);
        JButton browse = new JButton(SwingHost.host().message("gui.directory-suggestion.browse"));
        browse.addActionListener(event -> {
            JFileChooser chooser = new JFileChooser(path.getText());
            chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            chooser.setAcceptAllFileFilterUsed(false);
            if (chooser.showOpenDialog(dialog) == JFileChooser.APPROVE_OPTION) path.setText(chooser.getSelectedFile().getAbsolutePath());
        });
        field.add(browse, BorderLayout.EAST);
        body.add(field, BorderLayout.CENTER);
        JPanel footer = new JPanel(new BorderLayout(0, 8));
        JLabel notice = paragraph("");
        notice.setVisible(false);
        footer.add(notice, BorderLayout.NORTH);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        JButton cancel = new JButton(SwingHost.host().message("desktop.ui.action.cancel"));
        JButton confirm = new JButton(SwingHost.host().message("gui.directory-suggestion.confirm"));
        Runnable dismiss = () -> {
            if (saving) return;
            close();
            new SwingWorker<Void, Void>() {
                @Override protected Void doInBackground() { send(value, "", true); return null; }
            }.execute();
        };
        cancel.addActionListener(event -> dismiss.run());
        confirm.addActionListener(event -> {
            if (saving || path.getText().isBlank()) return;
            String selected = path.getText().trim();
            JDialog requestedDialog = dialog;
            saving = true;
            confirm.setEnabled(false); cancel.setEnabled(false); browse.setEnabled(false); path.setEnabled(false);
            notice.setVisible(false);
            new SwingWorker<DesktopUiHost.GuiResponse, Void>() {
                @Override protected DesktopUiHost.GuiResponse doInBackground() { return send(value, selected, false); }
                @Override protected void done() {
                    if (dialog != requestedDialog) return;
                    saving = false;
                    try {
                        if (get().successful()) {
                            if (window instanceof MainFrame frame) frame.pluginDirectorySaved(
                                    value.path("owner").path("pluginId").asText(),
                                    value.path("suggestion").path("configurationKey").asText(), selected);
                            close();
                            return;
                        }
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    } catch (java.util.concurrent.ExecutionException failure) {
                        log.debug("Directory confirmation request failed", failure.getCause());
                    }
                    confirm.setEnabled(true); cancel.setEnabled(true); browse.setEnabled(true); path.setEnabled(true);
                    notice.setText(paragraphHtml(SwingHost.host().message("gui.directory-suggestion.failed"))); notice.setVisible(true);
                    dialog.pack();
                }
            }.execute();
        });
        actions.add(cancel); actions.add(confirm);
        footer.add(actions, BorderLayout.SOUTH);
        body.add(footer, BorderLayout.SOUTH);
        dialog.setContentPane(body);
        dialog.getRootPane().setDefaultButton(confirm);
        dialog.getRootPane().registerKeyboardAction(event -> dismiss.run(), KeyStroke.getKeyStroke("ESCAPE"), JComponent.WHEN_IN_FOCUSED_WINDOW);
        dialog.addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent event) { dismiss.run(); }
            @Override public void windowOpened(WindowEvent event) { path.requestFocusInWindow(); }
        });
        dialog.pack();
        dialog.setLocationRelativeTo(window);
        dialog.setVisible(true);
    }

    private static DesktopUiHost.GuiResponse send(DesktopUiHost.GuiValue value, String directory, boolean dismiss) {
        var owner = value.path("owner");
        return SwingHost.host().guiPostJson("control-center/directory", Map.of("owner", Map.of(
                "pluginId", owner.path("pluginId").asText(), "packageId", owner.path("packageId").asText(),
                "generation", owner.path("generation").asLong(), "publication", owner.path("publication").asLong()),
                "suggestionId", value.path("suggestion").path("suggestionId").asText(), "directory", directory, "dismiss", dismiss), 5_000);
    }

    private static JLabel paragraph(String text) {
        return new JLabel(paragraphHtml(text));
    }
    private static String paragraphHtml(String text) {
        return "<html><div style='width:400px'>" + text.replace("&", "&amp;")
                .replace("<", "&lt;").replace(">", "&gt;").replace("\n", "<br>") + "</div></html>";
    }
    private static String identity(DesktopUiHost.GuiValue value) {
        return value.path("owner").path("publication").asText() + ":" + value.path("suggestion").path("suggestionId").asText();
    }
    public void close() {
        if (dialog != null) dialog.dispose();
        dialog = null; current = null;
        saving = false;
    }
    public void dispose() { disposed = true; close(); }
}
