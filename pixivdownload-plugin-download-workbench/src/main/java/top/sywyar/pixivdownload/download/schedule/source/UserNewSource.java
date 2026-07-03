package top.sywyar.pixivdownload.download.schedule.source;

import com.fasterxml.jackson.databind.JsonNode;
import top.sywyar.pixivdownload.core.schedule.ScheduledTaskType;

import java.util.List;

/**
 * 画师新作来源（{@code USER_NEW}）：单画师全量发现（ID 降序），走 ID 水位线增量。插画 / 小说同构，
 * 按任务 kind 选对应发现接口。
 */
public final class UserNewSource extends AbstractScheduledSource {

    public UserNewSource() {
        super(ScheduledTaskType.USER_NEW);
    }

    @Override
    public DiscoveryMode mode(JsonNode source) {
        return DiscoveryMode.WATERMARK;
    }

    @Override
    public boolean accountScoped() {
        return false;
    }

    @Override
    public String notificationLabelKey() {
        return "mail.template.common.task-type.user-new";
    }

    @Override
    public void discoverAndDispatch(ScheduledSourceContext ctx) throws Exception {
        String userId = ctx.source().path("userId").asText("");
        List<String> all = ctx.novel()
                ? ctx.fetch().discoverUserNovelIds(userId, ctx.cookie())
                : ctx.fetch().discoverUserArtworkIds(userId, ctx.cookie());
        // 单次全量发现，无翻页；页间不延迟。
        ctx.watermarkScan(p -> p == 1 ? all : List.of(), () -> {});
    }
}
