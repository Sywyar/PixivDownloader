package top.sywyar.pixivdownload.download.request;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Setter
@Getter
public class DownloadRequest {
    // getters and setters
    @NotNull(message = "作品ID不能为空")
    private Long artworkId;

    @NotNull(message = "作品名称不能为空")
    private String title;

    @NotEmpty(message = "图片URL列表不能为空")
    private List<String> imageUrls;

    private String referer = "https://www.pixiv.net/";

    // 新增Cookie字段
    private String cookie;

}