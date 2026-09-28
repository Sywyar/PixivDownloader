package top.sywyar.pixivdownload.guicompose.model;

import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode;
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode.*;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiHost;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiText;
import top.sywyar.pixivdownload.plugin.api.gui.media.DesktopMediaTool;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static top.sywyar.pixivdownload.guicompose.model.DesktopUiNodes.*;

/** Compose 拥有媒体表单和交互状态；副作用仅由宿主的精确能力句柄执行。 */
final class DesktopMediaToolsController implements AutoCloseable {
    private final ComposeDesktopUiModel owner;
    private final DesktopUiHost host;
    private final Map<DesktopMediaTool.Identity, State> states = new ConcurrentHashMap<>();
    private final ScheduledExecutorService polling = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "desktop-media-status");
        thread.setDaemon(true);
        return thread;
    });
    private volatile boolean closed;

    DesktopMediaToolsController(ComposeDesktopUiModel owner, DesktopUiHost host) {
        this.owner = owner;
        this.host = host;
    }

    List<DesktopUiNode> panels(Map<String, Consumer<List<String>>> selections, Map<String, Runnable> actions) {
        List<DesktopMediaTool> tools = host.mediaTools();
        if (tools == null) tools = List.of();
        var identities = tools.stream().map(DesktopMediaTool::identity).toList();
        states.keySet().removeIf(identity -> !identities.contains(identity));
        List<DesktopUiNode> panels = new ArrayList<>();
        for (var tool : tools) {
            State state = states.get(tool.identity());
            if (state == null) {
                state = new State(tool);
                states.put(tool.identity(), state);
                State discovered = state;
                owner.executeAsync(() -> refresh(discovered));
            }
            panels.add(panel(state, selections, actions));
        }
        return panels;
    }

    boolean accept(String binding, String value) {
        for (State state : states.values()) {
            if (binding.equals(state.id + ".thumbnails")) {
                if (!state.busy && !state.running()) {
                    state.thumbnails = Boolean.parseBoolean(value);
                    state.preview = null;
                    state.notice = null;
                }
                return true;
            }
        }
        return false;
    }

    private DesktopUiNode panel(State state, Map<String, Consumer<List<String>>> selections, Map<String, Runnable> actions) {
        String id = state.id;
        boolean editable = !owner.busy() && !state.busy && !state.running();
        List<DesktopUiNode> body = new ArrayList<>();
        body.add(new Text(id + ".help", token(state, "media.tools.help"), TextStyle.BODY, true, false));
        body.add(choice(state, false, editable, selections));
        body.add(choice(state, true, editable, selections));
        body.add(new Toggle(id + ".thumbnails", id + ".thumbnails", token(state, "media.tools.thumbnails"), null,
                ToggleStyle.CHECKBOX, state.thumbnails, editable));
        body.add(row(id + ".actions",
                action(state, "preview", editable, actions, () -> preview(state)),
                action(state, "start", editable && state.preview != null && !state.preview.files().isEmpty(), actions, () -> start(state)),
                action(state, "cancel", state.running() && !state.cancelling, actions, () -> cancel(state)),
                action(state, "refresh", !state.busy, actions, () -> owner.executeAsync(() -> refresh(state)))));
        var preview = state.preview;
        var status = state.status;
        if (preview != null) {
            var files = preview.files();
            body.add(new Text(id + ".ready", token(state, files.isEmpty() ? "media.tools.empty" : "media.tools.ready", files.size(), preview.scanned()), TextStyle.BODY, true, false));
            if (preview.skipped() > 0) body.add(new Text(id + ".skipped", token(state, "media.tools.skipped", preview.skipped()), TextStyle.WARNING, true, false));
            if (preview.limited()) body.add(new Text(id + ".limited", token(state, "media.tools.limited"), TextStyle.WARNING, true, false));
        }
        int fileCount = preview != null ? preview.files().size() : status == null ? 0 : status.failures().size();
        if (fileCount > 0) {
            state.page = Math.min(state.page, (fileCount - 1) / 10);
            int from = state.page * 10;
            int to = Math.min(fileCount, from + 10);
            List<String> files = preview != null ? preview.files().subList(from, to).stream()
                    .map(file -> file.artworkId() + " / " + file.page() + "  " + file.fileName()).toList()
                    : status.failures().subList(from, to).stream()
                    .map(file -> file.artworkId() + " / " + file.page()).toList();
            body.add(raw(id + ".files", String.join("\n", files), TextStyle.CODE));
            if (preview != null) {
                for (int index = from; index < to; index++) {
                    var file = preview.files().get(index);
                    String rowId = id + ".file." + (index % 10);
                    if (!file.missingFormats().isEmpty()) body.add(new Text(rowId + ".formats",
                            token(state, "media.tools.missing-formats", String.join(", ", file.missingFormats()).toUpperCase(java.util.Locale.ROOT)), TextStyle.SECONDARY, true, false));
                    if (file.missingThumbnail()) body.add(new Text(rowId + ".thumbnail", token(state, "media.tools.thumbnail"), TextStyle.SECONDARY, true, false));
                }
            }
            body.add(row(id + ".pages",
                    action(state, "previous", state.page > 0, actions, () -> { state.page--; owner.rebuild(); }),
                    raw(id + ".page", (state.page + 1) + " / " + ((fileCount + 9) / 10), TextStyle.CAPTION),
                    action(state, "next", (state.page + 1) * 10 < fileCount, actions, () -> { state.page++; owner.rebuild(); })));
        }
        if (status != null && !status.state().equals("idle")) {
            if (state.running()) {
                body.add(new Progress(id + ".progress", status.total() == 0 ? 0 : (double) status.completed() / status.total(), status.total() == 0,
                        token(state, status.state().equals("scanning") ? "media.tools.scan-progress" : "media.tools.progress", status.completed(), status.total(), status.failed())));
            } else {
                body.add(new Text(id + ".summary", token(state, status.state().equals("scanning") ? "media.tools.scan-progress" : "media.tools.progress", status.completed(), status.total(), status.failed()), TextStyle.SECONDARY, true, false));
            }
            body.add(new Text(id + ".state", token(state, state.cancelling ? "media.tools.cancelling" : "media.tools.state." + status.state()),
                    TextStyle.BODY, true, false));
        }
        body.add(new Text(id + ".cap-title", token(state, "media.tools.capabilities"), TextStyle.HEADING, true, false));
        body.add(new Text(id + ".cap-help", token(state, "media.tools.capabilities-help"), TextStyle.SECONDARY, true, false));
        body.add(action(state, "check", editable, actions, () -> run(state, () -> state.report = source(state).capabilities().valueOrThrow())));
        if (state.report != null) {
            body.add(raw(id + ".command", state.report.command() + " (" + state.report.source() + ")", TextStyle.CODE));
            for (var capability : state.report.capabilities()) {
                body.add(row(id + ".cap." + capability.name(),
                        new Text(id + ".cap." + capability.name() + ".name", token(state, "media.capability." + capability.name()), TextStyle.BODY, true, false),
                        new Text(id + ".cap." + capability.name() + ".result", token(state, capability.available() ? "media.tools.available" : "media.tools.unavailable"), TextStyle.BODY, true, false)));
            }
        }
        if (state.busy) body.add(new Progress(id + ".busy", 0, true, token(state, "media.tools.state.running")));
        if (state.notice != null) body.add(new Text(id + ".notice", state.notice, TextStyle.ERROR, true, false));
        var title = state.tool.description().title();
        return new Group(id, new TextToken(title.namespace(), title.key(), title.fallback(), title.arguments()), column(id + ".content", body), false);
    }

    private Choice choice(State state, boolean animation, boolean enabled, Map<String, Consumer<List<String>>> selections) {
        String binding = state.id + (animation ? ".ugoira" : ".images");
        List<String> formats = animation ? List.of("webp", "gif", "apng", "mp4", "zip") : List.of("original", "png", "jpg", "webp");
        selections.put(binding, values -> {
            if (state.busy || state.running() || values.isEmpty()) return;
            if (animation) state.ugoiraFormats = String.join(",", values);
            else state.imageFormats = String.join(",", values);
            state.preview = null;
            state.notice = null;
        });
        return new Choice(binding, binding, token(state, animation ? "media.ugoira-formats.label" : "media.image-formats.label"),
                animation ? token(state, "media.tools.animations") : null, ChoiceStyle.COMBO_BOX, SelectionMode.MULTIPLE,
                formats.stream().map(format -> new Option(format, token(state, "media.format." + format), true)).toList(),
                Arrays.asList((animation ? state.ugoiraFormats : state.imageFormats).split(",")), enabled);
    }

    private Button action(State state, String name, boolean enabled, Map<String, Runnable> actions, Runnable action) {
        String id = state.id + "." + name;
        actions.put(id, () -> { if (active(state)) action.run(); });
        return new Button(id, id, token(state, "media.tools." + name), null, ButtonStyle.NORMAL, enabled);
    }

    private void preview(State state) {
        state.preview = null;
        run(state, () -> {
            state.scanning = true;
            scheduleRefresh(state);
            try {
                state.preview = source(state).preview(new DesktopMediaTool.Request(state.imageFormats, state.ugoiraFormats, state.thumbnails)).valueOrThrow();
            } finally {
                state.scanning = false;
                state.status = source(state).status();
                state.cancelling = false;
            }
            state.page = 0;
        });
    }

    private void start(State state) {
        var preview = state.preview;
        if (preview == null) return;
        state.preview = null;
        state.page = 0;
        run(state, () -> {
            state.status = source(state).start(preview.token()).valueOrThrow();
            scheduleRefresh(state);
        });
    }

    private void cancel(State state) {
        state.cancelling = true;
        state.revision++;
        owner.rebuild();
        owner.executeAsync(() -> {
            try { source(state).cancel(); }
            catch (RuntimeException failure) { fail(state, failure); state.cancelling = false; }
            owner.rebuild();
        });
    }

    private void run(State state, Runnable action) {
        if (state.busy || state.running()) return;
        state.busy = true;
        state.revision++;
        state.notice = null;
        owner.rebuild();
        owner.executeAsync(() -> {
            try { if (active(state)) action.run(); }
            catch (RuntimeException failure) { fail(state, failure); }
            finally { state.busy = false; if (active(state)) owner.rebuild(); }
        });
    }

    private void refresh(State state) {
        if (!active(state)) return;
        long revision = state.revision;
        try {
            var status = source(state).status();
            if (!active(state) || state.revision != revision) return;
            state.status = status;
            if (!state.running()) state.cancelling = false;
            else scheduleRefresh(state);
        } catch (RuntimeException failure) { fail(state, failure); }
        if (active(state)) owner.rebuild();
    }

    private void scheduleRefresh(State state) {
        if (active(state) && state.polling.compareAndSet(false, true)) {
            try {
                polling.schedule(() -> {
                    state.polling.set(false);
                    refresh(state);
                }, 1, TimeUnit.SECONDS);
            } catch (RejectedExecutionException rejected) {
                state.polling.set(false);
                if (!closed) throw rejected;
            }
        }
    }

    private DesktopMediaTool.Source source(State state) { return host.mediaTool(state.tool.identity()); }
    private boolean active(State state) { return !closed && states.get(state.tool.identity()) == state; }
    private static TextToken token(State state, String key, Object... arguments) {
        return new TextToken(state.tool.description().namespace(), key, key, Arrays.stream(arguments).map(String::valueOf).toList());
    }
    private static void fail(State state, RuntimeException failure) {
        DesktopUiText text = failure instanceof DesktopMediaTool.OperationException operation
                ? operation.text() : DesktopUiText.key("desktop.ui.action.failed");
        state.notice = new TextToken(text.namespace(), text.key(), text.fallback(), text.arguments());
        state.preview = null;
    }
    @Override public void close() { closed = true; states.clear(); polling.shutdownNow(); }

    private static final class State {
        final DesktopMediaTool tool;
        final String id;
        volatile boolean scanning;
        volatile String imageFormats;
        volatile String ugoiraFormats;
        volatile boolean thumbnails = true;
        volatile boolean busy;
        volatile boolean cancelling;
        volatile long revision;
        volatile int page;
        volatile DesktopMediaTool.Preview preview;
        volatile DesktopMediaTool.Status status;
        volatile DesktopMediaTool.Report report;
        volatile TextToken notice;
        final java.util.concurrent.atomic.AtomicBoolean polling = new java.util.concurrent.atomic.AtomicBoolean();
        State(DesktopMediaTool tool) {
            this.tool = tool;
            id = "media." + tool.identity().pluginId() + "." + tool.identity().publication();
            imageFormats = tool.description().imageFormats();
            ugoiraFormats = tool.description().ugoiraFormats();
        }
        boolean running() { return scanning || status != null && (status.state().equals("running") || status.state().equals("scanning")); }
    }
}
