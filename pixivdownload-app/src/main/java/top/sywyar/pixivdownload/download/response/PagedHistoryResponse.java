package top.sywyar.pixivdownload.download.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

@Getter
@AllArgsConstructor
public class PagedHistoryResponse {
    private final List<DownloadedResponse> content;
    private final long totalElements;
    private final int page;
    private final int size;
    private final int totalPages;
}
