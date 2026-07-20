package top.sywyar.pixivdownload.download.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

@Getter
@AllArgsConstructor
public class SearchResponse {
    private final List<SearchItem> items;
    private final int total;
    private final int page;

    public record SearchItem(
            String id,
            String title,
            int illustType,
            int xRestrict,
            int aiType,
            String thumbnailUrl,
            int pageCount,
            String userId,
            String userName,
            List<String> tags
    ) {}
}
