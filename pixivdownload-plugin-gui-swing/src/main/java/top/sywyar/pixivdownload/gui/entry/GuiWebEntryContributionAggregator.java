package top.sywyar.pixivdownload.gui.entry;

import top.sywyar.pixivdownload.guiswing.SwingHost;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiPluginSnapshot;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiText;
import top.sywyar.pixivdownload.plugin.api.web.AccessPolicy;
import top.sywyar.pixivdownload.plugin.api.web.NavigationContribution;
import top.sywyar.pixivdownload.plugin.api.web.NavigationPlacements;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Aggregates active plugin navigation contributions for Swing-side web shortcuts. */
public final class GuiWebEntryContributionAggregator {
    private GuiWebEntryContributionAggregator() {}

    public static GuiWebEntrySnapshot fromRegisteredPlugins(
            List<DesktopUiPluginSnapshot> registeredPlugins
    ) {
        if (registeredPlugins == null || registeredPlugins.isEmpty()) {
            return GuiWebEntrySnapshot.empty();
        }
        List<GuiWebEntryContributionDiagnostic> diagnostics = new ArrayList<>();
        List<GuiWebEntrySpec> statusActions = new ArrayList<>();
        List<GuiWebEntrySpec> trayActions = new ArrayList<>();
        for (DesktopUiPluginSnapshot registered : registeredPlugins) {
            if (registered == null) {
                diagnostics.add(new GuiWebEntryContributionDiagnostic(
                        "unknown", null, "null plugin snapshot while aggregating GUI web entries"));
                continue;
            }
            for (NavigationContribution contribution : registered.navigation()) {
                GuiWebEntrySpec spec = toSpec(registered, contribution, diagnostics);
                if (spec == null) continue;
                if (contribution.placements().contains(NavigationPlacements.GUI_STATUS_ACTIONS)) {
                    statusActions.add(spec);
                }
                if (contribution.placements().contains(NavigationPlacements.GUI_TRAY_ACTIONS)) {
                    trayActions.add(spec);
                }
            }
        }
        Comparator<GuiWebEntrySpec> order = Comparator
                .comparingInt(GuiWebEntrySpec::priority)
                .thenComparing(GuiWebEntrySpec::id);
        return new GuiWebEntrySnapshot(
                statusActions.stream().sorted(order).toList(),
                trayActions.stream().sorted(order).toList(),
                diagnostics);
    }

    private static GuiWebEntrySpec toSpec(
            DesktopUiPluginSnapshot registered,
            NavigationContribution contribution,
            List<GuiWebEntryContributionDiagnostic> diagnostics
    ) {
        if (contribution == null || contribution.placements() == null
                || (!contribution.placements().contains(NavigationPlacements.GUI_STATUS_ACTIONS)
                && !contribution.placements().contains(NavigationPlacements.GUI_TRAY_ACTIONS))) {
            return null;
        }
        String id = normalize(contribution.id());
        if (id == null) {
            diagnostics.add(new GuiWebEntryContributionDiagnostic(
                    registered.id(), null, "GUI web entry id is blank"));
            return null;
        }
        AccessPolicy visibleTo = contribution.visibleTo();
        if (visibleTo == null || !visibleTo.supportsUiVisibility()) {
            diagnostics.add(new GuiWebEntryContributionDiagnostic(
                    registered.id(), id,
                    "GUI web entry visibleTo must support UI visibility: " + visibleTo));
            return null;
        }
        String namespace = normalize(contribution.labelNamespace());
        String key = normalize(contribution.labelI18nKey());
        String href = normalize(contribution.href());
        if (namespace == null || key == null) {
            diagnostics.add(new GuiWebEntryContributionDiagnostic(
                    registered.id(), id, "GUI web entry label namespace or key is blank"));
            return null;
        }
        if (href == null || !href.startsWith("/")) {
            diagnostics.add(new GuiWebEntryContributionDiagnostic(
                    registered.id(), id,
                    "GUI web entry href must start with '/': " + contribution.href()));
            return null;
        }
        String fallback;
        try {
            fallback = SwingHost.context().resolveText(
                    new DesktopUiText(namespace, key, key, List.of()));
        } catch (RuntimeException failure) {
            diagnostics.add(new GuiWebEntryContributionDiagnostic(
                    registered.id(), id, "GUI web entry text resolution failed: " + safeMessage(failure)));
            fallback = key;
        }
        return new GuiWebEntrySpec(
                registered.id(), id, fallback, namespace, key, href,
                contribution.icon(), contribution.priority());
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String safeMessage(Throwable error) {
        String message = error == null ? null : error.getMessage();
        return message == null || message.isBlank()
                ? error == null ? "unknown" : error.getClass().getSimpleName()
                : message;
    }
}
