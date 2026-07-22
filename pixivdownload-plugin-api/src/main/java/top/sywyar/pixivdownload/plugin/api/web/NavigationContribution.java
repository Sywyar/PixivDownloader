package top.sywyar.pixivdownload.plugin.api.web;

import java.util.Set;

/**
 * 插件声明的导航项。{@code /api/navigation} 按当前用户可见性过滤后返回。
 * <p>
 * 每条导航项显式声明它要进入的一个或多个 <b>placement</b>（slot id，如 {@code app.top} /
 * {@code gallery.sidebar} / {@code gallery.type-switch}）。页面只声明空 slot（{@code data-nav-slot="<placement>"}），
 * slot 的内容完全来自匹配该 placement 的导航贡献——页面不再用 include/exclude 过滤 id 来模拟 slot。
 * 同一逻辑入口可属于多个 placement（如下载工作台同时进入顶部栏与各侧栏），由 {@link #placements()} 表达，
 * 故无需为同一入口重复声明多条。
 * <p>
 * 排序由消费端（{@code NavigationController}）按「来源层级 → placement 内 priority → id」三级稳定排序：
 * 来源层级保证<b>内置插件恒先于第三方插件</b>（第三方即便填很小的 priority 也排在内置项之后），
 * placement 内由 {@link #priority()} 决定先后（内置基础页面取较小值、功能页面其次、管理入口最大）。
 *
 * @param id           导航项全局唯一 id（用于诊断 / 去重 / 前端 {@code PixivNav.isAvailable}）
 * @param placements   该入口要进入的 placement（slot id）集合，非空；同一入口可进入多个 slot
 * @param labelNamespace 标签所在的 i18n namespace（在该 namespace 内解析 {@code labelI18nKey}）；{@code null}/空白是<b>有意的回退
 *                       语义</b>、注册期<b>不</b>fail-fast——表示该入口未绑定确定 namespace，由消费端回退（前端 {@code tns} 退化为
 *                       {@code t()} 裸 key，在页面首个 namespace 内解析）。这与
 *                       {@code PageSectionContribution#titleNamespace} 的「必填、缺省即 fail-fast」语义刻意不同
 * @param labelI18nKey 标签的 i18n key（<b>纯 key</b>，不带 namespace、不直接携带文案）
 * @param href         目标链接（同一 placement 内不可重复）
 * @param icon         图标标识（label-only 的 slot（如类型切换 tab）会忽略它）
 * @param visibleTo    可见所需的访问策略；必须满足 {@link AccessPolicy#supportsUiVisibility()}，Web 注册与 GUI 聚合时拒绝流程专用策略
 * @param priority     placement 内排序权重，越小越靠前（<b>不</b>跨越来源层级：第三方项不会因 priority 小而越过内置项）
 * @param markers      中性语义标记，供前端导览等消费者定位「某类入口」；不参与渲染槽位匹配与排序
 */
public record NavigationContribution(
        String id,
        Set<String> placements,
        String labelNamespace,
        String labelI18nKey,
        String href,
        String icon,
        AccessPolicy visibleTo,
        int priority,
        Set<String> markers
) {
    public NavigationContribution {
        placements = placements == null ? Set.of() : Set.copyOf(placements);
        markers = markers == null ? Set.of() : Set.copyOf(markers);
    }

    /** 兼容构造：不声明语义标记。 */
    public NavigationContribution(String id, Set<String> placements, String labelNamespace, String labelI18nKey,
                                  String href, String icon, AccessPolicy visibleTo, int priority) {
        this(id, placements, labelNamespace, labelI18nKey, href, icon, visibleTo, priority, Set.of());
    }

    /** 便捷构造：单一 placement 的导航项。 */
    public NavigationContribution(String id, String placement, String labelNamespace, String labelI18nKey,
                                  String href, String icon, AccessPolicy visibleTo, int priority) {
        this(id, placement == null ? Set.of() : Set.of(placement), labelNamespace, labelI18nKey,
                href, icon, visibleTo, priority, Set.of());
    }
}
