package top.sywyar.pixivdownload.plugin.catalog.download;

import top.sywyar.pixivdownload.plugin.catalog.manifest.PluginCatalogPackage;
import top.sywyar.pixivdownload.plugin.catalog.repository.PluginRepository;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** 一次获取操作等待精确摘要确认时持有的单包；复用后仍由安装器完整复验。 */
public final class PluginCatalogDownloadSession implements AutoCloseable {
    private Path artifact;
    private PluginRepository repository;
    private PluginCatalogPackage selected;
    private boolean closed;

    public synchronized Path take(PluginRepository repository, PluginCatalogPackage selected, String confirmedSha256) {
        if (artifact != null && this.repository.equals(repository) && this.selected.equals(selected)
                && selected.sha256().equalsIgnoreCase(confirmedSha256)) {
            Path result = artifact;
            artifact = null;
            this.repository = null;
            this.selected = null;
            return result;
        }
        clear();
        return null;
    }

    public synchronized void retain(Path artifact, PluginRepository repository, PluginCatalogPackage selected) {
        clear();
        this.artifact = artifact;
        if (closed) { clear(); return; }
        this.repository = repository;
        this.selected = selected;
    }

    @Override
    public synchronized void close() {
        closed = true;
        clear();
    }

    private void clear() {
        if (artifact != null) {
            try { Files.deleteIfExists(artifact); }
            catch (IOException failure) {
                org.slf4j.LoggerFactory.getLogger(getClass()).warn("Could not remove pending plugin download", failure);
            }
        }
        artifact = null;
        repository = null;
        selected = null;
    }
}
