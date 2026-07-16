package top.sywyar.pixivdownload.core.download.request;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class ArtworkBatchRequest {
    private List<Long> artworkIds;
    private boolean includeDeleted;

    public ArtworkBatchRequest(List<Long> artworkIds) {
        this(artworkIds, false);
    }
}
