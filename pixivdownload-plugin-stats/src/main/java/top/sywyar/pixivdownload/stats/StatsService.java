package top.sywyar.pixivdownload.stats;

import lombok.RequiredArgsConstructor;
import top.sywyar.pixivdownload.core.stats.StatsAggregates;
import top.sywyar.pixivdownload.core.stats.StatsQueryStore;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopControlCenterAvailability;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopDashboardCardContribution;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopDashboardSnapshot;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopDashboardSource;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiIcon;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiTone;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiText;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginManagedBean;

import java.time.Instant;
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
public class StatsService implements DesktopDashboardSource {

    private final StatsQueryStore statsQueryStore;

    private static final String DASHBOARD_NAMESPACE = "stats";
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

    /**
     * 发布现有统计总览能够直接证明的插画总数，不为桌面卡片新增查询或推断口径。
     *
     * @return stats owner 的桌面首页只读快照
     */
    @Override
    public DesktopDashboardSnapshot snapshot() {
        StatsAggregates.Overview overview = statsQueryStore.overview();
        Instant observedAt = Instant.now();
        DesktopDashboardCardContribution card = new DesktopDashboardCardContribution(
                "total-artworks",
                40,
                text("overview.artworks", "Total artworks"),
                DesktopUiText.raw(Long.toString(overview.totalArtworks())),
                text("plugin.summary",
                        "Dashboard of download statistics such as artwork and image counts (admin only)."),
                DesktopUiTone.INFO,
                DesktopUiIcon.STATISTICS,
                DesktopControlCenterAvailability.AVAILABLE,
                observedAt);
        return new DesktopDashboardSnapshot(List.of(card), List.of(), observedAt);
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

    private static DesktopUiText text(String key, String fallback) {
        return new DesktopUiText(DASHBOARD_NAMESPACE, key, fallback, List.of());
    }
}
