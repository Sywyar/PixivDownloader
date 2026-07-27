package top.sywyar.pixivdownload.download.request;

import com.fasterxml.jackson.annotation.JsonGetter;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;
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
        private boolean isUserDownload;
        private String username;
        private Long authorId;
        private String authorName;
        @JsonProperty("xRestrict")
        private int xRestrict;
        private boolean isAi;
        private boolean isUgoira;
        private String ugoiraZipUrl;
        private List<Integer> ugoiraDelays;
        private int delayMs = 0;
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
        /** 系列简介（{@code /ajax/series/{id}} → {@code body.illustSeries[0].caption}）。下载时一并落库，best-effort。 */
        private String seriesDescription;
        /** 系列封面 URL（pximg），下载时若本地尚无封面则尝试 SSRF-safe 拉取后落盘。 */
        private String seriesCoverUrl;
        /** Pixiv illustType: 0=illust, 1=manga, 2=ugoira。用于决定是否值得异步回填系列信息。 */
        private Integer illustType;

        @JsonGetter("isAi")
        public boolean isAi() {
            return isAi;
        }

        @JsonSetter("isAi")
        public void setAi(boolean isAi) {
            this.isAi = isAi;
        }

        @JsonGetter("isUgoira")
        public boolean isUgoira() {
            return isUgoira;
        }

        @JsonSetter("isUgoira")
        public void setUgoira(boolean isUgoira) {
            this.isUgoira = isUgoira;
        }

        @JsonGetter("isUserDownload")
        public boolean isUserDownload() {
            return isUserDownload;
        }

        @JsonSetter("isUserDownload")
        public void setUserDownload(boolean isUserDownload) {
            this.isUserDownload = isUserDownload;
        }
    }
}
