package top.sywyar.pixivdownload.novel.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

@Getter
@AllArgsConstructor
public class NovelSearchResponse {
    private final List<NovelSearchItem> items;
    private final int total;
    private final int page;

    public record NovelSearchItem(
            String id,
            String title,
            int xRestrict,
            int aiType,
            int bookmarkCount,
            int wordCount,
            int textLength,
            String userId,
            String userName,
            String coverUrl,
            boolean isOriginal,
            List<String> tags
    ) {}
}
