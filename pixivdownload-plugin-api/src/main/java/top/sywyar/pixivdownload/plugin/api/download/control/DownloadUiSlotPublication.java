package top.sywyar.pixivdownload.plugin.api.download.control;

import top.sywyar.pixivdownload.plugin.api.web.WebUiSlotContribution;

import java.util.Objects;

/** 宿主盖章的当前下载页 UI 槽位。 */
public record DownloadUiSlotPublication(
        DownloadExtensionIdentity owner,
        WebUiSlotContribution slot
) {

    public DownloadUiSlotPublication {
        Objects.requireNonNull(owner, "download extension owner");
        Objects.requireNonNull(slot, "download UI slot contribution");
    }
}
