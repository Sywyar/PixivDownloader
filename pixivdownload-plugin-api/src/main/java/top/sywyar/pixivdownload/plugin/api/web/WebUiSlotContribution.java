package top.sywyar.pixivdownload.plugin.api.web;

import java.util.Map;

/**
 * 插件向某个宿主页面 <b>UI 槽位</b>（mount point）贡献的内容声明。把此前仅存在于前端 JS 的「页面槽位」
 * 机制（宿主页声明稳定的槽位锚点、活动插件把片段注入同名锚点）提升为后端可追踪、可随插件生命周期
 * <b>动态注册 / 注销</b>的契约：每个槽位由其所属插件声明一条本记录，核心 {@code WebUiSlotRegistry}
 * 合并各<b>活动</b>插件的声明并以不可变快照对外暴露（如经下载工作台扩展点接口）。
 * <p>
 * 与 {@link NavigationContribution}（一条导航链接）/ {@link PageSectionContribution}（带标题 / 操作 / 内嵌导航的
 * 复杂区块）并列，本记录承载更细粒度的「单个挂载点」：宿主页只声明稳定的槽位锚点（{@link #target()}），
 * 锚点内渲染什么、由哪个插件渲染、是否渲染，全部由活动插件的本声明决定——宿主<b>不需要知道是哪个插件、
 * 是否启用</b>；禁用 / 停用 / 卸载该插件后其槽位声明自然从快照消失，宿主入口随之缺席（与既有「插件禁用即
 * 入口消失」语义一致）。<b>实际 DOM 片段 / 组件仍由 {@link #moduleUrl()} 指向的前端模块渲染</b>，本记录只声明
 * 「谁、在哪、以何顺序、用哪个模块」的元数据（与 {@link PageSectionContribution} 的 {@code moduleUrl} 同理：
 * 后端声明槽位的存在与归属，复杂内容由贡献方自有 JS 渲染）。
 * <p>
 * <b>信任模型与安全边界</b>：{@link #moduleUrl()} 是<b>已安装、可信插件</b>的<b>同源</b>脚本钩子——由贡献方插件
 * 自有 ClassLoader 提供、经声明的静态资源路由 serving，与宿主页同源、共享执行上下文。它<b>不是</b>不可信第三方
 * 插件的沙箱：本契约不隔离脚本能力，仅约束 URL 形态。{@code moduleUrl} 非空时必须是同源绝对路径（以单个
 * {@code /} 开头；禁止 {@code javascript:} 伪协议、{@code http(s)://} 外部 URL 与 {@code //host} 协议相对 URL），
 * 由 {@code WebUiSlotRegistry} 在注册期校验、违反即启动失败。<b>前端可见性（禁用即消失）只是渲染体验，不是权限
 * 边界</b>——槽位内任何模块 / 其调用的 API，其访问权限仍由后端 {@code AuthFilter} 依据 {@code RouteAccessRegistry}
 * 鉴权。
 *
 * @param pluginId  声明方插件 id（注册时校验与登记方一致）
 * @param slotId    槽位全局唯一 id（用于诊断 / 去重 / 前端定位；建议形如 {@code <plugin>.<target>}）
 * @param target    宿主页面挂载锚点 id（宿主与贡献方之间的稳定契约名，不含具体类型字样；下载页当前以
 *                  {@code <template data-qt-slot="<target>">} 锚点实现）
 * @param moduleUrl 渲染该槽位的前端模块脚本 URL（已安装可信插件的同源脚本钩子，由贡献方自有 ClassLoader 提供
 *                  并 serving）；{@code null} 表示由宿主内联或贡献方已加载的行为模块渲染。非空时必须是同源绝对路径
 *                  （以单个 {@code /} 开头）
 * @param order     同一 {@code target} 内的叠放顺序，越小越靠前（多个插件贡献同一锚点时据此稳定排序）
 * @param metadata  可选扩展元数据（不可变；不提供时为空 map）。供贡献方携带渲染所需的少量声明式提示，
 *                  契约面不约束其语义；不得携带文案（文案走 i18n）或敏感数据
 */
public record WebUiSlotContribution(
        String pluginId,
        String slotId,
        String target,
        String moduleUrl,
        int order,
        Map<String, String> metadata
) {

    public WebUiSlotContribution {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    /** 便捷构造：不带扩展元数据的槽位声明。 */
    public WebUiSlotContribution(String pluginId, String slotId, String target, String moduleUrl, int order) {
        this(pluginId, slotId, target, moduleUrl, order, Map.of());
    }
}
