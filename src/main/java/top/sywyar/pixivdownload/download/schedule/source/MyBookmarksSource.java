package top.sywyar.pixivdownload.download.schedule.source;

import com.fasterxml.jackson.databind.JsonNode;
import top.sywyar.pixivdownload.core.schedule.ScheduledTaskType;

import java.util.List;

/**
 * 我的收藏来源（{@code MY_BOOKMARKS}）：账号私有，全量发现收藏、跳过已下载。收藏顺序非单调、无水位线，
 * 故按「每轮上限」逐轮抽干积压（{@code fetchLimit>0} 时每轮最多派发 {@code fetchLimit} 个新作）。
 * 插画 / 小说按任务 kind 选对应发现接口。
 */
public final class MyBookmarksSource extends AbstractScheduledSource {

    public MyBookmarksSource() {
        super(ScheduledTaskType.MY_BOOKMARKS);
    }

    @Override
    public DiscoveryMode mode(JsonNode source) {
        return DiscoveryMode.FULL;
    }

    @Override
    public boolean accountScoped() {
        return true;
    }

    @Override
    public String notificationLabelKey() {
        return "mail.template.common.task-type.my-bookmarks";
    }

    @Override
    public void discoverAndDispatch(ScheduledSourceContext ctx) throws Exception {
        String rest = ctx.source().path("rest").asText("show");
        List<String> ids = ctx.novel()
                ? ctx.fetch().discoverMyNovelBookmarkIds(rest, ctx.cookie())
                : ctx.fetch().discoverMyIllustBookmarkIds(rest, ctx.cookie());
        int queueLimit = ctx.fetchLimit() > 0 ? ctx.fetchLimit() : 0;
        ctx.fullScan(ids, queueLimit);
    }
}
