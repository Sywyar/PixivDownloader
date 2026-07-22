package top.sywyar.pixivdownload.plugin;

import top.sywyar.pixivdownload.plugin.api.download.type.DownloadTypeDescriptor;

import java.util.List;

/** 测试夹具：构造完整的下载类型 descriptor。 */
final class TestDownloadTypeDescriptors {

    private TestDownloadTypeDescriptors() {
    }

    static DownloadTypeDescriptor create(String type,
                                         String displayNamespace,
                                         String displayI18nKey,
                                         int order,
                                         String moduleUrl) {
        return create(type, displayNamespace, displayI18nKey, order, moduleUrl,
                DownloadTypeDescriptor.CURRENT_CONTRACT_VERSION);
    }

    static DownloadTypeDescriptor create(String type,
                                         String displayNamespace,
                                         String displayI18nKey,
                                         int order,
                                         String moduleUrl,
                                         int contractVersion) {
        return new DownloadTypeDescriptor(
                contractVersion,
                type,
                displayNamespace,
                displayI18nKey,
                order,
                "download",
                "neutral",
                moduleUrl,
                List.of(),
                false,
                List.of(),
                List.of(),
                displayNamespace);
    }
}
