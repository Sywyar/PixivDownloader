package top.sywyar.pixivdownload.core.download;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import top.sywyar.pixivdownload.core.artwork.download.ArtworkDownloadCompletion;
import top.sywyar.pixivdownload.core.artwork.download.ArtworkDownloadHistory;
import top.sywyar.pixivdownload.core.db.ArtworkRecord;
import top.sywyar.pixivdownload.core.db.PixivDatabase;
import top.sywyar.pixivdownload.core.db.TagDto;
import top.sywyar.pixivdownload.core.metadata.ArtworkMetadataQuality;

import java.util.List;

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
        ArtworkRecord previous = pixivDatabase.getArtwork(completion.artworkId());
        boolean titleIsMeaningful = ArtworkMetadataQuality.isMeaningfulTitle(
                completion.artworkId(), completion.title());
        String newTitle = titleIsMeaningful ? completion.title().trim() : null;
        Integer newRestriction = titleIsMeaningful
                && completion.restriction() >= 0 && completion.restriction() <= 2
                ? completion.restriction() : null;
        Boolean newAiGenerated = titleIsMeaningful ? completion.aiGenerated() : null;
        Long newAuthorId = positiveOrNull(completion.authorId());
        String newDescription = textOrNull(completion.description());
        boolean hasNewSeries = completion.seriesId() != null && completion.seriesId() > 0;
        Long newSeriesId = hasNewSeries ? completion.seriesId() : null;
        Long newSeriesOrder = hasNewSeries ? nonNegativeOrNull(completion.seriesOrder()) : null;
        String title = valueOrPrevious(newTitle, previous == null ? "" : previous.title());
        Integer restriction = valueOrPrevious(newRestriction, previous == null ? null : previous.xRestrict());
        Boolean aiGenerated = valueOrPrevious(newAiGenerated, previous == null ? null : previous.isAi());
        Long authorId = valueOrPrevious(newAuthorId, previous == null ? null : previous.authorId());
        String description = valueOrPrevious(newDescription, previous == null ? null : previous.description());
        Long seriesId = valueOrPrevious(newSeriesId, previous == null ? null : previous.seriesId());
        Long seriesOrder = valueOrPrevious(newSeriesOrder, previous == null ? null : previous.seriesOrder());
        long fileNameTemplateId = pixivDatabase.getOrCreateFileNameTemplateId(
                completion.fileNameTemplate());
        long resolvedFileAuthorNameId = completion.normalizedAuthorName() == null
                ? 0L
                : pixivDatabase.getOrCreateFileAuthorNameId(completion.normalizedAuthorName());
        Long fileAuthorNameId = previous == null ? null : previous.fileAuthorNameId();
        if (resolvedFileAuthorNameId > 0) {
            fileAuthorNameId = resolvedFileAuthorNameId;
        }
        pixivDatabase.insertArtwork(
                completion.artworkId(),
                title,
                completion.folder().toAbsolutePath().toString(),
                completion.imageCount(),
                String.join(",", completion.extensions()),
                completion.recordTime(),
                restriction,
                aiGenerated,
                authorId,
                description,
                fileNameTemplateId,
                fileAuthorNameId,
                seriesId,
                seriesOrder
        );
        pixivDatabase.refreshArtworkMetadataAfterDownload(
                completion.artworkId(),
                newTitle,
                newRestriction,
                newAiGenerated,
                newAuthorId,
                newDescription,
                fileNameTemplateId,
                resolvedFileAuthorNameId > 0 ? resolvedFileAuthorNameId : null,
                newSeriesId,
                newSeriesOrder
        );
        List<TagDto> tags = completion.tags() == null ? List.of() : completion.tags().stream()
                .filter(tag -> tag != null)
                .map(tag -> new TagDto(tag.tagId(), tag.name(), tag.translatedName()))
                .toList();
        pixivDatabase.replaceArtworkTagsAfterDownload(completion.artworkId(), tags);
    }

    private static <T> T valueOrPrevious(T value, T previous) {
        return value == null ? previous : value;
    }

    private static Long positiveOrNull(Long value) {
        return value != null && value > 0 ? value : null;
    }

    private static Long nonNegativeOrNull(Long value) {
        return value != null && value >= 0 ? value : null;
    }

    private static String textOrNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
