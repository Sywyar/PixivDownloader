package top.sywyar.pixivdownload.plugin.catalog.repository;

import top.sywyar.pixivdownload.plugin.signature.TrustedPluginKey;

import java.util.List;

/** 已严格校验且可用于预览/落盘的 repository.json 原始字节事实。 */
record ParsedRepositoryDescriptor(
        String descriptorUrl,
        String descriptorSha256,
        RepositoryDescriptor descriptor,
        List<TrustedPluginKey> trustedKeys,
        List<RepositoryKeyPreview> keyPreviews,
        List<String> networkHosts,
        String effectiveProxyPolicy,
        String redirectBoundary) {

    ParsedRepositoryDescriptor {
        trustedKeys = List.copyOf(trustedKeys);
        keyPreviews = List.copyOf(keyPreviews);
        networkHosts = List.copyOf(networkHosts);
    }
}
