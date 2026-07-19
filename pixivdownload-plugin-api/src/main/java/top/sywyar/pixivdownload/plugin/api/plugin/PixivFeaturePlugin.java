package top.sywyar.pixivdownload.plugin.api.plugin;

import top.sywyar.pixivdownload.plugin.api.maintenance.MaintenanceTask;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigContribution;
import top.sywyar.pixivdownload.plugin.api.gui.GuiOnboardingStepContribution;
import top.sywyar.pixivdownload.plugin.api.gui.GuiThemeContribution;
import top.sywyar.pixivdownload.plugin.api.schedule.ScheduledSourceProvider;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledSourceDescriptor;
import top.sywyar.pixivdownload.plugin.api.schema.SchemaContribution;
import top.sywyar.pixivdownload.plugin.api.web.DrilldownContribution;
import top.sywyar.pixivdownload.plugin.api.web.I18nContribution;
import top.sywyar.pixivdownload.plugin.api.web.LandingContribution;
import top.sywyar.pixivdownload.plugin.api.web.NavigationContribution;
import top.sywyar.pixivdownload.plugin.api.web.PageSectionContribution;
import top.sywyar.pixivdownload.plugin.api.web.QueueTypeContribution;
import top.sywyar.pixivdownload.plugin.api.web.StartupRouteContribution;
import top.sywyar.pixivdownload.plugin.api.web.StaticResourceContribution;
import top.sywyar.pixivdownload.plugin.api.web.TabContribution;
import top.sywyar.pixivdownload.plugin.api.web.UserscriptContribution;
import top.sywyar.pixivdownload.plugin.api.web.WebRouteContribution;
import top.sywyar.pixivdownload.plugin.api.web.WebUiSlotContribution;

import java.util.List;

/**
 * 功能插件主接口。插件通过本接口向核心声明需要统一合并的信息
 * （schema、路由、i18n、导航、静态资源等）；业务 Bean 仍由各插件的
 * {@code @Configuration} 显式装配，不经本接口返回。
 * <p>
 * 实现类不得携带任何 Spring 注解（插件实例可能由非 Spring 的插件管理器创建），
 * 由每插件一个的 {@code XxxPluginConfiguration} 以 {@code @Bean} 形式提供。
 * <p>
 * 维护任务目前仍经 Spring 自动发现注册到 {@code MaintenanceCoordinator}，
 * 不经本接口声明。
 */
public interface PixivFeaturePlugin {

    /** 插件唯一 id，小写短横线风格，例如 {@code download-workbench}。 */
    String id();

    /**
     * 展示名称的 i18n key（<b>纯 key</b>，不带 namespace、不直接携带文案）；namespace 由 {@link #displayNamespace()}
     * 提供（与导航 {@code NavigationContribution} 的「namespace 与 key 分离」模型一致）。<b>必须由插件显式声明</b>、
     * 无默认实现。消费端（Web 插件管理页 / GUI 配置面板）按当前语言，在 {@link #displayNamespace()} 指定的 namespace
     * （{@link #i18n()} 贡献的 bundle，如 {@code i18n/web/<namespace>.properties}）中解析为本地化文案——文案归插件
     * 所有、不落在核心 GUI bundle 里。推荐使用专用身份 key（官方插件统一为 {@code plugin.name}）；导航标签
     * {@code nav.label}、页面标题和表单字段标签属于各自上下文文案，不应作为插件身份的 canonical source。
     */
    String displayName();

    /**
     * 一句话简介的 i18n key（<b>纯 key</b>，语义同 {@link #displayName()}：在 {@link #displayNamespace()} 指定的
     * namespace 中解析；<b>必须由插件显式声明</b>）。
     */
    String description();

    /**
     * {@link #displayName()} / {@link #description()} 所在的 i18n namespace。默认取本插件 {@link #i18n()} 声明的
     * <b>第一个</b> namespace——插件展示文案通常就放在自有首个 namespace；无 i18n 贡献时返回 {@code null}（消费端
     * 无从解析、回退到插件 id）。无单一自有内容 namespace 的插件（如核心壳、计划任务宿主，其展示文案借用插件管理页
     * 的 {@code plugins} namespace）覆写本方法显式返回承载其展示文案的 namespace。
     */
    default String displayNamespace() {
        List<I18nContribution> contributions = i18n();
        return contributions.isEmpty() ? null : contributions.get(0).namespace();
    }

    /** {@link #iconKey()} 未声明时的默认受控 token。 */
    String DEFAULT_ICON_KEY = "puzzle";

    /** {@link #colorToken()} 未声明时的默认受控 token。 */
    String DEFAULT_COLOR_TOKEN = "neutral";

    /**
     * 展示图标的<b>受控 token</b>（如 {@code download} / {@code book}）：仅一个稳定标识符，<b>不是</b> URL / SVG / HTML /
     * 远程资源，由消费端（Web 插件管理页 / 插件市场）在共享白名单内映射为图标。白名单外的未知 token 一律回退到
     * {@value #DEFAULT_ICON_KEY}，原始 token 绝不被当作图标类名 / 标记直接渲染。默认 {@value #DEFAULT_ICON_KEY}；
     * 插件可覆写为白名单内的其它 token。
     */
    default String iconKey() {
        return DEFAULT_ICON_KEY;
    }

    /**
     * 卡片强调色的<b>受控 token</b>（如 {@code blue} / {@code green}）：仅一个稳定标识符，<b>不是</b>任意 CSS 颜色，
     * 由消费端映射为固定的 CSS class / data 属性。白名单外的未知 token 一律回退到 {@value #DEFAULT_COLOR_TOKEN}。
     * 默认 {@value #DEFAULT_COLOR_TOKEN}。颜色仅用于卡片的可扫描性，<b>不</b>构成主题系统。
     */
    default String colorToken() {
        return DEFAULT_COLOR_TOKEN;
    }

    /** 插件类别。 */
    PluginKind kind();

    /**
     * 是否为必选插件。必选插件无法经 {@code plugins.<id>.enabled} 关闭：恒进入活动快照、其托管 Bean 恒装配，
     * 即便配置或手工把开关写成 {@code false} 也会被忽略。核心插件（{@link PluginKind#CORE}）恒为必选；
     * 功能插件默认可禁用，必选的功能插件覆写本方法返回 {@code true}。
     */
    default boolean required() {
        return kind() == PluginKind.CORE;
    }

    /**
     * 生命周期：启动。注册中心在应用启动（或插件被安装启用）时调用一次。
     */
    default void start() {
    }

    /**
     * 生命周期：停止。必须幂等，并负责释放该插件的全部注册与在途工作；
     * 除应用关闭外，运行期卸载插件时也会调用。
     */
    default void stop() {
    }

    /** 插件声明的自有表、补列与路径列规则；所有权由宿主按已注册插件身份盖章。 */
    default List<SchemaContribution> schema() {
        return List.of();
    }

    /** 插件声明的路由与访问级别。 */
    default List<WebRouteContribution> routes() {
        return List.of();
    }

    /** 插件声明的静态资源目录。 */
    default List<StaticResourceContribution> staticResources() {
        return List.of();
    }

    /** 插件声明的 i18n namespace。 */
    default List<I18nContribution> i18n() {
        return List.of();
    }

    /** 插件声明的导航项。 */
    default List<NavigationContribution> navigation() {
        return List.of();
    }

    /** 插件声明的默认启动落点（{@code /redirect} 据此选定落点页）。 */
    default List<StartupRouteContribution> startupRoutes() {
        return List.of();
    }

    /**
     * 插件声明的默认落点 / 入口（landing entrypoint）：供业务流程按<b>身份</b>解析默认跳转目标，
     * 例如受邀访客兑换邀请码成功后的落地页。与导航排序解耦：落点选择只消费
     * {@link LandingContribution#priority()}（landing/entrypoint 优先级），<b>不</b>复用
     * {@link NavigationContribution#priority()}（导航展示顺序）——两者是各自独立的契约，第三方插件即便注册一个
     * 导航 priority 极小的项也无法间接改变业务落点，必须显式声明 {@link LandingContribution} 才参与落点竞争。
     */
    default List<LandingContribution> landings() {
        return List.of();
    }

    /**
     * 插件声明的页面区块 / slot 贡献（{@code /api/page-sections} 按当前身份过滤后返回）：让宿主页面只声明稳定的
     * section slot，复杂区块（标题 / 操作入口 / 内嵌导航 slot / 由贡献方自有 JS 渲染的列表）的内容完全由活动插件
     * 注册——宿主不需要知道是哪个插件、是否启用。禁用插件后其 section 自然消失。
     */
    default List<PageSectionContribution> pageSections() {
        return List.of();
    }

    /**
     * 插件声明的 Web UI 槽位贡献（mount point）：让宿主页面只声明稳定的槽位锚点，锚点内渲染什么、由哪个插件
     * 渲染、是否渲染，全部由活动插件声明——宿主不需要知道是哪个插件、是否启用。经核心 {@code WebUiSlotRegistry}
     * 合并、随插件生命周期动态注册 / 注销；禁用 / 停用插件后其槽位自然从快照消失、宿主入口缺席。复杂内容仍由
     * {@link WebUiSlotContribution#moduleUrl()} 指向的前端模块渲染。
     */
    default List<WebUiSlotContribution> uiSlots() {
        return List.of();
    }

    /** 插件声明的 GUI 主题贡献。主题应用由宿主在 AWT 事件分发线程上调度。 */
    default List<GuiThemeContribution> guiThemes() {
        return List.of();
    }

    /** 插件声明的宿主 GUI 配置字段贡献。贡献只包含纯数据，由宿主聚合后渲染。 */
    default List<GuiConfigContribution> guiConfigContributions() {
        return List.of();
    }

    /** 插件声明的宿主 GUI 引导步骤。贡献只包含纯数据，由宿主聚合后渲染。 */
    default List<GuiOnboardingStepContribution> guiOnboardingSteps() {
        return List.of();
    }

    /**
     * 插件声明的语义下钻贡献（{@code /api/drilldowns} 按当前身份过滤后返回）：让宿主页面只在语义 placement 上以运行期
     * 变量请求一个 href，跨插件下钻链接的目标页面路径 / 查询参数名完全由活动插件以 {@link DrilldownContribution#hrefTemplate()}
     * 决定——宿主不需要知道是哪个插件。禁用插件后其下钻贡献自然消失，宿主回到纯展示。
     */
    default List<DrilldownContribution> drilldowns() {
        return List.of();
    }

    /** 插件声明的油猴脚本扫描来源（由分发该脚本的插件声明）。 */
    default List<UserscriptContribution> userscripts() {
        return List.of();
    }

    /**
     * 已发布的轻量计划来源身份 SPI。为保持 1.0 插件二进制兼容继续保留；新执行平台通过
     * {@link #scheduledSourceDescriptors()} 声明完整纯数据描述符，宿主可为旧 provider 建兼容适配。
     */
    default List<ScheduledSourceProvider> scheduledSources() {
        return List.of();
    }

    /**
     * 插件声明的完整计划来源描述符。来源、作品、凭证与 Guard 的行为实现由插件 child context 中对应的
     * schedule executor Bean 提供，不允许把运行 Bean、context 或插件私有对象捕获进 descriptor。
     */
    default List<ScheduledSourceDescriptor> scheduledSourceDescriptors() {
        return List.of();
    }

    /** 插件贡献的下载队列作品类型（work-type 轴：下载什么；由提供该类型的插件声明）。 */
    default List<QueueTypeContribution> queueTypes() {
        return List.of();
    }

    /** 插件贡献的下载工作台获取方式标签页（acquisition 轴：怎么找作品；由提供该标签页的插件声明）。 */
    default List<TabContribution> downloadTabs() {
        return List.of();
    }

    /**
     * 插件声明其拥有的维护任务类型。维护协调器据此把交互 / 聚合 / 展示型维护任务归属到插件——
     * 插件被禁用时跳过它声明的任务；不被任何插件声明的维护任务视为核心任务、始终执行。
     * 维护任务实现仍由各插件的 {@code XxxPluginConfiguration} 装配为 Bean，本方法只声明归属关系。
     */
    default List<Class<? extends MaintenanceTask>> maintenanceTasks() {
        return List.of();
    }
}
