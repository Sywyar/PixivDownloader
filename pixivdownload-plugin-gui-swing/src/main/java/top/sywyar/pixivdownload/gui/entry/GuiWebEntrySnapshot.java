package top.sywyar.pixivdownload.gui.entry;

import java.util.List;

/**
 * Active plugin-contributed Swing web entry snapshot.
 */
public record GuiWebEntrySnapshot(
        List<GuiWebEntrySpec> statusActions,
        List<GuiWebEntrySpec> trayActions,
        List<GuiWebEntryContributionDiagnostic> diagnostics
) {
    public GuiWebEntrySnapshot {
        statusActions = statusActions == null ? List.of() : List.copyOf(statusActions);
        trayActions = trayActions == null ? List.of() : List.copyOf(trayActions);
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
    }

    public static GuiWebEntrySnapshot empty() {
        return new GuiWebEntrySnapshot(List.of(), List.of(), List.of());
    }
}
