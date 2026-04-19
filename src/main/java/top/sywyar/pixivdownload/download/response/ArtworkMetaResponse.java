package top.sywyar.pixivdownload.download.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Getter;
import top.sywyar.pixivdownload.download.db.TagDto;

import java.util.List;

@Getter
@AllArgsConstructor
public class ArtworkMetaResponse {
    private final int illustType;
    private final String illustTitle;
    private final int xRestrict;
    @JsonProperty("isAi")
    private final boolean isAi;
    private final String description;
    private final List<TagDto> tags;
}
