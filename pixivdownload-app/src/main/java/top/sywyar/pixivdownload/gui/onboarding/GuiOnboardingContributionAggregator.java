package top.sywyar.pixivdownload.gui.onboarding;

import top.sywyar.pixivdownload.gui.i18n.PluginContributionText;
import top.sywyar.pixivdownload.plugin.api.gui.GuiOnboardingStepContribution;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.web.I18nContribution;
import top.sywyar.pixivdownload.plugin.registry.PluginRegistry;
import top.sywyar.pixivdownload.plugin.registry.PluginSource;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Aggregates active plugin onboarding steps for the Swing welcome panel.
 */
public final class GuiOnboardingContributionAggregator {

    private GuiOnboardingContributionAggregator() {
    }

    public static GuiOnboardingSnapshot from(PluginRegistry pluginRegistry) {
        if (pluginRegistry == null) {
            return GuiOnboardingSnapshot.empty();
        }
        return fromRegisteredPlugins(pluginRegistry.registeredPlugins());
    }

    public static GuiOnboardingSnapshot fromRegisteredPlugins(List<PluginRegistry.RegisteredPlugin> registeredPlugins) {
        if (registeredPlugins == null || registeredPlugins.isEmpty()) {
            return GuiOnboardingSnapshot.empty();
        }

        List<GuiOnboardingContributionDiagnostic> diagnostics = new ArrayList<>();
        List<GuiOnboardingStepSpec> steps = new ArrayList<>();
        Set<String> seenStepIds = new LinkedHashSet<>();
        for (PluginRegistry.RegisteredPlugin registered : registeredPlugins) {
            if (registered == null) {
                diagnostics.add(new GuiOnboardingContributionDiagnostic("unknown", null,
                        "null registered plugin while aggregating GUI onboarding steps"));
                continue;
            }
            PixivFeaturePlugin plugin = registered.plugin();
            List<GuiOnboardingStepContribution> contributions;
            try {
                contributions = plugin.guiOnboardingSteps();
            } catch (RuntimeException e) {
                diagnostics.add(new GuiOnboardingContributionDiagnostic(registered.id(), null,
                        "GUI onboarding step contribution threw: " + safeMessage(e)));
                continue;
            }
            if (contributions == null) {
                diagnostics.add(new GuiOnboardingContributionDiagnostic(registered.id(), null,
                        "GUI onboarding step contribution list is null"));
                continue;
            }
            List<I18nContribution> i18n = pluginI18n(registered, diagnostics);
            for (GuiOnboardingStepContribution contribution : contributions) {
                GuiOnboardingStepSpec spec = toSpec(registered, contribution, i18n, diagnostics);
                if (spec == null) {
                    continue;
                }
                if (!seenStepIds.add(spec.stepId())) {
                    diagnostics.add(new GuiOnboardingContributionDiagnostic(registered.id(), spec.stepId(),
                            "duplicate GUI onboarding step id"));
                    steps.removeIf(step -> step.stepId().equals(spec.stepId()));
                    continue;
                }
                steps.add(spec);
            }
        }
        return new GuiOnboardingSnapshot(
                steps.stream()
                        .sorted(Comparator
                                .comparingInt(GuiOnboardingStepSpec::order)
                                .thenComparing(GuiOnboardingStepSpec::stepId))
                        .toList(),
                diagnostics);
    }

    private static GuiOnboardingStepSpec toSpec(PluginRegistry.RegisteredPlugin registered,
                                                GuiOnboardingStepContribution contribution,
                                                List<I18nContribution> i18n,
                                                List<GuiOnboardingContributionDiagnostic> diagnostics) {
        if (contribution == null) {
            diagnostics.add(new GuiOnboardingContributionDiagnostic(registered.id(), null,
                    "null GUI onboarding step contribution"));
            return null;
        }
        if (!registered.id().equals(contribution.pluginId())) {
            diagnostics.add(new GuiOnboardingContributionDiagnostic(registered.id(), contribution.stepId(),
                    "GUI onboarding step pluginId mismatch: " + contribution.pluginId()));
            return null;
        }
        String stepId = normalize(contribution.stepId());
        if (stepId == null) {
            diagnostics.add(new GuiOnboardingContributionDiagnostic(registered.id(), null,
                    "GUI onboarding step id is blank"));
            return null;
        }
        String namespace = normalize(contribution.i18nNamespace());
        if (namespace == null) {
            diagnostics.add(new GuiOnboardingContributionDiagnostic(registered.id(), stepId,
                    "GUI onboarding step namespace is blank"));
            return null;
        }
        List<String> bullets = contribution.bulletKeys().stream()
                .map(GuiOnboardingContributionAggregator::normalize)
                .toList();
        if (normalize(contribution.titleKey()) == null
                || normalize(contribution.bodyKey()) == null
                || bullets.stream().anyMatch(value -> value == null)
                || normalize(contribution.actionLabelKey()) == null
                || normalize(contribution.waitingKey()) == null
                || normalize(contribution.completionKey()) == null) {
            diagnostics.add(new GuiOnboardingContributionDiagnostic(registered.id(), stepId,
                    "GUI onboarding step contains blank i18n or completion key"));
            return null;
        }
        String href = normalize(contribution.actionHref());
        if (href == null || !href.startsWith("/")) {
            diagnostics.add(new GuiOnboardingContributionDiagnostic(registered.id(), stepId,
                    "GUI onboarding step href must start with '/': " + contribution.actionHref()));
            return null;
        }
        List<I18nContribution> refreshableI18n = registered.source() == PluginSource.BUILT_IN ? i18n : List.of();
        ClassLoader refreshableClassLoader = registered.source() == PluginSource.BUILT_IN
                ? registered.classLoader()
                : null;
        return new GuiOnboardingStepSpec(
                registered.id(),
                stepId,
                text(i18n, registered.classLoader(), namespace, contribution.titleKey().trim()),
                contribution.titleKey().trim(),
                text(i18n, registered.classLoader(), namespace, contribution.bodyKey().trim()),
                contribution.bodyKey().trim(),
                bullets.stream()
                        .map(key -> text(i18n, registered.classLoader(), namespace, key))
                        .toList(),
                bullets,
                text(i18n, registered.classLoader(), namespace, contribution.actionLabelKey().trim()),
                contribution.actionLabelKey().trim(),
                href,
                text(i18n, registered.classLoader(), namespace, contribution.waitingKey().trim()),
                contribution.waitingKey().trim(),
                contribution.completionKey().trim(),
                contribution.order(),
                namespace,
                refreshableI18n,
                refreshableClassLoader);
    }

    private static String text(List<I18nContribution> i18n, ClassLoader classLoader, String namespace, String key) {
        return PluginContributionText.resolve(i18n, classLoader, namespace, key);
    }

    private static List<I18nContribution> pluginI18n(PluginRegistry.RegisteredPlugin registered,
                                                     List<GuiOnboardingContributionDiagnostic> diagnostics) {
        try {
            List<I18nContribution> i18n = registered.plugin().i18n();
            if (i18n == null) {
                diagnostics.add(new GuiOnboardingContributionDiagnostic(registered.id(), null,
                        "GUI onboarding plugin i18n contribution list is null"));
                return List.of();
            }
            return List.copyOf(i18n);
        } catch (RuntimeException e) {
            diagnostics.add(new GuiOnboardingContributionDiagnostic(registered.id(), null,
                    "GUI onboarding plugin i18n threw: " + safeMessage(e)));
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
