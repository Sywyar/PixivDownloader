package top.sywyar.pixivdownload.download.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import top.sywyar.pixivdownload.download.db.TagDto;

import java.util.List;

@Getter
@Builder
@AllArgsConstructor
public class DownloadedResponse {
    private final Long artworkId;
    private final String title;
    private final String folder;
    private final int count;
    private final String extensions;
    private final Long time;
    private final boolean moved;
    private final String moveFolder;
    private final Long moveTime;
    @JsonProperty("xRestrict")
    private final Integer xRestrict;
    @JsonProperty("isAi")
    private final Boolean isAi;
    private final Long authorId;
    private final String authorName;
    private final String description;
    private final Long fileName;
    private final String fileNameTemplate;
    private final List<TagDto> tags;
    private final Long seriesId;
    private final Long seriesOrder;
    private final String seriesTitle;
    /** 已被画廊删除（软删除标记）：记录仍在但磁盘文件已删，客户端据此决定跳过或允许重新下载。 */
    private final boolean deleted;
}
