package top.sywyar.pixivdownload.gui.entry;

/**
 * Diagnostic emitted while aggregating plugin GUI web entry contributions.
 */
public record GuiWebEntryContributionDiagnostic(
        String pluginId,
        String key,
        String message
) {
}
