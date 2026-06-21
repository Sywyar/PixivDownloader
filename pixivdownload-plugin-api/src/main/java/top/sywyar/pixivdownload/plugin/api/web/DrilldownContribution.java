package top.sywyar.pixivdownload.plugin.api.web;

import java.util.Set;

/**
 * 插件向某个语义 placement 贡献的「下钻链接模板」（drilldown）。{@code /api/drilldowns} 按当前请求身份过滤后返回。
 * <p>
 * 与 {@link NavigationContribution}（一条固定链接）、{@link PageSectionContribution}（一块区块）不同，下钻贡献是
 * 一个<b>带变量占位</b>的 href 模板：宿主页面只声明语义 placement（如 {@code stats.top-authors}），在渲染某条记录时
 * 以一组运行期变量（如作者 id / 名称）向该 placement 请求一个具体 href。模板里的 {@code {变量名}} 占位由前端通用
 * 下钻渲染器（{@code /js/pixiv-drilldowns.js}）做 {@code encodeURIComponent} 后替换。宿主<b>不需要知道是哪个插件、
 * 目标页面路径或查询参数名</b>——这些只存在于贡献方插件与运行期响应里；禁用贡献方插件后该 placement 没有贡献，
 * 宿主拿不到 href、自然回到纯展示。
 * <p>
 * 可见性与排序口径同 {@link NavigationContribution} / {@link PageSectionContribution}：经 {@link #visibleTo()} 按当前
 * 身份过滤、按「来源层级（内置先于第三方）→ placement 内 {@link #priority()} → id」排序。同一 placement 命中多条时，
 * 渲染器取排序后的首条（胜者）。
 * <p>
 * <b>信任模型与安全边界</b>：{@link #hrefTemplate()} 是<b>已安装、可信插件</b>声明的<b>同源</b>链接模板，必须是同源
 * 绝对路径（以单个 {@code /} 开头；禁止 {@code javascript:} 伪协议、{@code http(s)://} 外部 URL 与 {@code //host} /
 * {@code /\host} 协议相对变体），由 {@code DrilldownRegistry} 在注册期校验、违反即启动失败；变量经
 * {@code encodeURIComponent} 编码后替换，故无法越权改写 origin 或注入查询参数。<b>前端可见性（按身份过滤、禁用即
 * 消失）只是渲染体验，不是权限边界</b>——下钻链接指向的目标 URL（如画廊页）其访问权限仍由后端 {@code AuthFilter}
 * 依据 {@code RouteAccessRegistry} 鉴权；某下钻对当前身份隐藏，不代表其目标 URL 在后端开放，反之亦然。
 *
 * @param pluginId     声明方插件 id（注册时校验与登记方一致）
 * @param id           下钻贡献全局唯一 id（用于诊断 / 去重）
 * @param placements   该下钻要进入的语义 placement（slot id）集合，非空；同一模板可服务多个 placement
 * @param hrefTemplate 带 {@code {变量名}} 占位的目标链接模板；必须是同源绝对路径（以单个 {@code /} 开头）
 * @param visibleTo    可见所需的访问策略（与 {@code /api/drilldowns} 的可见性过滤对照）
 * @param priority     placement 内排序权重，越小越靠前（不跨越来源层级：第三方贡献不会因 priority 小而越过内置贡献）
 */
public record DrilldownContribution(
        String pluginId,
        String id,
        Set<String> placements,
        String hrefTemplate,
        AccessPolicy visibleTo,
        int priority
) {
    public DrilldownContribution {
        placements = placements == null ? Set.of() : Set.copyOf(placements);
    }

    /** 便捷构造：单一 placement 的下钻贡献。 */
    public DrilldownContribution(String pluginId, String id, String placement, String hrefTemplate,
                                 AccessPolicy visibleTo, int priority) {
        this(pluginId, id, placement == null ? Set.of() : Set.of(placement), hrefTemplate, visibleTo, priority);
    }
}
