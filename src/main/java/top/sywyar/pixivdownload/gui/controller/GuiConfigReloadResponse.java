package top.sywyar.pixivdownload.gui.controller;

import java.util.List;

public record GuiConfigReloadResponse(
        boolean success,
        List<String> appliedKeys,
        String message
) {
}
