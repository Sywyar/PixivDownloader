package top.sywyar.pixivdownload.gallery.web;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * gallery 作品详情与列表的 HTTP 投影。
 */
public record GalleryArtworkResponse(
        Long artworkId,
        String title,
        String folder,
        int count,
        String extensions,
        Long time,
        boolean moved,
        String moveFolder,
        Long moveTime,
        @JsonProperty("xRestrict") Integer xRestrict,
        @JsonProperty("isAi") Boolean isAi,
        Long authorId,
        String authorName,
        String description,
        Long fileName,
        String fileNameTemplate,
        List<GalleryTagResponse> tags,
        Long seriesId,
        Long seriesOrder,
        String seriesTitle,
        boolean deleted,
        Long uploadTime) {

    public GalleryArtworkResponse(Long artworkId,
        String title,
        String folder,
        int count,
        String extensions,
        Long time,
        boolean moved,
        String moveFolder,
        Long moveTime,
        Integer xRestrict,
        Boolean isAi,
        Long authorId,
        String authorName,
        String description,
        Long fileName,
        String fileNameTemplate,
        List<GalleryTagResponse> tags,
        Long seriesId,
        Long seriesOrder,
        String seriesTitle,
        boolean deleted) {
        this(artworkId, title, folder, count, extensions, time, moved, moveFolder, moveTime, xRestrict, isAi, authorId, authorName, description, fileName, fileNameTemplate, tags, seriesId, seriesOrder, seriesTitle, deleted, null);
    }

    public GalleryArtworkResponse {
        tags = tags == null ? List.of() : List.copyOf(tags);
    }
}
