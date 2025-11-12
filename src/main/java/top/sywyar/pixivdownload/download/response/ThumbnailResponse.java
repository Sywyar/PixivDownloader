package top.sywyar.pixivdownload.download.response;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

@AllArgsConstructor
@Getter
@Setter
public class ThumbnailResponse {
    private boolean success;
    private String image;
    private String extension;
    private int fileSize;
    private int width;
    private int height;
    private String message;
}
