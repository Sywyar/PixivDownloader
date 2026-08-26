package top.sywyar.pixivdownload.guicompose.model;

import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiHost;

import top.sywyar.pixivdownload.guicompose.model.config.RepositoryConfigValidator;
import top.sywyar.pixivdownload.plugin.api.gui.RepositoryConfigEntry;
import top.sywyar.pixivdownload.plugin.api.gui.TrustedKeyConfigEntry;
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiDocument;
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode;
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode.ChoiceStyle;
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode.InputKind;
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode.SelectionMode;
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode.TextStyle;
import top.sywyar.pixivdownload.plugin.api.web.NavigationPlacements;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static top.sywyar.pixivdownload.guicompose.model.GuiActionResponseSafety.responseDetail;
import static top.sywyar.pixivdownload.guicompose.model.GuiActionResponseSafety.safeJsonPath;
import static top.sywyar.pixivdownload.guicompose.model.GuiActionResponseSafety.sanitizeActionText;
import static top.sywyar.pixivdownload.guicompose.model.DesktopUiNodes.*;

/**
 * 自定义插件仓库及其信任密钥编辑流程。
 */
final class DesktopRepositorySettingsController {
    private final ComposeDesktopUiModel owner;
    private final DesktopUiHost host;
    private final Map<String, String> formValues;
    private final DesktopTrustedKeyEditorView trustedKeyView;

    private volatile List<RepositoryConfigEntry> entries = List.of();
    private volatile List<RepositoryConfigEntry> savedEntries = List.of();
    private volatile boolean loaded;
    private volatile String loadFailure = "";
    private volatile String selectedRepositoryRow;
    private volatile int editingRepositoryIndex = -1;
    private volatile String unknownProxyPolicy = "";
    private volatile List<TrustedKeyConfigEntry> trustedKeys = List.of();
    private volatile String selectedTrustedKeyRow;
    private volatile int editingTrustedKeyIndex = -1;
    volatile String formErrorKey = "";

    DesktopRepositorySettingsController(
            ComposeDesktopUiModel owner,
            DesktopUiHost host,
            Map<String, String> formValues
    ) {
        this.owner = owner;
        this.host = host;
        this.formValues = formValues;
        this.trustedKeyView = new DesktopTrustedKeyEditorView(this);
    }

    DesktopUiNode section(Map<String, Runnable> nextActions) {
        List<DesktopUiNode> nodes = new ArrayList<>();
        nodes.add(text(
                "config.market.heading",
                "gui.config.market.repos.heading",
                TextStyle.HEADING
        ));
        nodes.add(new DesktopUiNode.Surface(
                "config.market.risk",
                DesktopUiNode.SurfaceStyle.WARNING,
                DesktopUiNode.Insets.all(10),
                true,
                text(
                        "config.market.risk.text",
                        "gui.config.market.repo.risk",
                        TextStyle.CAPTION
                )
        ));
        if (!loaded) {
            nodes.add(new DesktopUiNode.Text(
                    "config.market.read-failed",
                    appToken("gui.config.market.repo.read-failed", loadFailure),
                    TextStyle.ERROR,
                    true,
                    true
            ));
        }
        List<DesktopUiNode.TableRow> rows = new ArrayList<>();
        for (int index = 0; index < entries.size(); index++) {
            RepositoryConfigEntry entry = entries.get(index);
            rows.add(new DesktopUiNode.TableRow(
                    repositoryRowId(index),
                    List.of(
                            entry.id(),
                            host.message(entry.enabled() ? "gui.config.market.table.yes" : "gui.config.market.table.no"),
                            entry.manifestUrl(),
                            repositoryProxyLabel(entry.proxyPolicy())
                    )
            ));
        }
        int selected = repositoryRowIndex(selectedRepositoryRow);
        nodes.add(new DesktopUiNode.Table(
                "config.market.repositories",
                "config.market.repositories.selected",
                List.of(
                        new DesktopUiNode.TableColumn(
                                "id",
                                key("gui.config.market.table.col.id"),
                                120
                        ),
                        new DesktopUiNode.TableColumn(
                                "enabled",
                                key("gui.config.market.table.col.enabled"),
                                60
                        ),
                        new DesktopUiNode.TableColumn(
                                "url",
                                key("gui.config.market.table.col.url"),
                                320
                        ),
                        new DesktopUiNode.TableColumn(
                                "proxy",
                                key("gui.config.market.table.col.proxy"),
                                160
                        )
                ),
                rows,
                SelectionMode.SINGLE,
                selected >= 0 && selected < rows.size() ? List.of(repositoryRowId(selected)) : List.of(),
                loaded && !owner.busy()
        ));
        boolean selectedEntry = loaded && selected >= 0 && selected < entries.size();
        nodes.add(row(
                "config.market.repository.actions",
                button(
                        "config.market.repository.add",
                        "config.market.repository.add",
                        "gui.config.market.action.add",
                        loaded && !owner.busy(),
                        nextActions,
                        () -> openRepositoryEditor(-1)
                ),
                button(
                        "config.market.repository.edit",
                        "config.market.repository.edit",
                        "gui.config.market.action.edit",
                        selectedEntry && !owner.busy(),
                        nextActions,
                        () -> openRepositoryEditor(repositoryRowIndex(selectedRepositoryRow))
                ),
                button(
                        "config.market.repository.delete",
                        "config.market.repository.delete",
                        "gui.config.market.action.delete",
                        selectedEntry && !owner.busy(),
                        nextActions,
                        this::requestRepositoryDelete
                ),
                button(
                        "config.market.repository.up",
                        "config.market.repository.up",
                        "gui.config.market.action.up",
                        selectedEntry && selected > 0 && !owner.busy(),
                        nextActions,
                        () -> moveRepository(-1)
                ),
                button(
                        "config.market.repository.down",
                        "config.market.repository.down",
                        "gui.config.market.action.down",
                        selectedEntry && selected < entries.size() - 1 && !owner.busy(),
                        nextActions,
                        () -> moveRepository(1)
                )
        ));

        List<DesktopUiNode> webEntries = owner.navigation.webEntryButtons(
                NavigationPlacements.PLUGINS_SEGMENT,
                "config.market.web",
                nextActions
        );
        if (!webEntries.isEmpty()) {
            nodes.add(text(
                    "config.market.web.heading",
                    "gui.config.market.open.heading",
                    TextStyle.HEADING
            ));
            nodes.add(row("config.market.web.actions", webEntries));
            nodes.add(text(
                    "config.market.web.hint",
                    "gui.config.market.open.hint",
                    TextStyle.CAPTION
            ));
        }
        return column("config.market.section", nodes);
    }

    private String repositoryProxyLabel(String policyId) {
        for (DesktopUiHost.RepositoryProxyPolicy policy : DesktopUiHost.RepositoryProxyPolicy.values()) {
            if (policy.configId().equalsIgnoreCase(nullToEmpty(policyId))) {
                return host.message("gui.config.market.repo.proxy." + policy.configId());
            }
        }
        return nullToEmpty(policyId);
    }

    private void openRepositoryEditor(int index) {
        if (!loaded || index >= entries.size()) return;
        editingRepositoryIndex = index;
        formErrorKey = "";
        selectedTrustedKeyRow = null;
        RepositoryConfigEntry existing = index < 0 ? null : entries.get(index);
        putForm("config.market.repository.id", existing == null ? "" : existing.id());
        putForm(
                "config.market.repository.url",
                existing == null ? "" : existing.manifestUrl()
        );
        putForm(
                "config.market.repository.enabled",
                Boolean.toString(existing == null || existing.enabled())
        );
        String proxyPolicy = existing == null ? DesktopUiHost.RepositoryProxyPolicy.DEFAULT.configId() : existing.proxyPolicy();
        boolean knownPolicy = java.util.Arrays.stream(DesktopUiHost.RepositoryProxyPolicy.values()).anyMatch(
                policy -> policy.configId().equalsIgnoreCase(proxyPolicy));
        unknownProxyPolicy = knownPolicy ? "" : proxyPolicy;
        putForm(
                "config.market.repository.proxy",
                knownPolicy ? proxyPolicy : "unknown-policy"
        );
        putForm(
                "config.market.repository.allow-redirects",
                Boolean.toString(existing != null && existing.allowRedirects())
        );
        putForm(
                "config.market.repository.strict-https",
                Boolean.toString(existing == null || existing.strictHttps())
        );
        putForm(
                "config.market.repository.allow-non-public",
                Boolean.toString(existing != null && existing.allowNonPublicAddresses())
        );
        putForm(
                "config.market.repository.use-proxy",
                Boolean.toString(existing != null && existing.useProxy())
        );
        putForm(
                "config.market.repository.connect-timeout",
                overrideText(existing == null ? 0 : existing.connectTimeoutMs())
        );
        putForm(
                "config.market.repository.read-timeout",
                overrideText(existing == null ? 0 : existing.readTimeoutMs())
        );
        putForm(
                "config.market.repository.max-manifest",
                overrideText(existing == null ? 0 : existing.maxManifestBytes())
        );
        putForm(
                "config.market.repository.max-package",
                overrideText(existing == null ? 0 : existing.maxPackageBytes())
        );
        TrustedKeyConfigEntry official = officialRepositoryKey();
        putForm(
                "config.market.repository.inherit-official",
                Boolean.toString(existing != null && official != null && existing.trustedKeys().contains(
                        official))
        );
        trustedKeys = existing == null ? List.of() : existing.trustedKeys().stream().filter(trusted -> official == null || !trusted.equals(
                official)).toList();
        showRepositoryEditorDialog();
    }

    void showRepositoryEditorDialog() {
        String title = editingRepositoryIndex < 0 ? "gui.config.market.repo.dialog.add.title" : "gui.config.market.repo.dialog.edit.title";
        owner.showDialog(
                "config.market.repository.dialog",
                title,
                DesktopUiDocument.DialogStyle.INFO,
                this::repositoryEditorContent,
                720,
                760
        );
    }

    private DesktopUiNode repositoryEditorContent(
            Map<String, Runnable> nextActions,
            String dismissAction,
            Runnable dismiss
    ) {
        List<DesktopUiNode> fields = new ArrayList<>();
        List<DesktopUiNode.FormRow> details = new ArrayList<>();
        details.add(formRow(
                "config.market.repository.id.row",
                "gui.config.market.repo.field.id",
                null,
                input(
                        "config.market.repository.id.input",
                        "config.market.repository.id",
                        "gui.config.market.repo.field.id",
                        null,
                        InputKind.TEXT,
                        form("config.market.repository.id", ""),
                        true
                )
        ));
        details.add(formRow(
                "config.market.repository.url.row",
                "gui.config.market.repo.field.url",
                null,
                input(
                        "config.market.repository.url.input",
                        "config.market.repository.url",
                        "gui.config.market.repo.field.url",
                        null,
                        InputKind.TEXT,
                        form("config.market.repository.url", ""),
                        true
                )
        ));
        details.add(formRow(
                "config.market.repository.enabled.row",
                "gui.config.market.repo.field.enabled",
                null,
                toggle(
                        "config.market.repository.enabled.input",
                        "config.market.repository.enabled",
                        "gui.config.market.repo.field.enabled",
                        boolForm("config.market.repository.enabled", true),
                        true
                )
        ));

        List<DesktopUiNode.Option> policies = new ArrayList<>();
        if (!unknownProxyPolicy.isBlank()) {
            policies.add(new DesktopUiNode.Option(
                    "unknown-policy",
                    appToken("gui.config.market.repo.proxy.unknown-display", unknownProxyPolicy),
                    true
            ));
        }
        for (DesktopUiHost.RepositoryProxyPolicy policy : DesktopUiHost.RepositoryProxyPolicy.values()) {
            policies.add(new DesktopUiNode.Option(
                    policy.configId(),
                    key("gui.config.market.repo.proxy." + policy.configId()),
                    true
            ));
        }
        String selectedPolicy = form(
                "config.market.repository.proxy",
                DesktopUiHost.RepositoryProxyPolicy.DEFAULT.configId()
        );
        details.add(formRow(
                "config.market.repository.proxy.row",
                "gui.config.market.repo.field.proxy",
                null,
                choice(
                        "config.market.repository.proxy.input",
                        "config.market.repository.proxy",
                        "gui.config.market.repo.field.proxy",
                        null,
                        policies,
                        selectedPolicy,
                        true
                )
        ));
        String persistedPolicy = "unknown-policy".equals(selectedPolicy) ? unknownProxyPolicy : selectedPolicy;
        boolean custom = DesktopUiHost.RepositoryProxyPolicy.CUSTOM.configId().equalsIgnoreCase(
                persistedPolicy);
        if (custom) {
            details.add(formRow(
                    "config.market.repository.allow-redirects.row",
                    "gui.config.market.repo.custom.allow-redirects",
                    null,
                    toggle(
                            "config.market.repository.allow-redirects.input",
                            "config.market.repository.allow-redirects",
                            "gui.config.market.repo.custom.allow-redirects",
                            boolForm("config.market.repository.allow-redirects", false),
                            true
                    )
            ));
            details.add(formRow(
                    "config.market.repository.strict-https.row",
                    "gui.config.market.repo.custom.strict-https",
                    null,
                    toggle(
                            "config.market.repository.strict-https.input",
                            "config.market.repository.strict-https",
                            "gui.config.market.repo.custom.strict-https",
                            boolForm("config.market.repository.strict-https", true),
                            true
                    )
            ));
            details.add(formRow(
                    "config.market.repository.allow-non-public.row",
                    "gui.config.market.repo.custom.allow-non-public",
                    null,
                    toggle(
                            "config.market.repository.allow-non-public.input",
                            "config.market.repository.allow-non-public",
                            "gui.config.market.repo.custom.allow-non-public",
                            boolForm("config.market.repository.allow-non-public", false),
                            true
                    )
            ));
            details.add(formRow(
                    "config.market.repository.use-proxy.row",
                    "gui.config.market.repo.custom.use-proxy",
                    null,
                    toggle(
                            "config.market.repository.use-proxy.input",
                            "config.market.repository.use-proxy",
                            "gui.config.market.repo.custom.use-proxy",
                            boolForm("config.market.repository.use-proxy", false),
                            true
                    )
            ));
        }
        fields.add(new DesktopUiNode.Form(
                "config.market.repository.details",
                DesktopUiNode.FormStyle.RESPONSIVE,
                key("gui.punctuation.colon"),
                details
        ));
        if (custom || DesktopUiHost.RepositoryProxyPolicy.PROXY_TRUSTED.configId().equalsIgnoreCase(
                persistedPolicy)) {
            fields.add(new DesktopUiNode.Surface(
                    "config.market.repository.policy-risk",
                    DesktopUiNode.SurfaceStyle.WARNING,
                    DesktopUiNode.Insets.all(8),
                    true,
                    text(
                            "config.market.repository.policy-risk.text",
                            custom ? "gui.config.market.repo.custom.risk" : "gui.config.market.repo.proxy-trusted.risk",
                            TextStyle.CAPTION
                    )
            ));
        }

        fields.add(text(
                "config.market.repository.trust.heading",
                "gui.config.market.repo.trust.heading",
                TextStyle.HEADING
        ));
        fields.add(text(
                "config.market.repository.trust.hint",
                "gui.config.market.repo.trust.hint",
                TextStyle.CAPTION
        ));
        fields.add(toggle(
                "config.market.repository.inherit-official.input",
                "config.market.repository.inherit-official",
                "gui.config.market.repo.trust.inherit-official",
                boolForm("config.market.repository.inherit-official", false),
                officialRepositoryKey() != null
        ));
        List<DesktopUiNode.TableRow> trustedRows = new ArrayList<>();
        for (int index = 0; index < trustedKeys.size(); index++) {
            TrustedKeyConfigEntry trusted = trustedKeys.get(index);
            trustedRows.add(new DesktopUiNode.TableRow(
                    trustedKeyRowId(index),
                    List.of(
                            trusted.keyId(),
                            trusted.algorithm(),
                            trustedKeyStateLabel(trusted.state()),
                            trusted.publisher(),
                            trusted.trustLabel()
                    )
            ));
        }
        int selectedTrusted = trustedKeyRowIndex(selectedTrustedKeyRow);
        fields.add(new DesktopUiNode.Table(
                "config.market.repository.trusted",
                "config.market.repository.trusted.selected",
                List.of(
                        new DesktopUiNode.TableColumn(
                                "key-id",
                                key("gui.config.market.repo.trust.table.col.key-id"),
                                150
                        ),
                        new DesktopUiNode.TableColumn(
                                "algorithm",
                                key("gui.config.market.repo.trust.table.col.algorithm"),
                                90
                        ),
                        new DesktopUiNode.TableColumn(
                                "state",
                                key("gui.config.market.repo.trust.table.col.state"),
                                90
                        ),
                        new DesktopUiNode.TableColumn(
                                "publisher",
                                key("gui.config.market.repo.trust.table.col.publisher"),
                                140
                        ),
                        new DesktopUiNode.TableColumn(
                                "trust-label",
                                key("gui.config.market.repo.trust.table.col.trust-label"),
                                160
                        )
                ),
                trustedRows,
                SelectionMode.SINGLE,
                selectedTrusted >= 0 && selectedTrusted < trustedRows.size() ? List.of(
                        trustedKeyRowId(selectedTrusted)) : List.of(),
                true
        ));
        boolean hasTrustedSelection = selectedTrusted >= 0 && selectedTrusted < trustedKeys.size();
        fields.add(row(
                "config.market.repository.trusted.actions",
                button(
                        "config.market.repository.trusted.add",
                        "config.market.repository.trusted.add",
                        "gui.config.market.repo.trust.action.add",
                        true,
                        nextActions,
                        () -> openTrustedKeyEditor(-1)
                ),
                button(
                        "config.market.repository.trusted.edit",
                        "config.market.repository.trusted.edit",
                        "gui.config.market.repo.trust.action.edit",
                        hasTrustedSelection,
                        nextActions,
                        () -> openTrustedKeyEditor(trustedKeyRowIndex(selectedTrustedKeyRow))
                ),
                button(
                        "config.market.repository.trusted.delete",
                        "config.market.repository.trusted.delete",
                        "gui.config.market.repo.trust.action.delete",
                        hasTrustedSelection,
                        nextActions,
                        this::deleteTrustedKey
                )
        ));

        fields.add(new DesktopUiNode.Form(
                "config.market.repository.overrides",
                DesktopUiNode.FormStyle.RESPONSIVE,
                key("gui.punctuation.colon"),
                List.of(
                        formRow(
                                "config.market.repository.connect-timeout.row",
                                "gui.config.market.repo.field.connect-timeout",
                                "gui.config.market.repo.override.hint",
                                input(
                                        "config.market.repository.connect-timeout.input",
                                        "config.market.repository.connect-timeout",
                                        "gui.config.market.repo.field.connect-timeout",
                                        "gui.config.market.repo.override.hint",
                                        InputKind.NUMBER,
                                        form("config.market.repository.connect-timeout", ""),
                                        true
                                )
                        ),
                        formRow(
                                "config.market.repository.read-timeout.row",
                                "gui.config.market.repo.field.read-timeout",
                                "gui.config.market.repo.override.hint",
                                input(
                                        "config.market.repository.read-timeout.input",
                                        "config.market.repository.read-timeout",
                                        "gui.config.market.repo.field.read-timeout",
                                        "gui.config.market.repo.override.hint",
                                        InputKind.NUMBER,
                                        form("config.market.repository.read-timeout", ""),
                                        true
                                )
                        ),
                        formRow(
                                "config.market.repository.max-manifest.row",
                                "gui.config.market.repo.field.max-manifest",
                                "gui.config.market.repo.override.hint",
                                input(
                                        "config.market.repository.max-manifest.input",
                                        "config.market.repository.max-manifest",
                                        "gui.config.market.repo.field.max-manifest",
                                        "gui.config.market.repo.override.hint",
                                        InputKind.NUMBER,
                                        form("config.market.repository.max-manifest", ""),
                                        true
                                )
                        ),
                        formRow(
                                "config.market.repository.max-package.row",
                                "gui.config.market.repo.field.max-package",
                                "gui.config.market.repo.override.hint",
                                input(
                                        "config.market.repository.max-package.input",
                                        "config.market.repository.max-package",
                                        "gui.config.market.repo.field.max-package",
                                        "gui.config.market.repo.override.hint",
                                        InputKind.NUMBER,
                                        form("config.market.repository.max-package", ""),
                                        true
                                )
                        )
                )
        ));
        if (!formErrorKey.isBlank()) {
            fields.add(text(
                    "config.market.repository.error",
                    formErrorKey,
                    TextStyle.ERROR
            ));
        }
        return new DesktopUiNode.Dock(
                "config.market.repository.dialog.layout",
                12,
                null,
                scroll(
                        "config.market.repository.dialog.scroll",
                        column("config.market.repository.dialog.fields", fields)
                ),
                row(
                        "config.market.repository.dialog.actions",
                        button(
                                "config.market.repository.dialog.save",
                                "config.market.repository.dialog.save",
                                "gui.config.market.repo.dialog.ok",
                                true,
                                nextActions,
                                this::saveRepositoryEditor
                        ),
                        button(
                                "config.market.repository.dialog.cancel",
                                dismissAction,
                                "gui.config.market.repo.dialog.cancel",
                                true,
                                nextActions,
                                dismiss
                        )
                ),
                null,
                null
        );
    }

    private void saveRepositoryEditor() {
        String id = form("config.market.repository.id", "").trim();
        String url = form("config.market.repository.url", "").trim();
        String selectedPolicy = form(
                "config.market.repository.proxy",
                DesktopUiHost.RepositoryProxyPolicy.DEFAULT.configId()
        );
        String policy = "unknown-policy".equals(selectedPolicy) ? unknownProxyPolicy : selectedPolicy;
        List<RepositoryConfigEntry> others = new ArrayList<>(entries);
        if (editingRepositoryIndex >= 0 && editingRepositoryIndex < others.size()) {
            others.remove(editingRepositoryIndex);
        }
        String error = RepositoryConfigValidator.validateId(
                id,
                others,
                host.reservedPluginRepositoryIds()
        );
        boolean strictHttps = !DesktopUiHost.RepositoryProxyPolicy.CUSTOM.configId().equalsIgnoreCase(
                policy) || boolForm("config.market.repository.strict-https", true);
        if (error == null) error = RepositoryConfigValidator.validateManifestUrl(
                url,
                strictHttps
        );
        if (error == null) error = RepositoryConfigValidator.validateProxyPolicy(policy);
        if (error == null) error = RepositoryConfigValidator.validateTimeoutOverride(form(
                "config.market.repository.connect-timeout",
                ""
        ));
        if (error == null) error = RepositoryConfigValidator.validateTimeoutOverride(form(
                "config.market.repository.read-timeout",
                ""
        ));
        if (error == null) error = RepositoryConfigValidator.validateSizeOverride(form(
                "config.market.repository.max-manifest",
                ""
        ));
        if (error == null) error = RepositoryConfigValidator.validateSizeOverride(form(
                "config.market.repository.max-package",
                ""
        ));
        List<TrustedKeyConfigEntry> trustedKeys = trustedKeysForSave();
        if (error == null && hasDuplicateTrustedKeyIds(trustedKeys)) {
            error = "gui.config.market.repo.trust.error.key-id-duplicate";
        }
        if (error != null) {
            formErrorKey = error;
            owner.rebuild();
            return;
        }
        RepositoryConfigEntry existing = editingRepositoryIndex < 0 ? null : entries.get(
                editingRepositoryIndex);
        RepositoryConfigEntry entry = new RepositoryConfigEntry(
                id,
                existing == null ? "" : existing.displayNameKey(),
                url,
                boolForm("config.market.repository.enabled", true),
                policy,
                boolForm("config.market.repository.allow-redirects", false),
                boolForm("config.market.repository.strict-https", true),
                boolForm("config.market.repository.allow-non-public", false),
                boolForm("config.market.repository.use-proxy", false),
                RepositoryConfigValidator.parseOverride(form(
                        "config.market.repository.connect-timeout",
                        ""
                )),
                RepositoryConfigValidator.parseOverride(form(
                        "config.market.repository.read-timeout",
                        ""
                )),
                RepositoryConfigValidator.parseOverride(form(
                        "config.market.repository.max-manifest",
                        ""
                )),
                RepositoryConfigValidator.parseOverride(form(
                        "config.market.repository.max-package",
                        ""
                )),
                trustedKeys,
                existing == null ? new LinkedHashMap<>() : existing.extraFields()
        );
        List<RepositoryConfigEntry> updated = new ArrayList<>(entries);
        int selected;
        if (editingRepositoryIndex < 0) {
            updated.add(entry);
            selected = updated.size() - 1;
        } else {
            updated.set(editingRepositoryIndex, entry);
            selected = editingRepositoryIndex;
        }
        entries = List.copyOf(updated);
        selectedRepositoryRow = repositoryRowId(selected);
        owner.closeDialog();
        owner.rebuild();
    }

    private void requestRepositoryDelete() {
        int selected = repositoryRowIndex(selectedRepositoryRow);
        if (selected < 0 || selected >= entries.size()) return;
        RepositoryConfigEntry entry = entries.get(selected);
        owner.showDialog(
                "config.market.repository.delete",
                "gui.config.market.repo.delete.title",
                DesktopUiDocument.DialogStyle.QUESTION,
                (nextActions, dismissAction, dismiss) -> column(
                        "config.market.repository.delete.content",
                        new DesktopUiNode.Text(
                                "config.market.repository.delete.message",
                                appToken("gui.config.market.repo.delete.confirm", entry.id()),
                                TextStyle.BODY,
                                true,
                                true
                        ),
                        row(
                                "config.market.repository.delete.actions",
                                button(
                                        "config.market.repository.delete.confirm",
                                        "config.market.repository.delete.confirm",
                                        "gui.config.market.action.delete",
                                        true,
                                        nextActions,
                                        () -> {
                                            List<RepositoryConfigEntry> updated = new ArrayList<>(
                                                    entries);
                                            updated.remove(selected);
                                            entries = List.copyOf(updated);
                                            selectedRepositoryRow = null;
                                            owner.closeDialog();
                                            owner.rebuild();
                                        }
                                ),
                                button(
                                        "config.market.repository.delete.cancel",
                                        dismissAction,
                                        "desktop.ui.action.cancel",
                                        true,
                                        nextActions,
                                        dismiss
                                )
                        )
                ),
                460,
                0
        );
    }

    private void moveRepository(int delta) {
        int selected = repositoryRowIndex(selectedRepositoryRow);
        int target = selected + delta;
        if (selected < 0 || target < 0 || target >= entries.size()) return;
        List<RepositoryConfigEntry> updated = new ArrayList<>(entries);
        RepositoryConfigEntry moved = updated.remove(selected);
        updated.add(target, moved);
        entries = List.copyOf(updated);
        selectedRepositoryRow = repositoryRowId(target);
        owner.rebuild();
    }

    private void openTrustedKeyEditor(int index) {
        if (index >= trustedKeys.size()) return;
        editingTrustedKeyIndex = index;
        formErrorKey = "";
        TrustedKeyConfigEntry existing = index < 0 ? null : trustedKeys.get(index);
        putForm(
                "config.market.trusted-key.id",
                existing == null ? "" : existing.keyId()
        );
        putForm(
                "config.market.trusted-key.algorithm",
                existing == null ? "Ed25519" : existing.algorithm()
        );
        putForm(
                "config.market.trusted-key.public-key",
                existing == null ? "" : existing.publicKey()
        );
        putForm(
                "config.market.trusted-key.state",
                existing == null ? "ACTIVE" : existing.state()
        );
        putForm(
                "config.market.trusted-key.publisher",
                existing == null ? "" : existing.publisher()
        );
        putForm(
                "config.market.trusted-key.trust-label",
                existing == null ? "" : existing.trustLabel()
        );
        String title = index < 0 ? "gui.config.market.repo.trust.dialog.add.title" : "gui.config.market.repo.trust.dialog.edit.title";
        owner.showDialog(
                "config.market.trusted-key.dialog",
                title,
                DesktopUiDocument.DialogStyle.INFO,
                trustedKeyView::content,
                false,
                600,
                0
        );
    }

    void saveTrustedKeyEditor() {
        List<String> otherIds = new ArrayList<>();
        for (int index = 0; index < trustedKeys.size(); index++) {
            if (index != editingTrustedKeyIndex) otherIds.add(trustedKeys.get(index).keyId());
        }
        if (boolForm("config.market.repository.inherit-official", false)) {
            TrustedKeyConfigEntry official = officialRepositoryKey();
            if (official != null) otherIds.add(official.keyId());
        }
        String error = RepositoryConfigValidator.validateTrustedKey(
                form("config.market.trusted-key.id", ""),
                form("config.market.trusted-key.algorithm", ""),
                form("config.market.trusted-key.public-key", ""),
                form("config.market.trusted-key.state", ""),
                otherIds
        );
        if (error != null) {
            formErrorKey = error;
            owner.rebuild();
            return;
        }
        TrustedKeyConfigEntry existing = editingTrustedKeyIndex < 0 ? null : trustedKeys.get(
                editingTrustedKeyIndex);
        TrustedKeyConfigEntry trusted = new TrustedKeyConfigEntry(
                form("config.market.trusted-key.id", ""),
                form("config.market.trusted-key.algorithm", ""),
                form("config.market.trusted-key.public-key", ""),
                form("config.market.trusted-key.state", ""),
                form("config.market.trusted-key.publisher", ""),
                form("config.market.trusted-key.trust-label", ""),
                existing == null ? new LinkedHashMap<>() : existing.extraFields()
        );
        List<TrustedKeyConfigEntry> updated = new ArrayList<>(trustedKeys);
        int selected;
        if (editingTrustedKeyIndex < 0) {
            updated.add(trusted);
            selected = updated.size() - 1;
        } else {
            updated.set(editingTrustedKeyIndex, trusted);
            selected = editingTrustedKeyIndex;
        }
        trustedKeys = List.copyOf(updated);
        selectedTrustedKeyRow = trustedKeyRowId(selected);
        formErrorKey = "";
        showRepositoryEditorDialog();
    }

    private void deleteTrustedKey() {
        int selected = trustedKeyRowIndex(selectedTrustedKeyRow);
        if (selected < 0 || selected >= trustedKeys.size()) return;
        List<TrustedKeyConfigEntry> updated = new ArrayList<>(trustedKeys);
        updated.remove(selected);
        trustedKeys = List.copyOf(updated);
        selectedTrustedKeyRow = null;
        owner.rebuild();
    }

    private List<TrustedKeyConfigEntry> trustedKeysForSave() {
        List<TrustedKeyConfigEntry> trusted = new ArrayList<>();
        TrustedKeyConfigEntry official = officialRepositoryKey();
        if (official != null && boolForm(
                "config.market.repository.inherit-official",
                false
        )) {
            trusted.add(official);
        }
        for (TrustedKeyConfigEntry key : trustedKeys) {
            if (official == null || !key.equals(official)) trusted.add(key);
        }
        return List.copyOf(trusted);
    }

    private TrustedKeyConfigEntry officialRepositoryKey() {
        try {
            return host.officialPluginRepositoryKey();
        } catch (RuntimeException unsupported) {
            return null;
        }
    }

    private static boolean hasDuplicateTrustedKeyIds(List<TrustedKeyConfigEntry> keys) {
        Set<String> seen = new LinkedHashSet<>();
        for (TrustedKeyConfigEntry key : keys) {
            String id = key.keyId().trim().toLowerCase(Locale.ROOT);
            if (!id.isBlank() && !seen.add(id)) return true;
        }
        return false;
    }

    private String trustedKeyStateLabel(String state) {
        String normalized = nullToEmpty(state).trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "active", "retired", "revoked" ->
                    host.message("gui.config.market.repo.trust.state." + normalized);
            default -> state;
        };
    }

    private void putForm(String key, String value) {
        formValues.put(key, value == null ? "" : value);
    }

    private static String overrideText(long value) {
        return value > 0 ? Long.toString(value) : "";
    }

    private static String repositoryRowId(int index) {
        return "repository." + index;
    }

    private static int repositoryRowIndex(String id) {
        return indexedRow(id, "repository.");
    }

    private static String trustedKeyRowId(int index) {
        return "trusted-key." + index;
    }

    private static int trustedKeyRowIndex(String id) {
        return indexedRow(id, "trusted-key.");
    }

    private static int indexedRow(String id, String prefix) {
        if (id == null || !id.startsWith(prefix)) return -1;
        return parseInt(id.substring(prefix.length()), -1);
    }

    void acceptForm(String binding, String value) {
        if ("config.market.repositories.selected".equals(binding)) {
            selectedRepositoryRow = value.isBlank() ? null : value;
        } else if ("config.market.repository.trusted.selected".equals(binding)) {
            selectedTrustedKeyRow = value.isBlank() ? null : value;
        }
    }

    void load() {
        try {
            List<RepositoryConfigEntry> loadedEntries = List.copyOf(host.readPluginRepositories(host.applicationConfig()));
            entries = loadedEntries;
            savedEntries = loadedEntries;
            loaded = true;
            loadFailure = "";
            if (repositoryRowIndex(selectedRepositoryRow) >= loadedEntries.size()) {
                selectedRepositoryRow = null;
            }
        } catch (Exception failure) {
            entries = List.of();
            savedEntries = List.of();
            loaded = false;
            loadFailure = safeMessage(failure);
            selectedRepositoryRow = null;
        }
    }

    boolean changed() {
        return !entries.equals(savedEntries);
    }

    boolean loaded() {
        return loaded;
    }

    String loadFailure() {
        return loadFailure;
    }

    List<RepositoryConfigEntry> entries() {
        return entries;
    }

    void markSaved() {
        savedEntries = List.copyOf(entries);
    }

    static DesktopUiNode.FormRow formRow(
            String id,
            String labelKey,
            String helpKey,
            DesktopUiNode content
    ) {
        return new DesktopUiNode.FormRow(
                id,
                key(labelKey),
                helpKey == null ? null : key(helpKey),
                content,
                null
        );
    }

    static DesktopUiNode.Choice choice(
            String id,
            String binding,
            String label,
            String help,
            List<DesktopUiNode.Option> options,
            String selected,
            boolean enabled
    ) {
        List<String> selectedIds = options.stream().anyMatch(option -> option.id().equals(selected)) ? List.of(
                selected) : List.of();
        return new DesktopUiNode.Choice(
                id,
                binding,
                key(label),
                help == null ? null : key(help),
                ChoiceStyle.COMBO_BOX,
                SelectionMode.SINGLE,
                options,
                selectedIds,
                enabled
        );
    }

    String form(String key, String fallback) {
        return formValues.getOrDefault(key, fallback);
    }

    private boolean boolForm(String key, boolean fallback) {
        return Boolean.parseBoolean(form(key, Boolean.toString(fallback)));
    }
}
