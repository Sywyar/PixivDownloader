package top.sywyar.pixivdownload.douyin.db.history;

import java.util.List;

public record DouyinHistoryPage(
        List<DouyinWorkRecord> works,
        long total
) {

    public DouyinHistoryPage {
        works = works == null ? List.of() : List.copyOf(works);
        total = Math.max(0, total);
    }
}
