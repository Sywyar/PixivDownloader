package top.sywyar.pixivdownload.plugin;

import top.sywyar.pixivdownload.plugin.runtime.install.PluginPackageIntegrity;
import top.sywyar.pixivdownload.plugin.runtime.install.PluginPackageOrigin;
import top.sywyar.pixivdownload.plugin.runtime.install.provenance.PluginProvenanceStore;
import top.sywyar.pixivdownload.plugin.signature.VerificationResult;
import top.sywyar.pixivdownload.plugin.signature.VerificationStatus;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

public final class PluginTestProvenance {

    private PluginTestProvenance() {
    }

    public static void writeLocalUpload(Path pluginsDir, Path artifact, String pluginId, String version)
            throws IOException {
        VerificationResult result = new VerificationResult(VerificationStatus.UNSIGNED_ALLOWED,
                pluginId, version, null, null, null, null, Instant.now(), Files.size(artifact),
                PluginPackageIntegrity.sha256Hex(artifact), "UNSIGNED_ALLOWED");
        new PluginProvenanceStore(pluginsDir).write(artifact, PluginPackageOrigin.localUpload(), result);
    }
}
