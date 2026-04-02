package top.sywyar.pixivdownload.download.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

@Data
public class DownloadRequest {
    @NotNull(message = "作品ID不能为空")
    private Long artworkId;

    @NotNull(message = "作品名称不能为空")
    private String title;

    @NotEmpty(message = "图片URL列表不能为空")
    private List<String> imageUrls;

    private String referer = "https://www.pixiv.net/";

    private Other other = new Other();

    private String cookie;

    @Data
    public static class Other {
        @JsonProperty("isUserDownload")
        private boolean isUserDownload;
        private String username;
        @JsonProperty("isR18")
        private boolean isR18;
        @JsonProperty("isUgoira")
        private boolean isUgoira;
        private String ugoiraZipUrl;
        private List<Integer> ugoiraDelays;
        private int delayMs = 0;
        @JsonProperty("bookmark")
        private boolean bookmark;
    }
}