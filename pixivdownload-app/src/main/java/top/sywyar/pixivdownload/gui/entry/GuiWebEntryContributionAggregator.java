package top.sywyar.pixivdownload.gui.entry;

import top.sywyar.pixivdownload.gui.i18n.PluginContributionText;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.web.I18nContribution;
import top.sywyar.pixivdownload.plugin.api.web.NavigationContribution;
import top.sywyar.pixivdownload.plugin.api.web.NavigationPlacements;
import top.sywyar.pixivdownload.plugin.registry.PluginRegistry;
import top.sywyar.pixivdownload.plugin.registry.PluginSource;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Aggregates active plugin navigation contributions for Swing-side web shortcuts.
 */
public final class GuiWebEntryContributionAggregator {

    private GuiWebEntryContributionAggregator() {
    }

    public static GuiWebEntrySnapshot from(PluginRegistry pluginRegistry) {
        if (pluginRegistry == null) {
            return GuiWebEntrySnapshot.empty();
        }
        return fromRegisteredPlugins(pluginRegistry.registeredPlugins());
    }

    public static GuiWebEntrySnapshot fromRegisteredPlugins(List<PluginRegistry.RegisteredPlugin> registeredPlugins) {
        if (registeredPlugins == null || registeredPlugins.isEmpty()) {
            return GuiWebEntrySnapshot.empty();
        }

        List<GuiWebEntryContributionDiagnostic> diagnostics = new ArrayList<>();
        List<GuiWebEntrySpec> statusActions = new ArrayList<>();
        List<GuiWebEntrySpec> trayActions = new ArrayList<>();
        for (PluginRegistry.RegisteredPlugin registered : registeredPlugins) {
            if (registered == null) {
                diagnostics.add(new GuiWebEntryContributionDiagnostic("unknown", null,
                        "null registered plugin while aggregating GUI web entries"));
                continue;
            }
            PixivFeaturePlugin plugin = registered.plugin();
            List<NavigationContribution> navigation;
            try {
                navigation = plugin.navigation();
            } catch (RuntimeException e) {
                diagnostics.add(new GuiWebEntryContributionDiagnostic(registered.id(), null,
                        "GUI web entry navigation threw: " + safeMessage(e)));
                continue;
            }
            if (navigation == null) {
                diagnostics.add(new GuiWebEntryContributionDiagnostic(registered.id(), null,
                        "GUI web entry navigation list is null"));
                continue;
            }
            List<I18nContribution> i18n = pluginI18n(registered, diagnostics);
            for (NavigationContribution contribution : navigation) {
                GuiWebEntrySpec spec = toSpec(registered, contribution, i18n, diagnostics);
                if (spec == null) {
                    continue;
                }
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

    private static GuiWebEntrySpec toSpec(PluginRegistry.RegisteredPlugin registered,
                                          NavigationContribution contribution,
                                          List<I18nContribution> i18n,
                                          List<GuiWebEntryContributionDiagnostic> diagnostics) {
        if (contribution == null
                || contribution.placements() == null
                || (!contribution.placements().contains(NavigationPlacements.GUI_STATUS_ACTIONS)
                && !contribution.placements().contains(NavigationPlacements.GUI_TRAY_ACTIONS))) {
            return null;
        }
        String id = normalize(contribution.id());
        if (id == null) {
            diagnostics.add(new GuiWebEntryContributionDiagnostic(registered.id(), null,
                    "GUI web entry id is blank"));
            return null;
        }
        String labelNamespace = normalize(contribution.labelNamespace());
        if (labelNamespace == null) {
            diagnostics.add(new GuiWebEntryContributionDiagnostic(registered.id(), id,
                    "GUI web entry label namespace is blank"));
            return null;
        }
        String labelKey = normalize(contribution.labelI18nKey());
        if (labelKey == null) {
            diagnostics.add(new GuiWebEntryContributionDiagnostic(registered.id(), id,
                    "GUI web entry label key is blank"));
            return null;
        }
        String href = normalize(contribution.href());
        if (href == null || !href.startsWith("/")) {
            diagnostics.add(new GuiWebEntryContributionDiagnostic(registered.id(), id,
                    "GUI web entry href must start with '/': " + contribution.href()));
            return null;
        }
        String label = PluginContributionText.resolve(i18n, registered.classLoader(), labelNamespace, labelKey);
        List<I18nContribution> refreshableI18n = registered.source() == PluginSource.BUILT_IN ? i18n : List.of();
        ClassLoader refreshableClassLoader = registered.source() == PluginSource.BUILT_IN
                ? registered.classLoader()
                : null;
        return new GuiWebEntrySpec(registered.id(), id, label, labelNamespace, labelKey, href,
                contribution.icon(), contribution.priority(), refreshableI18n, refreshableClassLoader);
    }

    private static List<I18nContribution> pluginI18n(PluginRegistry.RegisteredPlugin registered,
                                                     List<GuiWebEntryContributionDiagnostic> diagnostics) {
        try {
            List<I18nContribution> i18n = registered.plugin().i18n();
            if (i18n == null) {
                diagnostics.add(new GuiWebEntryContributionDiagnostic(registered.id(), null,
                        "GUI web entry plugin i18n contribution list is null"));
                return List.of();
            }
            return List.copyOf(i18n);
        } catch (RuntimeException e) {
            diagnostics.add(new GuiWebEntryContributionDiagnostic(registered.id(), null,
                    "GUI web entry plugin i18n threw: " + safeMessage(e)));
            return List.of();
        }
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String safeMessage(Throwable error) {
        if (error == null) {
            return "unknown";
        }
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }
}
