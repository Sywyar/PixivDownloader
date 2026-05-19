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

    @Getter
    @AllArgsConstructor
    public static class NovelSearchItem {
        private final String id;
        private final String title;
        private final int xRestrict;
        private final int aiType;
        private final int wordCount;
        private final int textLength;
        private final String userId;
        private final String userName;
        private final String coverUrl;
        private final boolean isOriginal;
        private final List<String> tags;
    }
}
