package top.sywyar.pixivdownload.novelgallery;

import java.util.List;

public record NovelBatchRequest(
        String mode,
        List<Long> ids,
        List<Long> excludeIds,
        Filter filter,
        Long collectionId,
        String groupBy,
        String format,
        Boolean deleteAfter
) {
    public boolean filterMode() {
        return "filter".equalsIgnoreCase(mode) && filter != null;
    }

    public record Filter(
            Integer page,
            Integer size,
            String sort,
            String order,
            String search,
            String searchType,
            String r18,
            String ai,
            List<Long> collectionIds,
            List<Long> tagIds,
            List<Long> notTagIds,
            List<Long> orTagIds,
            List<Long> authorIds,
            List<Long> notAuthorIds,
            List<Long> orAuthorIds,
            Long authorId,
            List<Long> seriesIds,
            List<Long> notSeriesIds,
            Long seriesId,
            Long uploadedFrom,
            Long uploadedBefore,
            Long downloadedFrom,
            Long downloadedBefore
    ) {
        public Filter(Integer page,
            Integer size,
            String sort,
            String order,
            String search,
            String searchType,
            String r18,
            String ai,
            List<Long> collectionIds,
            List<Long> tagIds,
            List<Long> notTagIds,
            List<Long> orTagIds,
            List<Long> authorIds,
            List<Long> notAuthorIds,
            List<Long> orAuthorIds,
            Long authorId,
            List<Long> seriesIds,
            List<Long> notSeriesIds,
            Long seriesId) {
            this(page, size, sort, order, search, searchType, r18, ai, collectionIds, tagIds, notTagIds, orTagIds, authorIds, notAuthorIds, orAuthorIds, authorId, seriesIds, notSeriesIds, seriesId, null, null, null, null);
        }
    }
}
