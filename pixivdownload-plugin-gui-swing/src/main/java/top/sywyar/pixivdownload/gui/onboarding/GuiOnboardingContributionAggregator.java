package top.sywyar.pixivdownload.gui.onboarding;

import top.sywyar.pixivdownload.guiswing.SwingHost;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiPluginSnapshot;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiText;
import top.sywyar.pixivdownload.plugin.api.gui.GuiOnboardingStepContribution;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Aggregates active plugin onboarding steps for the Swing welcome panel. */
public final class GuiOnboardingContributionAggregator {
    private GuiOnboardingContributionAggregator() {}

    public static GuiOnboardingSnapshot fromRegisteredPlugins(
            List<DesktopUiPluginSnapshot> registeredPlugins
    ) {
        if (registeredPlugins == null || registeredPlugins.isEmpty()) {
            return GuiOnboardingSnapshot.empty();
        }
        List<GuiOnboardingContributionDiagnostic> diagnostics = new ArrayList<>();
        List<GuiOnboardingStepSpec> steps = new ArrayList<>();
        Set<String> seenStepIds = new LinkedHashSet<>();
        for (DesktopUiPluginSnapshot registered : registeredPlugins) {
            if (registered == null) {
                diagnostics.add(new GuiOnboardingContributionDiagnostic(
                        "unknown", null, "null plugin snapshot while aggregating GUI onboarding steps"));
                continue;
            }
            for (GuiOnboardingStepContribution contribution : registered.onboardingSteps()) {
                GuiOnboardingStepSpec spec = toSpec(registered, contribution, diagnostics);
                if (spec == null) continue;
                if (!seenStepIds.add(spec.stepId())) {
                    diagnostics.add(new GuiOnboardingContributionDiagnostic(
                            registered.id(), spec.stepId(), "duplicate GUI onboarding step id"));
                    steps.removeIf(step -> step.stepId().equals(spec.stepId()));
                    continue;
                }
                steps.add(spec);
            }
        }
        return new GuiOnboardingSnapshot(
                steps.stream().sorted(Comparator
                        .comparingInt(GuiOnboardingStepSpec::order)
                        .thenComparing(GuiOnboardingStepSpec::stepId)).toList(),
                diagnostics);
    }

    private static GuiOnboardingStepSpec toSpec(
            DesktopUiPluginSnapshot registered,
            GuiOnboardingStepContribution contribution,
            List<GuiOnboardingContributionDiagnostic> diagnostics
    ) {
        if (contribution == null) {
            diagnostics.add(new GuiOnboardingContributionDiagnostic(
                    registered.id(), null, "null GUI onboarding step contribution"));
            return null;
        }
        String stepId = normalize(contribution.stepId());
        String namespace = normalize(contribution.i18nNamespace());
        List<String> bulletKeys = contribution.bulletKeys().stream()
                .map(GuiOnboardingContributionAggregator::normalize).toList();
        if (stepId == null || namespace == null
                || normalize(contribution.titleKey()) == null
                || normalize(contribution.bodyKey()) == null
                || bulletKeys.stream().anyMatch(value -> value == null)
                || normalize(contribution.actionLabelKey()) == null
                || normalize(contribution.waitingKey()) == null
                || normalize(contribution.completionKey()) == null) {
            diagnostics.add(new GuiOnboardingContributionDiagnostic(
                    registered.id(), stepId, "GUI onboarding step contains blank id or key"));
            return null;
        }
        String href = normalize(contribution.actionHref());
        if (href == null || !href.startsWith("/")) {
            diagnostics.add(new GuiOnboardingContributionDiagnostic(
                    registered.id(), stepId,
                    "GUI onboarding step href must start with '/': " + contribution.actionHref()));
            return null;
        }
        return new GuiOnboardingStepSpec(
                registered.id(),
                stepId,
                contribution.titleKey().trim(),
                contribution.bodyKey().trim(),
                bulletKeys,
                contribution.actionLabelKey().trim(),
                href,
                contribution.waitingKey().trim(),
                contribution.completionKey().trim(),
                contribution.order(),
                namespace);
    }

    static String resolve(String namespace, String key) {
        return SwingHost.context().resolveText(new DesktopUiText(namespace, key, key, List.of()));
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
