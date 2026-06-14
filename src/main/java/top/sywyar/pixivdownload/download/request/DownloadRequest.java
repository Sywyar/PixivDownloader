package top.sywyar.pixivdownload.download.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import top.sywyar.pixivdownload.core.db.TagDto;

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
        /** 系列简介（{@code /ajax/series/{id}} → {@code body.illustSeries[0].caption}）。下载时一并落库，best-effort。 */
        private String seriesDescription;
        /** 系列封面 URL（pximg），下载时若本地尚无封面则尝试 SSRF-safe 拉取后落盘。 */
        private String seriesCoverUrl;
        /** Pixiv illustType: 0=illust, 1=manga, 2=ugoira。用于决定是否值得异步回填系列信息。 */
        private Integer illustType;
        /**
         * 前端转发的、轻剪枝后的 Pixiv 作品原始 body（{@code /ajax/illust/{id}}）JSON 串。
         * 由油猴脚本在下载前顺手附带（脚本本就已抓到完整 body，零额外请求），下载成功且作品行已落库后，
         * 后端旁路归一化为 meta sidecar + {@code upload_time}/{@code is_original} 列投影；best-effort，
         * 解析 / 落盘失败不影响下载结果。仅前端交互下载链路填充；计划任务走后端自抓 body，不读此字段。
         */
        private String rawMetaJson;
    }
}
