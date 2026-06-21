package top.sywyar.pixivdownload.download.schedule.source;

import com.fasterxml.jackson.databind.JsonNode;
import top.sywyar.pixivdownload.core.schedule.ScheduledTaskType;
import top.sywyar.pixivdownload.download.PixivFetchService;

import java.util.List;

/**
 * 关注新作来源（{@code FOLLOW_LATEST}）：账号私有，已关注用户的新作 feed（按发布时间倒序 ≈ ID 降序、
 * 仅头部追加），走 ID 水位线增量。逐页拉 follow_latest 喂给水位线扫描，命中 {@code isLastPage} 后下一次取页
 * 直接返回空使扫描自然停止（兼顾 Pixiv 偶发越界页不返回空数组）。仅插画。
 */
public final class FollowLatestSource extends AbstractScheduledSource {

    public FollowLatestSource() {
        super(ScheduledTaskType.FOLLOW_LATEST);
    }

    @Override
    public DiscoveryMode mode(JsonNode source) {
        return DiscoveryMode.WATERMARK;
    }

    @Override
    public boolean accountScoped() {
        return true;
    }

    @Override
    public String notificationLabelKey() {
        return "mail.template.common.task-type.follow-latest";
    }

    @Override
    public void discoverAndDispatch(ScheduledSourceContext ctx) throws Exception {
        boolean[] reachedLast = {false};
        PageSupplier pages = p -> {
            if (reachedLast[0]) {
                return List.of();
            }
            PixivFetchService.FollowLatestPage page = ctx.fetch().fetchFollowLatestPage(p, ctx.cookie());
            if (page.lastPage()) {
                reachedLast[0] = true;
            }
            return page.ids();
        };
        // 与既有 follow_latest 全量发现一致，页间不强制延迟。
        ctx.watermarkScan(pages, () -> {});
    }
}
