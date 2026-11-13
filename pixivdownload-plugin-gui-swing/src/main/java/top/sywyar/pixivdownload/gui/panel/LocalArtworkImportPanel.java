package top.sywyar.pixivdownload.gui.panel;

import top.sywyar.pixivdownload.core.work.filename.WorkTemplateVariables;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiContext;
import top.sywyar.pixivdownload.gui.LocalImportGuideButton;
import top.sywyar.pixivdownload.plugin.api.gui.importing.LocalArtworkImports;
import top.sywyar.pixivdownload.plugin.api.gui.importing.LocalArtworkImports.*;
import javax.swing.*;
import javax.swing.event.*;
import java.awt.*;
import java.awt.event.*;
import java.util.*;
import java.util.List;
import java.util.function.Supplier;

/** Swing 工具页入口；临时模板和 ID 不写回设置。 */
final class LocalArtworkImportPanel extends JPanel implements AutoCloseable {
    private final DesktopUiContext context;
    private final JButton entry;
    private final JTextArea templates = new JTextArea(2, 40), ids = new JTextArea(3, 40), details = new JTextArea();
    private final JTextField file = new JTextField(40);
    private final JTextArea presetPaths = help("");
    private final JCheckBox refresh;
    private final JButton scan, start, cancel, browse;
    private final JLabel state = new JLabel(), notice = new JLabel();
    private final JProgressBar progress = new JProgressBar();
    private final javax.swing.Timer timer = new javax.swing.Timer(1000, e -> poll());
    private Snapshot status;
    private List<Item> samples = List.of();
    private JDialog dialog;
    private JPanel options;
    private JToggleButton disclosure;
    private JLabel templateLabel;
    private final javax.swing.border.Border templateBorder = templates.getBorder();
    private boolean busy, querying, closed;

    LocalArtworkImportPanel(DesktopUiContext context) {
        this.context = context;
        setOpaque(false); setLayout(new BorderLayout());
        setAlignmentX(Component.LEFT_ALIGNMENT);
        entry = button("title"); scan = button("scan"); start = button("start"); cancel = button("cancel"); browse = button("browse");
        refresh = new JCheckBox(message("refresh"));
        entry.setName("tools.local-import"); add(entry, BorderLayout.WEST);
        entry.addActionListener(e -> open());
        scan.addActionListener(e -> scan());
        start.addActionListener(e -> { if (status != null) run(() -> source().start(status.id())); });
        cancel.addActionListener(e -> { if (status != null) run(() -> source().cancel(status.id())); });
        browse.addActionListener(e -> {
            JFileChooser chooser = new JFileChooser();
            if (chooser.showOpenDialog(dialog) == JFileChooser.APPROVE_OPTION) file.setText(chooser.getSelectedFile().getAbsolutePath());
        });
        DocumentListener changes = new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { invalidatePreview(); }
            public void removeUpdate(DocumentEvent e) { invalidatePreview(); }
            public void changedUpdate(DocumentEvent e) { invalidatePreview(); }
        };
        templates.getDocument().addDocumentListener(changes); ids.getDocument().addDocumentListener(changes); file.getDocument().addDocumentListener(changes);
        refresh.addActionListener(e -> invalidatePreview());
        update();
    }
    private JButton button(String key) { return new JButton(message(key)); }
    private String message(String key, Object... args) { return context.host().message("local-import." + key, args); }
    private LocalArtworkImports source() { return context.host().localArtworkImports().orElseThrow(); }
    void refreshTools() { entry.setEnabled(!closed); }
    private void open() {
        if (dialog == null) {
            dialog = new JDialog(SwingUtilities.getWindowAncestor(this), message("title"), Dialog.ModalityType.MODELESS);
            var fields = new ScrollablePagePanel();
            fields.setBorder(BorderFactory.createEmptyBorder(16, 20, 16, 20));
            fields.add(help(message("help")));
            fields.add(new LocalImportGuideButton(context.host()));
            fields.add(top.sywyar.pixivdownload.gui.config.LocalImportPresetEditor.savedPresets(this::presetsSaved));
            fields.add(presetPaths);
            options = new JPanel(); options.setLayout(new BoxLayout(options, BoxLayout.Y_AXIS));
            options.setOpaque(false);
            disclosure = new JToggleButton(message("options"));
            options.setVisible(false);
            disclosure.addActionListener(e -> { options.setVisible(disclosure.isSelected()); fields.revalidate(); fields.repaint(); });
            fields.add(disclosure); fields.add(options);
            options.add(field("templates", templates, null)); options.add(help(message("templates-help", WorkTemplateVariables.importTokens())));
            options.add(field("ids", ids, null)); options.add(help(message("ids-help")));
            options.add(field("file", file, browse)); options.add(help(message("file-help")));
            options.add(refresh); fields.add(state); fields.add(notice); fields.add(progress);
            details.setEditable(false); details.setLineWrap(true); details.setWrapStyleWord(true); details.setRows(12);
            fields.add(details);
            for (Component c : fields.getComponents()) if (c instanceof JComponent child) child.setAlignmentX(Component.LEFT_ALIGNMENT);
            JPanel panel = new JPanel(new BorderLayout(10, 10)); panel.add(new JScrollPane(fields), BorderLayout.CENTER);
            JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT)); actions.add(scan); actions.add(start); actions.add(cancel); panel.add(actions, BorderLayout.SOUTH);
            dialog.setContentPane(panel); dialog.setSize(820, 700); dialog.setMinimumSize(new Dimension(480, 360)); dialog.setLocationRelativeTo(this);
            dialog.setDefaultCloseOperation(WindowConstants.HIDE_ON_CLOSE);
            panel.registerKeyboardAction(e -> { dialog.setVisible(false); timer.stop(); entry.requestFocusInWindow(); }, KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
            dialog.addWindowListener(new WindowAdapter() { @Override public void windowClosing(WindowEvent e) { timer.stop(); entry.requestFocusInWindow(); } });
        }
        refreshPresetPaths();
        dialog.setVisible(true); timer.start(); poll(); update();
    }
    private void presetsSaved() {
        refreshPresetPaths();
        invalidatePreview();
    }
    private void refreshPresetPaths() {
        if (context.host().localArtworkImports().isEmpty()) {
            presetPaths.setText(context.host().message("gui.security.error.backend-unreachable"));
            return;
        }
        var saved = context.host().localArtworkImports().map(LocalArtworkImports::presets).orElse(List.of());
        presetPaths.setText(saved.isEmpty() ? message("saved-paths-empty") : message("preset-paths", String.join("\n", saved.stream().map(Preset::template).toList())));
    }
    private JPanel field(String key, JComponent input, JButton action) {
        JPanel row = new JPanel(new BorderLayout(8, 4)); row.setOpaque(false);
        JLabel label = new JLabel(message(key)); label.setLabelFor(input); input.setName("local-import." + key);
        if (key.equals("templates")) templateLabel = label;
        row.add(label, BorderLayout.NORTH); row.add(input, BorderLayout.CENTER); if (action != null) row.add(action, BorderLayout.EAST);
        return row;
    }
    private static JTextArea help(String value) {
        JTextArea text = new JTextArea(value); text.setEditable(false); text.setOpaque(false); text.setLineWrap(true); text.setWrapStyleWord(true); return text;
    }
    private void invalidatePreview() {
        if (!busy && !running()) { status = null; samples = List.of(); notice.setText(""); templates.setBorder(templateBorder); update(); }
    }
    private void scan() {
        try {
            if (templates.getText().isBlank() && source().presets().isEmpty()) {
                notice.setText(message("error.IMPORT_NO_TEMPLATES"));
                notice.setForeground(UIManager.getColor("Component.error.focusedBorderColor"));
                templates.setBorder(BorderFactory.createLineBorder(UIManager.getColor("Component.error.focusedBorderColor"), 2));
                disclosure.setSelected(true); options.setVisible(true);
                dialog.validate();
                templates.scrollRectToVisible(new Rectangle(0, 0, templates.getWidth(), templates.getHeight()));
                templates.requestFocusInWindow();
                return;
            }
            var request = new Request(templates.getText().lines().map(String::strip).filter(s -> !s.isEmpty()).toList(),
                    ids.getText().isBlank() ? List.of() : Arrays.stream(ids.getText().strip().split("[\\s,;，；]+", -1)).map(Long::valueOf).toList(),
                    refresh.isSelected(), file.getText().strip());
            samples = List.of(); run(() -> source().scan(request));
        } catch (RuntimeException failure) { notice.setText(message("error.INVALID_IMPORT_REQUEST")); }
    }
    private void run(Supplier<Snapshot> action) {
        if (busy || closed) return;
        busy = true; notice.setText(""); update();
        new SwingWorker<Snapshot, Void>() {
            protected Snapshot doInBackground() { return action.get(); }
            protected void done() {
                if (closed) return;
                try { status = get(); } catch (Exception failure) {
                    Throwable cause = failure instanceof java.util.concurrent.ExecutionException ? failure.getCause() : failure;
                    String code = cause.getMessage();
                    notice.setText(cause instanceof VariableException variable
                            ? message("error." + code, "{" + variable.variable() + "}")
                            : message("error." + (code != null && code.matches("(?:IMPORT_|INVALID_|AMBIGUOUS_|UNKNOWN_)[A-Z_]{1,64}") ? code : "IMPORT_IO_FAILED")));
                }
                busy = false; update(); poll();
            }
        }.execute();
    }
    private void poll() {
        if (closed || busy || querying || status == null || !running() && (status.phase() != Phase.READY || !samples.isEmpty())) return;
        querying = true; UUID id = status.id();
        new SwingWorker<Snapshot, Void>() {
            private List<Item> preview;
            protected Snapshot doInBackground() {
                var service = source(); var next = service.status(id);
                if (next.phase() == Phase.READY) preview = service.items(id, 0, 20);
                return next;
            }
            protected void done() {
                querying = false;
                if (closed || status == null || !id.equals(status.id())) return;
                try { status = get(); if (preview != null) samples = preview; }
                catch (Exception failure) { status = null; notice.setText(message("error.IMPORT_TASK_EXPIRED")); }
                update();
            }
        }.execute();
    }
    private boolean running() { return status != null && Set.of(Phase.SCANNING, Phase.IMPORTING, Phase.CANCELLING).contains(status.phase()); }
    private void update() {
        boolean editable = !busy && !running() && context.host().localArtworkImports().isPresent();
        boolean hasPresets = context.host().localArtworkImports().map(s -> !s.presets().isEmpty()).orElse(false);
        if (disclosure != null) disclosure.setText(message(hasPresets ? "options" : "options-required"));
        if (templateLabel != null) templateLabel.setText(message(hasPresets ? "templates" : "templates-required"));
        presetPaths.setVisible(templates.getText().isBlank() && !presetPaths.getText().isEmpty());
        templates.setEnabled(editable); ids.setEnabled(editable); file.setEnabled(editable); refresh.setEnabled(editable); browse.setEnabled(editable);
        scan.setEnabled(editable);
        start.setEnabled(editable && status != null && status.phase() == Phase.READY && status.artworks() > 0);
        cancel.setEnabled(!busy && running() && status.phase() != Phase.CANCELLING);
        progress.setVisible(running()); progress.setIndeterminate(status != null && status.phase() == Phase.SCANNING);
        if (status == null) { state.setText(""); details.setText(""); return; }
        state.setText(message("phase." + status.phase())); progress.setMaximum(Math.max(1, status.artworks())); progress.setValue(status.processed());
        StringBuilder text = new StringBuilder(message("summary", status.artworks(), status.files(), status.imported(), status.skipped(), status.failed())).append('\n');
        if (status.cachedIndex()) text.append(message("cached")).append('\n');
        for (Item item : samples) {
            text.append(message("item", item.artworkId(), item.files().size(), message("disposition." + item.disposition()))).append('\n');
            if (!item.reason().isEmpty()) text.append(message("error." + item.reason())).append('\n');
            if (!item.files().isEmpty()) text.append(item.files().getFirst().path()).append('\n');
        }
        if (status.artworks() > samples.size()) text.append(message("samples", samples.size(), status.artworks())).append('\n');
        for (Problem problem : status.problems()) text.append(message("problem", problem.artworkId() == null ? "" : problem.artworkId(), message("error." + problem.code()))).append('\n');
        String value = text.toString(); if (!details.getText().equals(value)) details.setText(value);
    }
    @Override public void close() { closed = true; timer.stop(); if (dialog != null) dialog.dispose(); samples = List.of(); status = null; }
}
