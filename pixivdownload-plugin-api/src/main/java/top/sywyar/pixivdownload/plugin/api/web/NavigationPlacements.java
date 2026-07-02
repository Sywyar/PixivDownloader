package top.sywyar.pixivdownload.plugin.api.web;

/**
 * 内置导航 placement（slot id）常量：插件 {@link NavigationContribution#placements()} 与页面
 * {@code data-nav-slot="<placement>"} 的共享契约名。集中声明避免各处散落的字符串拼写漂移。
 * <p>
 * placement 表达「这条入口要进哪个菜单 / slot」，页面只声明空 slot、内容全部来自匹配该 placement 的导航贡献。
 * 第三方插件可声明自有 placement 字符串并配套页面 slot；本类只收口内置页面用到的 placement。
 */
public final class NavigationPlacements {

    private NavigationPlacements() {
    }

    /** 顶部应用导航栏（监控页 / 下载工作台页）。 */
    public static final String APP_TOP = "app.top";

    /**
     * 宿主页面的<b>通用侧栏主导航</b> slot：与 {@link #APP_TOP} 同为 plugin-neutral 的宿主 chrome slot
     *（不绑定任何业务插件家族），承载「应进入应用主侧栏」的跨页入口。供本身不属于画廊 / 小说家族、却需要一份
     * 主侧栏导航的宿主页面（如统计页）声明 {@code data-nav-slot="app.sidebar"}；内容完全由注册到本 placement 的
     * 导航贡献决定，宿主不需要知道是哪些插件、是否启用——禁用某插件其入口自然消失。
     * <p>
     * 与 {@link #GALLERY_SIDEBAR} / {@link #NOVEL_SIDEBAR} 的区别：后两者是「画廊 / 小说家族」专属侧栏
     *（各含本家族主入口、不含对方，经类型切换互达），由这两个插件的自有页面使用；{@code APP_SIDEBAR} 不含家族
     * 语义，是宿主中立的主侧栏，相关内置插件把主入口同时贡献到此。
     */
    public static final String APP_SIDEBAR = "app.sidebar";

    /** 画廊家族页面（画廊 / 系列页）的侧栏主导航——含画廊、不含小说（小说经类型切换抵达）。 */
    public static final String GALLERY_SIDEBAR = "gallery.sidebar";

    /** 小说画廊页的侧栏主导航——含小说、不含画廊（画廊经类型切换抵达）。 */
    public static final String NOVEL_SIDEBAR = "novel.sidebar";

    /** 画廊页的「画廊↔小说」类型切换：承载指向小说画廊的入口（由小说插件贡献）。 */
    public static final String GALLERY_TYPE_SWITCH = "gallery.type-switch";

    /** 小说画廊页的「小说↔画廊」类型切换：承载指向画廊的入口（由画廊插件贡献）。 */
    public static final String NOVEL_TYPE_SWITCH = "novel.type-switch";

    /** 疑似重复页顶部的图标入口区（画廊 / 统计图标，由各自插件贡献）。 */
    public static final String DUPLICATES_HEADER_ICONS = "duplicates.header-icons";

    /** 统计页侧栏借用的画廊视图快捷入口（全部 / 按作者 / 按系列，由画廊插件贡献）。 */
    public static final String STATS_GALLERY_LINKS = "stats.gallery-links";

    /**
     * 统计页侧栏的「页面区块」slot（{@code data-section-slot}，由 {@code PageSectionContribution} 供给，
     * <b>非</b>导航 slot）：统计页只声明此空 slot，画廊插件向它贡献「视图」「收藏夹」等借用画廊能力的区块；
     * 禁用画廊后这些区块自然消失，统计页不需要知道画廊。集中声明此名以避免前后端字符串漂移。
     */
    public static final String STATS_SIDEBAR_SECTIONS = "stats.sidebar.sections";

    /** 桌面 GUI 状态页的 Web 快捷操作入口。 */
    public static final String GUI_STATUS_ACTIONS = "gui.status.actions";

    /** 桌面 GUI 托盘菜单的 Web 快捷操作入口。 */
    public static final String GUI_TRAY_ACTIONS = "gui.tray.actions";

    /** 邀请管理页的返回入口 slot。 */
    public static final String INVITE_MANAGE_BACK = "invite.manage.back";
}
