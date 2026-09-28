package top.sywyar.pixivdownload.gui.panel;

import top.sywyar.pixivdownload.gui.config.ConfigFieldSpec;
import top.sywyar.pixivdownload.gui.config.FieldRenderer;
import top.sywyar.pixivdownload.gui.config.FieldType;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiContext;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiText;
import top.sywyar.pixivdownload.plugin.api.gui.media.DesktopMediaTool;

import javax.swing.*;
import java.awt.*;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

/** 原生 Swing 媒体工具；耗时命令不占用 EDT，关闭面板只停止观察，不主动开始或取消任务。 */
final class MediaToolsPanel extends JPanel {
    private final DesktopUiContext context;
    private final Map<DesktopMediaTool.Identity, ToolPanel> panels = new LinkedHashMap<>();
    private final Timer timer = new Timer(1000, event -> {
        if (isShowing()) panels.values().forEach(panel -> { if (panel.running()) panel.refresh(); });
    });
    private boolean closed;

    MediaToolsPanel(DesktopUiContext context) {
        this.context = context;
        setOpaque(false);
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        refreshTools();
    }

    void refreshTools() {
        if (closed) return;
        List<DesktopMediaTool> tools = context.host().mediaTools();
        if (tools == null) tools = List.of();
        var identities = tools.stream().map(DesktopMediaTool::identity).toList();
        panels.entrySet().removeIf(entry -> {
            if (identities.contains(entry.getKey())) return false;
            entry.getValue().detached = true;
            remove(entry.getValue());
            return true;
        });
        for (var tool : tools) {
            if (!panels.containsKey(tool.identity())) {
                ToolPanel panel = new ToolPanel(tool);
                panels.put(tool.identity(), panel);
                add(panel);
                panel.refresh();
            }
        }
        revalidate();
        repaint();
    }

    @Override public void addNotify() {
        super.addNotify();
        refreshTools();
        panels.values().forEach(ToolPanel::refresh);
        timer.start();
    }
    @Override public void removeNotify() { timer.stop(); super.removeNotify(); }
    void close() { closed = true; timer.stop(); panels.values().forEach(panel -> panel.detached = true); panels.clear(); }

    private final class ToolPanel extends JPanel {
        private final DesktopMediaTool tool;
        private final FieldRenderer.RenderedField images;
        private final FieldRenderer.RenderedField animations;
        private final JCheckBox thumbnails;
        private final JButton previewButton;
        private final JButton startButton;
        private final JButton cancelButton;
        private final JButton checkButton;
        private final JButton previousButton;
        private final JButton nextButton;
        private final JLabel stateLabel = new JLabel();
        private final JLabel previewLabel = new JLabel();
        private final JLabel pageLabel = new JLabel();
        private final JTextArea files = textArea();
        private final JTextArea capabilities = textArea();
        private final JTextArea notice = textArea();
        private final JProgressBar progress = new JProgressBar();
        private DesktopMediaTool.Preview preview;
        private DesktopMediaTool.Status status;
        private boolean busy;
        private boolean querying;
        private boolean scanning;
        private boolean cancelling;
        private boolean detached;
        private long revision;
        private int page;

        ToolPanel(DesktopMediaTool tool) {
            this.tool = tool;
            setLayout(new GridBagLayout());
            setOpaque(false);
            setBorder(BorderFactory.createTitledBorder(context.resolveText(tool.description().title())));
            add(help("media.tools.help"));
            images = formats(false);
            animations = formats(true);
            JLabel imagesLabel = label("media.image-formats.label");
            imagesLabel.setLabelFor(images.control());
            add(imagesLabel);
            add(images.control());
            JLabel animationsLabel = label("media.ugoira-formats.label");
            animationsLabel.setLabelFor(animations.control());
            add(animationsLabel);
            add(animations.control());
            thumbnails = new JCheckBox(text("media.tools.thumbnails"), true);
            thumbnails.setOpaque(false);
            thumbnails.setName("media.thumbnails");
            add(thumbnails);
            previewButton = button("preview", this::preview);
            startButton = button("start", this::start);
            cancelButton = button("cancel", this::cancel);
            checkButton = button("check", () -> perform(() -> source().capabilities().valueOrThrow(), this::showCapabilities));
            JPanel actions = row(previewButton, startButton, cancelButton, button("refresh", this::refresh));
            actions.setLayout(new GridLayout(0, 2, 6, 6));
            add(actions);
            add(previewLabel);
            add(files);
            previousButton = button("previous", () -> { page--; showFiles(); });
            nextButton = button("next", () -> { page++; showFiles(); });
            add(row(previousButton, pageLabel, nextButton));
            add(stateLabel);
            progress.setStringPainted(true);
            add(progress);
            add(help("media.tools.capabilities-help"));
            add(row(checkButton));
            add(capabilities);
            add(notice);
            thumbnails.addActionListener(event -> invalidatePreview());
            images.control().addPropertyChangeListener("text", event -> invalidatePreview());
            animations.control().addPropertyChangeListener("text", event -> invalidatePreview());
            updateControls();
            showFiles();
        }

        @Override public Component add(Component component) {
            GridBagConstraints constraints = new GridBagConstraints();
            constraints.gridx = 0;
            constraints.gridy = getComponentCount();
            constraints.weightx = 1;
            if (component == notice) constraints.weighty = 1;
            constraints.fill = GridBagConstraints.HORIZONTAL;
            constraints.anchor = GridBagConstraints.NORTHWEST;
            constraints.insets = new Insets(3, 4, 3, 4);
            super.add(component, constraints);
            return component;
        }

        private FieldRenderer.RenderedField formats(boolean animation) {
            List<String> values = animation ? List.of("webp", "gif", "apng", "mp4", "zip") : List.of("original", "png", "jpg", "webp");
            Map<String, String> labels = new LinkedHashMap<>();
            values.forEach(format -> labels.put(format, text("media.format." + format)));
            var spec = ConfigFieldSpec.builder(animation ? "media.ugoira" : "media.images",
                    text(animation ? "media.ugoira-formats.label" : "media.image-formats.label"), FieldType.MULTI_ENUM, "")
                    .defaultValue(animation ? tool.description().ugoiraFormats() : tool.description().imageFormats())
                    .enumValues(values.toArray(String[]::new)).enumValueLabels(labels).build();
            var field = FieldRenderer.render(spec);
            field.control().setName(animation ? "media.ugoira" : "media.images");
            return field;
        }

        private void invalidatePreview() {
            preview = null;
            page = 0;
            if (previewButton != null) { showFiles(); updateControls(); }
        }

        private void preview() {
            var request = new DesktopMediaTool.Request(images.getValue().get(), animations.getValue().get(), thumbnails.isSelected());
            preview = null;
            scanning = true;
            perform(() -> source().preview(request).valueOrThrow(), result -> {
                preview = result;
                scanning = false;
                status = null;
                page = 0;
                showFiles();
            });
        }

        private void start() {
            if (preview == null) return;
            String token = preview.token();
            preview = null;
            page = 0;
            showFiles();
            perform(() -> source().start(token).valueOrThrow(), this::showStatus);
        }

        private void cancel() {
            if (!running() || cancelling) return;
            cancelling = true;
            updateControls();
            new SwingWorker<Void, Void>() {
                protected Void doInBackground() { source().cancel(); return null; }
                protected void done() {
                    try { get(); refresh(); } catch (Exception failure) { showFailure(failure.getCause()); }
                }
            }.execute();
        }

        void refresh() {
            if (querying || busy && !scanning || detached || closed) return;
            querying = true;
            long expectedRevision = revision;
            new SwingWorker<DesktopMediaTool.Status, Void>() {
                protected DesktopMediaTool.Status doInBackground() { return source().status(); }
                protected void done() {
                    querying = false;
                    if (detached || closed || revision != expectedRevision) return;
                    try { showStatus(get()); }
                    catch (Exception failure) { showFailure(failure.getCause() == null ? failure : failure.getCause()); }
                }
            }.execute();
        }

        private <T> void perform(Callable<T> operation, Consumer<T> complete) {
            if (busy || detached || closed) return;
            busy = true;
            revision++;
            notice.setText("");
            updateControls();
            new SwingWorker<T, Void>() {
                protected T doInBackground() throws Exception { return operation.call(); }
                protected void done() {
                    busy = false;
                    if (detached || closed) return;
                    try { complete.accept(get()); }
                    catch (Exception failure) { showFailure(failure.getCause() == null ? failure : failure.getCause()); }
                    updateControls();
                }
            }.execute();
        }

        private void showFailure(Throwable failure) {
            notice.setText(failure instanceof DesktopMediaTool.OperationException operation
                    ? context.resolveText(operation.text()) : text("media.tools.failed"));
            preview = null;
            scanning = false;
            cancelling = false;
            showFiles();
            updateControls();
        }

        private void showStatus(DesktopMediaTool.Status value) {
            status = value;
            if (!running()) cancelling = false;
            stateLabel.setText(text(cancelling ? "media.tools.cancelling" : "media.tools.state." + value.state()));
            progress.setMaximum(Math.max(1, value.total()));
            progress.setValue(value.completed());
            progress.setString(text(value.state().equals("scanning") ? "media.tools.scan-progress" : "media.tools.progress", value.completed(), value.total(), value.failed()));
            showFiles();
            updateControls();
        }

        private void showFiles() {
            List<String> rows = preview != null ? preview.files().stream()
                    .map(file -> file.artworkId() + " / " + file.page() + "  " + file.fileName() + "  "
                            + (file.missingFormats().isEmpty() ? "" : text("media.tools.missing-formats", String.join(", ", file.missingFormats()).toUpperCase(java.util.Locale.ROOT)))
                            + (file.missingThumbnail() ? "  " + text("media.tools.thumbnail") : "")).toList()
                    : status == null ? List.of() : status.failures().stream().map(file -> file.artworkId() + " / " + file.page()).toList();
            page = Math.min(page, Math.max(0, (rows.size() - 1) / 10));
            files.setText(String.join("\n", rows.stream().skip(page * 10L).limit(10).toList()));
            previewLabel.setText(preview == null ? "" : text(rows.isEmpty() ? "media.tools.empty" : "media.tools.ready", rows.size(), preview.scanned()));
            if (preview != null && (preview.skipped() > 0 || preview.limited())) notice.setText(
                    (preview.skipped() > 0 ? text("media.tools.skipped", preview.skipped()) : "")
                            + (preview.limited() ? "\n" + text("media.tools.limited") : ""));
            pageLabel.setText(rows.isEmpty() ? "" : (page + 1) + " / " + ((rows.size() + 9) / 10));
            previousButton.setEnabled(page > 0);
            nextButton.setEnabled((page + 1) * 10 < rows.size());
            revalidate();
        }

        private void showCapabilities(DesktopMediaTool.Report report) {
            StringBuilder result = new StringBuilder(report.command()).append(" (").append(report.source()).append(")\n");
            report.capabilities().forEach(value -> result.append(text("media.capability." + value.name())).append(": ")
                    .append(text(value.available() ? "media.tools.available" : "media.tools.unavailable")).append('\n'));
            capabilities.setText(result.toString());
            revalidate();
        }

        private void updateControls() {
            boolean editable = !busy && !running();
            images.control().setEnabled(editable);
            animations.control().setEnabled(editable);
            thumbnails.setEnabled(editable);
            previewButton.setEnabled(editable);
            startButton.setEnabled(editable && preview != null && !preview.files().isEmpty());
            checkButton.setEnabled(editable);
            cancelButton.setEnabled(running() && !cancelling);
            progress.setIndeterminate(busy);
        }

        boolean running() { return scanning || status != null && (status.state().equals("running") || status.state().equals("scanning")); }
        private DesktopMediaTool.Source source() { return context.host().mediaTool(tool.identity()); }
        private JLabel label(String key) { return new JLabel(text(key)); }
        private JTextArea help(String key) {
            JTextArea area = textArea();
            area.setRows(2);
            area.setText(text(key));
            return area;
        }
        private String text(String key, Object... arguments) {
            return context.resolveText(new DesktopUiText(tool.description().namespace(), key, key, Arrays.stream(arguments).map(String::valueOf).toList()));
        }
        private JButton button(String name, Runnable action) {
            JButton button = new JButton(text("media.tools." + name));
            button.setName("media." + name);
            button.addActionListener(event -> action.run());
            return button;
        }
    }

    private static JPanel row(Component... components) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT));
        row.setOpaque(false);
        for (Component component : components) row.add(component);
        return row;
    }
    private static JTextArea textArea() {
        JTextArea area = new JTextArea();
        area.setEditable(false);
        area.setOpaque(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        return area;
    }
}
