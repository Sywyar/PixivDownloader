package top.sywyar.pixivdownload.plugin.api.schedule;

import java.util.Set;

/**
 * 计划任务来源 SPI。一个 provider 对应一类可调度的作品来源
 * （画师新作 / 搜索 / 系列 / 收藏 / 关注新作 / 珍藏集 ...）。
 *
 * <p>本接口只承载来源的「身份」与「legacy 类型映射」：
 * <ul>
 *   <li>{@link #type()} 是规范类型字符串（小写短横线，如 {@code user-new}），全局唯一；</li>
 *   <li>{@link #legacyTypeNames()} 列出该来源历史上使用过的类型字符串（{@code scheduled_tasks.type}
 *       列里已存的枚举名，如 {@code USER_NEW}），供调度器把存量任务的 type 兼容映射到本 provider。</li>
 * </ul>
 * 来源的发现 / 派发执行语义（{@code run(...)} 及其上下文 / 结果类型）由调度器承载、不在本接口表达；
 * 本 SPI 仅为来源的身份与 legacy 映射，是对调度能力的纯增量补充。
 *
 * <p>实现可由下载 / 小说等插件分别贡献，经各插件
 * {@code PixivFeaturePlugin#scheduledSources()} 声明、由核心注册中心合并。为支持 PF4J 热插拔，
 * 本接口及其全部参数 / 返回类型须由父 ClassLoader 共享，故必须保持纯 JDK、无框架与业务包依赖。
 */
public interface ScheduledSourceProvider {

    /** 规范类型字符串（小写短横线，全局唯一）。 */
    String type();

    /**
     * 该来源历史上使用过的类型字符串（如数据库 {@code type} 列已存的枚举名）。
     * 用于把既有任务的存量 type 兼容映射到本 provider；无历史别名时返回空集合。
     */
    default Set<String> legacyTypeNames() {
        return Set.of();
    }
}
