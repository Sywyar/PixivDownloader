package top.sywyar.pixivdownload.plugin.api.web;

/**
 * 插件向某个宿主页面 placement 贡献的「页面区块」（section / slot block）。{@code /api/page-sections}
 * 按当前请求身份过滤后返回。宿主页面只声明稳定的 section slot（{@code data-section-slot="<placement>"}），
 * 区块的标题 / 可选操作入口 / 可选内嵌导航 slot / 可选由贡献方自有 JS 渲染的复杂列表，全部由活动插件贡献——
 * 宿主<b>不需要知道是哪个插件、是否启用</b>；禁用该插件后其区块（及内嵌导航、操作入口、列表）自然消失。
 * <p>
 * 比 {@link NavigationContribution} 承载更复杂的区块：导航项只是「一条链接」，区块可带标题、操作按钮、
 * 一个内嵌导航 slot（{@link #navPlacement()}，其链接复用匹配该 placement 的导航贡献渲染）以及一个前端模块钩子
 * （{@link #moduleUrl()}，由贡献方自有脚本渲染列表等复杂内容）。各可选字段为 {@code null} 表示不提供。
 * <p>
 * 可见性与排序口径同 {@link NavigationContribution}：经 {@link #visibleTo()} 按当前身份过滤、按
 * 「来源层级 → placement 内 priority → id」排序。
 * <p>
 * <b>信任模型与安全边界</b>：{@link #moduleUrl()} 是<b>已安装、可信插件</b>的<b>同源</b>脚本钩子——它由贡献方
 * 插件自有 ClassLoader 提供、经声明的静态资源路由 serving，通用渲染器以 {@code <script src=moduleUrl>} 加载，
 * 因此与宿主页同源、共享同一执行上下文。它<b>不是</b>不可信第三方插件的沙箱：本契约不隔离脚本能力，仅约束
 * URL 形态。{@code actionHref} / {@code moduleUrl} 非空时必须是同源绝对路径（以单个 {@code /} 开头；禁止
 * {@code javascript:} 伪协议、{@code http(s)://} 外部 URL 与 {@code //host} 协议相对 URL），由
 * {@code PageSectionRegistry} 在注册期校验、违反即启动失败。<b>前端可见性（按身份过滤、禁用即消失）只是渲染体验，
 * 不是权限边界</b>——区块内任何 href / moduleUrl / 其调用的 API，其访问权限仍由后端 {@code AuthFilter} 依据
 * {@code RouteAccessRegistry} 的路由访问策略鉴权；某区块对当前身份隐藏，不代表其 URL 在后端开放。
 *
 * @param pluginId           声明方插件 id（注册时校验与登记方一致）
 * @param id                 区块全局唯一 id（用于诊断 / 去重 / 前端模块定位自身容器）
 * @param placement          宿主页面 slot id（如 {@code stats.sidebar.sections}）
 * @param titleNamespace     区块标题所在的 i18n namespace（在该 namespace 内解析 {@code titleI18nKey}）；<b>必填</b>，
 *                           注册期对 {@code null}/空白 fail-fast（纯 key 需确定 namespace 才能解析）
 * @param titleI18nKey       区块标题的 i18n key（<b>纯 key</b>，不带 namespace）
 * @param navPlacement       可选：区块内嵌的导航 slot placement（其链接由匹配该 placement 的导航贡献供给），{@code null} 表示无
 * @param actionHref         可选：区块标题右侧操作入口的目标链接（如「新建」），{@code null} 表示无操作入口；非空时必须是同源绝对路径（以 {@code /} 开头）
 * @param actionIcon         可选：操作入口图标 token，{@code null} 表示无
 * @param actionTitleNamespace 操作入口标题所在 i18n namespace（在该 namespace 内解析 {@code actionTitleI18nKey}）；随
 *                             {@code actionTitleI18nKey} 条件必填——后者为空时可为 {@code null}（无操作标题），后者非空时<b>必填</b>，注册期对该组合 fail-fast
 * @param actionTitleI18nKey 可选：操作入口 title / aria-label 的 i18n key（<b>纯 key</b>），{@code null} 表示无
 * @param moduleUrl          可选：渲染区块复杂内容的前端模块脚本 URL（已安装可信插件的同源脚本钩子，由贡献方自有 ClassLoader 提供并 serving），{@code null} 表示无；非空时必须是同源绝对路径（以 {@code /} 开头）
 * @param visibleTo          可见所需的访问策略；必须满足 {@link AccessPolicy#supportsUiVisibility()}，注册期拒绝流程专用策略
 * @param priority           placement 内排序权重，越小越靠前（不跨越来源层级：第三方区块不会因 priority 小而越过内置区块）
 */
public record PageSectionContribution(
        String pluginId,
        String id,
        String placement,
        String titleNamespace,
        String titleI18nKey,
        String navPlacement,
        String actionHref,
        String actionIcon,
        String actionTitleNamespace,
        String actionTitleI18nKey,
        String moduleUrl,
        AccessPolicy visibleTo,
        int priority
) {
}
