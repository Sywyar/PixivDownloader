package top.sywyar.pixivdownload.core.download.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

@Getter
@AllArgsConstructor
public class BatchArtworksResponse {
    private final List<DownloadedResponse> artworks;
    private final List<Long> deletedArtworkIds;

    public BatchArtworksResponse(List<DownloadedResponse> artworks) {
        this(artworks, List.of());
    }
}
