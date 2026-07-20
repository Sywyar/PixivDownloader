package top.sywyar.pixivdownload.core.download.response;

import lombok.Builder;
import top.sywyar.pixivdownload.core.db.TagDto;

import java.util.List;

@Builder
public record DownloadedResponse(
        Long artworkId,
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
        List<TagDto> tags,
        Long seriesId,
        Long seriesOrder,
        String seriesTitle,
        /** 已被画廊删除（软删除标记）：记录仍在但磁盘文件已删，客户端据此决定跳过或允许重新下载。 */
        boolean deleted
) {}
