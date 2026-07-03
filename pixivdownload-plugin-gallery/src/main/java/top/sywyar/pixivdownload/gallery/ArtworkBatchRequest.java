package top.sywyar.pixivdownload.gallery;

import java.util.List;

public record ArtworkBatchRequest(
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
            List<String> formats,
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
            Long seriesId
    ) {
    }
}
