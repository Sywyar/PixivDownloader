package top.sywyar.pixivdownload.gui.config;

import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigSectionNoticeStyle;

/**
 * Resolved section notice metadata ready for host-side Swing rendering.
 */
public record GuiConfigSectionNoticeSpec(
        String noticeId,
        String text,
        GuiConfigSectionNoticeStyle style,
        int order
) {

    public GuiConfigSectionNoticeSpec {
        noticeId = noticeId == null ? "" : noticeId;
        text = text == null ? "" : text;
        style = style == null ? GuiConfigSectionNoticeStyle.HINT : style;
    }
}
