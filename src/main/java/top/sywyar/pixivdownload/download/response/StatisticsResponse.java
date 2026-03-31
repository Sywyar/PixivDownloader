package top.sywyar.pixivdownload.download.response;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class StatisticsResponse {
    private boolean success;
    private int totalArtworks;
    private int totalImages;
    private int totalMoved;
    private String message;
}
