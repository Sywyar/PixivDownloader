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
import top.sywyar.pixivdownload.core.download.queue.QueueOperationCommands;
import top.sywyar.pixivdownload.core.download.queue.QueueOperationRegistry;
import top.sywyar.pixivdownload.core.download.queue.QueueOperationUnavailableException;
import top.sywyar.pixivdownload.core.download.response.DownloadResponse;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.plugin.api.web.RequestOwnerIdentity;
import top.sywyar.pixivdownload.plugin.api.web.RequestOwnerIdentityResolver;
import top.sywyar.pixivdownload.plugin.registry.DownloadExtensionOwner;
import top.sywyar.pixivdownload.plugin.registry.DownloadExtensionRegistry;
import top.sywyar.pixivdownload.plugin.registry.DownloadExtensionRegistry.RegisteredDownloadType;

import java.util.Optional;

/**
 * 下载队列宿主操作（取消单项 / 清空队列）。跨作品类型经核心队列宿主注册中心
 * {@link QueueOperationRegistry} 多态派发，本控制器不直接依赖任一具体作品类型下载服务
 *（插画 {@code ArtworkDownloadExecutor} / 小说 {@code NovelDownloadService} 等），消除队列控制器对下载实现的反向耦合。
 * <p>
 * 取消单项必须同时携带 queueType 与不透明 workKey，经 {@link QueueOperationRegistry#resolve(String)}
 * 定向派发给单个队列。旧插画数字 URL 仅作为兼容入口解析 {@code illust}，不会再广播到其它类型。
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

    private final QueueOperationRegistry queueOperationRegistry;
    private final DownloadExtensionRegistry downloadExtensionRegistry;
    private final RequestOwnerIdentityResolver requestOwnerIdentityResolver;
    private final AppMessages messages;

    /** 旧插画数字 URL 兼容入口：仅定向 illust 队列。 */
    @PostMapping({"/cancel/{artworkId}", "/download/cancel/{artworkId}"})
    public ResponseEntity<?> cancelDownload(@PathVariable Long artworkId,
                                            HttpServletRequest httpRequest) {
        RegisteredDownloadType descriptor = downloadExtensionRegistry
                .resolveDownloadType(LEGACY_ILLUST_QUEUE_TYPE).orElse(null);
        if (descriptor == null) {
            return failure(HttpStatus.NOT_FOUND, CODE_DESCRIPTOR_NOT_FOUND);
        }
        if (!descriptor.descriptor().cancelSupported()) {
            return failure(HttpStatus.CONFLICT, CODE_UNSUPPORTED);
        }
        Optional<QueueOperationCommands> operation = resolveOperation(
                LEGACY_ILLUST_QUEUE_TYPE,
                descriptor.owner().featurePluginId(),
                descriptor.owner().packageId(),
                descriptor.owner().generation());
        if (operation.isEmpty()) {
            return failure(HttpStatus.SERVICE_UNAVAILABLE, CODE_OPERATION_UNAVAILABLE);
        }
        RegisteredDownloadType confirmed = downloadExtensionRegistry
                .resolveDownloadType(LEGACY_ILLUST_QUEUE_TYPE).orElse(null);
        if (!samePublication(descriptor, confirmed)
                || !confirmed.descriptor().cancelSupported()) {
            return failure(HttpStatus.CONFLICT, CODE_DESCRIPTOR_STALE);
        }
        return cancel(LEGACY_ILLUST_QUEUE_TYPE, Long.toString(artworkId), operation.get(), httpRequest);
    }

    /** 新稳定入口：按 queueType 定向取消不透明 workKey。 */
    @PostMapping("/download/queue/{queueType}/cancel")
    public ResponseEntity<?> cancelDownload(
            @PathVariable String queueType,
            @RequestBody QueueCancelRequest request,
            HttpServletRequest httpRequest) {
        if (!validRequest(request)) {
            return failure(HttpStatus.BAD_REQUEST, CODE_REQUEST_INVALID);
        }
        QueueCancelOwner requestedOwner = request.owner();
        // 先捕获请求所指代的 operation；descriptor 校验后仍由 registry.cancel 复核同一命令对象是当前项。
        Optional<QueueOperationCommands> operation = resolveOperation(
                queueType,
                requestedOwner.pluginId(),
                requestedOwner.packageId(),
                requestedOwner.generation());
        RegisteredDownloadType descriptor = downloadExtensionRegistry.resolveDownloadType(queueType).orElse(null);
        if (descriptor == null) {
            return failure(HttpStatus.NOT_FOUND, CODE_DESCRIPTOR_NOT_FOUND);
        }
        if (!matches(descriptor, request.owner())) {
            return failure(HttpStatus.CONFLICT, CODE_DESCRIPTOR_STALE);
        }
        if (!descriptor.descriptor().cancelSupported()) {
            return failure(HttpStatus.CONFLICT, CODE_UNSUPPORTED);
        }

        if (operation.isEmpty()) {
            return failure(HttpStatus.SERVICE_UNAVAILABLE, CODE_OPERATION_UNAVAILABLE);
        }
        // 覆盖 operation 捕获与首次 descriptor 校验之间 / 之后的 publication 切换。
        RegisteredDownloadType confirmed = downloadExtensionRegistry.resolveDownloadType(queueType).orElse(null);
        if (confirmed == null || !matches(confirmed, requestedOwner)
                || !confirmed.descriptor().cancelSupported()) {
            return failure(HttpStatus.CONFLICT, CODE_DESCRIPTOR_STALE);
        }
        return cancel(queueType, request.workKey(), operation.get(), httpRequest);
    }

    private Optional<QueueOperationCommands> resolveOperation(
            String queueType,
            String pluginId,
            String packageId,
            long generation) {
        return generation == 0L
                ? queueOperationRegistry.resolveHost(queueType)
                : queueOperationRegistry.resolveOwned(queueType, pluginId, packageId, generation)
                        .map(QueueOperationRegistry.OwnedQueueCommands::commands);
    }

    private boolean validRequest(QueueCancelRequest request) {
        if (request == null || request.workKey() == null || request.workKey().isBlank()
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

    private boolean matches(RegisteredDownloadType descriptor, QueueCancelOwner requested) {
        DownloadExtensionOwner current = descriptor.owner();
        return current.featurePluginId().equals(requested.pluginId())
                && current.packageId().equals(requested.packageId())
                && current.generation() == requested.generation()
                && descriptor.publicationId() == requested.publicationId();
    }

    private boolean samePublication(RegisteredDownloadType expected, RegisteredDownloadType current) {
        return current != null
                && expected.owner().equals(current.owner())
                && expected.publicationId() == current.publicationId();
    }

    private ResponseEntity<?> cancel(
            String queueType,
            String workKey,
            QueueOperationCommands operation,
            HttpServletRequest httpRequest) {
        RequestOwnerIdentity identity = requestOwnerIdentityResolver.resolve(httpRequest);
        try {
            queueOperationRegistry.cancel(
                    queueType, operation, workKey, identity.ownerUuid(), identity.admin());
        } catch (QueueOperationUnavailableException unavailable) {
            return failure(HttpStatus.SERVICE_UNAVAILABLE, CODE_OPERATION_UNAVAILABLE);
        }
        return cancelledResponse();
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
        int cleared = 0;
        for (QueueOperationCommands operations : queueOperationRegistry.all()) {
            cleared += identity.admin()
                    ? operations.clearAll()
                    : operations.clearForOwner(identity.ownerUuid());
        }
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
