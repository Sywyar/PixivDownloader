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

/** 编辑本地导入预设，确认后交还设置草稿，取消不改变已保存配置。 */
final class DesktopImportPresetEditor {
    private static final String ID = "import-presets";
    private final ComposeDesktopUiModel owner;
    private final DesktopUiHost host;
    private final List<Draft> drafts = new ArrayList<>();
    private int nextId;
    private Consumer<String> apply;
    private TextToken error;

    DesktopImportPresetEditor(ComposeDesktopUiModel owner, DesktopUiHost host) { this.owner = owner; this.host = host; }
    void open(String value, Consumer<String> apply) {
        drafts.clear(); nextId = 0; this.apply = apply; error = null;
        host.parseLocalImportPresets(value).forEach(p -> drafts.add(new Draft(nextId++, p.template())));
        owner.showDialog(ID, "local-import.presets", DesktopUiDocument.DialogStyle.INFO, this::content, 760, 520);
    }
    boolean accept(String binding, String value) {
        if (!binding.startsWith(ID + ".field.")) return false;
        String[] parts = binding.substring((ID + ".field.").length()).split("\\.");
        int id = Integer.parseInt(parts[0]);
        drafts.stream().filter(d -> d.id == id).findFirst().ifPresent(d -> d.template = value);
        error = null; return true;
    }
    private DesktopUiNode content(Map<String, Runnable> actions, String dismissAction, Runnable dismiss) {
        List<DesktopUiNode> body = new ArrayList<>();
        body.add(new Text(ID + ".help", key("local-import.presets-save-help"), TextStyle.SECONDARY, true, false));
        List<TextInput> inputs = new ArrayList<>();
        List<Button> removals = new ArrayList<>();
        for (int i = 0; i < drafts.size(); i++) {
            Draft draft = drafts.get(i); String id = ID + ".field." + draft.id;
            inputs.add(new TextInput(id + ".template", id + ".template", key("local-import.template-row", i + 1),
                    null, InputKind.TEXT, draft.template, 48, 1, true));
            removals.add(action(id + ".remove", "remove", true, actions, () -> {
                drafts.remove(draft); error = null; owner.rebuild();
            }));
        }
        body.add(new ImportPresetList(ID + ".list", inputs, removals,
                key("local-import.template"), key("local-import.presets-empty")));
        body.add(new Group(ID + ".rules", key("gui.config.directory.variables"),
                new Text(ID + ".rules.text", key("local-import.preset-template-help", WorkTemplateVariables.importTokens()),
                        TextStyle.SECONDARY, true, false), true));
        if (error != null) body.add(new Text(ID + ".error", error, TextStyle.ERROR, true, false));
        return dialogContent(ID + ".content", column(ID + ".body", body), endRow(ID + ".actions",
                action(ID + ".add", "add", drafts.size() < 32, actions, () -> {
                    drafts.add(new Draft(nextId++, "")); error = null; owner.rebuild();
                }),
                new Button(ID + ".dismiss", dismissAction, key("local-import.cancel"), null, ButtonStyle.NORMAL, true),
                action(ID + ".apply", "apply", true, actions, () -> {
                    try {
                        String value = host.formatLocalImportPresets(drafts.stream().map(d -> new Preset(d.template.strip())).toList());
                        apply.accept(value); dismiss.run();
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
                key.equals("apply") ? ButtonStyle.PRIMARY : ButtonStyle.NORMAL, enabled);
    }
    private static final class Draft {
        final int id;
        String template;
        Draft(int id, String template) { this.id = id; this.template = template; }
    }
}
