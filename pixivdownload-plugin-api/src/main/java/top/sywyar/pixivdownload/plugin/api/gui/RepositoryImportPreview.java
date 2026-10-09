package top.sywyar.pixivdownload.plugin.api.gui;

import java.util.List;

/**
 * 用户确认前的只读仓库描述符投影；不包含可被客户端回传为保存权威的隐藏字段。
 *
 * @param descriptorUrl 描述符地址
 * @param descriptorHost 描述符主机
 * @param descriptorSha256 描述符原始字节的 SHA-256
 * @param repositoryId 仓库标识
 * @param displayName 仓库显示名称
 * @param publisherId 发布者标识
 * @param publisherDisplayName 发布者显示名称
 * @param publisherHomepageUrl 发布者主页地址
 * @param publisherHomepageHost 发布者主页主机
 * @param catalogProtocol 插件目录协议
 * @param catalogEndpoint 插件目录端点
 * @param catalogHost 插件目录主机
 * @param revocationsUrl 撤销信息地址
 * @param revocationsHost 撤销信息主机
 * @param updateProofUrl 仓库更新证明地址
 * @param updateProofHost 仓库更新证明主机
 * @param networkHosts 描述符声明的联网主机
 * @param networkProfile 仓库网络档位
 * @param effectiveProxyPolicy 实际采用的代理策略
 * @param redirectBoundary 重定向边界
 * @param trustedKeys 待核对的完整密钥事实
 * @param communityDirectoryStatus 社区目录认证状态
 * @param directorySequence 社区目录序号
 * @param directoryCertifiedFingerprints 社区目录认证的密钥指纹
 * @param existingRepository 是否已有该仓库
 * @param repositoryIdConflict 仓库标识是否冲突
 * @param keysChanged 信任密钥是否变化
 * @param networkExpanded 联网边界是否扩大
 * @param updateProofStatus 仓库更新证明状态
 * @param restartRequired 是否需要重启
 * @param executableCodeWarningKey 可执行代码风险提示的本地化键
 */
public record RepositoryImportPreview(
        String descriptorUrl,
        String descriptorHost,
        String descriptorSha256,
        String repositoryId,
        String displayName,
        String publisherId,
        String publisherDisplayName,
        String publisherHomepageUrl,
        String publisherHomepageHost,
        String catalogProtocol,
        String catalogEndpoint,
        String catalogHost,
        String revocationsUrl,
        String revocationsHost,
        String updateProofUrl,
        String updateProofHost,
        List<String> networkHosts,
        String networkProfile,
        String effectiveProxyPolicy,
        String redirectBoundary,
        List<RepositoryKeyPreview> trustedKeys,
        String communityDirectoryStatus,
        Long directorySequence,
        List<String> directoryCertifiedFingerprints,
        boolean existingRepository,
        boolean repositoryIdConflict,
        boolean keysChanged,
        boolean networkExpanded,
        String updateProofStatus,
        boolean restartRequired,
        String executableCodeWarningKey) {

    /**
     * 将集合归一化为不可变快照，避免预览事实随调用方修改而变化。
     *
     * @param descriptorUrl 描述符地址
     * @param descriptorHost 描述符主机
     * @param descriptorSha256 描述符原始字节的 SHA-256
     * @param repositoryId 仓库标识
     * @param displayName 仓库显示名称
     * @param publisherId 发布者标识
     * @param publisherDisplayName 发布者显示名称
     * @param publisherHomepageUrl 发布者主页地址
     * @param publisherHomepageHost 发布者主页主机
     * @param catalogProtocol 插件目录协议
     * @param catalogEndpoint 插件目录端点
     * @param catalogHost 插件目录主机
     * @param revocationsUrl 撤销信息地址
     * @param revocationsHost 撤销信息主机
     * @param updateProofUrl 仓库更新证明地址
     * @param updateProofHost 仓库更新证明主机
     * @param networkHosts 描述符声明的联网主机
     * @param networkProfile 仓库网络档位
     * @param effectiveProxyPolicy 实际采用的代理策略
     * @param redirectBoundary 重定向边界
     * @param trustedKeys 待核对的完整密钥事实
     * @param communityDirectoryStatus 社区目录认证状态
     * @param directorySequence 社区目录序号
     * @param directoryCertifiedFingerprints 社区目录认证的密钥指纹
     * @param existingRepository 是否已有该仓库
     * @param repositoryIdConflict 仓库标识是否冲突
     * @param keysChanged 信任密钥是否变化
     * @param networkExpanded 联网边界是否扩大
     * @param updateProofStatus 仓库更新证明状态
     * @param restartRequired 是否需要重启
     * @param executableCodeWarningKey 可执行代码风险提示的本地化键
     */
    public RepositoryImportPreview {
        networkHosts = List.copyOf(networkHosts == null ? List.of() : networkHosts);
        trustedKeys = List.copyOf(trustedKeys == null ? List.of() : trustedKeys);
        directoryCertifiedFingerprints = List.copyOf(
                directoryCertifiedFingerprints == null ? List.of() : directoryCertifiedFingerprints);
    }
}
