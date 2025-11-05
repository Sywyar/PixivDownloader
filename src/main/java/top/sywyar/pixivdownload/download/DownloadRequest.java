package top.sywyar.pixivdownload.download;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;

public class DownloadRequest {
    @NotNull(message = "作品ID不能为空")
    private Long artworkId;

    @NotEmpty(message = "图片URL列表不能为空")
    private List<String> imageUrls;

    private String referer = "https://www.pixiv.net/";

    // 新增Cookie字段
    private String cookie;

    // getters and setters
    public Long getArtworkId() { return artworkId; }
    public void setArtworkId(Long artworkId) { this.artworkId = artworkId; }

    public List<String> getImageUrls() { return imageUrls; }
    public void setImageUrls(List<String> imageUrls) { this.imageUrls = imageUrls; }

    public String getReferer() { return referer; }
    public void setReferer(String referer) { this.referer = referer; }

    public String getCookie() { return cookie; }
    public void setCookie(String cookie) { this.cookie = cookie; }
}