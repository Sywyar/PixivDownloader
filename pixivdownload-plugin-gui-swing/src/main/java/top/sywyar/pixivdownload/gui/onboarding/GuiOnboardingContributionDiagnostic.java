package top.sywyar.pixivdownload.gui.onboarding;

/**
 * Diagnostic emitted while aggregating plugin GUI onboarding step contributions.
 */
public record GuiOnboardingContributionDiagnostic(
        String pluginId,
        String key,
        String message
) {
}
