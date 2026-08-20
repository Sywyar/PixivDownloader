package top.sywyar.pixivdownload.gui.entry;

/**
 * 聚合插件 GUI Web 入口贡献时产生的诊断。
 */
public record GuiWebEntryContributionDiagnostic(
        String pluginId,
        String key,
        String message
) {
}
