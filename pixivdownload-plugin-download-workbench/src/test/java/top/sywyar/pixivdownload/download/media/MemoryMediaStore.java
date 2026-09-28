package top.sywyar.pixivdownload.download.media;

import top.sywyar.pixivdownload.core.asset.ArtworkMediaManifest;
import top.sywyar.pixivdownload.core.asset.ArtworkMediaStore;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** 下载测试使用的进程内存储；真实 SQL 与重启耐久性在宿主测试中验证。 */
public final class MemoryMediaStore implements ArtworkMediaStore {
    private final Map<String, ArtworkMediaManifest> pages = new ConcurrentHashMap<>();
    public Optional<ArtworkMediaManifest> find(long id, int page) { return Optional.ofNullable(pages.get(id + ":" + page)); }
    public void save(long id, int page, ArtworkMediaManifest media) { pages.put(id + ":" + page, media); }
}
