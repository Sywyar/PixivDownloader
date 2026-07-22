package top.sywyar.pixivdownload.plugin.web;

import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import top.sywyar.pixivdownload.plugin.api.download.type.DownloadAcquisitionMode;
import top.sywyar.pixivdownload.plugin.api.download.type.DownloadTypeDescriptor;
import top.sywyar.pixivdownload.plugin.registry.DownloadExtensionOwner;
import top.sywyar.pixivdownload.plugin.registry.DownloadExtensionRegistry;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 下载工作台扩展点接口：返回 {@link DownloadExtensionRegistry} 单一 owner 原子快照中的下载类型
 * 与下载页 Web UI 槽位，供下载页动态装配队列行为与槽位。
 * <p>
 * 类型 descriptor 只暴露稳定的下载类型字段；owner 与 publication 身份由宿主盖章。类型专属的队列、
 * 计划、画廊或槽位能力不进入该响应，UI 槽位只来自独立的活动 publication。
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
        List<DownloadTypeView> downloadTypes = snapshot.downloadTypes().stream()
                .sorted(Comparator.comparingInt(
                                (DownloadExtensionRegistry.RegisteredDownloadType item) ->
                                        item.descriptor().order())
                        .thenComparing(item -> item.descriptor().type()))
                .map(DownloadTypeView::from)
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
                        Map.of(),
                        OwnerView.from(item.owner(), item.publicationId())))
                .toList();
        DownloadExtensionsView view = new DownloadExtensionsView(
                snapshot.epoch(), snapshot.revision(), downloadTypes, uiSlots);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(view);
    }

    /** 下载页扩展点对外视图：已启用的下载类型与独立发布的 Web UI 槽位。 */
    public record DownloadExtensionsView(String epoch,
                                          long revision,
                                          List<DownloadTypeView> downloadTypes,
                                          List<UiSlotView> uiSlots) {
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

    /** 下载类型稳定 descriptor 视图；owner 由宿主注册身份盖章，不信任 descriptor 自报归属。 */
    public record DownloadTypeView(
            int contractVersion,
            String type,
            String displayNamespace,
            String displayI18nKey,
            int order,
            String iconKey,
            String colorToken,
            String moduleUrl,
            List<String> acquisitionModes,
            boolean cancelSupported,
            List<String> filters,
            List<String> settings,
            String i18nNamespace,
            OwnerView owner
    ) {

        static DownloadTypeView from(DownloadExtensionRegistry.RegisteredDownloadType registered) {
            DownloadTypeDescriptor descriptor = registered.descriptor();
            return new DownloadTypeView(
                    descriptor.contractVersion(),
                    descriptor.type(),
                    descriptor.displayNamespace(),
                    descriptor.displayI18nKey(),
                    descriptor.order(),
                    descriptor.iconKey(),
                    descriptor.colorToken(),
                    descriptor.moduleUrl(),
                    descriptor.acquisitionModes().stream().map(DownloadAcquisitionMode::code).toList(),
                    descriptor.cancelSupported(),
                    descriptor.filters(),
                    descriptor.settings(),
                    descriptor.i18nNamespace(),
                    OwnerView.from(registered.owner(), registered.publicationId()));
        }
    }
}
