package top.sywyar.pixivdownload.gui.entry;

import top.sywyar.pixivdownload.gui.i18n.PluginContributionText;
import top.sywyar.pixivdownload.plugin.api.web.I18nContribution;

import java.util.List;

/**
 * Swing-side web shortcut contributed by a plugin.
 */
public record GuiWebEntrySpec(
        String pluginId,
        String id,
        String label,
        String labelNamespace,
        String labelKey,
        String href,
        String icon,
        int priority,
        List<I18nContribution> i18n,
        ClassLoader classLoader
) {
    public GuiWebEntrySpec {
        i18n = i18n == null ? List.of() : List.copyOf(i18n);
    }

    public String label() {
        if (labelNamespace == null || labelKey == null || i18n.isEmpty() || classLoader == null) {
            return label;
        }
        return PluginContributionText.resolve(i18n, classLoader, labelNamespace, labelKey);
    }
}
