package top.sywyar.pixivdownload.plugin.api.web;

/**
 * 插件声明的下载队列「作品类型」（work-type 轴：下载什么）。
 * <p>
 * 下载工作台的统一队列引擎按作品类型多态派发：每个类型由其所属插件声明一条本记录，
 * 可由 {@code moduleUrl} 指向的同源前端行为模块在运行期向宿主注册下载行为（判重 / 载荷 / 状态轮询 /
 * 完成处理等钩子）。「数据声明（本记录）+ 行为模块（JS）」分离：非空模块资源由声明插件的 classloader
 * 解析；插件禁用时本记录不再合并，宿主隐藏该类型的交互入口并暂停残留队列项（不报错、不删除）。
 *
 * @param pluginId     声明该类型的插件 id
 * @param type           类型 id（与队列项 {@code kind}、{@link DownloadTypeDescriptor#type()} 共享口径）
 * @param labelNamespace 子模式标签所在的 i18n namespace（在该 namespace 内解析 {@code labelI18nKey}）；<b>必填</b>，
 *                       注册期对 {@code null}/空白 fail-fast（纯 key 需确定 namespace 才能解析，留空会令前端误解析）
 * @param labelI18nKey   子模式（kind 单选）标签的 i18n key（<b>纯 key</b>，不带 namespace、不直接携带文案）
 * @param order          子模式渲染顺序，越小越靠前
 * @param moduleUrl      可选的前端行为模块 URL；非空时必须是同源绝对路径，{@code null} 表示无前端行为模块
 * @param descriptor     下载类型稳定 descriptor；旧构造器会生成最小兼容 descriptor，新类型应显式声明完整能力
 */
public record QueueTypeContribution(
        String pluginId,
        String type,
        String labelNamespace,
        String labelI18nKey,
        int order,
        String moduleUrl,
        DownloadTypeDescriptor descriptor
) {

    public QueueTypeContribution(String pluginId,
                                 String type,
                                 String labelNamespace,
                                 String labelI18nKey,
                                 int order,
                                 String moduleUrl) {
        this(pluginId, type, labelNamespace, labelI18nKey, order, moduleUrl,
                DownloadTypeDescriptor.legacy(pluginId, type, labelNamespace, labelI18nKey, order, moduleUrl));
    }

    /**
     * 是否由 1.0 时代的六参数构造器生成。宿主只用该标记开启受当前后端 publication 与
     * {@code document.currentScript} 双重约束的前端兼容入口；实际取得模式仍由已加载模块中
     * 通过校验的 hook 推导，绝不预先猜测能力。
     */
    public boolean usesLegacyDescriptor() {
        return descriptor != null && descriptor.equals(DownloadTypeDescriptor.legacy(
                pluginId, type, labelNamespace, labelI18nKey, order, moduleUrl));
    }
}
