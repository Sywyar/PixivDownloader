package top.sywyar.pixivdownload.gallery.web;

import top.sywyar.pixivdownload.core.work.model.FileManagement;
import com.fasterxml.jackson.annotation.JsonProperty;
import top.sywyar.pixivdownload.plugin.api.web.HttpApiNullable;
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
        long time,
        boolean moved,
        @HttpApiNullable String moveFolder,
        @HttpApiNullable Long moveTime,
        @HttpApiNullable @JsonProperty("xRestrict") Integer xRestrict,
        @HttpApiNullable @JsonProperty("isAi") Boolean isAi,
        @HttpApiNullable Long authorId,
        @HttpApiNullable String authorName,
        @HttpApiNullable String description,
        @HttpApiNullable Long fileName,
        @HttpApiNullable String fileNameTemplate,
        List<GalleryTagResponse> tags,
        @HttpApiNullable Long seriesId,
        @HttpApiNullable Long seriesOrder,
        @HttpApiNullable String seriesTitle,
        boolean deleted,
        @HttpApiNullable Long uploadTime,
        @HttpApiNullable Long createTime,
        @HttpApiNullable Long updateTime,
        @HttpApiNullable Long reuploadTime,
        FileManagement fileManagement) {
    public GalleryArtworkResponse(Long artworkId,
        String title,
        String folder,
        int count,
        String extensions,
        long time,
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
        this(artworkId, title, folder, count, extensions, time, moved, moveFolder, moveTime, xRestrict, isAi, authorId, authorName, description, fileName, fileNameTemplate, tags, seriesId, seriesOrder, seriesTitle, deleted, uploadTime, null, null, null, FileManagement.MANAGED);
    }


    public GalleryArtworkResponse(Long artworkId,
        String title,
        String folder,
        int count,
        String extensions,
        long time,
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
