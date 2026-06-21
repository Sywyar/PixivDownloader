package top.sywyar.pixivdownload.plugin;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import top.sywyar.pixivdownload.plugin.api.web.QueueTypeContribution;
import top.sywyar.pixivdownload.plugin.api.web.TabContribution;

import java.util.Comparator;
import java.util.List;

/**
 * 下载工作台扩展点接口：返回 {@link QueueTypeRegistry} / {@link DownloadTabRegistry} 合并后的
 * 已启用作品类型与获取方式标签页，供下载页动态装配队列引擎（取代页面中对小说等类型的硬编码分支）。
 * <p>
 * 仅暴露前端装配所需字段（类型 id / 标签 i18n key / 排序 / 行为模块 URL / 标签页支持的类型）；
 * 文案由前端按当前语言解析。某类型 / 标签页的插件被禁用时其不再出现在响应中，下载页据此隐藏对应
 * 交互入口、并把残留队列项标记为暂停。
 * <p>
 * 访问归属：本端点随下载工作台页面消费，其路由由 {@code DownloadWorkbenchPlugin.routes()} 以
 * {@link top.sywyar.pixivdownload.plugin.api.web.AccessPolicy#VISITOR} 显式声明
 *（multi 访客可读 / solo 需会话 / 邀请访客 403 / 不入 monitor，与未声明时的访问行为逐字等价，
 * 声明只为消除「未声明路由」的语义歧义）。本控制器自身是核心基础设施 Bean（与队列类型 / 标签页注册中心
 * 同住核心 {@code plugin} 包、根包扫描装配），路由归属与 Bean 物理位置可不一致——与下载状态等核心路由同理。
 */
@RestController
@RequestMapping("/api/download/extensions")
@RequiredArgsConstructor
public class DownloadExtensionController {

    private final QueueTypeRegistry queueTypeRegistry;
    private final DownloadTabRegistry downloadTabRegistry;

    @GetMapping
    public DownloadExtensionsView extensions() {
        List<QueueTypeView> queueTypes = queueTypeRegistry.queueTypes().stream()
                .map(QueueTypeRegistry.RegisteredQueueType::queueType)
                .sorted(Comparator.comparingInt(QueueTypeContribution::order)
                        .thenComparing(QueueTypeContribution::type))
                .map(item -> new QueueTypeView(
                        item.type(), item.labelI18nKey(), item.order(), item.moduleUrl()))
                .toList();
        List<TabView> tabs = downloadTabRegistry.tabs().stream()
                .map(DownloadTabRegistry.RegisteredTab::tab)
                .sorted(Comparator.comparingInt(TabContribution::order)
                        .thenComparing(TabContribution::tabId))
                .map(item -> new TabView(item.tabId(), item.order(), item.supportedQueueTypes()))
                .toList();
        return new DownloadExtensionsView(queueTypes, tabs);
    }

    /** 下载页扩展点对外视图：已启用的作品类型与获取方式标签页。 */
    public record DownloadExtensionsView(List<QueueTypeView> queueTypes, List<TabView> tabs) {
    }

    /** 作品类型对外视图：刻意不含 {@code pluginId}（内部归属）。 */
    public record QueueTypeView(String type, String labelI18nKey, int order, String moduleUrl) {
    }

    /** 标签页对外视图：刻意不含 {@code pluginId}（内部归属）。 */
    public record TabView(String tabId, int order, List<String> supportedQueueTypes) {
    }
}
