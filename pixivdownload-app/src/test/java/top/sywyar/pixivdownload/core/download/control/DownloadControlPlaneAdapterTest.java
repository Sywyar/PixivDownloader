package top.sywyar.pixivdownload.core.download.control;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.core.download.queue.QueueOperationCommands;
import top.sywyar.pixivdownload.core.download.queue.QueueOperationOwner;
import top.sywyar.pixivdownload.core.download.queue.QueueOperationRegistry;
import top.sywyar.pixivdownload.core.download.queue.QueueOperationRegistry.PreparedQueueOperations;
import top.sywyar.pixivdownload.plugin.api.download.control.DownloadExtensionIdentity;
import top.sywyar.pixivdownload.plugin.api.download.control.DownloadExtensionSnapshot;
import top.sywyar.pixivdownload.plugin.api.download.control.DownloadQueueCancelCommand;
import top.sywyar.pixivdownload.plugin.api.download.control.DownloadQueueCancelResult;
import top.sywyar.pixivdownload.plugin.api.download.queue.QueueOperations;
import top.sywyar.pixivdownload.plugin.api.download.type.DownloadTypeDescriptor;
import top.sywyar.pixivdownload.plugin.api.web.RequestOwnerIdentity;
import top.sywyar.pixivdownload.plugin.api.web.WebUiSlotContribution;
import top.sywyar.pixivdownload.plugin.registry.DownloadExtensionOwner;
import top.sywyar.pixivdownload.plugin.registry.DownloadExtensionRegistry;
import top.sywyar.pixivdownload.plugin.registry.DownloadExtensionRegistry.RegisteredDownloadType;
import top.sywyar.pixivdownload.plugin.registry.DownloadExtensionRegistry.RegisteredUiSlot;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("DownloadControlPlaneAdapter 稳定下载控制面适配")
class DownloadControlPlaneAdapterTest {

    @Test
    @DisplayName("扩展 registry 快照投影为不携带宿主实现类型的不可变值")
    void extensionsProjectRegistrySnapshotToStableValues() {
        DownloadExtensionRegistry extensions = mock(DownloadExtensionRegistry.class);
        DownloadExtensionOwner owner = new DownloadExtensionOwner("demo", "demo-package", 4L);
        RegisteredDownloadType type = descriptor(owner, 17L, "demo", true);
        RegisteredUiSlot slot = new RegisteredUiSlot(
                owner,
                17L,
                new WebUiSlotContribution("demo.settings", "settings-card", "/demo/settings.js", 5));
        when(extensions.snapshot()).thenReturn(new DownloadExtensionRegistry.Snapshot(
                "host-epoch", 9L, List.of(type), List.of(slot)));
        DownloadControlPlaneAdapter adapter =
                new DownloadControlPlaneAdapter(new QueueOperationRegistry(List.of()), extensions);

        DownloadExtensionSnapshot snapshot = adapter.extensions();

        assertThat(snapshot.epoch()).isEqualTo("host-epoch");
        assertThat(snapshot.revision()).isEqualTo(9L);
        assertThat(snapshot.downloadTypes()).singleElement().satisfies(published -> {
            assertThat(published.owner()).isEqualTo(
                    new DownloadExtensionIdentity("demo", "demo-package", 4L, 17L));
            assertThat(published.descriptor()).isSameAs(type.descriptor());
        });
        assertThat(snapshot.uiSlots()).singleElement().satisfies(published -> {
            assertThat(published.owner().publicationId()).isEqualTo(17L);
            assertThat(published.slot()).isSameAs(slot.slot());
        });
    }

    @Test
    @DisplayName("精确取消保留不透明作品键并惰性解析 owner")
    void exactCancelPreservesOpaqueWorkKeyAndResolvesOwnerLazily() {
        QueueOperations operations = mock(QueueOperations.class);
        when(operations.queueType()).thenReturn("novel");
        QueueOperationRegistry queues = new QueueOperationRegistry(List.of(operations));
        DownloadExtensionRegistry extensions = mock(DownloadExtensionRegistry.class);
        RegisteredDownloadType descriptor = descriptor(
                new DownloadExtensionOwner("host-novel", "host-novel", 0L),
                2L, "novel", true);
        when(extensions.resolveDownloadType("novel")).thenReturn(Optional.of(descriptor));
        DownloadControlPlaneAdapter adapter = new DownloadControlPlaneAdapter(queues, extensions);
        AtomicInteger ownerReads = new AtomicInteger();
        String ownerUuid = "11111111-1111-1111-1111-111111111111";
        String workKey = " opaque/path:part ? # 中文 ";

        DownloadQueueCancelResult result = adapter.cancelExact(
                command(workKey, descriptor),
                () -> {
                    ownerReads.incrementAndGet();
                    return RequestOwnerIdentity.owner(ownerUuid);
                });

        assertThat(result).isEqualTo(DownloadQueueCancelResult.CANCELLED);
        assertThat(ownerReads).hasValue(1);
        verify(operations).cancel(workKey, ownerUuid, false);
    }

    @Test
    @DisplayName("精确取消调用当前外置 operation 并保留完整 publication")
    void exactCancelInvokesCurrentExternalOperation() {
        QueueOperationRegistry queues = new QueueOperationRegistry(List.of());
        QueueOperations operations = mock(QueueOperations.class);
        queues.registerPrepared(
                new QueueOperationOwner("external", "external-package", 6L, 13L),
                List.of(prepared("external-work", operations)));
        DownloadExtensionRegistry extensions = mock(DownloadExtensionRegistry.class);
        RegisteredDownloadType descriptor = descriptor(
                new DownloadExtensionOwner("external", "external-package", 6L),
                23L, "external-work", true);
        when(extensions.resolveDownloadType("external-work"))
                .thenReturn(Optional.of(descriptor));
        DownloadControlPlaneAdapter adapter = new DownloadControlPlaneAdapter(queues, extensions);
        AtomicInteger ownerReads = new AtomicInteger();
        String ownerUuid = "11111111-1111-1111-1111-111111111111";
        String workKey = " opaque/exact:key ? # 中文 ";

        DownloadQueueCancelResult result = adapter.cancelExact(
                command(workKey, descriptor),
                () -> {
                    ownerReads.incrementAndGet();
                    return RequestOwnerIdentity.owner(ownerUuid);
                });

        assertThat(result).isEqualTo(DownloadQueueCancelResult.CANCELLED);
        assertThat(ownerReads).hasValue(1);
        verify(operations).cancel(workKey, ownerUuid, false);
    }

    @Test
    @DisplayName("旧 generation 请求不得改投当前外置队列 operation")
    void staleGenerationCannotReachCurrentExternalOperation() {
        QueueOperationRegistry queues = new QueueOperationRegistry(List.of());
        QueueOperations current = mock(QueueOperations.class);
        queues.registerPrepared(
                new QueueOperationOwner("external", "external", 8L, 11L),
                List.of(prepared("external-work", current)));
        DownloadExtensionRegistry extensions = mock(DownloadExtensionRegistry.class);
        RegisteredDownloadType currentDescriptor = descriptor(
                new DownloadExtensionOwner("external", "external", 8L),
                21L, "external-work", true);
        when(extensions.resolveDownloadType("external-work"))
                .thenReturn(Optional.of(currentDescriptor));
        DownloadControlPlaneAdapter adapter = new DownloadControlPlaneAdapter(queues, extensions);
        AtomicInteger ownerReads = new AtomicInteger();

        DownloadQueueCancelResult result = adapter.cancelExact(
                new DownloadQueueCancelCommand(
                        "external-work",
                        "stale/key",
                        new DownloadExtensionIdentity("external", "external", 7L, 21L)),
                () -> {
                    ownerReads.incrementAndGet();
                    return RequestOwnerIdentity.adminScope();
                });

        assertThat(result).isEqualTo(DownloadQueueCancelResult.DESCRIPTOR_STALE);
        assertThat(ownerReads).hasValue(0);
        verifyNoInteractions(current);
    }

    @Test
    @DisplayName("同 generation descriptor replacement 不得触达新旧 operation")
    void exactCancelFailsClosedAcrossDescriptorReplacement() {
        QueueOperationRegistry queues = new QueueOperationRegistry(List.of());
        QueueOperationOwner oldOwner = new QueueOperationOwner("external", "external", 7L, 10L);
        QueueOperationOwner newOwner = new QueueOperationOwner("external", "external", 7L, 11L);
        QueueOperations oldRaw = mock(QueueOperations.class);
        QueueOperations newRaw = mock(QueueOperations.class);
        queues.registerPrepared(oldOwner, List.of(prepared("external-work", oldRaw)));
        RegisteredDownloadType oldDescriptor = descriptor(
                new DownloadExtensionOwner("external", "external", 7L),
                20L, "external-work", true);
        RegisteredDownloadType newDescriptor = descriptor(
                new DownloadExtensionOwner("external", "external", 7L),
                21L, "external-work", true);
        DownloadExtensionRegistry extensions = mock(DownloadExtensionRegistry.class);
        AtomicInteger reads = new AtomicInteger();
        when(extensions.resolveDownloadType("external-work")).thenAnswer(invocation -> {
            if (reads.incrementAndGet() == 1) {
                return Optional.of(oldDescriptor);
            }
            queues.unregisterPrepared(oldOwner);
            queues.registerPrepared(newOwner, List.of(prepared("external-work", newRaw)));
            return Optional.of(newDescriptor);
        });
        DownloadControlPlaneAdapter adapter = new DownloadControlPlaneAdapter(queues, extensions);
        AtomicInteger ownerReads = new AtomicInteger();

        DownloadQueueCancelResult result = adapter.cancelExact(
                command("old/key 中文", oldDescriptor),
                () -> {
                    ownerReads.incrementAndGet();
                    return RequestOwnerIdentity.adminScope();
                });

        assertThat(result).isEqualTo(DownloadQueueCancelResult.DESCRIPTOR_STALE);
        assertThat(ownerReads).hasValue(0);
        verify(oldRaw, never()).cancel(anyString(), any(), anyBoolean());
        verify(newRaw, never()).cancel(anyString(), any(), anyBoolean());
    }

    @Test
    @DisplayName("descriptor 复核后 operation replacement 返回不可用且不改投新实例")
    void operationReplacementAfterDescriptorConfirmationFailsClosed() {
        QueueOperationRegistry queues = new QueueOperationRegistry(List.of());
        QueueOperationOwner oldOwner = new QueueOperationOwner("external", "external", 7L, 30L);
        QueueOperationOwner newOwner = new QueueOperationOwner("external", "external", 7L, 31L);
        QueueOperations oldRaw = mock(QueueOperations.class);
        QueueOperations newRaw = mock(QueueOperations.class);
        queues.registerPrepared(oldOwner, List.of(prepared("external-work", oldRaw)));
        RegisteredDownloadType current = descriptor(
                new DownloadExtensionOwner("external", "external", 7L),
                40L, "external-work", true);
        DownloadExtensionRegistry extensions = mock(DownloadExtensionRegistry.class);
        when(extensions.resolveDownloadType("external-work")).thenReturn(Optional.of(current));
        DownloadControlPlaneAdapter adapter = new DownloadControlPlaneAdapter(queues, extensions);

        DownloadQueueCancelResult result = adapter.cancelExact(
                command("old/key", current),
                () -> {
                    queues.unregisterPrepared(oldOwner);
                    queues.registerPrepared(newOwner, List.of(prepared("external-work", newRaw)));
                    return RequestOwnerIdentity.owner("11111111-1111-1111-1111-111111111111");
                });

        assertThat(result).isEqualTo(DownloadQueueCancelResult.OPERATION_UNAVAILABLE);
        verify(oldRaw, never()).cancel(anyString(), any(), anyBoolean());
        verify(newRaw, never()).cancel(anyString(), any(), anyBoolean());
    }

    @Test
    @DisplayName("owner 解析期间 descriptor replacement 返回过期且不调用队列命令")
    void descriptorReplacementDuringOwnerResolutionFailsClosed() {
        QueueOperations operations = mock(QueueOperations.class);
        when(operations.queueType()).thenReturn("novel");
        QueueOperationRegistry queues = new QueueOperationRegistry(List.of(operations));
        RegisteredDownloadType oldDescriptor = descriptor(
                new DownloadExtensionOwner("host-novel", "host-novel", 0L),
                40L, "novel", true);
        RegisteredDownloadType newDescriptor = descriptor(
                new DownloadExtensionOwner("host-novel", "host-novel", 0L),
                41L, "novel", true);
        AtomicReference<RegisteredDownloadType> current =
                new AtomicReference<>(oldDescriptor);
        DownloadExtensionRegistry extensions = mock(DownloadExtensionRegistry.class);
        when(extensions.resolveDownloadType("novel"))
                .thenAnswer(invocation -> Optional.of(current.get()));
        DownloadControlPlaneAdapter adapter = new DownloadControlPlaneAdapter(queues, extensions);

        DownloadQueueCancelResult result = adapter.cancelExact(
                command("novel/42", oldDescriptor),
                () -> {
                    current.set(newDescriptor);
                    return RequestOwnerIdentity.adminScope();
                });

        assertThat(result).isEqualTo(DownloadQueueCancelResult.DESCRIPTOR_STALE);
        verify(operations, never()).cancel(anyString(), any(), anyBoolean());
    }

    @Test
    @DisplayName("迟到的同 generation 精确请求不得改投 replacement")
    void lateExactCancelCannotReachSameGenerationReplacement() {
        QueueOperationRegistry queues = new QueueOperationRegistry(List.of());
        QueueOperationOwner newOwner =
                new QueueOperationOwner("download-workbench", "download-workbench", 9L, 81L);
        QueueOperations newRaw = mock(QueueOperations.class);
        queues.registerPrepared(newOwner, List.of(prepared("illust", newRaw)));
        RegisteredDownloadType newDescriptor = descriptor(
                new DownloadExtensionOwner("download-workbench", "download-workbench", 9L),
                91L, "illust", true);
        DownloadExtensionRegistry extensions = mock(DownloadExtensionRegistry.class);
        when(extensions.resolveDownloadType("illust")).thenReturn(Optional.of(newDescriptor));
        DownloadControlPlaneAdapter adapter = new DownloadControlPlaneAdapter(queues, extensions);
        AtomicInteger ownerReads = new AtomicInteger();

        DownloadQueueCancelResult result = adapter.cancelExact(
                new DownloadQueueCancelCommand(
                        "illust",
                        "12345",
                        new DownloadExtensionIdentity(
                                "download-workbench", "download-workbench", 9L, 90L)),
                () -> {
                    ownerReads.incrementAndGet();
                    return RequestOwnerIdentity.adminScope();
                });

        assertThat(result).isEqualTo(DownloadQueueCancelResult.DESCRIPTOR_STALE);
        assertThat(ownerReads).hasValue(0);
        verify(newRaw, never()).cancel(anyString(), any(), anyBoolean());
    }

    @Test
    @DisplayName("descriptor 缺失、不支持或无匹配 operation 时返回稳定结果且不解析 owner")
    void preflightFailuresDoNotResolveRequestOwner() {
        DownloadExtensionRegistry extensions = mock(DownloadExtensionRegistry.class);
        QueueOperationRegistry queues = new QueueOperationRegistry(List.of());
        DownloadControlPlaneAdapter adapter = new DownloadControlPlaneAdapter(queues, extensions);
        AtomicInteger ownerReads = new AtomicInteger();
        var ownerSupplier = (java.util.function.Supplier<RequestOwnerIdentity>) () -> {
            ownerReads.incrementAndGet();
            return RequestOwnerIdentity.adminScope();
        };

        when(extensions.resolveDownloadType("missing")).thenReturn(Optional.empty());
        assertThat(adapter.cancelExact(
                new DownloadQueueCancelCommand(
                        "missing",
                        "1",
                        new DownloadExtensionIdentity("missing", "missing", 1L, 1L)),
                ownerSupplier))
                .isEqualTo(DownloadQueueCancelResult.DESCRIPTOR_NOT_FOUND);

        RegisteredDownloadType unsupported = descriptor(
                new DownloadExtensionOwner("host", "host", 0L),
                1L, "unsupported", false);
        when(extensions.resolveDownloadType("unsupported")).thenReturn(Optional.of(unsupported));
        assertThat(adapter.cancelExact(command("1", unsupported), ownerSupplier))
                .isEqualTo(DownloadQueueCancelResult.UNSUPPORTED);

        RegisteredDownloadType unavailable = descriptor(
                new DownloadExtensionOwner("external", "external", 4L),
                2L, "unavailable", true);
        when(extensions.resolveDownloadType("unavailable")).thenReturn(Optional.of(unavailable));
        assertThat(adapter.cancelExact(command("opaque/key", unavailable), ownerSupplier))
                .isEqualTo(DownloadQueueCancelResult.OPERATION_UNAVAILABLE);

        assertThat(ownerReads).hasValue(0);
    }

    @Test
    @DisplayName("非管理员清空只作用于当前 owner 的全部在场队列")
    void clearQueuesScopesNonAdminToCurrentOwner() {
        QueueOperations illust = mock(QueueOperations.class);
        QueueOperations novel = mock(QueueOperations.class);
        when(illust.queueType()).thenReturn("illust");
        when(novel.queueType()).thenReturn("novel");
        String ownerUuid = "11111111-1111-1111-1111-111111111111";
        when(illust.clearForOwner(ownerUuid)).thenReturn(2);
        when(novel.clearForOwner(ownerUuid)).thenReturn(1);
        DownloadControlPlaneAdapter adapter = new DownloadControlPlaneAdapter(
                new QueueOperationRegistry(List.of(illust, novel)),
                mock(DownloadExtensionRegistry.class));

        int cleared = adapter.clearQueues(RequestOwnerIdentity.owner(ownerUuid));

        assertThat(cleared).isEqualTo(3);
        verify(illust).clearForOwner(ownerUuid);
        verify(novel).clearForOwner(ownerUuid);
        verify(illust, never()).clearAll();
        verify(novel, never()).clearAll();
    }

    @Test
    @DisplayName("管理员清空调用全部在场队列且不携带 owner")
    void clearQueuesUsesGlobalScopeForAdmin() {
        QueueOperations illust = mock(QueueOperations.class);
        QueueOperations novel = mock(QueueOperations.class);
        when(illust.queueType()).thenReturn("illust");
        when(novel.queueType()).thenReturn("novel");
        when(illust.clearAll()).thenReturn(2);
        when(novel.clearAll()).thenReturn(1);
        DownloadControlPlaneAdapter adapter = new DownloadControlPlaneAdapter(
                new QueueOperationRegistry(List.of(illust, novel)),
                mock(DownloadExtensionRegistry.class));

        int cleared = adapter.clearQueues(RequestOwnerIdentity.adminScope());

        assertThat(cleared).isEqualTo(3);
        verify(illust).clearAll();
        verify(novel).clearAll();
        verify(illust, never()).clearForOwner(any());
        verify(novel, never()).clearForOwner(any());
    }

    private static PreparedQueueOperations prepared(String queueType, QueueOperations raw) {
        QueueOperationCommands commands = new QueueOperationCommands() {
            @Override
            public void cancel(String workKey, String ownerUuid, boolean admin) {
                raw.cancel(workKey, ownerUuid, admin);
            }

            @Override
            public int clearAll() {
                return raw.clearAll();
            }

            @Override
            public int clearForOwner(String ownerUuid) {
                return raw.clearForOwner(ownerUuid);
            }
        };
        return new PreparedQueueOperations(queueType, raw, commands, raw.getClass().getName());
    }

    private static RegisteredDownloadType descriptor(
            DownloadExtensionOwner owner,
            long publicationId,
            String type,
            boolean cancelSupported) {
        return new RegisteredDownloadType(
                owner,
                publicationId,
                new DownloadTypeDescriptor(
                        DownloadTypeDescriptor.CURRENT_CONTRACT_VERSION,
                        type,
                        "test",
                        "type.label",
                        0,
                        "download",
                        "green",
                        "/test-download/" + type + ".js",
                        List.of(),
                        cancelSupported,
                        List.of(),
                        List.of(),
                        "test"));
    }

    private static DownloadQueueCancelCommand command(
            String workKey,
            RegisteredDownloadType descriptor) {
        DownloadExtensionOwner owner = descriptor.owner();
        return new DownloadQueueCancelCommand(
                descriptor.descriptor().type(),
                workKey,
                new DownloadExtensionIdentity(
                        owner.featurePluginId(),
                        owner.packageId(),
                        owner.generation(),
                        descriptor.publicationId()));
    }
}
