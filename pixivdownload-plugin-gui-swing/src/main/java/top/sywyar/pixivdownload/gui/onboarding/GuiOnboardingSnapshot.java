package top.sywyar.pixivdownload.gui.onboarding;

import java.util.List;
import java.util.Optional;

/**
 * Active plugin-contributed Swing onboarding step snapshot.
 */
public record GuiOnboardingSnapshot(
        List<GuiOnboardingStepSpec> steps,
        List<GuiOnboardingContributionDiagnostic> diagnostics
) {
    public GuiOnboardingSnapshot {
        steps = steps == null ? List.of() : List.copyOf(steps);
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
    }

    public static GuiOnboardingSnapshot empty() {
        return new GuiOnboardingSnapshot(List.of(), List.of());
    }

    public Optional<GuiOnboardingStepSpec> firstStep() {
        return steps.stream().findFirst();
    }
}
