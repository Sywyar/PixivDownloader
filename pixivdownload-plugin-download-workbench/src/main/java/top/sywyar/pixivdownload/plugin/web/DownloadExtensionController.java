package top.sywyar.pixivdownload.plugin.web;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import top.sywyar.pixivdownload.plugin.api.web.DownloadAcquisitionMode;
import top.sywyar.pixivdownload.plugin.api.web.DownloadGalleryCapabilities;
import top.sywyar.pixivdownload.plugin.api.web.DownloadQueueCapabilities;
import top.sywyar.pixivdownload.plugin.api.web.DownloadScheduleCapabilities;
import top.sywyar.pixivdownload.plugin.api.web.DownloadTypeDescriptor;
import top.sywyar.pixivdownload.plugin.api.web.QueueTypeContribution;
import top.sywyar.pixivdownload.plugin.api.web.TabContribution;
import top.sywyar.pixivdownload.plugin.api.web.WebUiSlotContribution;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import top.sywyar.pixivdownload.plugin.registry.DownloadTabRegistry;
import top.sywyar.pixivdownload.plugin.registry.QueueTypeRegistry;
import top.sywyar.pixivdownload.plugin.registry.WebUiSlotRegistry;

/**
 * 下载工作台扩展点接口：返回 {@link QueueTypeRegistry} / {@link DownloadTabRegistry} /
 * {@link WebUiSlotRegistry} 合并后的已启用作品类型、获取方式标签页与 Web UI 槽位，供下载页动态装配
 * 队列引擎与槽位（取代页面中对小说等类型的硬编码分支）。
 * <p>
 * 仅暴露前端装配所需字段（类型 id / 标签 i18n key / 排序 / 行为模块 URL / 标签页支持的类型 /
 * 槽位锚点与渲染模块）；文案由前端按当前语言解析。某类型 / 标签页 / 槽位的插件被禁用 / 停用时其不再
 * 出现在响应中，下载页据此隐藏对应交互入口、并把残留队列项标记为暂停。
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
    private final WebUiSlotRegistry webUiSlotRegistry;

    @GetMapping
    public DownloadExtensionsView extensions() {
        List<QueueTypeView> queueTypes = queueTypeRegistry.queueTypes().stream()
                .map(QueueTypeRegistry.RegisteredQueueType::queueType)
                .sorted(Comparator.comparingInt(QueueTypeContribution::order)
                        .thenComparing(QueueTypeContribution::type))
                .map(item -> new QueueTypeView(
                        item.type(), item.labelNamespace(), item.labelI18nKey(), item.order(), item.moduleUrl()))
                .toList();
        List<DownloadTypeView> downloadTypes = queueTypeRegistry.queueTypes().stream()
                .map(QueueTypeRegistry.RegisteredQueueType::queueType)
                .sorted(Comparator.comparingInt(QueueTypeContribution::order)
                        .thenComparing(QueueTypeContribution::type))
                .map(QueueTypeContribution::descriptor)
                .map(DownloadTypeView::from)
                .toList();
        List<TabView> tabs = downloadTabRegistry.tabs().stream()
                .map(DownloadTabRegistry.RegisteredTab::tab)
                .sorted(Comparator.comparingInt(TabContribution::order)
                        .thenComparing(TabContribution::tabId))
                .map(item -> new TabView(item.tabId(), item.order(), item.supportedQueueTypes()))
                .toList();
        List<UiSlotView> uiSlots = webUiSlotRegistry.slots().stream()
                .map(WebUiSlotRegistry.RegisteredUiSlot::slot)
                .sorted(Comparator.comparingInt(WebUiSlotContribution::order)
                        .thenComparing(WebUiSlotContribution::slotId))
                .map(item -> new UiSlotView(
                        item.slotId(), item.target(), item.moduleUrl(), item.order(), item.metadata()))
                .toList();
        return new DownloadExtensionsView(queueTypes, downloadTypes, tabs, uiSlots);
    }

    /** 下载页扩展点对外视图：已启用的作品类型、获取方式标签页与 Web UI 槽位。 */
    public record DownloadExtensionsView(List<QueueTypeView> queueTypes,
                                          List<DownloadTypeView> downloadTypes,
                                          List<TabView> tabs,
                                          List<UiSlotView> uiSlots) {
    }

    /** 作品类型对外视图：刻意不含 {@code pluginId}（内部归属）。 */
    public record QueueTypeView(String type, String labelNamespace, String labelI18nKey, int order, String moduleUrl) {
    }

    /** 标签页对外视图：刻意不含 {@code pluginId}（内部归属）。 */
    public record TabView(String tabId, int order, List<String> supportedQueueTypes) {
    }

    /** UI 槽位对外视图：槽位 id / 宿主锚点 / 渲染模块 / 排序 / 元数据；刻意不含 {@code pluginId}（内部归属）。 */
    public record UiSlotView(String slotId, String target, String moduleUrl, int order, Map<String, String> metadata) {
    }

    /** 下载类型稳定 descriptor 视图：供前端行为模块按版本化契约自检能力与错误语义。 */
    public record DownloadTypeView(
            int contractVersion,
            String pluginId,
            String type,
            String displayNamespace,
            String displayI18nKey,
            int order,
            String iconKey,
            String colorToken,
            String moduleUrl,
            List<String> acquisitionModes,
            QueueCapabilitiesView queue,
            ScheduleCapabilitiesView schedule,
            List<String> filters,
            List<String> settings,
            List<String> uiSlots,
            String i18nNamespace,
            GalleryCapabilitiesView gallery
    ) {

        static DownloadTypeView from(DownloadTypeDescriptor descriptor) {
            return new DownloadTypeView(
                    descriptor.contractVersion(),
                    descriptor.pluginId(),
                    descriptor.type(),
                    descriptor.displayNamespace(),
                    descriptor.displayI18nKey(),
                    descriptor.order(),
                    descriptor.iconKey(),
                    descriptor.colorToken(),
                    descriptor.moduleUrl(),
                    descriptor.acquisitionModes().stream().map(DownloadAcquisitionMode::code).toList(),
                    QueueCapabilitiesView.from(descriptor.queue()),
                    ScheduleCapabilitiesView.from(descriptor.schedule()),
                    descriptor.filters(),
                    descriptor.settings(),
                    descriptor.uiSlots(),
                    descriptor.i18nNamespace(),
                    GalleryCapabilitiesView.from(descriptor.gallery()));
        }
    }

    public record QueueCapabilitiesView(boolean clearAll, boolean clearForOwner, boolean cancel) {
        static QueueCapabilitiesView from(DownloadQueueCapabilities capabilities) {
            return new QueueCapabilitiesView(
                    capabilities.clearAll(),
                    capabilities.clearForOwner(),
                    capabilities.cancel());
        }
    }

    public record ScheduleCapabilitiesView(boolean saveable,
                                           boolean sourceSerializable,
                                           boolean suspendWhenExecutorMissing) {
        static ScheduleCapabilitiesView from(DownloadScheduleCapabilities capabilities) {
            return new ScheduleCapabilitiesView(
                    capabilities.saveable(),
                    capabilities.sourceSerializable(),
                    capabilities.suspendWhenExecutorMissing());
        }
    }

    public record GalleryCapabilitiesView(boolean unifiedGallery,
                                          boolean independentPage,
                                          String reasonNamespace,
                                          String reasonI18nKey) {
        static GalleryCapabilitiesView from(DownloadGalleryCapabilities capabilities) {
            return new GalleryCapabilitiesView(
                    capabilities.unifiedGallery(),
                    capabilities.independentPage(),
                    capabilities.reasonNamespace(),
                    capabilities.reasonI18nKey());
        }
    }
}
