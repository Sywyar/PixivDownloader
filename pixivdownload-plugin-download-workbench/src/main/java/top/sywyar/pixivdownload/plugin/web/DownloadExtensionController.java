package top.sywyar.pixivdownload.plugin.web;

import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import top.sywyar.pixivdownload.plugin.api.web.DownloadAcquisitionMode;
import top.sywyar.pixivdownload.plugin.api.web.DownloadGalleryCapabilities;
import top.sywyar.pixivdownload.plugin.api.web.DownloadQueueCapabilities;
import top.sywyar.pixivdownload.plugin.api.web.DownloadScheduleCapabilities;
import top.sywyar.pixivdownload.plugin.api.web.DownloadTypeDescriptor;
import top.sywyar.pixivdownload.plugin.registry.DownloadExtensionOwner;
import top.sywyar.pixivdownload.plugin.registry.DownloadExtensionRegistry;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 下载工作台扩展点接口：返回 {@link DownloadExtensionRegistry} 单一 owner 原子快照中的已启用作品类型、
 * 获取方式标签页与下载页 Web UI 槽位，供下载页动态装配
 * 队列引擎与槽位（取代页面中对小说等类型的硬编码分支）。
 * <p>
 * 仅暴露前端装配所需字段（类型 id / 标签 i18n key / 排序 / 行为模块 URL / 标签页支持的类型 /
 * 槽位锚点与渲染模块）；文案由前端按当前语言解析。某类型 / 标签页 / 槽位的插件被禁用 / 停用时其不再
 * 出现在响应中，下载页据此隐藏对应交互入口、并把残留队列项标记为暂停。
 * <p>
 * 访问归属：本端点随下载工作台页面消费，其路由由 {@code DownloadWorkbenchPlugin.routes()} 以
 * {@link top.sywyar.pixivdownload.plugin.api.web.AccessPolicy#VISITOR} 显式声明
 *（multi 游客可读 / solo 需会话 / 邀请访客 403 / 不入 monitor，与未声明时的访问行为逐字等价，
 * 声明只为消除「未声明路由」的语义歧义）。本控制器由下载工作台插件子 context 显式装配，
 * 通过父上下文注入宿主维护的原子 registry 快照。
 */
@RestController
@RequestMapping("/api/download/extensions")
@RequiredArgsConstructor
public class DownloadExtensionController {

    private final DownloadExtensionRegistry extensionRegistry;

    @GetMapping
    public ResponseEntity<DownloadExtensionsView> extensions() {
        DownloadExtensionRegistry.Snapshot snapshot = extensionRegistry.snapshot();
        List<QueueTypeView> queueTypes = snapshot.queueTypes().stream()
                .sorted(Comparator.comparingInt(
                                (DownloadExtensionRegistry.RegisteredQueueType item) -> item.queueType().order())
                        .thenComparing(item -> item.queueType().type()))
                .map(item -> new QueueTypeView(
                        item.queueType().type(),
                        item.queueType().labelNamespace(),
                        item.queueType().labelI18nKey(),
                        item.queueType().order(),
                        item.queueType().moduleUrl(),
                        OwnerView.from(item.owner(), item.publicationId())))
                .toList();
        List<DownloadTypeView> downloadTypes = snapshot.queueTypes().stream()
                .sorted(Comparator.comparingInt(
                                (DownloadExtensionRegistry.RegisteredQueueType item) -> item.queueType().order())
                        .thenComparing(item -> item.queueType().type()))
                .map(DownloadTypeView::from)
                .toList();
        List<TabView> tabs = snapshot.tabs().stream()
                .sorted(Comparator.comparingInt(
                                (DownloadExtensionRegistry.RegisteredTab item) -> item.tab().order())
                        .thenComparing(item -> item.tab().tabId()))
                .map(item -> new TabView(
                        item.tab().tabId(),
                        item.tab().order(),
                        item.supportedQueueTypes(),
                        OwnerView.from(item.owner(), item.publicationId())))
                .toList();
        List<UiSlotView> uiSlots = snapshot.uiSlots().stream()
                .sorted(Comparator.comparingInt(
                                (DownloadExtensionRegistry.RegisteredUiSlot item) -> item.slot().order())
                        .thenComparing(item -> item.slot().slotId()))
                .map(item -> new UiSlotView(
                        item.slot().slotId(),
                        item.slot().target(),
                        item.slot().moduleUrl(),
                        item.slot().order(),
                        item.slot().metadata(),
                        OwnerView.from(item.owner(), item.publicationId())))
                .toList();
        DownloadExtensionsView view = new DownloadExtensionsView(
                snapshot.epoch(), snapshot.revision(), queueTypes, downloadTypes, tabs, uiSlots);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(view);
    }

    /** 下载页扩展点对外视图：已启用的作品类型、获取方式标签页与 Web UI 槽位。 */
    public record DownloadExtensionsView(String epoch,
                                          long revision,
                                          List<QueueTypeView> queueTypes,
                                          List<DownloadTypeView> downloadTypes,
                                          List<TabView> tabs,
                                          List<UiSlotView> uiSlots) {
    }

    /** 作品类型对外视图；owner 由宿主注册身份盖章，不信任 descriptor 自报归属。 */
    public record QueueTypeView(String type, String labelNamespace, String labelI18nKey,
                                int order, String moduleUrl, OwnerView owner) {
    }

    /** 标签页对外视图；supportedQueueTypes 已按 descriptor 取得模式推导并应用显式兼容上限。 */
    public record TabView(String tabId, int order, List<String> supportedQueueTypes, OwnerView owner) {
    }

    /** UI 槽位对外视图：只含下载页稳定锚点，并携带宿主盖章的 owner 身份。 */
    public record UiSlotView(String slotId, String target, String moduleUrl, int order,
                             Map<String, String> metadata, OwnerView owner) {
    }

    public record OwnerView(String pluginId, String packageId, long generation, long publicationId) {
        static OwnerView from(DownloadExtensionOwner owner, long publicationId) {
            return new OwnerView(
                    owner.featurePluginId(), owner.packageId(), owner.generation(), publicationId);
        }
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
            GalleryCapabilitiesView gallery,
            boolean legacyContract,
            OwnerView owner
    ) {

        static DownloadTypeView from(DownloadExtensionRegistry.RegisteredQueueType registered) {
            DownloadTypeDescriptor descriptor = registered.queueType().descriptor();
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
                    GalleryCapabilitiesView.from(descriptor.gallery()),
                    registered.queueType().usesLegacyDescriptor(),
                    OwnerView.from(registered.owner(), registered.publicationId()));
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
