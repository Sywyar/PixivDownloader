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
        return imageHashMapper.findAll().stream()
                .map(row -> new ArtworkHashEntry(
                        row.artworkId(), row.page(), row.dHash(), row.aHash(), row.title(),
                        row.authorId(), row.authorName(), row.xRestrict()))
                .toList();
    }

    @Override
    public ArtworkHashFingerprint fingerprint() {
        return new ArtworkHashFingerprint(
                imageHashMapper.countAllHashRows(), imageHashMapper.maxCreatedTime());
    }

    @Override
    public long artworkCount() {
        return pixivDatabase.countArtworks();
    }

    @Override
    public int missingArtworkCount() {
        return imageHashMapper.countArtworksMissingHashes();
    }

    @Override
    public List<Long> artworkIdsNewestFirst() {
        return List.copyOf(pixivDatabase.getArtworkIdsSortedByTimeDesc());
    }

    @Override
    public List<Long> artworkIdsMissingHashes(int limit) {
        return List.copyOf(imageHashMapper.artworkIdsMissingHashes(limit));
    }

    @Override
    public void clearAllHashes() {
        imageHashMapper.deleteAll();
    }

    @Override
    public OptionalInt rebuildArtwork(long artworkId) {
        ArtworkRecord artwork = pixivDatabase.getArtwork(artworkId);
        if (artwork == null) {
            return OptionalInt.empty();
        }
        return OptionalInt.of(artworkHashService.recordArtworkHashes(artwork));
    }
}
