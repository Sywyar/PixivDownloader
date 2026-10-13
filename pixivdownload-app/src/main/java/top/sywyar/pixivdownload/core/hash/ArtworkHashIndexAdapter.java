package top.sywyar.pixivdownload.core.hash;

import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.core.db.ArtworkRecord;
import top.sywyar.pixivdownload.core.db.PixivDatabase;

import java.util.List;
import java.util.OptionalInt;

/**
 * 把应用内数据库与哈希写入实现适配为面向外置插件的核心语义端口。
 */
@Component
public class ArtworkHashIndexAdapter implements ArtworkHashIndexQuery, ArtworkHashIndexMaintenance {

    private final ImageHashMapper imageHashMapper;
    private final PixivDatabase pixivDatabase;
    private final ArtworkHashService artworkHashService;

    public ArtworkHashIndexAdapter(ImageHashMapper imageHashMapper,
                                   PixivDatabase pixivDatabase,
                                   ArtworkHashService artworkHashService) {
        this.imageHashMapper = imageHashMapper;
        this.pixivDatabase = pixivDatabase;
        this.artworkHashService = artworkHashService;
    }

    @Override
    public List<ArtworkHashEntry> findAllEntries() {
        if (pixivDatabase.isUnavailable()) return List.of();
        return imageHashMapper.findAll().stream()
                .map(row -> new ArtworkHashEntry(
                        row.artworkId(), row.page(), row.dHash(), row.aHash(), row.title(),
                        row.authorId(), row.authorName(), row.xRestrict()))
                .toList();
    }

    @Override
    public ArtworkHashFingerprint fingerprint() {
        if (pixivDatabase.isUnavailable()) return new ArtworkHashFingerprint(0, null);
        return new ArtworkHashFingerprint(
                imageHashMapper.countAllHashRows(), imageHashMapper.maxCreatedTime());
    }

    @Override
    public long artworkCount() {
        if (pixivDatabase.isUnavailable()) throw new top.sywyar.pixivdownload.core.database.DatabaseUnavailableException();
        return pixivDatabase.countArtworks();
    }

    @Override
    public int missingArtworkCount() {
        if (pixivDatabase.isUnavailable()) throw new top.sywyar.pixivdownload.core.database.DatabaseUnavailableException();
        return imageHashMapper.countArtworksMissingHashes();
    }

    @Override
    public List<Long> artworkIdsNewestFirst() {
        if (pixivDatabase.isUnavailable()) throw new top.sywyar.pixivdownload.core.database.DatabaseUnavailableException();
        return List.copyOf(pixivDatabase.getArtworkIdsSortedByTimeDesc());
    }

    @Override
    public List<Long> artworkIdsMissingHashes(int limit) {
        if (pixivDatabase.isUnavailable()) throw new top.sywyar.pixivdownload.core.database.DatabaseUnavailableException();
        return List.copyOf(imageHashMapper.artworkIdsMissingHashes(limit));
    }

    @Override
    public void clearAllHashes() {
        if (pixivDatabase.isUnavailable()) throw new top.sywyar.pixivdownload.core.database.DatabaseUnavailableException();
        imageHashMapper.deleteAll();
    }

    @Override
    public OptionalInt rebuildArtwork(long artworkId) {
        if (pixivDatabase.isUnavailable()) throw new top.sywyar.pixivdownload.core.database.DatabaseUnavailableException();
        ArtworkRecord artwork = pixivDatabase.getArtwork(artworkId);
        if (artwork == null) {
            return OptionalInt.empty();
        }
        return OptionalInt.of(artworkHashService.recordArtworkHashes(artwork));
    }
}
