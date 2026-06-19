package top.sywyar.pixivdownload.schedule.source;

import com.fasterxml.jackson.databind.JsonNode;
import top.sywyar.pixivdownload.core.schedule.ScheduledTaskType;

import java.util.List;

/**
 * 画师约稿成品来源（{@code USER_REQUEST}）：单画师约稿成品全量发现（已按 ID 降序），走 ID 水位线增量，
 * 与 {@link UserNewSource} 同构。约稿成品仅插画（kind 锁 illust），不分小说。
 */
public final class UserRequestSource extends AbstractScheduledSource {

    public UserRequestSource() {
        super(ScheduledTaskType.USER_REQUEST);
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
        return "mail.template.common.task-type.user-request";
    }

    @Override
    public void discoverAndDispatch(ScheduledSourceContext ctx) throws Exception {
        String userId = ctx.source().path("userId").asText("");
        List<String> all = ctx.fetch().discoverUserRequestArtworkIds(userId, ctx.cookie());
        // 单次全量发现，无翻页；页间不延迟。
        ctx.watermarkScan(p -> p == 1 ? all : List.of(), () -> {});
    }
}
