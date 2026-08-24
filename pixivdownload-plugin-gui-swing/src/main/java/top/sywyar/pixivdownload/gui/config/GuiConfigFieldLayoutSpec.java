package top.sywyar.pixivdownload.gui.config;

/**
 * Resolved field layout metadata ready for Swing rendering.
 */
public record GuiConfigFieldLayoutSpec(
        String fieldKey,
        String cardId,
        String cardLabel,
        int order,
        String ownerPluginId
) {

    public GuiConfigFieldLayoutSpec(String fieldKey, String cardId, String cardLabel, int order) {
        this(fieldKey, cardId, cardLabel, order, null);
    }
}
