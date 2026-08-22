package top.sywyar.pixivdownload.gui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiCapability;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiExperienceProfile;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiIcon;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiPageContribution;
import top.sywyar.pixivdownload.plugin.api.gui.document.DesktopUiDocument;
import top.sywyar.pixivdownload.plugin.api.gui.document.DesktopUiNode;
import top.sywyar.pixivdownload.plugin.api.gui.document.DesktopUiNode.TextStyle;
import top.sywyar.pixivdownload.plugin.api.web.AccessPolicy;
import top.sywyar.pixivdownload.plugin.api.web.HttpMethod;
import top.sywyar.pixivdownload.plugin.api.web.WebRouteContribution;

import static top.sywyar.pixivdownload.gui.DesktopUiNodes.*;

/**
 * 聚合并校验插件贡献的桌面页面，同时守卫 renderer 能力边界。
 */
final class DesktopPluginPageController {
    private static final Logger LOG = LoggerFactory.getLogger(DesktopPluginPageController.class);
    private static final String APP_OWNER = "app";

    private final AppDesktopUiModel owner;
    private final DesktopUiHost host;
    private final AppDesktopUiModel.RendererContract rendererContract;
    private final Set<String> dismissedCompatibilityNotices = new LinkedHashSet<>();

    DesktopPluginPageController(
            AppDesktopUiModel owner,
            DesktopUiHost host,
            AppDesktopUiModel.RendererContract rendererContract
    ) {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.host = Objects.requireNonNull(host, "host");
        this.rendererContract = Objects.requireNonNull(
                rendererContract,
                "rendererContract"
        );
    }

    void requireCompatibleCore(
            List<DesktopUiDocument.Page> pages,
            List<DesktopUiDocument.Dialog> dialogs,
            long candidateRevision
    ) {
        List<UiCompatibilityDiagnostic> diagnostics = new ArrayList<>();
        for (DesktopUiDocument.Page page : pages) {
            diagnostics.addAll(compatibilityDiagnostics(
                    null,
                    "page",
                    page.id(),
                    page.content(),
                    candidateRevision,
                    "desktop.ui.compatibility.core-unavailable"
            ));
            page.floatingAction().ifPresent(action -> diagnostics.addAll(compatibilityDiagnostics(
                    null,
                    "page-floating-action",
                    page.id(),
                    action,
                    candidateRevision,
                    "desktop.ui.compatibility.core-unavailable"
            )));
        }
        for (DesktopUiDocument.Dialog dialog : dialogs) {
            diagnostics.addAll(compatibilityDiagnostics(
                    null,
                    "dialog",
                    dialog.id(),
                    dialog.content(),
                    candidateRevision,
                    "desktop.ui.compatibility.core-unavailable"
            ));
        }
        if (diagnostics.isEmpty()) return;
        diagnostics.forEach(diagnostic -> logCompatibility(diagnostic, true));
        throw new IllegalStateException(host.message(
                "desktop.ui.compatibility.core-unavailable",
                rendererContract.providerId()
        ));
    }

    void appendPages(
            List<DesktopUiDocument.Page> pages,
            List<DesktopUiDocument.Dialog> dialogs,
            Map<String, Runnable> nextActions,
            long candidateRevision
    ) {
        List<PluginPageCandidate> candidates = candidates();
        Set<String> duplicatePageIds = duplicatePageIds(candidates);
        Set<String> activeCompatibilityNoticeKeys = new LinkedHashSet<>();
        for (PluginPageCandidate candidate : candidates) {
            appendPage(
                    candidate,
                    duplicatePageIds,
                    pages,
                    dialogs,
                    nextActions,
                    candidateRevision,
                    activeCompatibilityNoticeKeys
            );
        }
        dismissedCompatibilityNotices.retainAll(activeCompatibilityNoticeKeys);
    }

    private List<PluginPageCandidate> candidates() {
        List<PluginPageCandidate> candidates = new ArrayList<>();
        for (DesktopUiPluginSource source : owner.currentSources().stream().sorted(Comparator.comparing(
                DesktopUiPluginSource::id)).toList()) {
            List<DesktopUiPageContribution> contributions = contributions(source);
            if (contributions == null) continue;
            List<WebRouteContribution> routes = routes(source);
            for (int index = 0; index < contributions.size(); index++) {
                DesktopUiPageContribution contribution = contributions.get(index);
                if (contribution != null) {
                    candidates.add(new PluginPageCandidate(
                            source,
                            index,
                            contribution,
                            routes
                    ));
                }
            }
        }
        candidates.sort(Comparator.comparingInt((PluginPageCandidate candidate) -> candidate.page().order()).thenComparing(
                candidate -> candidate.source().id()).thenComparing(candidate -> candidate.page().pageId()).thenComparingInt(
                PluginPageCandidate::declarationOrder));
        return candidates;
    }

    private List<DesktopUiPageContribution> contributions(DesktopUiPluginSource source) {
        try {
            return source.plugin().desktopPages();
        } catch (RuntimeException failure) {
            LOG.warn(
                    "Unable to read desktop page contributions (owner={}, generation={})",
                    source.id(),
                    source.generation(),
                    failure
            );
            return null;
        }
    }

    private List<WebRouteContribution> routes(DesktopUiPluginSource source) {
        try {
            List<WebRouteContribution> declared = source.plugin().routes();
            return declared == null ? List.of() : declared.stream().filter(Objects::nonNull).toList();
        } catch (RuntimeException failure) {
            LOG.warn(
                    "Unable to read desktop page routes (owner={}, generation={})",
                    source.id(),
                    source.generation(),
                    failure
            );
            return List.of();
        }
    }

    private static Set<String> duplicatePageIds(List<PluginPageCandidate> candidates) {
        Set<String> seenPageIds = new LinkedHashSet<>();
        Set<String> duplicatePageIds = new LinkedHashSet<>();
        for (PluginPageCandidate candidate : candidates) {
            if (!seenPageIds.add(candidate.page().pageId())) {
                duplicatePageIds.add(candidate.page().pageId());
            }
        }
        return duplicatePageIds;
    }

    private void appendPage(
            PluginPageCandidate candidate,
            Set<String> duplicatePageIds,
            List<DesktopUiDocument.Page> pages,
            List<DesktopUiDocument.Dialog> dialogs,
            Map<String, Runnable> nextActions,
            long candidateRevision,
            Set<String> activeCompatibilityNoticeKeys
    ) {
        DesktopUiPageContribution contribution = candidate.page();
        if (duplicatePageIds.contains(contribution.pageId()) || !validPluginPage(
                candidate.source().id(),
                contribution,
                candidate.routes()
        )) {
            LOG.warn(
                    "Ignored invalid desktop page contribution (owner={}, generation={}, pageId={})",
                    candidate.source().id(),
                    candidate.source().generation(),
                    contribution.pageId()
            );
            return;
        }

        List<UiCompatibilityDiagnostic> pageDiagnostics = compatibilityDiagnostics(
                candidate.source(),
                "page",
                contribution.pageId(),
                contribution.content(),
                candidateRevision,
                "desktop.ui.compatibility.page-unavailable"
        );
        pageDiagnostics.forEach(diagnostic -> logCompatibility(diagnostic, false));
        DesktopUiDocument.Page page = pageDiagnostics.isEmpty() ? new DesktopUiDocument.Page(
                contribution.pageId(),
                contribution.title(),
                DesktopUiIcon.PLUGIN,
                contribution.content()
        ) : incompatiblePluginPage(contribution);
        Set<String> acceptedActions = new LinkedHashSet<>();
        if (pageDiagnostics.isEmpty())
            collectPluginActions(contribution.content(), acceptedActions);
        List<DesktopUiDocument.Dialog> acceptedDialogs = new ArrayList<>();
        List<CompatibilityNotice> notices = compatibleDialogs(
                candidate,
                contribution,
                acceptedActions,
                acceptedDialogs,
                candidateRevision
        );
        Set<String> candidateActionIds = new LinkedHashSet<>(acceptedActions);
        notices.stream().filter(notice -> !dismissedCompatibilityNotices.contains(notice.key())).forEach(
                notice -> candidateActionIds.add(notice.actionId()));
        if (candidateActionIds.stream().anyMatch(nextActions::containsKey)) {
            LOG.warn(
                    "Ignored desktop page contribution with conflicting actions " + "(owner={}, generation={}, pageId={})",
                    candidate.source().id(),
                    candidate.source().generation(),
                    contribution.pageId()
            );
            return;
        }
        if (!validDocument(
                candidate,
                page,
                acceptedDialogs,
                notices,
                pages,
                dialogs
        )) return;
        pages.add(page);
        dialogs.addAll(acceptedDialogs);
        String pluginOwner = candidate.source().id();
        for (String actionId : acceptedActions) {
            String endpoint = contribution.actions().get(actionId);
            nextActions.put(
                    actionId,
                    () -> runPluginPageAction(pluginOwner, actionId, endpoint)
            );
        }
        for (CompatibilityNotice notice : notices) {
            activeCompatibilityNoticeKeys.add(notice.key());
            if (!dismissedCompatibilityNotices.contains(notice.key())) {
                dialogs.add(notice.dialog());
                nextActions.put(notice.actionId(), notice.dismiss());
            }
        }
    }

    private List<CompatibilityNotice> compatibleDialogs(
            PluginPageCandidate candidate,
            DesktopUiPageContribution contribution,
            Set<String> acceptedActions,
            List<DesktopUiDocument.Dialog> acceptedDialogs,
            long candidateRevision
    ) {
        List<CompatibilityNotice> notices = new ArrayList<>();
        for (int index = 0; index < contribution.dialogs().size(); index++) {
            DesktopUiDocument.Dialog dialog = contribution.dialogs().get(index);
            List<UiCompatibilityDiagnostic> diagnostics = compatibilityDiagnostics(
                    candidate.source(),
                    "dialog",
                    dialog.id(),
                    dialog.content(),
                    candidateRevision,
                    "desktop.ui.compatibility.dialog-unavailable"
            );
            if (diagnostics.isEmpty()) {
                acceptedDialogs.add(dialog);
                collectPluginActions(dialog.content(), acceptedActions);
                if (dialog.dismissible()) acceptedActions.add(dialog.dismissActionId());
            } else {
                diagnostics.forEach(diagnostic -> logCompatibility(diagnostic, false));
                notices.add(compatibilityNotice(candidate, index, dialog.id()));
            }
        }
        return notices;
    }

    private boolean validDocument(
            PluginPageCandidate candidate,
            DesktopUiDocument.Page page,
            List<DesktopUiDocument.Dialog> acceptedDialogs,
            List<CompatibilityNotice> notices,
            List<DesktopUiDocument.Page> pages,
            List<DesktopUiDocument.Dialog> dialogs
    ) {
        try {
            List<DesktopUiDocument.Page> candidatePages = new ArrayList<>(pages);
            candidatePages.add(page);
            List<DesktopUiDocument.Dialog> candidateDialogs = new ArrayList<>(dialogs);
            candidateDialogs.addAll(acceptedDialogs);
            notices.stream().filter(notice -> !dismissedCompatibilityNotices.contains(notice.key())).map(
                    CompatibilityNotice::dialog).forEach(candidateDialogs::add);
            new DesktopUiDocument(candidatePages, candidateDialogs);
            return true;
        } catch (RuntimeException invalid) {
            LOG.warn(
                    "Ignored conflicting desktop page contribution (owner={}, generation={}, pageId={})",
                    candidate.source().id(),
                    candidate.source().generation(),
                    candidate.page().pageId(),
                    invalid
            );
            return false;
        }
    }

    private DesktopUiDocument.Page incompatiblePluginPage(DesktopUiPageContribution contribution) {
        String base = contribution.pageId() + ".compatibility";
        DesktopUiNode content = new DesktopUiNode.Surface(
                base + ".surface",
                DesktopUiNode.SurfaceStyle.WARNING,
                DesktopUiNode.Insets.all(12),
                true,
                column(
                        base + ".content",
                        new DesktopUiNode.Text(
                                base + ".message",
                                key("desktop.ui.compatibility.page-unavailable"),
                                TextStyle.BODY,
                                true,
                                true
                        )
                )
        );
        return new DesktopUiDocument.Page(
                contribution.pageId(),
                contribution.title(),
                DesktopUiIcon.PLUGIN,
                content
        );
    }

    private CompatibilityNotice compatibilityNotice(
            PluginPageCandidate candidate,
            int dialogIndex,
            String rejectedDialogId
    ) {
        String base = "desktop.compatibility." + candidate.source().id() + "." + candidate.source().generation() + "." + candidate.declarationOrder() + "." + dialogIndex;
        String actionId = base + ".dismiss";
        String noticeKey = candidate.source().fingerprint() + ":" + rejectedDialogId;
        DesktopUiNode content = new DesktopUiNode.Surface(
                base + ".surface",
                DesktopUiNode.SurfaceStyle.WARNING,
                DesktopUiNode.Insets.all(12),
                true,
                column(
                        base + ".content",
                        new DesktopUiNode.Text(
                                base + ".message",
                                key("desktop.ui.compatibility.dialog-unavailable"),
                                TextStyle.BODY,
                                true,
                                true
                        )
                )
        );
        DesktopUiDocument.Dialog dialog = new DesktopUiDocument.Dialog(
                base,
                key("gui.dialog.warning.title"),
                DesktopUiDocument.DialogStyle.WARNING,
                content,
                actionId,
                true,
                440,
                0
        );
        return new CompatibilityNotice(
                noticeKey,
                dialog,
                actionId,
                () -> {
            dismissedCompatibilityNotices.add(noticeKey);
            owner.rebuild();
        }
        );
    }

    private static void collectPluginActions(
            DesktopUiNode node,
            Set<String> actions
    ) {
        if (node instanceof DesktopUiNode.Button button) actions.add(button.actionId());
        else if (node instanceof DesktopUiNode.Link link) actions.add(link.actionId());
        else if (node instanceof DesktopUiNode.Surface surface && surface.actionId() != null) {
            actions.add(surface.actionId());
        }
        for (DesktopUiNode child : node.childNodes()) collectPluginActions(
                child,
                actions
        );
    }

    private static boolean validPluginPage(
            String owner,
            DesktopUiPageContribution contribution,
            List<WebRouteContribution> routes
    ) {
        String pagePrefix = contribution.pageId() + ".";
        if (!contribution.pageId().startsWith(owner + ".")) return false;
        Set<String> referencedActions = new LinkedHashSet<>();
        if (!validPluginPageNode(contribution.content(), pagePrefix, referencedActions))
            return false;
        for (DesktopUiDocument.Dialog dialog : contribution.dialogs()) {
            if (dialog == null || !dialog.id().startsWith(pagePrefix) || !dialog.dismissActionId().startsWith(
                    pagePrefix) || !validPluginPageNode(
                            dialog.content(),
                            pagePrefix,
                            referencedActions
                    )) {
                return false;
            }
            if (dialog.dismissible()) referencedActions.add(dialog.dismissActionId());
        }
        if (!referencedActions.equals(contribution.actions().keySet())) return false;
        return contribution.actions().entrySet().stream().allMatch(entry -> entry.getKey().startsWith(
                pagePrefix) && validId(entry.getKey()) && validGuiEndpoint(entry.getValue()) && hasExactGuiPostRoute(
                        routes,
                        entry.getValue()
                ));
    }

    private static boolean validPluginPageNode(
            DesktopUiNode node,
            String pagePrefix,
            Set<String> referencedActions
    ) {
        if (!node.id().startsWith(pagePrefix)) return false;
        if (node instanceof DesktopUiNode.Image image && image.image().mediaType().startsWith(
                "image/svg")) {
            return false;
        }
        if (node instanceof DesktopUiNode.Button button) {
            if (!button.actionId().startsWith(pagePrefix)) return false;
            referencedActions.add(button.actionId());
        } else if (node instanceof DesktopUiNode.Link link) {
            if (!link.actionId().startsWith(pagePrefix)) return false;
            referencedActions.add(link.actionId());
        } else if (node instanceof DesktopUiNode.Surface surface && surface.actionId() != null) {
            if (!surface.actionId().startsWith(pagePrefix)) return false;
            referencedActions.add(surface.actionId());
        } else {
            String bindingId = pluginBindingId(node);
            if (bindingId != null && (!bindingId.startsWith(pagePrefix) || pluginInputEnabled(node))) {
                return false;
            }
        }
        for (DesktopUiNode child : node.childNodes()) {
            if (!validPluginPageNode(child, pagePrefix, referencedActions)) return false;
        }
        return true;
    }

    private static String pluginBindingId(DesktopUiNode node) {
        if (node instanceof DesktopUiNode.TextInput input) return input.bindingId();
        if (node instanceof DesktopUiNode.Toggle toggle) return toggle.bindingId();
        if (node instanceof DesktopUiNode.Choice choice) return choice.bindingId();
        if (node instanceof DesktopUiNode.NumberInput input) return input.bindingId();
        if (node instanceof DesktopUiNode.Table table) return table.bindingId();
        if (node instanceof DesktopUiNode.Tree tree) return tree.bindingId();
        return null;
    }

    private static boolean pluginInputEnabled(DesktopUiNode node) {
        if (node instanceof DesktopUiNode.TextInput input) return input.enabled();
        if (node instanceof DesktopUiNode.Toggle toggle) return toggle.enabled();
        if (node instanceof DesktopUiNode.Choice choice) return choice.enabled();
        if (node instanceof DesktopUiNode.NumberInput input) return input.enabled();
        if (node instanceof DesktopUiNode.Table table) return table.enabled();
        return node instanceof DesktopUiNode.Tree tree && tree.enabled();
    }

    private void runPluginPageAction(
            String pluginOwner,
            String actionId,
            String endpoint
    ) {
        owner.runBusy(() -> {
            try {
                DesktopUiHost.GuiResponse response = host.guiPostJson(
                        endpoint,
                        Map.of(),
                        30_000,
                        pluginOwner
                );
                if (!response.reachable()) {
                    LOG.warn(
                            "Desktop plugin page action could not reach the backend (owner={}, actionId={})",
                            pluginOwner,
                            actionId
                    );
                    owner.showDialog(
                            "plugin.action.failed",
                            "gui.dialog.error.title",
                            appToken("gui.config.action.notice.unreachable", actionId),
                            DesktopUiDocument.DialogStyle.ERROR
                    );
                } else if (response.status() < 200 || response.status() >= 300) {
                    LOG.warn(
                            "Desktop plugin page action failed (owner={}, actionId={}, status={})",
                            pluginOwner,
                            actionId,
                            response.status()
                    );
                    owner.showDialog(
                            "plugin.action.failed",
                            "gui.dialog.error.title",
                            appToken(
                                    "gui.config.action.notice.failed",
                                    actionId,
                                    "HTTP " + response.status()
                            ),
                            DesktopUiDocument.DialogStyle.ERROR
                    );
                }
            } catch (RuntimeException failure) {
                LOG.warn(
                        "Desktop plugin page action failed (owner={}, actionId={})",
                        pluginOwner,
                        actionId,
                        failure
                );
                owner.showDialog(
                        "plugin.action.failed",
                        "gui.dialog.error.title",
                        appToken("gui.config.action.notice.failed", actionId, safeMessage(failure)),
                        DesktopUiDocument.DialogStyle.ERROR
                );
            }
        });
    }

    private List<UiCompatibilityDiagnostic> compatibilityDiagnostics(
            DesktopUiPluginSource source,
            String locationType,
            String locationId,
            DesktopUiNode root,
            long candidateRevision,
            String remediationKey
    ) {
        List<UiCompatibilityDiagnostic> diagnostics = new ArrayList<>();
        String diagnosticOwner = source == null ? APP_OWNER : safeDiagnosticValue(source.id());
        String packageId = source == null ? APP_OWNER : safeDiagnosticValue(source.packageId());
        long generation = source == null ? 0L : source.generation();
        collectCompatibilityDiagnostics(
                root,
                new ArrayList<>(),
                diagnostics,
                diagnosticOwner,
                packageId,
                generation,
                packageId + "@" + generation,
                locationType,
                locationId,
                candidateRevision,
                remediationKey
        );
        return List.copyOf(diagnostics);
    }

    private boolean collectCompatibilityDiagnostics(
            DesktopUiNode node,
            List<String> path,
            List<UiCompatibilityDiagnostic> diagnostics,
            String diagnosticOwner,
            String packageId,
            long generation,
            String publication,
            String locationType,
            String locationId,
            long candidateRevision,
            String remediationKey
    ) {
        path.add(node.id());
        Set<DesktopUiNode.Kind> missingKinds = rendererContract.supportedKinds().contains(node.kind()) ? Set.of() : Set.of(
                node.kind());
        Set<DesktopUiCapability> missingCapabilities = new LinkedHashSet<>(DesktopUiNode.directRequiredCapabilities(
                node));
        missingCapabilities.removeAll(rendererContract.supportedCapabilities());
        if (!missingKinds.isEmpty() || !missingCapabilities.isEmpty()) {
            diagnostics.add(new UiCompatibilityDiagnostic(
                    rendererContract.providerId(),
                    rendererContract.experienceProfile(),
                    diagnosticOwner,
                    packageId,
                    generation,
                    publication,
                    locationType,
                    locationId,
                    node.id(),
                    String.join("/", path),
                    missingKinds,
                    Set.copyOf(missingCapabilities),
                    candidateRevision,
                    remediationKey
            ));
            path.remove(path.size() - 1);
            return true;
        }
        for (DesktopUiNode child : node.childNodes()) {
            if (collectCompatibilityDiagnostics(
                    child,
                    path,
                    diagnostics,
                    diagnosticOwner,
                    packageId,
                    generation,
                    publication,
                    locationType,
                    locationId,
                    candidateRevision,
                    remediationKey
            )) {
                path.remove(path.size() - 1);
                return true;
            }
        }
        path.remove(path.size() - 1);
        return false;
    }

    private static void logCompatibility(
            UiCompatibilityDiagnostic diagnostic,
            boolean fatal
    ) {
        String message = "Desktop UI compatibility diagnostic (provider={}, profile={}, owner={}, package={}, " + "generation={}, publication={}, locationType={}, locationId={}, nodeId={}, nodePath={}, " + "missingKinds={}, missingCapabilities={}, snapshotRevision={}, remediationKey={})";
        Object[] arguments = {diagnostic.providerId(), diagnostic.experienceProfile(), diagnostic.owner(), diagnostic.packageId(), diagnostic.generation(), diagnostic.publication(), diagnostic.locationType(), diagnostic.locationId(), diagnostic.nodeId(), diagnostic.nodePath(), diagnostic.missingKinds(), diagnostic.missingCapabilities(), diagnostic.snapshotRevision(), diagnostic.remediationKey()};
        if (fatal) LOG.error(message, arguments);
        else LOG.warn(message, arguments);
    }

    private static String safeDiagnosticValue(String value) {
        return validId(value) ? value : "invalid";
    }

    private static boolean validGuiEndpoint(String endpoint) {
        return endpoint != null && !endpoint.isBlank() && !endpoint.startsWith("/") && !endpoint.contains(
                "://") && !endpoint.contains("?") && !endpoint.contains("#") && !endpoint.contains(
                "\\") && java.util.Arrays.stream(endpoint.split("/")).allMatch(part -> validId(part) && !".".equals(
                part) && !"..".equals(part));
    }

    private static boolean hasExactGuiPostRoute(
            List<WebRouteContribution> routes,
            String endpoint
    ) {
        String path = "/api/gui/" + endpoint;
        return routes.stream().anyMatch(route -> path.equals(route.pathPattern()) && route.accessPolicy() == AccessPolicy.GUI && route.acceptsMethod(
                HttpMethod.POST));
    }

    private record PluginPageCandidate(
            DesktopUiPluginSource source,
            int declarationOrder,
            DesktopUiPageContribution page,
            List<WebRouteContribution> routes
    ) {
    }

    private record CompatibilityNotice(
            String key,
            DesktopUiDocument.Dialog dialog,
            String actionId,
            Runnable dismiss
    ) {
    }

    private record UiCompatibilityDiagnostic(
            String providerId,
            DesktopUiExperienceProfile experienceProfile,
            String owner,
            String packageId,
            long generation,
            String publication,
            String locationType,
            String locationId,
            String nodeId,
            String nodePath,
            Set<DesktopUiNode.Kind> missingKinds,
            Set<DesktopUiCapability> missingCapabilities,
            long snapshotRevision,
            String remediationKey
    ) {
    }
}
