package top.sywyar.pixivdownload.guicompose.model;

import top.sywyar.pixivdownload.core.work.filename.WorkTemplateVariables;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiHost;
import top.sywyar.pixivdownload.plugin.api.gui.importing.LocalArtworkImports.Preset;
import top.sywyar.pixivdownload.plugin.api.gui.importing.LocalArtworkImports.VariableException;
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiDocument;
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode;
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode.*;
import java.util.*;
import java.util.function.Consumer;
import static top.sywyar.pixivdownload.guicompose.model.DesktopUiNodes.*;

/** 复用路径列表编辑；工具入口明确保存预设，普通字段入口交还草稿。 */
final class DesktopImportPresetEditor {
    private static final String ID = "import-presets";
    private final ComposeDesktopUiModel owner;
    private final DesktopUiHost host;
    private final List<Draft> drafts = new ArrayList<>();
    private int nextId;
    private Consumer<String> apply;
    private TextToken error;
    private boolean persistent, saving;
    private Runnable afterSave;

    DesktopImportPresetEditor(ComposeDesktopUiModel owner, DesktopUiHost host) { this.owner = owner; this.host = host; }
    void open(String value, Consumer<String> apply) {
        persistent = false; saving = false;
        drafts.clear(); nextId = 0; this.apply = apply; error = null;
        host.parseLocalImportPresets(value).forEach(p -> drafts.add(new Draft(nextId++, p.template())));
        show();
    }
    void openSaved(Runnable afterSave) {
        owner.runBusy(() -> {
            try {
                String value = host.applicationConfig().readAll(List.of("local-import.presets"))
                        .getOrDefault("local-import.presets", "[]");
                synchronized (owner) {
                    open(value, null); persistent = true; this.afterSave = afterSave; show();
                }
            } catch (java.io.IOException | RuntimeException failure) {
                org.slf4j.LoggerFactory.getLogger(DesktopImportPresetEditor.class).error("Cannot load local import presets", failure);
                owner.showDialog(ID, "local-import.presets", "local-import.presets-load-failed", DesktopUiDocument.DialogStyle.ERROR);
            }
        });
    }
    private void show() {
        owner.showDialog(ID, "local-import.presets", DesktopUiDocument.DialogStyle.INFO, this::content, !saving, 760, 520);
    }
    private void save(String value) {
        saving = true; show();
        owner.executeAsync(() -> {
            try {
                host.saveLocalImportPresets(value);
                synchronized (owner) {
                    owner.coreConfigValueSaved("local-import.presets", value);
                    afterSave.run();
                    if (owner.isDialogOpen(ID)) owner.closeDialog();
                }
            } catch (java.io.IOException | RuntimeException failure) {
                org.slf4j.LoggerFactory.getLogger(DesktopImportPresetEditor.class).error("Cannot save local import presets", failure);
                error = key("local-import.presets-save-failed");
            } finally {
                saving = false;
                if (owner.isDialogOpen(ID)) show(); else owner.rebuild();
            }
        });
    }
    boolean accept(String binding, String value) {
        if (!binding.startsWith(ID + ".field.")) return false;
        if (saving) return true;
        String[] parts = binding.substring((ID + ".field.").length()).split("\\.");
        int id = Integer.parseInt(parts[0]);
        drafts.stream().filter(d -> d.id == id).findFirst().ifPresent(d -> d.template = value);
        error = null; return true;
    }
    private DesktopUiNode content(Map<String, Runnable> actions, String dismissAction, Runnable dismiss) {
        List<DesktopUiNode> body = new ArrayList<>();
        body.add(new Text(ID + ".help", key(persistent ? "local-import.presets-save-direct-help" : "local-import.presets-save-help"), TextStyle.SECONDARY, true, false));
        List<TextInput> inputs = new ArrayList<>();
        List<Button> removals = new ArrayList<>();
        for (int i = 0; i < drafts.size(); i++) {
            Draft draft = drafts.get(i); String id = ID + ".field." + draft.id;
            inputs.add(new TextInput(id + ".template", id + ".template", key("local-import.template-row", i + 1),
                    null, InputKind.TEXT, draft.template, 48, 1, !saving));
            removals.add(action(id + ".remove", "remove", !saving, actions, () -> {
                drafts.remove(draft); error = null; owner.rebuild();
            }));
        }
        body.add(new ImportPresetList(ID + ".list", inputs, removals,
                key("local-import.template"), key("local-import.presets-empty")));
        body.add(new Group(ID + ".rules", key("gui.config.directory.variables"),
                column(ID + ".rules.content", List.of(
                        new Text(ID + ".rules.text", key("local-import.preset-template-help", WorkTemplateVariables.importTokens()),
                                TextStyle.SECONDARY, true, false),
                        text(ID + ".rules.kind", "local-import.template-kind-help", TextStyle.INFO))), true));
        if (error != null) body.add(new Text(ID + ".error", error, TextStyle.ERROR, true, false));
        return dialogContent(ID + ".content", column(ID + ".body", body), endRow(ID + ".actions",
                action(ID + ".add", "add", !saving && drafts.size() < 32, actions, () -> {
                    drafts.add(new Draft(nextId++, "")); error = null; owner.rebuild();
                }),
                new Button(ID + ".dismiss", dismissAction, key("local-import.cancel"), null, ButtonStyle.NORMAL, !saving),
                action(ID + ".apply", persistent ? "save" : "apply", !saving, actions, () -> {
                    try {
                        String value = host.formatLocalImportPresets(drafts.stream().map(d -> new Preset(d.template.strip())).toList());
                        if (persistent) save(value);
                        else { apply.accept(value); dismiss.run(); }
                    } catch (RuntimeException failure) {
                        error = failure instanceof VariableException variable
                                ? key("local-import.error." + variable.getMessage(), "{" + variable.variable() + "}")
                                : key("local-import.error.INVALID_IMPORT_PRESET");
                        owner.rebuild();
                    }
                })));
    }
    private Button action(String id, String key, boolean enabled, Map<String, Runnable> actions, Runnable action) {
        actions.put(id, () -> { if (enabled) action.run(); });
        return new Button(id, id, key("local-import." + key), null,
                key.equals("apply") || key.equals("save") ? ButtonStyle.PRIMARY : ButtonStyle.NORMAL, enabled);
    }
    private static final class Draft {
        final int id;
        String template;
        Draft(int id, String template) { this.id = id; this.template = template; }
    }
}
