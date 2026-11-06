package top.sywyar.pixivdownload.gui.panel;

import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiContext;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiText;
import top.sywyar.pixivdownload.plugin.api.gui.importing.DesktopFileImportTool;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.*;
import java.util.List;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

/** Swing 工具入口及原生导入表单；离开只停止观察，取消由独立按钮发起。 */
final class FileImportToolsPanel extends JPanel implements AutoCloseable {
    private final DesktopUiContext context;
    private final Map<DesktopFileImportTool.Identity, ImportForm> forms = new LinkedHashMap<>();
    private final javax.swing.Timer timer = new javax.swing.Timer(1000, event ->
            forms.values().forEach(ImportForm::refresh));
    private boolean closed;
    private boolean completionDialogVisible;

    FileImportToolsPanel(DesktopUiContext context) {
        this.context = context;
        setOpaque(false);
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        refreshTools();
    }
    void refreshTools() {
        if (closed) return;
        var tools = context.host().fileImportTools();
        if (tools == null) tools = List.of();
        var identities = tools.stream().map(DesktopFileImportTool::identity).toList();
        forms.entrySet().removeIf(entry -> {
            if (identities.contains(entry.getKey())) return false;
            entry.getValue().dispose();
            remove(entry.getValue().entry);
            return true;
        });
        for (var tool : tools) {
            if (!forms.containsKey(tool.identity())) {
                ImportForm form = new ImportForm(tool);
                forms.put(tool.identity(), form);
                add(form.entry);
            }
        }
        revalidate();
        repaint();
    }
    @Override public void addNotify() { super.addNotify(); refreshTools(); timer.start(); }
    @Override public void removeNotify() { timer.stop(); super.removeNotify(); }
    @Override public void close() { closed = true; timer.stop(); forms.values().forEach(ImportForm::dispose); forms.clear(); }

    private final class ImportForm {
        final DesktopFileImportTool tool;
        final JButton entry;
        final JPanel panel = new JPanel(new BorderLayout(12, 12));
        final JTextField file = new JTextField(34), root = new JTextField(34);
        final JPasswordField credential = new JPasswordField(34);
        final JTextArea details = new JTextArea();
        final JLabel state = new JLabel(), notice = new JLabel();
        final JProgressBar progress = new JProgressBar();
        final JButton inspect = button("inspect"), start = button("start"), cancel = button("cancel");
        final JButton chooseFile = button("browse"), chooseRoot = button("browse");
        DesktopFileImportTool.Preview preview;
        DesktopFileImportTool.Status status;
        JDialog dialog;
        boolean busy, querying, cancelling, detached;
        String shownCompletion;
        long revision;

        ImportForm(DesktopFileImportTool tool) {
            this.tool = tool;
            entry = new JButton(context.resolveText(tool.description().title()));
            entry.setName("tools.import." + tool.identity().pluginId());
            entry.addActionListener(event -> open());
            panel.setBorder(BorderFactory.createEmptyBorder(18, 20, 18, 20));
            JPanel fields = new ScrollablePagePanel();
            fields.add(help(context.resolveText(tool.description().help())));
            fields.add(field("file", file, chooseFile));
            fields.add(field("root", root, chooseRoot));
            fields.add(help(message("root-help")));
            if (tool.description().requiresCredential()) {
                fields.add(field("credential", credential, null));
                fields.add(help(message("credential-help")));
            }
            fields.add(state);
            fields.add(notice);
            fields.add(progress);
            for (Component component : fields.getComponents())
                if (component instanceof JComponent child) child.setAlignmentX(Component.LEFT_ALIGNMENT);
            details.setEditable(false);
            details.setLineWrap(true);
            details.setWrapStyleWord(true);
            details.setName("import.details");
            details.setAlignmentX(Component.LEFT_ALIGNMENT);
            details.setRows(12);
            fields.add(details);
            panel.add(new JScrollPane(fields), BorderLayout.CENTER);
            JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT));
            actions.add(inspect); actions.add(start); actions.add(cancel);
            panel.add(actions, BorderLayout.SOUTH);
            chooseFile.addActionListener(event -> choose(file, false));
            chooseRoot.addActionListener(event -> choose(root, true));
            DocumentListener invalidate = new DocumentListener() {
                public void insertUpdate(DocumentEvent e) { changed(); }
                public void removeUpdate(DocumentEvent e) { changed(); }
                public void changedUpdate(DocumentEvent e) { changed(); }
                private void changed() { revision++; preview = null; update(); }
            };
            file.getDocument().addDocumentListener(invalidate);
            root.getDocument().addDocumentListener(invalidate);
            inspect.addActionListener(event -> {
                String selectedFile = file.getText(), selectedRoot = root.getText();
                preview = null;
                run(() -> source().inspect(new DesktopFileImportTool.Request(selectedFile, selectedRoot)).valueOrThrow(),
                        value -> preview = value);
            });
            start.addActionListener(event -> {
                if (preview == null) return;
                String token = preview.token();
                char[] chars = credential.getPassword();
                String secret = new String(chars);
                Arrays.fill(chars, '\0');
                credential.setText("");
                preview = null;
                run(() -> source().start(token, secret).valueOrThrow(), value -> status = value);
            });
            cancel.addActionListener(event -> {
                cancelling = true;
                update();
                new SwingWorker<Void, Void>() {
                    protected Void doInBackground() { source().cancel(); return null; }
                    protected void done() {
                        if (detached) return;
                        try { get(); } catch (Exception failure) { error(failure); }
                        refresh();
                    }
                }.execute();
            });
            update();
        }
        void open() {
            if (dialog == null) {
                dialog = new JDialog(SwingUtilities.getWindowAncestor(FileImportToolsPanel.this),
                        context.resolveText(tool.description().title()), Dialog.ModalityType.MODELESS);
                dialog.setDefaultCloseOperation(WindowConstants.HIDE_ON_CLOSE);
                dialog.setContentPane(panel);
                dialog.setSize(820, 680);
                dialog.setMinimumSize(new Dimension(480, 360));
                dialog.setLocationRelativeTo(FileImportToolsPanel.this);
                dialog.addWindowListener(new WindowAdapter() {
                    @Override public void windowClosing(WindowEvent event) { credential.setText(""); entry.requestFocusInWindow(); }
                });
                panel.registerKeyboardAction(event -> { credential.setText(""); dialog.setVisible(false); entry.requestFocusInWindow(); },
                        KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
            }
            dialog.setVisible(true);
            dialog.toFront();
            refresh();
        }
        private JPanel field(String key, JTextField input, JButton browse) {
            JPanel row = new JPanel(new BorderLayout(8, 4));
            JLabel label = new JLabel(message(key));
            label.setLabelFor(input);
            input.setName("import." + key);
            row.add(label, BorderLayout.NORTH);
            row.add(input, BorderLayout.CENTER);
            if (browse != null) row.add(browse, BorderLayout.EAST);
            row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 65));
            row.setBorder(BorderFactory.createEmptyBorder(6, 0, 6, 0));
            return row;
        }
        private void choose(JTextField input, boolean directory) {
            JFileChooser chooser = new JFileChooser();
            chooser.setFileSelectionMode(directory ? JFileChooser.DIRECTORIES_ONLY : JFileChooser.FILES_AND_DIRECTORIES);
            if (chooser.showOpenDialog(panel) == JFileChooser.APPROVE_OPTION) input.setText(chooser.getSelectedFile().getAbsolutePath());
        }
        private <T> void run(Callable<T> action, Consumer<T> success) {
            if (busy || running()) return;
            busy = true;
            revision++;
            notice.setText("");
            update();
            new SwingWorker<T, Void>() {
                protected T doInBackground() throws Exception { return action.call(); }
                protected void done() {
                    if (detached) return;
                    try { success.accept(get()); } catch (Exception failure) { error(failure); }
                    finally { busy = false; update(); refresh(); }
                }
            }.execute();
        }
        void refresh() {
            if (detached || querying) return;
            querying = true;
            long current = revision;
            new SwingWorker<DesktopFileImportTool.Status, Void>() {
                DesktopFileImportTool.Completion completion;
                protected DesktopFileImportTool.Status doInBackground() {
                    var current = source().status();
                    completion = source().pendingCompletion().orElse(null);
                    if (cancelling && current.active()) source().cancel();
                    return current;
                }
                protected void done() {
                    querying = false;
                    if (detached || revision != current) return;
                    try { status = get(); if (!busy && !status.active()) cancelling = false; }
                    catch (Exception failure) { error(failure); }
                    update();
                    if (completion != null) offerBackfill(completion);
                }
            }.execute();
        }
        private void offerBackfill(DesktopFileImportTool.Completion completion) {
            if (closed || detached || busy || running() || completionDialogVisible || completion.id().equals(shownCompletion)) return;
            Window window = SwingUtilities.getWindowAncestor(FileImportToolsPanel.this);
            if (window == null) return;
            if (Arrays.stream(Window.getWindows()).anyMatch(w -> w instanceof Dialog d && d.isModal() && d.isVisible())) return;
            completionDialogVisible = true;
            shownCompletion = completion.id();
            if (window instanceof Frame frame) frame.setExtendedState(frame.getExtendedState() & ~Frame.ICONIFIED);
            window.setVisible(true);
            window.toFront();
            window.requestFocus();
            int answer;
            try {
                answer = JOptionPane.showOptionDialog(window,
                        new Object[]{message("backfill-message", completion.imported()), message("backfill-later-help")},
                        message("backfill-title"), JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null,
                        new Object[]{message("backfill-later"), message("backfill-start")}, message("backfill-start"));
            } finally { completionDialogVisible = false; }
            boolean startBackfill = answer == 1;
            new SwingWorker<Void, Void>() {
                protected Void doInBackground() throws Exception {
                    try { source().acknowledgeCompletion(completion.id()); }
                    catch (RuntimeException failure) {
                        org.slf4j.LoggerFactory.getLogger(FileImportToolsPanel.class)
                                .warn("Import completion acknowledgement failed: source={}", tool.identity(), failure);
                    }
                    if (startBackfill) context.host().startBackfill(context.host().defaultBackfillOptions());
                    return null;
                }
                protected void done() {
                    if (detached) return;
                    try { get(); }
                    catch (Exception failure) {
                        error(failure);
                        JOptionPane.showMessageDialog(window, notice.getText(),
                                message("backfill-title"), JOptionPane.ERROR_MESSAGE);
                    }
                }
            }.execute();
        }
        private void update() {
            boolean editable = !busy && !running();
            file.setEnabled(editable); root.setEnabled(editable); credential.setEnabled(editable);
            chooseFile.setEnabled(editable); chooseRoot.setEnabled(editable);
            inspect.setEnabled(editable && !file.getText().isBlank() && !root.getText().isBlank());
            start.setEnabled(editable && preview != null && preview.candidates() > 0);
            cancel.setEnabled((busy || running()) && !cancelling);
            StringBuilder content = new StringBuilder();
            if (preview != null) {
                content.append(message("preview-summary", preview.records(), preview.works(), preview.candidates(), preview.skipped(), preview.unavailable())).append('\n');
                content.append(message("samples", preview.samples().size(), preview.works())).append('\n');
                for (var item : preview.samples()) content.append(message("item", item.workKey(), item.files(),
                        message("disposition." + item.disposition().name().toLowerCase(Locale.ROOT)))).append('\n');
            }
            if (status != null) {
                state.setText(message("phase." + status.phase().name().toLowerCase(Locale.ROOT)));
                progress.setMaximum(Math.max(1, status.total()));
                progress.setValue(status.processed());
                progress.setStringPainted(true);
                progress.setString(message("progress", status.processed(), status.total(), status.imported(), status.skipped(), status.failed()));
                for (var problem : status.problems()) content.append(problem.workKey()).append(": ")
                        .append(context.resolveText(problem.reason())).append('\n');
            }
            progress.setIndeterminate(busy && (status == null || status.total() == 0));
            if (!details.getText().contentEquals(content)) details.setText(content.toString());
        }
        private boolean running() { return status != null && status.active(); }
        private DesktopFileImportTool.Source source() { return context.host().fileImportTool(tool.identity()); }
        private void error(Exception failure) {
            Throwable cause = failure instanceof java.util.concurrent.ExecutionException ? failure.getCause() : failure;
            notice.setText(cause instanceof DesktopFileImportTool.OperationException operation
                    ? context.resolveText(operation.text()) : context.host().message("desktop.ui.action.failed"));
            preview = null;
        }
        private JButton button(String key) {
            JButton result = new JButton(message(key));
            result.setName("import." + key);
            return result;
        }
        private String message(String key, Object... args) { return context.host().message("gui.import." + key, args); }
        void dispose() { detached = true; credential.setText(""); if (dialog != null) dialog.dispose(); }
    }
    private static JLabel help(String value) {
        return ScrollablePagePanel.secondaryLabel(value);
    }
}
