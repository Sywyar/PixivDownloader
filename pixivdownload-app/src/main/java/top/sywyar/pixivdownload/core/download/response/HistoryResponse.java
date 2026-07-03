package top.sywyar.pixivdownload.core.download.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

@Getter
@AllArgsConstructor
public class HistoryResponse {
    private final List<String> artworkIds;
}
