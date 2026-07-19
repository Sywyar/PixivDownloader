package top.sywyar.pixivdownload.plugin;

import top.sywyar.pixivdownload.plugin.api.web.DownloadGalleryCapabilities;
import top.sywyar.pixivdownload.plugin.api.web.DownloadQueueCapabilities;
import top.sywyar.pixivdownload.plugin.api.web.DownloadScheduleCapabilities;
import top.sywyar.pixivdownload.plugin.api.web.DownloadTypeDescriptor;
import top.sywyar.pixivdownload.plugin.api.web.QueueTypeContribution;

import java.util.List;

/** 测试夹具：构造显式声明完整 descriptor 的队列类型。 */
final class TestQueueTypeContributions {

    private TestQueueTypeContributions() {
    }

    static QueueTypeContribution create(String pluginId,
                                        String type,
                                        String labelNamespace,
                                        String labelI18nKey,
                                        int order,
                                        String moduleUrl) {
        return create(pluginId, type, labelNamespace, labelI18nKey, order, moduleUrl,
                DownloadTypeDescriptor.CURRENT_CONTRACT_VERSION);
    }

    static QueueTypeContribution create(String pluginId,
                                        String type,
                                        String labelNamespace,
                                        String labelI18nKey,
                                        int order,
                                        String moduleUrl,
                                        int contractVersion) {
        return new QueueTypeContribution(
                pluginId,
                type,
                labelNamespace,
                labelI18nKey,
                order,
                moduleUrl,
                new DownloadTypeDescriptor(
                        contractVersion,
                        pluginId,
                        type,
                        labelNamespace,
                        labelI18nKey,
                        order,
                        null,
                        null,
                        moduleUrl,
                        List.of(),
                        DownloadQueueCapabilities.clearOnly(),
                        DownloadScheduleCapabilities.notSaveable(),
                        List.of(),
                        List.of(),
                        List.of(),
                        labelNamespace,
                        DownloadGalleryCapabilities.none()));
    }
}
