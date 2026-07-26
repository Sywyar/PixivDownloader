package top.sywyar.pixivdownload.download.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import top.sywyar.pixivdownload.download.response.DownloadResponse;
import top.sywyar.pixivdownload.i18n.MessageResolver;
import top.sywyar.pixivdownload.plugin.api.download.control.DownloadControlPlane;
import top.sywyar.pixivdownload.plugin.api.download.control.DownloadExtensionIdentity;
import top.sywyar.pixivdownload.plugin.api.download.control.DownloadQueueCancelCommand;
import top.sywyar.pixivdownload.plugin.api.download.control.DownloadQueueCancelResult;
import top.sywyar.pixivdownload.plugin.api.web.RequestOwnerIdentity;
import top.sywyar.pixivdownload.plugin.api.web.RequestOwnerIdentityResolver;

/**
 * 下载队列宿主操作（取消单项 / 清空队列）。跨作品类型经稳定 {@link DownloadControlPlane} 多态派发，
 * 本控制器不直接依赖宿主 registry 或任一具体作品类型下载服务
 *（插画 {@code ArtworkDownloadExecutor} / 小说 {@code NovelDownloadService} 等），消除队列控制器对下载实现的反向耦合。
 * <p>
 * 取消单项必须同时携带 queueType 与不透明 workKey，并由控制面定向派发给单个队列。旧插画数字 URL
 * 仅作为兼容入口解析 {@code illust}，不会再广播到其它类型。
 * 清空队列对全部在场作品类型成立——某作品类型插件被禁 /
 * 卸载时其操作缺席，清空只作用于在场类型。solo / multi / admin / 访客的归属语义保持不变。
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class DownloadQueueController {

    private static final String LEGACY_ILLUST_QUEUE_TYPE = "illust";
    private static final int MAX_WORK_KEY_LENGTH = 4096;
    private static final int MAX_OWNER_ID_LENGTH = 512;

    private static final String CODE_REQUEST_INVALID = "QUEUE_CANCEL_REQUEST_INVALID";
    private static final String CODE_DESCRIPTOR_NOT_FOUND = "QUEUE_CANCEL_DESCRIPTOR_NOT_FOUND";
    private static final String CODE_DESCRIPTOR_STALE = "QUEUE_CANCEL_DESCRIPTOR_STALE";
    private static final String CODE_UNSUPPORTED = "QUEUE_CANCEL_UNSUPPORTED";
    private static final String CODE_OPERATION_UNAVAILABLE = "QUEUE_CANCEL_OPERATION_UNAVAILABLE";

    private final DownloadControlPlane downloadControlPlane;
    private final RequestOwnerIdentityResolver requestOwnerIdentityResolver;
    private final MessageResolver messages;

    /** 旧插画数字 URL 兼容入口：仅定向 illust 队列。 */
    @PostMapping({"/cancel/{artworkId}", "/download/cancel/{artworkId}"})
    public ResponseEntity<?> cancelDownload(@PathVariable Long artworkId,
                                            HttpServletRequest httpRequest) {
        DownloadQueueCancelResult result = downloadControlPlane.cancelCurrent(
                LEGACY_ILLUST_QUEUE_TYPE,
                Long.toString(artworkId),
                () -> requestOwnerIdentityResolver.resolve(httpRequest));
        return cancelResponse(result);
    }

    /** 新稳定入口：按 queueType 定向取消不透明 workKey。 */
    @PostMapping("/download/queue/{queueType}/cancel")
    public ResponseEntity<?> cancelDownload(
            @PathVariable String queueType,
            @RequestBody QueueCancelRequest request,
            HttpServletRequest httpRequest) {
        if (!validRequest(queueType, request)) {
            return failure(HttpStatus.BAD_REQUEST, CODE_REQUEST_INVALID);
        }
        QueueCancelOwner requestedOwner = request.owner();
        DownloadQueueCancelCommand command = new DownloadQueueCancelCommand(
                queueType,
                request.workKey(),
                new DownloadExtensionIdentity(
                        requestedOwner.pluginId(),
                        requestedOwner.packageId(),
                        requestedOwner.generation(),
                        requestedOwner.publicationId()));
        DownloadQueueCancelResult result = downloadControlPlane.cancelExact(
                command,
                () -> requestOwnerIdentityResolver.resolve(httpRequest));
        return cancelResponse(result);
    }

    private boolean validRequest(String queueType, QueueCancelRequest request) {
        if (queueType == null || queueType.isBlank()
                || request == null || request.workKey() == null || request.workKey().isBlank()
                || request.workKey().length() > MAX_WORK_KEY_LENGTH || request.owner() == null) {
            return false;
        }
        QueueCancelOwner owner = request.owner();
        return validOwnerPart(owner.pluginId())
                && validOwnerPart(owner.packageId())
                && owner.generation() >= 0L
                && owner.publicationId() > 0L;
    }

    private boolean validOwnerPart(String value) {
        return value != null && !value.isBlank() && value.length() <= MAX_OWNER_ID_LENGTH;
    }

    private ResponseEntity<?> cancelResponse(DownloadQueueCancelResult result) {
        return switch (result) {
            case CANCELLED -> cancelledResponse();
            case DESCRIPTOR_NOT_FOUND -> failure(HttpStatus.NOT_FOUND, CODE_DESCRIPTOR_NOT_FOUND);
            case DESCRIPTOR_STALE -> failure(HttpStatus.CONFLICT, CODE_DESCRIPTOR_STALE);
            case UNSUPPORTED -> failure(HttpStatus.CONFLICT, CODE_UNSUPPORTED);
            case OPERATION_UNAVAILABLE ->
                    failure(HttpStatus.SERVICE_UNAVAILABLE, CODE_OPERATION_UNAVAILABLE);
        };
    }

    private ResponseEntity<DownloadResponse> cancelledResponse() {
        return ResponseEntity.ok(DownloadResponse.builder()
                .success(true)
                .message(messages.get("download.cancelled"))
                .build());
    }

    private ResponseEntity<QueueCancelFailure> failure(HttpStatus status, String code) {
        return ResponseEntity.status(status).body(new QueueCancelFailure(
                false, code, messages.get("error.request.param.invalid")));
    }

    @PostMapping("/download/queue/clear")
    public ResponseEntity<DownloadResponse> clearDownloadQueue(HttpServletRequest httpRequest) {
        // 与取消共用同一可信请求作用域：访客仅清自己，admin / solo 清全部。
        RequestOwnerIdentity identity = requestOwnerIdentityResolver.resolve(httpRequest);
        int cleared = downloadControlPlane.clearQueues(identity);
        return ResponseEntity.ok(DownloadResponse.builder()
                .success(true)
                .message(messages.get("download.queue-cleared", String.valueOf(cleared)))
                .build());
    }

    public record QueueCancelRequest(String workKey, QueueCancelOwner owner) {
    }

    public record QueueCancelOwner(
            String pluginId,
            String packageId,
            long generation,
            long publicationId) {
    }

    public record QueueCancelFailure(boolean success, String code, String message) {
    }

}
