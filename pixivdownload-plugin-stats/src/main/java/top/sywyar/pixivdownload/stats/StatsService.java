package top.sywyar.pixivdownload.stats;

import lombok.RequiredArgsConstructor;
import top.sywyar.pixivdownload.core.stats.StatsAggregates;
import top.sywyar.pixivdownload.core.stats.StatsQueryStore;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginManagedBean;

import java.util.ArrayList;
import java.util.List;

/**
 * 统计仪表盘业务服务：经核心 owned 语义接口 {@link StatsQueryStore} 取聚合结果，
 * 映射为对外响应 DTO {@link StatsDto}（并叠加作者名回退等展示逻辑）。
 * <p>
 * 不持有任何数据库底层（{@code DataSource} / {@code JdbcTemplate} / SQL）——核心表访问全部收口在
 * {@code core.stats} 语义接口之后。
 */
@PluginManagedBean
@RequiredArgsConstructor
public class StatsService {

    private final StatsQueryStore statsQueryStore;

    private static final int DEFAULT_TOP_AUTHORS = 15;
    private static final int DEFAULT_TOP_TAGS = 50;
    private static final int MAX_TOP = 200;

    public StatsDto.Dashboard dashboard(int topAuthors, int topTags) {
        int authorLimit = clamp(topAuthors, DEFAULT_TOP_AUTHORS);
        int tagLimit = clamp(topTags, DEFAULT_TOP_TAGS);
        return new StatsDto.Dashboard(
                toOverview(statsQueryStore.overview()),
                toAuthorStats(statsQueryStore.topAuthors(authorLimit)),
                toTagStats(statsQueryStore.topTags(tagLimit)),
                toMonthlyStats(statsQueryStore.monthlyArtworkCounts()));
    }

    private StatsDto.Overview toOverview(StatsAggregates.Overview o) {
        return new StatsDto.Overview(o.totalArtworks(), o.totalImages(), o.totalMoved(),
                o.totalNovels(), o.totalAuthors(), o.totalTags(), o.totalSeries());
    }

    /** 作者表缺名时回退到 author_id 字符串，保证前端始终有可显示的标签。 */
    private List<StatsDto.AuthorStat> toAuthorStats(List<StatsAggregates.AuthorStat> authors) {
        List<StatsDto.AuthorStat> out = new ArrayList<>(authors.size());
        for (StatsAggregates.AuthorStat a : authors) {
            String name = (a.name() == null || a.name().isBlank())
                    ? String.valueOf(a.authorId()) : a.name();
            out.add(new StatsDto.AuthorStat(a.authorId(), name, a.count()));
        }
        return out;
    }

    private List<StatsDto.TagStat> toTagStats(List<StatsAggregates.TagStat> tags) {
        List<StatsDto.TagStat> out = new ArrayList<>(tags.size());
        for (StatsAggregates.TagStat t : tags) {
            out.add(new StatsDto.TagStat(t.tagId(), t.name(), t.translatedName(), t.count()));
        }
        return out;
    }

    private List<StatsDto.MonthlyStat> toMonthlyStats(List<StatsAggregates.MonthlyStat> monthly) {
        List<StatsDto.MonthlyStat> out = new ArrayList<>(monthly.size());
        for (StatsAggregates.MonthlyStat m : monthly) {
            out.add(new StatsDto.MonthlyStat(m.month(), m.count()));
        }
        return out;
    }

    private int clamp(int requested, int fallback) {
        if (requested <= 0) return fallback;
        return Math.min(requested, MAX_TOP);
    }
}
