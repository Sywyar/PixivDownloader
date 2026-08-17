package top.sywyar.pixivdownload.plugin.api.download.control;

import java.util.List;

/** 当前下载扩展的不可变宿主投影。 */
public record DownloadExtensionSnapshot(
        String epoch,
        long revision,
        List<DownloadTypePublication> downloadTypes,
        List<DownloadUiSlotPublication> uiSlots
) {

    /**
     * 创建 {@code DownloadExtensionSnapshot} 实例。
     *
     * @param epoch 纪元时间
     * @param revision 修订版本
     * @param downloadTypes 下载类型集合
     * @param uiSlots 界面槽位列表
     */
    public DownloadExtensionSnapshot {
        if (epoch == null || epoch.isBlank()) {
            throw new IllegalArgumentException("download extension epoch must not be blank");
        }
        if (revision < 0L) {
            throw new IllegalArgumentException("download extension revision must not be negative");
        }
        downloadTypes = downloadTypes == null ? List.of() : List.copyOf(downloadTypes);
        uiSlots = uiSlots == null ? List.of() : List.copyOf(uiSlots);
    }
}
