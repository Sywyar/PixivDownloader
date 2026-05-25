package top.sywyar.pixivdownload.duplicate;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import top.sywyar.pixivdownload.download.ArtworkFileLocator;
import top.sywyar.pixivdownload.download.db.ArtworkRecord;
import top.sywyar.pixivdownload.i18n.AppMessages;

import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class ImageHashService {

    private final ImageHashMapper imageHashMapper;
    private final ArtworkFileLocator artworkFileLocator;
    private final DuplicateService duplicateService;
    private final AppMessages messages;

    public int recordArtworkHashes(ArtworkRecord artwork) {
        return recordArtworkHashes(artwork, true);
    }

    public int recordArtworkHashes(ArtworkRecord artwork, boolean invalidateDuplicateCache) {
        if (artwork == null) {
            return 0;
        }
        int written = 0;
        try {
            imageHashMapper.deleteByArtwork(artwork.artworkId());
            int pageCount = Math.max(artwork.count(), 0);
            for (int page = 0; page < pageCount; page++) {
                written += recordPageHash(artwork, page);
            }
            if (written == 0) {
                // 没有任何可哈希的页（文件缺失 / 解码失败 / 不支持的格式）：写入「已尝试」哨兵行，
                // 避免该作品在每次维护回填/扫描时被反复重试。
                imageHashMapper.markNoHash(artwork.artworkId(), System.currentTimeMillis());
            }
        } catch (Exception e) {
            log.warn(messages.getForLog("duplicate.log.hash.artwork-failed",
                    artwork.artworkId(), e.getMessage()), e);
        } finally {
            if (invalidateDuplicateCache) {
                duplicateService.invalidate();
            }
        }
        return written;
    }

    private int recordPageHash(ArtworkRecord artwork, int page) {
        try {
            ArtworkFileLocator.LocatedArtworkFile source = artworkFileLocator.resolveHashSourceFile(artwork, page);
            if (source == null) {
                log.warn(messages.getForLog("duplicate.log.hash.source-missing", artwork.artworkId(), page));
                return 0;
            }
            Optional<ImageHasher.Hashes> hashes = ImageHasher.hash(source.file().toPath());
            if (hashes.isEmpty()) {
                log.warn(messages.getForLog("duplicate.log.hash.decode-failed",
                        artwork.artworkId(), page, source.file().getAbsolutePath()));
                return 0;
            }
            ImageHasher.Hashes value = hashes.get();
            imageHashMapper.upsert(artwork.artworkId(), page, source.extension(),
                    value.dHash(), value.aHash(), System.currentTimeMillis());
            return 1;
        } catch (Exception e) {
            log.warn(messages.getForLog("duplicate.log.hash.page-failed",
                    artwork.artworkId(), page, e.getMessage()), e);
            return 0;
        }
    }
}
