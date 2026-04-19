package top.sywyar.pixivdownload.download.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

@Getter
@AllArgsConstructor
public class SearchResponse {
    private final List<SearchItem> items;
    private final int total;
    private final int page;

    @Getter
    @AllArgsConstructor
    public static class SearchItem {
        private final String id;
        private final String title;
        private final int illustType;
        @JsonProperty("xRestrict")
        private final int xRestrict;
        @JsonProperty("aiType")
        private final int aiType;
        private final String thumbnailUrl;
        private final int pageCount;
        private final String userId;
        private final String userName;
    }
}
