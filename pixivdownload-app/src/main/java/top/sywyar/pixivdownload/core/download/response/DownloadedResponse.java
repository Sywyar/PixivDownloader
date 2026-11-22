package top.sywyar.pixivdownload.core.download.response;

import top.sywyar.pixivdownload.plugin.api.web.HttpApiNullable;
import top.sywyar.pixivdownload.core.work.model.FileManagement;
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
        @HttpApiNullable String moveFolder,
        @HttpApiNullable Long moveTime,
        @HttpApiNullable Integer xRestrict,
        @HttpApiNullable Boolean isAi,
        @HttpApiNullable Long authorId,
        @HttpApiNullable String authorName,
        @HttpApiNullable String description,
        Long fileName,
        String fileNameTemplate,
        List<TagDto> tags,
        @HttpApiNullable Long seriesId,
        @HttpApiNullable Long seriesOrder,
        @HttpApiNullable String seriesTitle,
        FileManagement fileManagement,
        /** 画廊已软删除此登记，仍保留查重记录。 */
        boolean deleted
) {}
