package top.sywyar.pixivdownload.guicompose.model;

import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode;
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode.*;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiHost;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiText;
import top.sywyar.pixivdownload.plugin.api.gui.importing.DesktopFileImportTool;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static top.sywyar.pixivdownload.guicompose.model.DesktopUiNodes.*;

/** Compose 拥有导入表单；凭据仅留在临时草稿，绝不进入视图快照。 */
final class DesktopFileImportController implements AutoCloseable {
    private final ComposeDesktopUiModel owner;
    private final DesktopUiHost host;
    private final Map<DesktopFileImportTool.Identity, State> states = new ConcurrentHashMap<>();
    private final ScheduledExecutorService polling = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "desktop-import-status");
        thread.setDaemon(true);
        return thread;
    });
    private volatile boolean closed;

    DesktopFileImportController(ComposeDesktopUiModel owner, DesktopUiHost host) { this.owner = owner; this.host = host; }

    List<DesktopUiNode> panels(Map<String, Runnable> actions) {
        var tools = host.fileImportTools();
        if (tools == null) tools = List.of();
        var identities = tools.stream().map(DesktopFileImportTool::identity).toList();
        states.entrySet().removeIf(entry -> {
            if (identities.contains(entry.getKey())) return false;
            entry.getValue().credential = "";
            return true;
        });
        List<DesktopUiNode> result = new ArrayList<>();
        for (var tool : tools) {
            State state = states.get(tool.identity());
            if (state == null) {
                state = new State(tool);
                states.put(tool.identity(), state);
                State discovered = state;
                owner.executeAsync(() -> refresh(discovered));
            }
            result.add(panel(state, actions));
        }
        return result;
    }
    boolean accept(String binding, String value) {
        for (State state : states.values()) {
            if (!binding.startsWith(state.id + ".")) continue;
            if (state.busy || state.running()) return true;
            if (binding.equals(state.id + ".credential")) state.credential = value;
            else {
                if (binding.equals(state.id + ".file")) state.file = value;
                else if (binding.equals(state.id + ".root")) state.root = value;
                else return false;
                state.preview = null;
                state.notice = null;
            }
            state.revision++;
            return true;
        }
        return false;
    }

    private Group panel(State s, Map<String, Runnable> actions) {
        boolean editable = !owner.busy() && !s.busy && !s.running();
        List<DesktopUiNode> body = new ArrayList<>();
        body.add(new Text(s.id + ".help", token(s.tool.description().help()), TextStyle.SECONDARY, true, false));
        body.add(input(s.id + ".file", s.id + ".file", "gui.import.file", null, InputKind.FILE_OR_DIRECTORY, s.file, editable));
        body.add(input(s.id + ".root", s.id + ".root", "gui.import.root", "gui.import.root-help", InputKind.DIRECTORY, s.root, editable));
        body.add(new TextInput(s.id + ".credential", s.id + ".credential", key("gui.import.credential"),
                key("gui.import.credential-help"), InputKind.PASSWORD, "", 32, 1, editable, s.secretRevision));
        if (s.preview != null) {
            var p = s.preview;
            body.add(new Text(s.id + ".summary", key("gui.import.preview-summary", p.records(), p.works(), p.candidates(), p.skipped(), p.unavailable()), TextStyle.BODY, true, false));
            body.add(new Text(s.id + ".sample-label", key("gui.import.samples", p.samples().size(), p.works()), TextStyle.CAPTION, true, false));
            for (int i = 0; i < p.samples().size(); i++) {
                var item = p.samples().get(i);
                body.add(new Text(s.id + ".sample." + i,
                        key("gui.import.item", item.workKey(), item.files(), host.message("gui.import.disposition." + item.disposition().name().toLowerCase(Locale.ROOT))),
                        TextStyle.SECONDARY, true, false));
            }
        }
        var status = s.status;
        if (status != null && status.phase() != DesktopFileImportTool.Phase.IDLE) {
            body.add(new Text(s.id + ".state", key("gui.import.phase." + status.phase().name().toLowerCase(Locale.ROOT)), TextStyle.BODY, true, false));
            body.add(new Text(s.id + ".counts", key("gui.import.progress", status.processed(), status.total(), status.imported(), status.skipped(), status.failed()), TextStyle.SECONDARY, true, false));
            if (status.active()) body.add(new Progress(s.id + ".progress", status.total() == 0 ? 0 : (double) status.processed() / status.total(), status.total() == 0, key("gui.import.working")));
            for (int i = 0; i < status.problems().size(); i++) {
                var problem = status.problems().get(i);
                body.add(new Text(s.id + ".problem." + i, token(problem.reason()), TextStyle.ERROR, true, false));
                body.add(raw(s.id + ".problem-key." + i, problem.workKey(), TextStyle.CAPTION));
            }
        }
        if (s.busy && (status == null || !status.active())) body.add(new Progress(s.id + ".busy", 0, true, key("gui.import.working")));
        if (s.notice != null) body.add(new Text(s.id + ".notice", s.notice, TextStyle.ERROR, true, false));
        body.add(row(s.id + ".actions",
                action(s, "inspect", editable && !s.file.isBlank() && !s.root.isBlank(), actions, () -> inspect(s)),
                action(s, "start", editable && s.preview != null && s.preview.candidates() > 0, actions, () -> start(s)),
                action(s, "cancel", (s.busy || s.running()) && !s.cancelling, actions, () -> cancel(s)),
                action(s, "leave", true, actions, () -> { s.credential = ""; s.secretRevision++; owner.rebuild(); })));
        return new Group(s.id, token(s.tool.description().title()), column(s.id + ".content", body), false);
    }
    private Button action(State s, String name, boolean enabled, Map<String, Runnable> actions, Runnable action) {
        String id = s.id + "." + name;
        actions.put(id, () -> { if (active(s) && enabled) action.run(); });
        return new Button(id, id, key("gui.import." + name), null, ButtonStyle.NORMAL, enabled);
    }
    private void inspect(State s) {
        s.preview = null;
        String file = s.file, root = s.root;
        run(s, () -> s.preview = source(s).inspect(new DesktopFileImportTool.Request(file, root)).valueOrThrow());
    }
    private void start(State s) {
        var preview = s.preview;
        if (preview == null) return;
        String credential = s.credential;
        s.credential = "";
        s.secretRevision++;
        s.preview = null;
        run(s, () -> s.status = source(s).start(preview.token(), credential).valueOrThrow());
    }
    private void cancel(State s) {
        s.cancelling = true;
        owner.executeAsync(() -> {
            try { source(s).cancel(); } catch (RuntimeException failure) { fail(s, failure); }
            refresh(s);
        });
        owner.rebuild();
    }
    private void run(State s, Runnable action) {
        if (s.busy || s.running()) return;
        s.busy = true;
        s.revision++;
        s.notice = null;
        owner.rebuild();
        schedule(s);
        owner.executeAsync(() -> {
            try { if (active(s)) action.run(); }
            catch (RuntimeException failure) { fail(s, failure); }
            finally {
                s.busy = false;
                if (active(s)) refresh(s);
            }
        });
    }
    private void refresh(State s) {
        if (!active(s)) return;
        long revision = s.revision;
        try {
            var status = source(s).status();
            if (!active(s) || revision != s.revision) return;
            s.status = status;
            if (s.cancelling && status.active()) source(s).cancel();
            if (!s.busy && !status.active()) s.cancelling = false;
            if (s.busy || status.active()) schedule(s);
        } catch (RuntimeException failure) { fail(s, failure); }
        if (active(s)) owner.rebuild();
    }
    private void schedule(State s) {
        if (!active(s) || !s.polling.compareAndSet(false, true)) return;
        try {
            polling.schedule(() -> {
                s.polling.set(false);
                refresh(s);
            }, 1, TimeUnit.SECONDS);
        } catch (RejectedExecutionException rejected) { s.polling.set(false); if (!closed) throw rejected; }
    }
    private DesktopFileImportTool.Source source(State s) { return host.fileImportTool(s.tool.identity()); }
    private boolean active(State s) { return !closed && states.get(s.tool.identity()) == s; }
    private static TextToken token(DesktopUiText value) { return new TextToken(value.namespace(), value.key(), value.fallback(), value.arguments()); }
    private static TextToken key(String key, Object... args) {
        return new TextToken(null, key, key, Arrays.stream(args).map(String::valueOf).toList());
    }
    private static void fail(State s, RuntimeException failure) {
        s.notice = token(failure instanceof DesktopFileImportTool.OperationException operation ? operation.text() : DesktopUiText.key("desktop.ui.action.failed"));
        s.preview = null;
    }
    @Override public void close() {
        closed = true;
        states.values().forEach(s -> s.credential = "");
        states.clear();
        polling.shutdownNow();
    }
    private static final class State {
        final DesktopFileImportTool tool;
        final String id;
        volatile String file = "", root = "", credential = "";
        volatile boolean busy, cancelling;
        volatile long revision, secretRevision;
        volatile DesktopFileImportTool.Preview preview;
        volatile DesktopFileImportTool.Status status;
        volatile TextToken notice;
        final AtomicBoolean polling = new AtomicBoolean();
        State(DesktopFileImportTool tool) { this.tool = tool; id = "import." + tool.identity().pluginId() + "." + tool.identity().publication(); }
        boolean running() { return status != null && status.active(); }
    }
}
