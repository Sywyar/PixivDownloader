package top.sywyar.pixivdownload.download.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import top.sywyar.pixivdownload.download.db.TagDto;

import java.util.List;

@Data
public class DownloadRequest {
    @NotNull(message = "{validation.download.artwork-id.required}")
    private Long artworkId;

    @NotNull(message = "{validation.download.title.required}")
    private String title;

    @NotEmpty(message = "{validation.download.image-urls.required}")
    private List<String> imageUrls;

    private String referer = "https://www.pixiv.net/";

    private Other other = new Other();

    private String cookie;

    @Data
    public static class Other {
        @JsonProperty("isUserDownload")
        private boolean isUserDownload;
        private String username;
        private Long authorId;
        private String authorName;
        @JsonProperty("xRestrict")
        private int xRestrict;
        @JsonProperty("isAi")
        private boolean isAi;
        @JsonProperty("isUgoira")
        private boolean isUgoira;
        private String ugoiraZipUrl;
        private List<Integer> ugoiraDelays;
        private int delayMs = 0;
        @JsonProperty("bookmark")
        private boolean bookmark;
        private Long collectionId;
        private String description;
        private List<TagDto> tags;
        private String fileNameTemplate;
        private List<String> fileNames;
        /** Filename template timestamp, in epoch milliseconds. */
        private Long fileNameTimestamp;
        private Long seriesId;
        private Long seriesOrder;
        private String seriesTitle;
        /** Pixiv illustType: 0=illust, 1=manga, 2=ugoira。用于决定是否值得异步回填系列信息。 */
        private Integer illustType;
    }
}
