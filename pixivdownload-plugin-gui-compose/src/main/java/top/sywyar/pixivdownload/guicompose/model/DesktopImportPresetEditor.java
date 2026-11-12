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
    private Consumer<String> apply;
    private TextToken error;

    DesktopImportPresetEditor(ComposeDesktopUiModel owner, DesktopUiHost host) { this.owner = owner; this.host = host; }
    void open(String value, Consumer<String> apply) {
        drafts.clear(); this.apply = apply; error = null;
        host.parseLocalImportPresets(value).forEach(p -> drafts.add(new Draft(p.name(), p.template(), p.enabled())));
        owner.showDialog(ID, "local-import.presets", DesktopUiDocument.DialogStyle.INFO, this::content, 720, 620);
    }
    boolean accept(String binding, String value) {
        if (!binding.startsWith(ID + ".field.")) return false;
        String[] parts = binding.substring((ID + ".field.").length()).split("\\.");
        int index = Integer.parseInt(parts[0]);
        if (index < 0 || index >= drafts.size()) return true;
        Draft draft = drafts.get(index);
        switch (parts[1]) {
            case "name" -> draft.name = value;
            case "template" -> draft.template = value;
            case "enabled" -> draft.enabled = Boolean.parseBoolean(value);
        }
        error = null; return true;
    }
    private DesktopUiNode content(Map<String, Runnable> actions, String dismissAction, Runnable dismiss) {
        List<DesktopUiNode> body = new ArrayList<>();
        body.add(new Text(ID + ".help", key("local-import.presets-save-help"), TextStyle.SECONDARY, true, false));
        for (int i = 0; i < drafts.size(); i++) {
            Draft draft = drafts.get(i); String id = ID + ".field." + i;
            body.add(new TextInput(id + ".name", id + ".name", key("local-import.name"), null, InputKind.TEXT, draft.name, 24, 1, true));
            body.add(new TextInput(id + ".template", id + ".template", key("local-import.template"), key("local-import.templates-help", WorkTemplateVariables.importTokens()), InputKind.TEXT, draft.template, 48, 1, true));
            body.add(new Toggle(id + ".enabled", id + ".enabled", key("local-import.enabled"), null, ToggleStyle.CHECKBOX, draft.enabled, true));
            int index = i;
            body.add(action(id + ".remove", "remove", true, actions, () -> { drafts.remove(index); owner.rebuild(); }));
        }
        body.add(action(ID + ".add", "add", drafts.size() < 32, actions, () -> { drafts.add(new Draft("", "", true)); owner.rebuild(); }));
        if (error != null) body.add(new Text(ID + ".error", error, TextStyle.ERROR, true, false));
        return dialogContent(ID + ".content", column(ID + ".body", body), endRow(ID + ".actions",
                new Button(ID + ".dismiss", dismissAction, key("local-import.cancel"), null, ButtonStyle.NORMAL, true),
                action(ID + ".apply", "apply", true, actions, () -> {
                    try {
                        String value = host.formatLocalImportPresets(drafts.stream().map(d -> new Preset(d.name.strip(), d.template.strip(), d.enabled)).toList());
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
        return new Button(id, id, key("local-import." + key), null, ButtonStyle.NORMAL, enabled);
    }
    private static final class Draft {
        String name, template; boolean enabled;
        Draft(String name, String template, boolean enabled) { this.name = name; this.template = template; this.enabled = enabled; }
    }
}
