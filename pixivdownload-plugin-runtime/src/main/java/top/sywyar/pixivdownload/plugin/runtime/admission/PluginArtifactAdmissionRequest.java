package top.sywyar.pixivdownload.plugin.runtime.admission;

import top.sywyar.pixivdownload.plugin.runtime.install.model.CommunityPackageEvidence;

/** 已冻结、已验签 artifact 的加载准入事实。 */
public record PluginArtifactAdmissionRequest(String repositoryId, String pluginId, String version,
                                             String sha256, String keyId, String publisher,
                                             CommunityPackageEvidence communityEvidence) {
    public PluginArtifactAdmissionRequest(String repositoryId, String pluginId, String version,
                                          String sha256, String keyId, String publisher) {
        this(repositoryId, pluginId, version, sha256, keyId, publisher, null);
    }
}
