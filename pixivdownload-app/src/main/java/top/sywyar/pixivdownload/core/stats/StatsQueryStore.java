package top.sywyar.pixivdownload.core.stats;

import java.util.List;

/**
 * 统计仪表盘聚合查询的<b>核心 owned</b> 语义只读门面。
 *
 * <p><b>归属与边界：</b>这是<b>核心 owned 的语义 Query Store / API</b>（住 {@code core.stats}），不是某个插件
 * 私有的数据库实现类。统计插件（{@code stats}）的 {@code StatsController} / {@code StatsService} 均为
 * {@link top.sywyar.pixivdownload.plugin.api.plugin.PluginManagedBean}；它们<b>只依赖本接口</b>表达的聚合查询意图，
 * <b>不</b>触达池化 {@code DataSource}、{@code JdbcTemplate}、MyBatis mapper、裸 {@code Connection} 或可自由拼接的
 * SQL。底层实现（{@code DataSource} + {@code NamedParameterJdbcTemplate} + 聚合 SQL）收口在核心实现层
 * {@code core.stats.db}，对插件不可见——插件不应关心底层是 SQLite、PostgreSQL、MySQL 还是其它存储。
 *
 * <p>本接口<b>只</b>表达统计仪表盘实际需要的只读聚合意图：全库范围、无访客可见性裁剪（统计仪表盘是管理员专属页面），
 * <b>不</b>暴露 mapper accessor、裸连接或自由 SQL 入口。返回值是核心 owned 的纯 JDK record（{@link StatsAggregates}），
 * 核心接口<b>不</b>反向依赖 stats 插件的 {@code StatsDto}——由 {@code StatsService} 在插件侧映射为对外响应 DTO。
 */
public interface StatsQueryStore {

    /** 总览卡片：作品 / 图片 / 已移动来自 {@code statistics} 单行，其余为实时聚合计数。 */
    StatsAggregates.Overview overview();

    /** 下载量最高的作者，按作品数降序。{@code name} 可能为空，由上层回退展示。 */
    List<StatsAggregates.AuthorStat> topAuthors(int limit);

    /** 使用最多的标签，按作品数降序（标签词云 / Top 标签）。 */
    List<StatsAggregates.TagStat> topTags(int limit);

    /** 按月（本地时区）统计下载作品数（插画 + 小说），时间升序。 */
    List<StatsAggregates.MonthlyStat> monthlyArtworkCounts();
}
