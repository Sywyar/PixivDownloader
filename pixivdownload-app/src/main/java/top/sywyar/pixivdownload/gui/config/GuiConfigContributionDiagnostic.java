package top.sywyar.pixivdownload.gui.config;

/**
 * Diagnostic emitted while aggregating plugin GUI configuration field contributions.
 */
public record GuiConfigContributionDiagnostic(
        String pluginId,
        String key,
        String message
) {
}
