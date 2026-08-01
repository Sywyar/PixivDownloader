package top.sywyar.pixivdownload.core.download;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import top.sywyar.pixivdownload.core.artwork.download.ArtworkDownloadCompletion;
import top.sywyar.pixivdownload.core.artwork.download.ArtworkDownloadHistory;
import top.sywyar.pixivdownload.core.db.PixivDatabase;
import top.sywyar.pixivdownload.core.db.TagDto;

/**
 * 将插画下载历史端口适配到宿主数据库。
 */
@Component
public class ArtworkDownloadHistoryAdapter implements ArtworkDownloadHistory {

    private final PixivDatabase pixivDatabase;

    public ArtworkDownloadHistoryAdapter(PixivDatabase pixivDatabase) {
        this.pixivDatabase = pixivDatabase;
    }

    @Override
    public long allocateRecordTime(long preferredTime) {
        return preferredTime > 0
                ? pixivDatabase.getUniqueTime(preferredTime)
                : pixivDatabase.getUniqueTime();
    }

    @Override
    @Transactional
    public void record(ArtworkDownloadCompletion completion) {
        long fileNameTemplateId = pixivDatabase.getOrCreateFileNameTemplateId(
                completion.fileNameTemplate());
        long resolvedFileAuthorNameId = completion.normalizedAuthorName() == null
                ? 0L
                : pixivDatabase.getOrCreateFileAuthorNameId(completion.normalizedAuthorName());
        Long fileAuthorNameId = resolvedFileAuthorNameId > 0 ? resolvedFileAuthorNameId : null;
        pixivDatabase.insertArtwork(
                completion.artworkId(),
                completion.title(),
                completion.folder().toAbsolutePath().toString(),
                completion.imageCount(),
                String.join(",", completion.extensions()),
                completion.recordTime(),
                completion.restriction(),
                completion.aiGenerated(),
                completion.authorId(),
                completion.description(),
                fileNameTemplateId,
                fileAuthorNameId,
                completion.seriesId(),
                completion.seriesOrder()
        );
        pixivDatabase.saveArtworkTags(completion.artworkId(), completion.tags().stream()
                .map(tag -> new TagDto(tag.tagId(), tag.name(), tag.translatedName()))
                .toList());
    }
}
