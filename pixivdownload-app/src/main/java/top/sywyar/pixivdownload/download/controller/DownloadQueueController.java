package top.sywyar.pixivdownload.download.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import top.sywyar.pixivdownload.common.UuidUtils;
import top.sywyar.pixivdownload.core.download.queue.QueueOperations;
import top.sywyar.pixivdownload.core.download.queue.QueueOperationRegistry;
import top.sywyar.pixivdownload.download.response.DownloadResponse;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.setup.SetupService;

/**
 * 下载队列宿主操作（取消单项 / 清空队列）。跨作品类型经核心队列宿主注册中心
 * {@link QueueOperationRegistry} 多态派发，本控制器不直接依赖任一具体作品类型下载服务
 *（插画 {@code ArtworkDownloadExecutor} / 小说 {@code NovelDownloadService} 等），消除队列控制器对下载实现的反向耦合。
 * <p>
 * 取消单项是插画作品类型支持的能力（小说无单项取消入口）：遍历在场操作适配器调 {@link QueueOperations#cancel}，
 * 不支持的类型默认空实现，行为与现状（仅取消插画）逐字一致。清空队列对全部在场作品类型成立——某作品类型插件被禁 /
 * 卸载时其操作缺席，清空只作用于在场类型。solo / multi / admin / 访客的归属语义保持不变。
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class DownloadQueueController {

    private final QueueOperationRegistry queueOperationRegistry;
    private final SetupService setupService;
    private final AppMessages messages;

    //取消下载
    @PostMapping({"/cancel/{artworkId}", "/download/cancel/{artworkId}"})
    public ResponseEntity<DownloadResponse> cancelDownload(@PathVariable Long artworkId,
                                                           HttpServletRequest httpRequest) {
        boolean admin = setupService.hasAdminScope(httpRequest);
        // admin / solo 取消该作品在所有 owner 的下载（ownerUuid 被忽略）；否则仅取消归属当前 owner 的。
        String ownerUuid = admin ? null : extractUserUuid(httpRequest);
        for (QueueOperations operations : queueOperationRegistry.all()) {
            operations.cancel(artworkId, ownerUuid, admin);
        }
        return ResponseEntity.ok(DownloadResponse.builder()
                .success(true)
                .message(messages.get("download.cancelled"))
                .build());
    }

    @PostMapping("/download/queue/clear")
    public ResponseEntity<DownloadResponse> clearDownloadQueue(HttpServletRequest httpRequest) {
        // 多人模式下的访客只能强制清除自己（owner）的下载；solo 模式或多人模式下已登录的管理员清除全部，
        // 与 cancelDownload 的归属语义保持一致。跨作品类型经注册中心遍历在场操作适配器，逐类型清空后累加。
        boolean perOwner = "multi".equals(setupService.getMode()) && !setupService.isAdminLoggedIn(httpRequest);
        String ownerUuid = perOwner ? extractUserUuid(httpRequest) : null;
        int cleared = 0;
        for (QueueOperations operations : queueOperationRegistry.all()) {
            cleared += perOwner ? operations.clearForOwner(ownerUuid) : operations.clearAll();
        }
        return ResponseEntity.ok(DownloadResponse.builder()
                .success(true)
                .message(messages.get("download.queue-cleared", String.valueOf(cleared)))
                .build());
    }

    /** 提取用户 UUID：优先 cookie，其次 X-User-UUID 请求头，最后基于 IP+UA 生成 */
    private String extractUserUuid(HttpServletRequest req) {
        return UuidUtils.extractOrGenerateUuid(req);
    }
}
