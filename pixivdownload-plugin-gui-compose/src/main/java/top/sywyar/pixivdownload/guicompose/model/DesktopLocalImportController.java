package top.sywyar.pixivdownload.guicompose.model;

import top.sywyar.pixivdownload.core.work.filename.WorkTemplateVariables;
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode;
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode.*;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiHost;
import top.sywyar.pixivdownload.plugin.api.gui.importing.LocalArtworkImports;
import top.sywyar.pixivdownload.plugin.api.gui.importing.LocalArtworkImports.*;
import java.util.*;
import java.util.concurrent.*;
import static top.sywyar.pixivdownload.guicompose.model.DesktopUiNodes.*;

/** 本地导入使用已保存路径，临时选项只作用于本次扫描。 */
final class DesktopLocalImportController implements AutoCloseable {
    private static final String ID = "local-import";
    private final ComposeDesktopUiModel owner;
    private final DesktopUiHost host;
    private final ScheduledExecutorService polling = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("local-import-status").factory());
    private String templates = "", ids = "", selectedFile = "";
    private boolean refresh;
    private volatile boolean closed, busy;
    private volatile Snapshot status;
    private volatile List<Item> samples = List.of();
    private volatile TextToken notice;

    DesktopLocalImportController(ComposeDesktopUiModel owner, DesktopUiHost host) {
        this.owner = owner; this.host = host;
        polling.scheduleWithFixedDelay(this::poll, 1, 1, TimeUnit.SECONDS);
    }
    List<DesktopUiNode> panels(Map<String, Runnable> actions) {
        if (host.localArtworkImports().isEmpty()) return List.of(new Group(ID, key("local-import.title"),
                column(ID + ".body", List.of(
                        text(ID + ".help", "local-import.help", TextStyle.SECONDARY),
                        button("presets", true, actions, () -> owner.importPresets.openSaved(this::invalidate)),
                        text(ID + ".unavailable", "gui.security.error.backend-unreachable", TextStyle.SECONDARY))), false));
        boolean editable = !busy && !running();
        List<DesktopUiNode> nodes = new ArrayList<>();
        nodes.add(new Text(ID + ".help", key("local-import.help"), TextStyle.SECONDARY, true, false));
        actions.put(ID + ".guide.open", () -> owner.openUri(host.message("local-import.guide-url")));
        nodes.add(new Link(ID + ".guide", ID + ".guide.open", key("local-import.guide"), null, true));
        nodes.add(button("presets", editable, actions, () -> owner.importPresets.openSaved(this::invalidate)));
        var saved = source().presets();
        nodes.add(new Text(ID + ".saved", key(!templates.isBlank() ? "local-import.temporary-help"
                : saved.isEmpty() ? "local-import.saved-paths-empty" : "local-import.saved-paths-help", saved.size()),
                saved.isEmpty() && templates.isBlank() ? TextStyle.WARNING : TextStyle.SECONDARY, true, false));
        nodes.add(new TextInput(ID + ".templates", ID + ".templates", key(saved.isEmpty() ? "local-import.templates-required" : "local-import.templates"), key("local-import.templates-help", WorkTemplateVariables.importTokens()), InputKind.MULTILINE, templates, 40, 2, editable));
        if (templates.isBlank()) {
            if (!saved.isEmpty()) nodes.add(new Text(ID + ".preset-paths",
                    key("local-import.preset-paths", String.join("\n", saved.stream().map(Preset::template).toList())),
                    TextStyle.SECONDARY, true, true));
        }
        nodes.add(new TextInput(ID + ".ids", ID + ".ids", key("local-import.ids"), key("local-import.ids-help"), InputKind.MULTILINE, ids, 40, 3, editable));
        nodes.add(input(ID + ".file", ID + ".file", "local-import.file", "local-import.file-help", InputKind.FILE, selectedFile, editable));
        nodes.add(new Toggle(ID + ".refresh", ID + ".refresh", key("local-import.refresh"), null, ToggleStyle.CHECKBOX, refresh, editable));
        Snapshot current = status;
        if (current != null) {
            nodes.add(new Text(ID + ".phase", key("local-import.phase." + current.phase()), TextStyle.BODY, true, false));
            nodes.add(new Text(ID + ".summary", key("local-import.summary", current.artworks(), current.files(), current.imported(), current.skipped(), current.failed()), TextStyle.SECONDARY, true, false));
            if (current.cachedIndex()) nodes.add(new Text(ID + ".cached", key("local-import.cached"), TextStyle.CAPTION, true, false));
            if (running()) nodes.add(new Progress(ID + ".progress", current.artworks() == 0 ? 0 : (double) current.processed() / current.artworks(), current.phase() == Phase.SCANNING, key("local-import.phase." + current.phase())));
            for (int i = 0; i < samples.size(); i++) {
                Item item = samples.get(i);
                nodes.add(new Text(ID + ".sample." + i, key("local-import.item", item.artworkId(), item.files().size(), host.message("local-import.disposition." + item.disposition())), TextStyle.BODY, true, false));
                if (!item.files().isEmpty()) nodes.add(new Text(ID + ".path." + i, TextToken.raw(item.files().getFirst().path()), TextStyle.CAPTION, true, true));
                if (!item.reason().isEmpty()) nodes.add(new Text(ID + ".reason." + i, key("local-import.error." + item.reason()), TextStyle.ERROR, true, false));
            }
            if (current.artworks() > samples.size()) nodes.add(new Text(ID + ".samples", key("local-import.samples", samples.size(), current.artworks()), TextStyle.CAPTION, true, false));
            for (int i = 0; i < current.problems().size(); i++) nodes.add(new Text(ID + ".problem." + i,
                    key("local-import.problem", current.problems().get(i).artworkId() == null ? "" : current.problems().get(i).artworkId(),
                            host.message("local-import.error." + current.problems().get(i).code())), TextStyle.ERROR, true, false));
        }
        if (notice != null) nodes.add(new Text(ID + ".notice", notice, TextStyle.ERROR, true, false));
        nodes.add(row(ID + ".actions", button("scan", editable, actions, this::scan),
                button("start", editable && current != null && current.phase() == Phase.READY && current.artworks() > 0, actions, () -> run(() -> source().start(status.id()))),
                button("cancel", running() && current.phase() != Phase.CANCELLING, actions, () -> run(() -> source().cancel(status.id())))));
        return List.of(new Group(ID, key("local-import.title"), column(ID + ".body", nodes), false));
    }
    private Button button(String name, boolean enabled, Map<String, Runnable> actions, Runnable action) {
        actions.put(ID + "." + name, () -> { if (enabled && !closed) action.run(); });
        return new Button(ID + "." + name, ID + "." + name, key("local-import." + name), null, ButtonStyle.NORMAL, enabled);
    }
    boolean accept(String binding, String value) {
        if (!Set.of(ID + ".templates", ID + ".ids", ID + ".file", ID + ".refresh").contains(binding)) return false;
        if (busy || running()) return true;
        switch (binding) {
            case ID + ".templates" -> templates = value;
            case ID + ".ids" -> ids = value;
            case ID + ".file" -> selectedFile = value;
            case ID + ".refresh" -> refresh = Boolean.parseBoolean(value);
        }
        invalidate(); return true;
    }
    private void invalidate() { status = null; samples = List.of(); notice = null; }
    private void scan() {
        try {
            if (templates.isBlank() && source().presets().isEmpty()) {
                notice = key("local-import.error.IMPORT_NO_TEMPLATES");
                owner.rebuild();
                return;
            }
            var request = new Request(templates.lines().map(String::strip).filter(s -> !s.isEmpty()).toList(),
                    ids.isBlank() ? List.of() : Arrays.stream(ids.strip().split("[\\s,;，；]+", -1)).map(Long::valueOf).toList(),
                    refresh, selectedFile.strip());
            invalidate();
            run(() -> source().scan(request));
        } catch (RuntimeException invalid) { notice = key("local-import.error.INVALID_IMPORT_REQUEST"); owner.rebuild(); }
    }
    private void run(java.util.function.Supplier<Snapshot> action) {
        if (busy || closed) return;
        busy = true; notice = null; owner.rebuild();
        owner.executeAsync(() -> {
            try {
                Snapshot next = action.get();
                if (!closed) {
                    if (status == null || !status.id().equals(next.id())) samples = List.of();
                    status = next;
                }
            }
            catch (RuntimeException failure) {
                String code = failure.getMessage();
                notice = failure instanceof VariableException variable
                        ? key("local-import.error." + code, "{" + variable.variable() + "}")
                        : key("local-import.error." + (code != null && code.matches("(?:IMPORT_|INVALID_|AMBIGUOUS_|UNKNOWN_)[A-Z_]{1,64}") ? code : "IMPORT_IO_FAILED"));
            }
            finally { busy = false; if (!closed) { poll(); owner.rebuild(); } }
        });
    }
    private void poll() {
        Snapshot before = status;
        if (closed || busy || before == null || !running() && (before.phase() != Phase.READY || !samples.isEmpty())) return;
        try {
            var service = source();
            Snapshot next = service.status(before.id());
            List<Item> items = next.phase() == Phase.READY && samples.isEmpty() ? service.items(next.id(), 0, 20) : samples;
            if (closed || status != before) return;
            boolean changed = !next.equals(before) || !items.equals(samples);
            status = next; samples = items;
            if (changed) owner.rebuild();
        } catch (RuntimeException failure) {
            if (!closed && status == before) { invalidate(); notice = key("local-import.error.IMPORT_TASK_EXPIRED"); owner.rebuild(); }
        }
    }
    private boolean running() { return status != null && Set.of(Phase.SCANNING, Phase.IMPORTING, Phase.CANCELLING).contains(status.phase()); }
    private LocalArtworkImports source() { return host.localArtworkImports().orElseThrow(); }
    @Override public void close() { closed = true; polling.shutdownNow(); samples = List.of(); status = null; }
}
