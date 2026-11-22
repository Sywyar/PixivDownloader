package top.sywyar.pixivdownload.guicompose.model;

import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiHost;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiHost.GuiValue;
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode.*;
import java.util.*;

/** 密钥编辑的短暂状态；离开安全页即丢弃一次性秘密。 */
final class DesktopProgramKeysController {
    private final ComposeDesktopUiModel owner;
    private final DesktopUiHost host;
    private final Map<String, String> form;
    private List<GuiValue> keys = List.of();
    private List<GuiValue> catalog = List.of();
    private GuiValue editing;
    private String nextTransport = "";
    private volatile String mode = "list";
    private volatile String secret = "";
    private TextToken agentPrompt;
    private volatile TextToken notice;
    private volatile boolean uncertain;
    private long revision;
    private volatile long visit;
    private boolean ready;

    DesktopProgramKeysController(ComposeDesktopUiModel owner, DesktopUiHost host, Map<String, String> form) {
        this.owner = owner;
        this.host = host;
        this.form = form;
    }

    ProgramKeysView view(Map<String, Runnable> callbacks, List<Button> actions) {
        add(callbacks, actions, "refresh", this::load, true);
        add(callbacks, actions, "create", () -> edit(null), ready && !uncertain);
        add(callbacks, actions, "cancel", () -> { if (mode.equals("created")) load(); else { clear(); mode = "list"; owner.rebuild(); } }, true);
        add(callbacks, actions, "review", this::review, !uncertain && mode.equals("edit"));
        add(callbacks, actions, "save", this::save, !uncertain && mode.equals("review"));
        add(callbacks, actions, "back", () -> {
            if (!mode.equals("review")) return;
            mode = "edit";
            notice = null;
            owner.rebuild();
        }, !uncertain && mode.equals("review"));
        add(callbacks, actions, "revoke", this::revoke, !uncertain && mode.equals("revoke"));
        add(callbacks, actions, "confirm", () -> write("program-keys/" + editing.path("id").asText() + "/transport",
                Map.of("revision", editing.path("revision").asLong(), "transport", nextTransport), false),
                !uncertain && mode.equals("transport"));
        add(callbacks, actions, "copy", () -> {
            try { host.copyText(secret); notice = label("copied"); }
            catch (Exception failure) { notice = label("copy.manual"); }
            owner.rebuild();
        }, !secret.isEmpty());
        List<ProgramKeyRow> rows = new ArrayList<>();
        // 撤销与传输确认浮在列表之上，列表保持可见但不可再次操作。
        boolean listing = mode.equals("list") || mode.equals("revoke") || mode.equals("transport");
        boolean rowActions = mode.equals("list") && !uncertain;
        for (var key : listing ? keys : List.<GuiValue>of()) {
            String id = key.path("id").asText();
            String edit = "edit." + id, revoke = "revoke." + id;
            add(callbacks, actions, edit, () -> loadForEditing(id), ready && rowActions);
            add(callbacks, actions, revoke, () -> { editing = key; mode = "revoke"; notice = null; owner.rebuild(); }, rowActions);
            String state = key.path("transport").asText("HTTPS_REQUIRED");
            String next = switch (state) {
                case "REVIEWED" -> "HTTP_ALLOWED";
                case "HTTP_ALLOWED" -> "HTTPS_REQUIRED";
                default -> "REVIEWED";
            };
            String transport = "transport." + id;
            add(callbacks, actions, transport, () -> {
                editing = key; mode = "transport"; nextTransport = next; notice = null; owner.rebuild();
            }, rowActions);
            rows.add(new ProgramKeyRow(id, key.path("name").asText(),
                    key.path("permissionCount").asInt(), key.path("anyIp").asBoolean(),
                    values(key.path("networks")).stream().map(GuiValue::asText).toList(),
                    key.path("createdAt").asLong(), key.path("lastUsedAt").asLong(), state, "program.keys." + edit,
                    "program.keys." + revoke, "program.keys." + transport, label("transport." + next)));
        }
        List<TextInput> inputs = new ArrayList<>();
        inputs.add(input("name", "name", 1));
        inputs.add(input("networks", "source.addresses", 4));
        List<Toggle> choices = new ArrayList<>();
        Map<String, List<Toggle>> grouped = new LinkedHashMap<>();
        for (var permission : mode.equals("edit") || mode.equals("review") ? catalog : List.<GuiValue>of()) {
            String id = permission.path("id").asText();
            String binding = binding(id);
            var token = TextToken.raw(permission.path("label").asText(id));
            var help = TextToken.raw(permission.path("ownerLabel").asText(permission.path("owner").asText()));
            choices.add(new Toggle(binding, binding, token, help, ToggleStyle.CHECKBOX,
                    Boolean.parseBoolean(form.get(binding)), !owner.busy() && mode.equals("edit")));
            grouped.computeIfAbsent(permission.path("owner").asText(), ignored -> new ArrayList<>()).add(choices.getLast());
        }
        List<ProgramPermissionGroup> groups = new ArrayList<>();
        grouped.forEach((ownerId, toggles) -> {
            String name = "group." + ownerId;
            boolean all = toggles.stream().allMatch(Toggle::selected);
            add(callbacks, actions, name, () -> {
                toggles.forEach(toggle -> form.put(toggle.bindingId(), Boolean.toString(!all)));
                owner.rebuild();
            }, mode.equals("edit") && !uncertain);
            groups.add(new ProgramPermissionGroup("program.keys." + name, toggles.getFirst().help(), List.copyOf(toggles)));
        });
        var any = new Toggle("program.keys.any", "program.keys.any", label("source.any"), null, ToggleStyle.CHECKBOX,
                Boolean.parseBoolean(form.get("program.keys.any")), !owner.busy() && mode.equals("edit"));
        return new ProgramKeysView(mode, List.copyOf(rows), List.copyOf(inputs), List.copyOf(groups), any, secret, agentPrompt, notice,
                editing == null ? "" : editing.path("name").asText(editing.path("policy").path("name").asText()), nextTransport, uncertain,
                editing == null, ready);
    }

    private static String binding(String id) {
        return "program.keys.permission." + UUID.nameUUIDFromBytes(id.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private void add(Map<String, Runnable> callbacks, List<Button> actions, String name, Runnable action, boolean enabled) {
        String id = "program.keys." + name;
        callbacks.put(id, action);
        actions.add(new Button(id, id, label(name.equals("back") ? "review.back"
                : name.contains(".") ? name.substring(0, name.indexOf('.')) : name),
                null, ButtonStyle.NORMAL, !owner.busy() && enabled));
    }

    private TextInput input(String field, String label, int rows) {
        String id = "program.keys." + field;
        return new TextInput(id, id, label(label), null, rows > 1 ? InputKind.MULTILINE : InputKind.TEXT, form.getOrDefault(id, ""), 24, rows,
                !owner.busy() && mode.equals("edit"), revision);
    }

    static TextToken label(String key) { return new TextToken("security", key, "", List.of()); }

    void load() {
        clear();
        mode = "list";
        ready = false;
        long started = visit;
        owner.runBusy(() -> {
            try {
                var nextCatalog = values(read(host.guiGet("program-keys/permissions", 5000)));
                var nextKeys = values(read(host.guiGet("program-keys", 5000)));
                synchronized (owner) {
                    if (started != visit) return;
                    ready = true;
                    catalog = nextCatalog;
                    keys = nextKeys;
                    uncertain = false;
                }
            } catch (RequestFailure failure) {
                synchronized (owner) { if (started == visit) notice = failure.token; }
            }
        });
    }

    void clear() {
        visit++;
        secret = "";
        agentPrompt = null;
        notice = null;
        form.keySet().removeIf(key -> key.startsWith("program.keys."));
        revision++;
    }

    private void loadForEditing(String id) {
        long started = visit;
        owner.runBusy(() -> {
            try {
                var key = read(host.guiGet("program-keys/" + id, 5000));
                if (!id.equals(key.path("id").asText())) throw new RequestFailure(label("request.failed"), false);
                synchronized (owner) { if (started == visit) edit(key); }
            } catch (RequestFailure failure) {
                synchronized (owner) { if (started == visit) notice = failure.token; }
            }
        });
    }

    private void edit(GuiValue key) {
        if (uncertain || !ready) return;
        clear();
        editing = key;
        mode = "edit";
        if (key != null) {
            var policy = key.path("policy");
            form.put("program.keys.name", policy.path("name").asText());
            form.put("program.keys.any", policy.path("anyIp").asText());
            form.put("program.keys.networks", String.join("\n", values(policy.path("networks")).stream().map(GuiValue::asText).toList()));
            var available = catalog.stream().map(value -> value.path("id").asText()).collect(java.util.stream.Collectors.toSet());
            for (var permission : policy.path("permissions")) {
                if (available.contains(permission.asText())) form.put(binding(permission.asText()), "true");
                else notice = label("permissions.unavailable");
            }
        }
        owner.rebuild();
    }

    private Map<String, Object> policy() {
        boolean any = Boolean.parseBoolean(form.get("program.keys.any"));
        var selected = catalog.stream().map(value -> value.path("id").asText())
                .filter(id -> Boolean.parseBoolean(form.get(binding(id)))).toList();
        var networks = any ? List.<String>of() : Arrays.stream(form.getOrDefault("program.keys.networks", "").split("[\\n,]"))
                .map(String::trim).filter(value -> !value.isEmpty()).toList();
        return Map.of("name", form.getOrDefault("program.keys.name", "").trim(), "permissions", selected, "anyIp", any, "networks", networks);
    }

    private void review() {
        var policy = policy();
        if (!Boolean.TRUE.equals(policy.get("anyIp")) && ((List<?>) policy.get("networks")).isEmpty()) notice = label("source.required");
        else { mode = "review"; notice = null; }
        owner.rebuild();
    }

    private void save() {
        if (!mode.equals("review") || uncertain) return;
        var target = editing;
        var policy = policy();
        write(target == null ? "program-keys" : "program-keys/" + target.path("id").asText() + "/update",
                target == null ? policy : Map.of("revision", target.path("revision").asLong(), "policy", policy), target == null);
    }

    private void revoke() {
        if (!mode.equals("revoke") || editing == null || uncertain) return;
        write("program-keys/" + editing.path("id").asText() + "/revoke", Map.of("revision", editing.path("revision").asLong()), false);
    }

    private void write(String path, Object body, boolean creating) {
        boolean canReadReference = creating && Boolean.parseBoolean(form.get(binding("core:api-reference.read")));
        long started = visit;
        owner.runBusy(() -> {
            boolean committed = false;
            try {
                var result = read(host.guiPostJson(path, body, 5000));
                committed = true;
                String issued = creating ? result.path("secret").asText() : "";
                if (creating && issued.isEmpty()) throw new RequestFailure(label("request.check-before-retry"), true);
                var refreshed = creating ? List.<GuiValue>of() : values(read(host.guiGet("program-keys", 5000)));
                var prompt = creating ? agentPrompt(canReadReference) : null;
                synchronized (owner) {
                    if (started != visit) return;
                    if (creating) {
                        secret = issued;
                        agentPrompt = prompt;
                        mode = "created";
                    } else {
                        keys = refreshed;
                        mode = "list";
                    }
                    notice = null;
                }
            } catch (RequestFailure failure) {
                synchronized (owner) {
                    if (started != visit) return;
                    uncertain = committed || failure.uncertain;
                    notice = failure.token;
                    if (!uncertain && mode.equals("review")) mode = "edit";
                }
            }
        });
    }

    private TextToken agentPrompt(boolean canReadReference) {
        String url;
        try {
            url = host.backendUri("/api/openapi.json").toASCIIString();
        } catch (RuntimeException unavailable) {
            return null;
        }
        return new TextToken("security", canReadReference ? "created.instructions" : "instructions.restricted", "", List.of(url));
    }

    private static GuiValue read(DesktopUiHost.GuiResponse response) {
        if (!response.reachable() || response.bodyLimitExceeded()) throw new RequestFailure(label("request.failed"), true);
        if (!response.is2xx()) {
            String error = response.body() == null ? "" : response.body().path("error").asText();
            throw new RequestFailure(error.isEmpty() ? label("request.failed") : new TextToken("", "", error, List.of()), response.status() >= 500);
        }
        return response.body() == null ? GuiValue.of(Map.of()) : response.body();
    }

    private static List<GuiValue> values(GuiValue source) {
        var values = new ArrayList<GuiValue>();
        if (!source.isArray()) throw new RequestFailure(label("request.failed"), true);
        source.forEach(values::add);
        return List.copyOf(values);
    }

    private static final class RequestFailure extends RuntimeException {
        final TextToken token;
        final boolean uncertain;
        RequestFailure(TextToken token, boolean uncertain) { this.token = token; this.uncertain = uncertain; }
    }
}
