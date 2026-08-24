package top.sywyar.pixivdownload.gui.config;

/**
 * Localized GUI configuration group metadata used by the Swing configuration panel.
 */
public record ConfigGroupSpec(
        String id,
        String label,
        int order,
        boolean visibleInTabs
) {
}
