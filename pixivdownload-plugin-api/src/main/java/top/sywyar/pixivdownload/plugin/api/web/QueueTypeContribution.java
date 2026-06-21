package top.sywyar.pixivdownload.plugin.api.web;

/**
 * 插件声明的下载队列「作品类型」（work-type 轴：下载什么）。
 * <p>
 * 下载工作台的统一队列引擎按作品类型多态派发：每个类型由其所属插件声明一条本记录，
 * 并由 {@code moduleUrl} 指向的前端行为模块在运行期向宿主注册下载行为（判重 / 载荷 / 状态轮询 /
 * 完成处理等钩子）。「数据声明（本记录）+ 行为模块（JS）」分离：插件禁用时本记录不再合并，
 * 宿主据此隐藏该类型的交互入口、并把残留队列项标记为暂停（不报错、不删除）。
 * <p>
 * 内置类型（如插画）由宿主直接内联注册行为、{@code moduleUrl} 为 {@code null}；外部贡献的
 * 类型（如小说）的行为模块由其声明插件的 classloader 提供（打进同一 jar 时即该 jar 内的静态资源，
 * 物理拆分为独立插件 jar 后随该插件 classloader 解析）。
 *
 * @param pluginId     声明该类型的插件 id
 * @param type         类型 id（与队列项 {@code kind}、{@code ScheduledSourceProvider} 共享口径，如 {@code illust} / {@code novel}）
 * @param labelI18nKey 子模式（kind 单选）标签的 i18n key（不直接携带文案）
 * @param order        子模式渲染顺序，越小越靠前
 * @param moduleUrl    前端行为模块 URL（内置类型为 {@code null}：宿主内联注册）
 */
public record QueueTypeContribution(
        String pluginId,
        String type,
        String labelI18nKey,
        int order,
        String moduleUrl
) {
}
