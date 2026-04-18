package top.sywyar.pixivdownload.download.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
@AllArgsConstructor
public class DownloadedResponse {
    private final Long artworkId;
    private final String title;
    private final String folder;
    private final int count;
    private final String extensions;
    private final Long time;
    private final boolean moved;
    private final String moveFolder;
    private final Long moveTime;
    @JsonProperty("R18")
    private final Boolean isR18;
    private final Long authorId;
    private final String authorName;
}
