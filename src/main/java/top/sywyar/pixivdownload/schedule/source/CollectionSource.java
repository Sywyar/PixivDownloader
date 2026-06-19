package top.sywyar.pixivdownload.schedule.source;

import com.fasterxml.jackson.databind.JsonNode;
import top.sywyar.pixivdownload.core.schedule.ScheduledTaskType;

/**
 * 珍藏集来源（{@code COLLECTION}）：账号私有，插画 + 小说混合。发现成员后分两遍各自走对应下载管线，
 * 由调度壳的独立珍藏集路径处理（{@link DiscoveryMode#COLLECTION}）——成员级 kind 不入任务 params、靠本轮
 * 发现的成员关系区分，与单 kind 的发现 / 派发流程不同。故本来源只承载身份与谓词，
 * {@link #discoverAndDispatch} 不参与（调度壳按 {@link #mode} 短路到珍藏集路径）。
 */
public final class CollectionSource extends AbstractScheduledSource {

    public CollectionSource() {
        super(ScheduledTaskType.COLLECTION);
    }

    @Override
    public DiscoveryMode mode(JsonNode source) {
        return DiscoveryMode.COLLECTION;
    }

    @Override
    public boolean accountScoped() {
        return true;
    }

    @Override
    public String notificationLabelKey() {
        return "mail.template.common.task-type.collection";
    }

    @Override
    public void discoverAndDispatch(ScheduledSourceContext ctx) {
        // 珍藏集走调度壳独立的两遍混合下载路径（按 mode()==COLLECTION 短路），不经共享扫描驱动。
        throw new IllegalStateException("COLLECTION source dispatched via the dedicated collection path");
    }
}
