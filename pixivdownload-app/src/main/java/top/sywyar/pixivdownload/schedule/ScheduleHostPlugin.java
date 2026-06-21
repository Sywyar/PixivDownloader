package top.sywyar.pixivdownload.schedule;

import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginKind;
import top.sywyar.pixivdownload.plugin.api.web.WebRouteContribution;

import java.util.List;

/**
 * 计划任务宿主插件：拥有计划任务的调度安全壳（tick / claim / 并发池 / 限流 / 熔断 / cookie·proxy 作用域 /
 * 隔离重试 / 水位线推进 / 运行队列 / 错误分类）与 {@code /api/schedule/**} 管理 API。
 * <p>
 * 本插件是<b>必选宿主</b>（{@link #required()} 返回 {@code true}，语义与下载工作台一致）：计划任务调度是核心使用
 * 路径，无法经 {@code plugins.schedule.enabled} 关闭——恒进入活动快照、其托管 Bean（含唯一 {@code @Scheduled}
 * tick {@link ScheduleRunner} 与 {@link ScheduleExecutor} / {@link ScheduleService} / 控制器）恒装配，即便手写
 * {@code plugins.schedule.enabled=false} 也被忽略。
 * <p>
 * <b>宿主只承载调度安全机器，扩展能力由各插件贡献：</b>
 * <ul>
 *   <li><b>来源（怎么找作品）</b>：下载工作台经 {@code scheduledSources()} 贡献其 7 个内置来源；来源执行契约住下载
 *       工作台域（{@code download.schedule.source}），调度壳经来源注册中心解析后向下转型派发。</li>
 *   <li><b>作品类型执行器（下载什么）</b>：下载工作台贡献插画执行器、小说插件贡献小说执行器，均实现核心契约
 *       {@code core.schedule.work.ScheduledWorkRunner}、经注册中心按 {@code kind} 解析；缺执行器时残留任务复用
 *       {@code SOURCE_UNAVAILABLE} 干净挂起，不删除、不偷跑。</li>
 * </ul>
 * 贡献它们的插件被禁用 / 卸载后，对应来源 / 执行器从注册中心消失，残留任务进入不可用态。
 * <p>
 * <b>数据访问只走核心语义接口：</b>{@code scheduled_tasks} / {@code scheduled_task_pending} 表归核心（schema 由核心
 * contribution 保证），调度壳全经核心 owned 语义 Store {@code core.schedule.ScheduledTaskStore} 读写，永远拿不到
 * MyBatis mapper / 池化 DataSource / 裸 Connection / 自由 SQL；故本宿主无核心列使用声明。
 * <p>
 * 计划任务的创建 / 状态管理 UI 目前随下载页（{@code /pixiv-batch}）呈现、经 {@code /api/schedule/**} 与本宿主交互；
 * 独立宿主管理页 / slot 的抽取作为后续工作另行推进。本宿主当前只声明 {@code /api/schedule/**} 路由、不带导航 /
 * i18n / 静态资源等前端贡献。
 */
public class ScheduleHostPlugin implements PixivFeaturePlugin {

    private static final String ID = "schedule";

    @Override
    public String id() {
        return ID;
    }

    // 计划任务宿主必选、永不在配置页「插件」分组呈现，故下列 key 不会被解析（仅为满足契约的占位）。
    @Override
    public String displayName() {
        return "plugin.label";
    }

    @Override
    public String description() {
        return "plugin.summary";
    }

    @Override
    public PluginKind kind() {
        return PluginKind.FEATURE;
    }

    /** 计划任务宿主是必选插件：后台调度 tick 与计划任务管理 API 是核心使用路径，不允许关闭。 */
    @Override
    public boolean required() {
        return true;
    }

    @Override
    public List<WebRouteContribution> routes() {
        // 计划任务管理 API：仅管理员（solo / multi 均仅 monitor）。随宿主插件声明、随其生命周期注册。
        return List.of(WebRouteContribution.admin("/api/schedule/**"));
    }
}
