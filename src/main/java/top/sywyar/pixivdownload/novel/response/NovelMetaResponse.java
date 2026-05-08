package top.sywyar.pixivdownload.novel.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Getter;
import top.sywyar.pixivdownload.download.db.TagDto;

import java.util.List;
import java.util.Map;

@Getter
@AllArgsConstructor
public class NovelMetaResponse {
    private final long novelId;
    private final String title;
    @JsonProperty("xRestrict")
    private final int xRestrict;
    @JsonProperty("isAi")
    private final boolean isAi;
    private final int bookmarkCount;
    private final Long authorId;
    private final String authorName;
    private final String description;
    private final List<TagDto> tags;
    private final Long seriesId;
    private final Long seriesOrder;
    private final String seriesTitle;
    private final String content;
    private final Integer wordCount;
    private final Integer textLength;
    private final Integer readingTimeSeconds;
    private final Integer pageCount;
    @JsonProperty("isOriginal")
    private final boolean isOriginal;
    private final String language;
    private final String coverUrl;
    private final Long uploadTimestamp;
    /** [uploadedimage:id] → 原图 URL（pximg.net）。可能为空。 */
    private final Map<String, String> textEmbeddedImages;
}
