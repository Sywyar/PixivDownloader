package top.sywyar.pixivdownload.download.response;

import lombok.AllArgsConstructor;
import lombok.Data;

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
