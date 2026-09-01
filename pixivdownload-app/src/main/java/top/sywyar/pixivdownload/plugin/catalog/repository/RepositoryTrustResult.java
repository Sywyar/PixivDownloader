package top.sywyar.pixivdownload.plugin.catalog.repository;

/** 仓库描述符确认写入结果。 */
public record RepositoryTrustResult(
        String repositoryId,
        String descriptorSha256,
        boolean saved,
        boolean restartRequired,
        String trustSource) {
}
