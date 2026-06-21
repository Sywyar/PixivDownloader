package top.sywyar.pixivdownload.plugin.api.web;

/**
 * 内置「下钻」（drilldown）placement（slot id）常量：宿主页面与贡献方插件共享的语义契约名。集中声明避免各处
 * 散落的字符串拼写漂移。
 * <p>
 * 与 {@link NavigationPlacements} 同为「语义 placement」机制，但承载的是<b>带变量的下钻链接模板</b>而非固定导航项：
 * 宿主页面在某语义 placement 上以一组运行期变量（如作者 id / 标签名）请求一个 href，由活动插件经
 * {@link DrilldownContribution#hrefTemplate()} 决定模板。宿主页面<b>只认得语义 placement</b>（如 {@code stats.top-authors}），
 * 不需要知道是哪个插件、目标页面路径或查询参数名；禁用贡献方插件后该 placement 没有贡献，宿主回到纯展示。
 * 第三方插件可声明自有 placement 字符串并由对应宿主页面消费；本类只收口内置宿主页面用到的 placement。
 */
public final class DrilldownPlacements {

    private DrilldownPlacements() {
    }

    /**
     * 统计页「下载量 Top 作者」列表项的下钻 placement：宿主（统计页）以作者 id / 名称为变量请求一个 href，
     * 由活动插件（当前为画廊）贡献「按作者过滤画廊」的链接模板。无贡献时统计页保持纯展示。
     */
    public static final String STATS_TOP_AUTHORS = "stats.top-authors";

    /**
     * 统计页「热门标签」标签项的下钻 placement：宿主（统计页）以标签 id / 名称 / 译名为变量请求一个 href，
     * 由活动插件（当前为画廊）贡献「按标签过滤画廊」的链接模板。无贡献时统计页保持纯展示。
     */
    public static final String STATS_TOP_TAGS = "stats.top-tags";
}
