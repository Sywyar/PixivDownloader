package top.sywyar.pixivdownload.core.stats;

import java.util.List;

/**
 * 统计仪表盘聚合查询的<b>核心 owned</b> 语义只读门面。
 *
 * <p><b>归属与边界：</b>这是核心 owned 的语义 Query Store，不是某个消费者私有的数据库实现类。
 * 消费者只依赖本接口表达的聚合查询意图，不触达连接池、mapper、裸连接或自由 SQL。底层实现留在宿主，
 * 对调用方不可见。
 *
 * <p>本接口<b>只</b>表达统计仪表盘实际需要的只读聚合意图：全库范围、无访客可见性裁剪（统计仪表盘是管理员专属页面），
 * <b>不</b>暴露 mapper accessor、裸连接或自由 SQL 入口。返回值是核心 owned 的纯 JDK record（{@link StatsAggregates}），
 * 核心接口不反向依赖任何消费者的响应 DTO，由消费者自行投影。
 */
public interface StatsQueryStore {

    /** 总览卡片所需的作品、图片、已移动及其它聚合计数。 */
    StatsAggregates.Overview overview();

    /** 下载量最高的作者，按作品数降序。{@code name} 可能为空，由上层回退展示。 */
    List<StatsAggregates.AuthorStat> topAuthors(int limit);

    /** 使用最多的标签，按作品数降序（标签词云 / Top 标签）。 */
    List<StatsAggregates.TagStat> topTags(int limit);

    /** 按月（本地时区）统计下载作品数（插画 + 小说），时间升序。 */
    List<StatsAggregates.MonthlyStat> monthlyArtworkCounts();
}
