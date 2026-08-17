package top.sywyar.pixivdownload.plugin.api.download.control;

import top.sywyar.pixivdownload.plugin.api.download.type.DownloadTypeDescriptor;

import java.util.Objects;

/** 宿主盖章的当前下载类型 descriptor。 */
public record DownloadTypePublication(
        DownloadExtensionIdentity owner,
        DownloadTypeDescriptor descriptor
) {

    /**
     * 创建 {@code DownloadTypePublication} 实例。
     *
     * @param owner 所有者
     * @param descriptor 描述符
     */
    public DownloadTypePublication {
        Objects.requireNonNull(owner, "download extension owner");
        Objects.requireNonNull(descriptor, "download type descriptor");
    }
}
