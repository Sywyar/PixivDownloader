package top.sywyar.pixivdownload.plugin.catalog.trust;

import com.fasterxml.jackson.annotation.JsonAlias;

import java.util.List;

/** revocations-v1 签名文档。 */
public record PluginRevocationDocument(Integer schemaVersion, String repositoryId, Long sequence,
                                       String generatedTime, String nextUpdate, List<Entry> entries) {
    public record Entry(String scope, String pluginId, String version,
                        @JsonAlias("sha256") String packageSha256,
                        String keyId, String publisherId, String action, String reasonCode,
                        String effectiveTime) { }
}
