package top.sywyar.pixivdownload.guicompose.model;

import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiHost;
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiDocument;
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode;
import java.util.Map;
import static top.sywyar.pixivdownload.guicompose.model.DesktopUiNodes.*;

/** Provider-owned confirmation state; candidates never become settings drafts automatically. */
final class DesktopDirectorySuggestionController {
    static final String ID = "directory-suggestion";
    private final ComposeDesktopUiModel owner;
    private final DesktopUiHost host;
    private final Map<String, String> form;
    private DesktopUiHost.GuiValue current;
    private String lastPresented = "";
    private boolean saving;
    private boolean failed;

    DesktopDirectorySuggestionController(ComposeDesktopUiModel owner, DesktopUiHost host, Map<String, String> form) {
        this.owner = owner;
        this.host = host;
        this.form = form;
    }

    void refresh(DesktopUiHost.GuiValue snapshot) {
        synchronized (owner) {
            var candidates = snapshot.path("directories");
            if (current != null) {
                boolean present = false;
                for (var candidate : candidates) if (identity(candidate).equals(identity(current))) present = true;
                if (!present && !saving) {
                    if (owner.isDialogOpen(ID)) owner.closeDialog();
                    current = null;
                    form.remove(ID + ".path");
                    owner.rebuild();
                }
            }
            if (current != null || owner.hasDialog() || owner.busy()) return;
            for (var candidate : candidates) {
                if (identity(candidate).equals(lastPresented)) continue;
                current = candidate;
                lastPresented = identity(candidate);
                failed = false;
                form.put(ID + ".path", candidate.path("suggestion").path("directory").asText());
                show();
                break;
            }
        }
    }

    private void show() {
        owner.showDialog(ID, "gui.directory-suggestion.title", DesktopUiDocument.DialogStyle.INFO,
                (actions, dismissAction, dismiss) -> {
                    actions.put(dismissAction, () -> resolve(true));
                    return new DesktopUiNode.Dock(ID + ".layout", 12, null,
                            scroll(ID + ".scroll", column(ID + ".content",
                            new DesktopUiNode.Text(ID + ".plugin", guiToken(current.path("displayName")),
                                    DesktopUiNode.TextStyle.EMPHASIS, true, false),
                            text(ID + ".help", "gui.directory-suggestion.help", DesktopUiNode.TextStyle.BODY),
                            input(ID + ".path", ID + ".path", "gui.directory-suggestion.directory", null,
                                    DesktopUiNode.InputKind.DIRECTORY, form.getOrDefault(ID + ".path", ""), !saving),
                            text(ID + ".notice", failed ? "gui.directory-suggestion.failed" : "gui.directory-suggestion.readonly",
                                    failed ? DesktopUiNode.TextStyle.ERROR : DesktopUiNode.TextStyle.CAPTION))),
                            endRow(ID + ".actions",
                                    button(ID + ".cancel", dismissAction, "desktop.ui.action.cancel", !saving, actions, () -> resolve(true)),
                                    button(ID + ".confirm", ID + ".confirm", "gui.directory-suggestion.confirm",
                                            !saving && !form.getOrDefault(ID + ".path", "").isBlank(), actions, () -> resolve(false))), null, null);
                }, !saving, 560, 420);
    }

    private void resolve(boolean dismiss) {
        if (current == null || saving) return;
        var selected = current;
        String directory = form.getOrDefault(ID + ".path", "").trim();
        if (dismiss) {
            owner.closeDialog();
            current = null;
            form.remove(ID + ".path");
            owner.rebuild();
        } else {
            saving = true;
            failed = false;
            show();
        }
        owner.executeAsync(() -> {
            var value = selected.path("owner");
            DesktopUiHost.GuiResponse result;
            try {
                result = host.guiPostJson("control-center/directory", Map.of(
                    "owner", Map.of("pluginId", value.path("pluginId").asText(), "packageId", value.path("packageId").asText(),
                            "generation", value.path("generation").asLong(), "publication", value.path("publication").asLong()),
                    "suggestionId", selected.path("suggestion").path("suggestionId").asText(),
                    "directory", directory, "dismiss", dismiss), 5_000);
            } catch (RuntimeException failure) {
                result = DesktopUiHost.GuiResponse.unreachable();
            }
            if (dismiss) return;
            synchronized (owner) {
                saving = false;
                if (!owner.isDialogOpen(ID) || current != selected) return;
                if (result.successful()) {
                    owner.pluginDirectorySaved(value.path("pluginId").asText(),
                            selected.path("suggestion").path("configurationKey").asText(), directory);
                    owner.closeDialog();
                    current = null;
                    form.remove(ID + ".path");
                    owner.rebuild();
                } else {
                    failed = true;
                    show();
                }
            }
        });
    }

    private static String identity(DesktopUiHost.GuiValue value) {
        return value.path("owner").path("publication").asText() + ":" + value.path("suggestion").path("suggestionId").asText();
    }
}
