package top.sywyar.pixivdownload.guicompose.model;

import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiHost;
import top.sywyar.pixivdownload.plugin.api.gui.RepositoryConfigEntry;
import top.sywyar.pixivdownload.plugin.api.gui.RepositoryImportPreview;
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Predicate;

import static top.sywyar.pixivdownload.guicompose.model.DesktopUiNodes.*;

/** 描述符预览与确认状态由仓库编辑器持有。 */
final class DesktopRepositoryImportView {
    private static final String ID = "config.market.repository.import";
    private static final String KEY = "gui.config.market.repo.import.";
    private final ComposeDesktopUiModel owner;
    private final DesktopUiHost host;
    private final Predicate<String> availableId;
    private final Consumer<RepositoryConfigEntry> accept;
    private volatile long revision;
    private volatile String url = "";
    private volatile String error = "";
    private volatile boolean confirmed;
    private volatile RepositoryImportPreview preview;

    DesktopRepositoryImportView(ComposeDesktopUiModel owner, DesktopUiHost host,
                                Predicate<String> availableId, Consumer<RepositoryConfigEntry> accept) {
        this.owner = owner;
        this.host = host;
        this.availableId = availableId;
        this.accept = accept;
    }

    void reset(RepositoryConfigEntry entry) {
        revision++;
        url = entry == null ? "" : String.valueOf(entry.extraFields().getOrDefault("descriptor-url", ""));
        preview = null;
        confirmed = false;
        error = "";
    }

    void acceptForm(String binding, String value) {
        if ((ID + ".url").equals(binding)) {
            revision++;
            url = value;
            preview = null;
            confirmed = false;
            error = "";
        } else if ((ID + ".confirm").equals(binding)) {
            confirmed = Boolean.parseBoolean(value);
        }
    }

    DesktopUiNode content(Map<String, Runnable> actions, String dismissAction, Runnable dismiss) {
        List<DesktopUiNode> fields = new ArrayList<>();
        fields.add(text(ID + ".description", KEY + "description", DesktopUiNode.TextStyle.CAPTION));
        fields.add(new DesktopUiNode.Form(ID + ".entry", DesktopUiNode.FormStyle.RESPONSIVE,
                key("gui.punctuation.colon"), List.of(DesktopRepositorySettingsController.formRow(ID + ".url.row", KEY + "url", null,
                input(ID + ".url", ID + ".url", KEY + "url", null,
                        DesktopUiNode.InputKind.TEXT, url, !owner.busy())))));
        var previewButton = button(ID + ".preview", ID + ".preview", KEY + "preview",
                !owner.busy() && !url.isBlank(), actions, this::preview);
        RepositoryImportPreview value = preview;
        boolean conflict = value != null && (value.repositoryIdConflict() || !availableId.test(value.repositoryId()));
        if (value != null) {
            fields.add(previewButton);
            List<DesktopUiNode.FormRow> facts = new ArrayList<>();
            fact(facts, "repository", value.displayName() + " (" + value.repositoryId() + ")");
            fact(facts, "publisher", value.publisherDisplayName() + " (" + value.publisherId() + ")");
            fact(facts, "homepage", value.publisherHomepageUrl());
            fact(facts, "descriptor", value.descriptorUrl());
            fact(facts, "digest", value.descriptorSha256());
            fact(facts, "catalog", value.catalogProtocol() + " · " + value.catalogEndpoint());
            fact(facts, "network", String.join(", ", value.networkHosts()));
            fact(facts, "policy", value.effectiveProxyPolicy() + " · " + value.redirectBoundary());
            fact(facts, "revocations", value.revocationsUrl());
            fact(facts, "update-proof", value.updateProofUrl());
            fact(facts, "proof-status", host.message(KEY + "status." + value.updateProofStatus()));
            fact(facts, "directory", host.message(KEY + "status." + value.communityDirectoryStatus()));
            fields.add(new DesktopUiNode.Form(ID + ".facts", DesktopUiNode.FormStyle.RESPONSIVE,
                    key("gui.punctuation.colon"), facts));
            fields.add(text(ID + ".keys.heading", KEY + "field.key", DesktopUiNode.TextStyle.HEADING));
            for (int index = 0; index < value.trustedKeys().size(); index++) {
                var trusted = value.trustedKeys().get(index);
                String keyId = ID + ".key." + index;
                fields.add(raw(keyId + ".label", trusted.keyId() + " · " + trusted.algorithm()
                        + " · " + host.message("gui.config.market.repo.trust.state." + trusted.state().toLowerCase(java.util.Locale.ROOT))
                        + " · " + trusted.publisher() + " · " + trusted.trustLabel(), DesktopUiNode.TextStyle.BODY));
                fields.add(raw(keyId + ".fingerprint", trusted.fingerprint(), DesktopUiNode.TextStyle.CODE));
            }
            fields.add(text(ID + ".warning", KEY + "executable-warning", DesktopUiNode.TextStyle.CAPTION));
            if (conflict) fields.add(text(ID + ".conflict", KEY + "conflict", DesktopUiNode.TextStyle.ERROR));
            fields.add(toggle(ID + ".confirm", ID + ".confirm", KEY + "confirm", confirmed,
                    !conflict && !owner.busy()));
        }
        if (owner.busy()) fields.add(text(ID + ".loading", KEY + "loading", DesktopUiNode.TextStyle.CAPTION));
        if (!error.isBlank()) fields.add(raw(ID + ".error", error, DesktopUiNode.TextStyle.ERROR));
        return new DesktopUiNode.Dock(ID + ".layout", 12, null,
                scroll(ID + ".scroll", column(ID + ".fields", fields)),
                row(ID + ".actions",
                        value == null ? previewButton : button(ID + ".accept", ID + ".accept", KEY + "accept",
                                value != null && confirmed && !conflict && !owner.busy(), actions, this::confirm),
                        button(ID + ".cancel", dismissAction, "gui.config.market.repo.dialog.cancel",
                                true, actions, dismiss)), null, null);
    }

    private void fact(List<DesktopUiNode.FormRow> rows, String name, String value) {
        rows.add(DesktopRepositorySettingsController.formRow(ID + ".fact." + name,
                KEY + "field." + name, null, raw(ID + ".value." + name, value, DesktopUiNode.TextStyle.CODE)));
    }

    private boolean current(long expected) {
        return revision == expected && owner.isDialogOpen("config.market.repository.dialog");
    }

    private void preview() {
        long expected = ++revision;
        String descriptorUrl = url.trim();
        preview = null;
        confirmed = false;
        error = "";
        owner.runBusy(() -> {
            try {
                var result = host.previewPluginRepository(descriptorUrl);
                if (current(expected)) preview = result;
            } catch (Exception failure) {
                if (current(expected)) error = safeMessage(failure);
            }
        });
    }

    private void confirm() {
        RepositoryImportPreview value = preview;
        if (value == null || !confirmed || value.repositoryIdConflict() || !availableId.test(value.repositoryId())) return;
        long expected = revision;
        error = "";
        owner.runBusy(() -> {
            try {
                var entry = host.preparePluginRepository(value.descriptorUrl(), value.descriptorSha256(), true);
                if (current(expected)) accept.accept(entry);
            } catch (Exception failure) {
                if (current(expected)) {
                    error = safeMessage(failure);
                    preview = null;
                    confirmed = false;
                }
            }
        });
    }
}
