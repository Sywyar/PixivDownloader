package top.sywyar.pixivdownload.schedule.source;

import com.fasterxml.jackson.databind.JsonNode;
import top.sywyar.pixivdownload.core.schedule.ScheduledTaskType;

import java.util.List;

/**
 * 系列来源（{@code SERIES}）：全量发现系列成员、跳过已下载，不走水位线、不封顶。
 * 小说系列在本轮有新章节时由调度壳触发系列合订（{@link #seriesMergeApplies()}）。
 */
public final class SeriesSource extends AbstractScheduledSource {

    public SeriesSource() {
        super(ScheduledTaskType.SERIES);
    }

    @Override
    public DiscoveryMode mode(JsonNode source) {
        return DiscoveryMode.FULL;
    }

    @Override
    public boolean accountScoped() {
        return false;
    }

    @Override
    public boolean seriesMergeApplies() {
        return true;
    }

    @Override
    public String notificationLabelKey() {
        return "mail.template.common.task-type.series";
    }

    @Override
    public void discoverAndDispatch(ScheduledSourceContext ctx) throws Exception {
        String seriesId = ctx.source().path("seriesId").asText("");
        List<String> ids = ctx.novel()
                ? ctx.fetch().discoverNovelSeriesIds(seriesId, ctx.cookie())
                : ctx.fetch().discoverSeriesArtworkIds(seriesId, ctx.cookie());
        ctx.fullScan(ids, 0);
    }
}
