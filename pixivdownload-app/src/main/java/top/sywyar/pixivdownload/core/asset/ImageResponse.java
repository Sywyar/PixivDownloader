package top.sywyar.pixivdownload.core.asset;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 已下载作品图片的 JSON 字节响应体（base64 内联）。由核心本地资产 serving 端点
 * （{@code /api/downloaded/thumbnail}、{@code /api/downloaded/image}）返回。
 */
@Data
@AllArgsConstructor
public class ImageResponse {
    private boolean success;
    private String image;
    private String extension;
    private int fileSize;
    private int width;
    private int height;
    private String message;
}
