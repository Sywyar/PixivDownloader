package top.sywyar.pixivdownload.download.schedule.source;

import com.fasterxml.jackson.databind.JsonNode;
import top.sywyar.pixivdownload.plugin.api.schedule.ScheduledSourceProvider;

/**
 * 已落库 Pixiv 来源类型迁移与受限旧执行路径使用的本地模型。在
 * {@link ScheduledSourceProvider} 的身份和存量类型映射之上，保留旧路径仍需的发现模式、账号范围、系列合订和通知标签。
 *
 * <p>当前来源能力由 {@code scheduledSourceDescriptors()} 声明，并由 child context 中的
 * {@code ScheduledSourceExecutor} 执行；本接口不是新增来源或第三方插件的执行扩展面。
 */
public interface ScheduledSource extends ScheduledSourceProvider {

    /** 本来源在给定参数下的发现模式（{@code source} 为任务 params 的 {@code source} 子节点）。 */
    DiscoveryMode mode(JsonNode source);

    /** 是否账号私有来源（收藏 / 关注新作 / 珍藏集）：dead cookie 一律挂起、不可匿名续跑。 */
    boolean accountScoped();

    /** 是否在「本轮有新章节」时触发小说系列合订（仅系列来源）。 */
    default boolean seriesMergeApplies() {
        return false;
    }

    /** 通知 / 邮件里本来源类型的本地化标签 i18n key。 */
    String notificationLabelKey();

    /**
     * 发现本来源的作品并经 {@code ctx} 的共享扫描驱动派发下载。
     * {@link DiscoveryMode#COLLECTION} 来源不经此路径（调度壳走独立的珍藏集两遍下载路径）。
     */
    void discoverAndDispatch(ScheduledSourceContext ctx) throws Exception;
}
