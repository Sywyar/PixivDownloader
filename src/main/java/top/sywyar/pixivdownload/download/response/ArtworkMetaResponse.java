package top.sywyar.pixivdownload.download.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class ArtworkMetaResponse {
    private final int illustType;
    private final String illustTitle;
    private final int xRestrict;
    private final String description;
    private final String tags;
}
