package top.sywyar.pixivdownload.core.db;

import lombok.Builder;

import java.util.Objects;

/**
 * 写入作品下载记录所需的完整参数。
 */
@Builder
public record InsertArtworkArgument(
        Long artworkId,
        String title,
        String folder,
        Integer count,
        String extensions,
        Long time,
        Integer xRestrict,
        Boolean isAi,
        Long authorId,
        String description,
        Long fileName,
        Long fileAuthorNameId,
        Long seriesId,
        Long seriesOrder
) {
    public InsertArtworkArgument {
        Objects.requireNonNull(artworkId, "artworkId");
        Objects.requireNonNull(folder, "folder");
        Objects.requireNonNull(count, "count");
        Objects.requireNonNull(time, "time");
        fileName = fileName == null ? PixivDatabase.DEFAULT_FILE_NAME_TEMPLATE_ID : fileName;
    }
}
