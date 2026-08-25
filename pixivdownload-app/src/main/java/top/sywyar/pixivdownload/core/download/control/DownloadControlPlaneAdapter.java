package top.sywyar.pixivdownload.core.download.control;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.core.download.queue.QueueOperationCommands;
import top.sywyar.pixivdownload.core.download.queue.QueueOperationRegistry;
import top.sywyar.pixivdownload.core.download.queue.QueueOperationUnavailableException;
import top.sywyar.pixivdownload.plugin.api.download.control.DownloadControlPlane;
import top.sywyar.pixivdownload.plugin.api.download.control.DownloadExtensionIdentity;
import top.sywyar.pixivdownload.plugin.api.download.control.DownloadExtensionSnapshot;
import top.sywyar.pixivdownload.plugin.api.download.control.DownloadQueueCancelCommand;
import top.sywyar.pixivdownload.plugin.api.download.control.DownloadQueueCancelResult;
import top.sywyar.pixivdownload.plugin.api.download.control.DownloadTypePublication;
import top.sywyar.pixivdownload.plugin.api.download.control.DownloadUiSlotPublication;
import top.sywyar.pixivdownload.plugin.api.web.RequestOwnerIdentity;
import top.sywyar.pixivdownload.plugin.registry.download.DownloadExtensionOwner;
import top.sywyar.pixivdownload.plugin.registry.download.DownloadExtensionRegistry;
import top.sywyar.pixivdownload.plugin.registry.download.DownloadExtensionRegistry.RegisteredDownloadType;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * 把宿主下载扩展与队列 registry 投影为稳定 Plugin API 控制面。
 *
 * <p>registry 实现、队列命令代理及 capability publication 始终留在宿主；插件只能取得不可变扩展值和
 * 稳定结果枚举。单项取消在捕获命令后、调用命令前再次复核 descriptor publication，并由队列 registry
 * 最后按命令对象身份拒绝 replacement。
 */
@Component
@RequiredArgsConstructor
public final class DownloadControlPlaneAdapter implements DownloadControlPlane {

    private final QueueOperationRegistry queueOperationRegistry;
    private final DownloadExtensionRegistry downloadExtensionRegistry;

    @Override
    public DownloadExtensionSnapshot extensions() {
        DownloadExtensionRegistry.Snapshot current = downloadExtensionRegistry.snapshot();
        return new DownloadExtensionSnapshot(
                current.epoch(),
                current.revision(),
                current.downloadTypes().stream()
                        .map(item -> new DownloadTypePublication(
                                identity(item.owner(), item.publicationId()),
                                item.descriptor()))
                        .toList(),
                current.uiSlots().stream()
                        .map(item -> new DownloadUiSlotPublication(
                                identity(item.owner(), item.publicationId()),
                                item.slot()))
                        .toList());
    }

    @Override
    public DownloadQueueCancelResult cancelExact(
            DownloadQueueCancelCommand command,
            Supplier<RequestOwnerIdentity> requestOwner) {
        Objects.requireNonNull(command, "download queue cancel command");
        Objects.requireNonNull(requestOwner, "request owner supplier");

        DownloadExtensionIdentity expected = command.expectedPublication();
        Optional<QueueOperationCommands> operation = resolveOperation(command.queueType(), expected);
        RegisteredDownloadType descriptor =
                downloadExtensionRegistry.resolveDownloadType(command.queueType()).orElse(null);
        if (descriptor == null) {
            return DownloadQueueCancelResult.DESCRIPTOR_NOT_FOUND;
        }
        if (!matches(descriptor, expected)) {
            return DownloadQueueCancelResult.DESCRIPTOR_STALE;
        }
        if (!descriptor.descriptor().cancelSupported()) {
            return DownloadQueueCancelResult.UNSUPPORTED;
        }
        if (operation.isEmpty()) {
            return DownloadQueueCancelResult.OPERATION_UNAVAILABLE;
        }
        RegisteredDownloadType confirmed =
                downloadExtensionRegistry.resolveDownloadType(command.queueType()).orElse(null);
        if (!matches(confirmed, expected)
                || !confirmed.descriptor().cancelSupported()) {
            return DownloadQueueCancelResult.DESCRIPTOR_STALE;
        }
        RequestOwnerIdentity identity = Objects.requireNonNull(
                requestOwner.get(), "resolved request owner");
        // Request identity resolution may cross a plugin lifecycle boundary.
        RegisteredDownloadType finalDescriptor =
                downloadExtensionRegistry.resolveDownloadType(command.queueType()).orElse(null);
        if (!matches(finalDescriptor, expected)
                || !finalDescriptor.descriptor().cancelSupported()) {
            return DownloadQueueCancelResult.DESCRIPTOR_STALE;
        }
        return invokeCancel(command.queueType(), command.workKey(), operation.get(), identity);
    }

    @Override
    public int clearQueues(RequestOwnerIdentity requestOwner) {
        Objects.requireNonNull(requestOwner, "request owner");
        int cleared = 0;
        for (QueueOperationCommands operations : queueOperationRegistry.all()) {
            cleared += requestOwner.admin()
                    ? operations.clearAll()
                    : operations.clearForOwner(requestOwner.ownerUuid());
        }
        return cleared;
    }

    private Optional<QueueOperationCommands> resolveOperation(
            String queueType,
            DownloadExtensionIdentity expected) {
        return expected.generation() == 0L
                ? queueOperationRegistry.resolveHost(queueType)
                : queueOperationRegistry.resolveOwned(
                                queueType,
                                expected.pluginId(),
                                expected.packageId(),
                                expected.generation())
                        .map(QueueOperationRegistry.OwnedQueueCommands::commands);
    }

    private DownloadQueueCancelResult invokeCancel(
            String queueType,
            String workKey,
            QueueOperationCommands operation,
            RequestOwnerIdentity identity) {
        try {
            queueOperationRegistry.cancel(
                    queueType, operation, workKey, identity.ownerUuid(), identity.admin());
        } catch (QueueOperationUnavailableException unavailable) {
            return DownloadQueueCancelResult.OPERATION_UNAVAILABLE;
        }
        return DownloadQueueCancelResult.CANCELLED;
    }

    private static DownloadExtensionIdentity identity(
            DownloadExtensionOwner owner,
            long publicationId) {
        return new DownloadExtensionIdentity(
                owner.featurePluginId(), owner.packageId(), owner.generation(), publicationId);
    }

    private static boolean matches(
            RegisteredDownloadType descriptor,
            DownloadExtensionIdentity expected) {
        if (descriptor == null) {
            return false;
        }
        DownloadExtensionOwner current = descriptor.owner();
        return current.featurePluginId().equals(expected.pluginId())
                && current.packageId().equals(expected.packageId())
                && current.generation() == expected.generation()
                && descriptor.publicationId() == expected.publicationId();
    }

}
