package top.sywyar.pixivdownload.schedule.source;

import com.fasterxml.jackson.databind.JsonNode;
import top.sywyar.pixivdownload.plugin.api.schedule.ScheduledSourceProvider;

/**
 * 计划任务来源的「执行契约」：在 plugin.api 的身份级 {@link ScheduledSourceProvider}（{@code type} +
 * legacy 映射）之上，附加调度壳派发本来源所需的发现 / 模式 / 谓词行为。
 *
 * <p>本接口住下载工作台域（{@code schedule.source}）、<b>不入</b> plugin.api——执行契约放在合适边界、
 * plugin.api 仅保留轻量身份 SPI。调度壳经来源注册中心把任务存量 {@code type} 解析到
 * {@link ScheduledSourceProvider}，再向下转型为本接口派发；当前 7 个内置来源全部由下载工作台贡献并实现本接口。
 * 由此调度主编排不再用任何按 {@link top.sywyar.pixivdownload.core.schedule.ScheduledTaskType} 枚举的 switch
 * 调具体来源实现，发现 / 模式判定 / 账号私有判定 / 通知标签全部由各来源对象承载。
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
