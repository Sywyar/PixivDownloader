package top.sywyar.pixivdownload.download.request;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class ArtworkBatchRequest {
    private List<Long> artworkIds;
}
