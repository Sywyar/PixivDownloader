package top.sywyar.pixivdownload.gui.entry;

import top.sywyar.pixivdownload.guiswing.SwingHost;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiText;

import java.util.List;

/** Swing-side web shortcut contributed by a plugin. */
public record GuiWebEntrySpec(
        String pluginId,
        String id,
        String fallbackLabel,
        String labelNamespace,
        String labelKey,
        String href,
        String icon,
        int priority
) {
    public String label() {
        try {
            return SwingHost.context().resolveText(
                    new DesktopUiText(labelNamespace, labelKey, fallbackLabel, List.of()));
        } catch (RuntimeException ignored) {
            return fallbackLabel;
        }
    }
}
