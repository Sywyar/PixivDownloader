package top.sywyar.pixivdownload.download.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import top.sywyar.pixivdownload.GlobalExceptionHandler;
import top.sywyar.pixivdownload.core.download.queue.QueueOperationCommands;
import top.sywyar.pixivdownload.core.download.queue.QueueOperationOwner;
import top.sywyar.pixivdownload.core.download.queue.QueueOperationRegistry;
import top.sywyar.pixivdownload.core.download.queue.QueueOperationRegistry.PreparedQueueOperations;
import top.sywyar.pixivdownload.plugin.api.download.queue.QueueOperations;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.i18n.TestI18nBeans;
import top.sywyar.pixivdownload.download.ArtworkDownloadExecutor;
import top.sywyar.pixivdownload.download.IllustQueueOperations;
import top.sywyar.pixivdownload.plugin.api.download.type.DownloadTypeDescriptor;
import top.sywyar.pixivdownload.plugin.api.web.RequestOwnerIdentity;
import top.sywyar.pixivdownload.plugin.api.web.RequestOwnerIdentityResolver;
import top.sywyar.pixivdownload.plugin.registry.DownloadExtensionOwner;
import top.sywyar.pixivdownload.plugin.registry.DownloadExtensionRegistry;
import top.sywyar.pixivdownload.plugin.registry.DownloadExtensionRegistry.RegisteredDownloadType;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * {@link DownloadQueueController} 单元测试。控制器经核心队列宿主注册中心
 * {@link QueueOperationRegistry} 跨类型派发，不再直接依赖具体下载服务——这里用真实操作适配器
 *（插画侧 {@link IllustQueueOperations} + 小说侧中性 {@link QueueOperations} fake）装配注册中心，
 * 验证取消按 queueType 定向、legacy URL 只命中插画，以及清空继续跨队列并保持 owner 作用域。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DownloadQueueController 单元测试")
class DownloadQueueControllerTest {
    private static final AppMessages APP_MESSAGES = TestI18nBeans.appMessages();

    @Mock
    private ArtworkDownloadExecutor artworkDownloadExecutor;
    @Mock
    private RequestOwnerIdentityResolver requestOwnerIdentityResolver;
    @Mock
    private QueueOperations novelQueueOperations;
    @Mock
    private DownloadExtensionRegistry downloadExtensionRegistry;

    private RegisteredDownloadType illustDescriptor;
    private RegisteredDownloadType novelDescriptor;

    /** 用真实操作适配器（插画 + 小说）包裹 mock 执行器建注册中心，再装配控制器。 */
    private MockMvc mockMvcWith(QueueOperationRegistry registry) {
        DownloadQueueController controller =
                new DownloadQueueController(
                        registry, downloadExtensionRegistry, requestOwnerIdentityResolver, APP_MESSAGES);
        return MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler(APP_MESSAGES))
                .build();
    }

    private QueueOperationRegistry illustAndNovelRegistry() {
        return new QueueOperationRegistry(List.of(
                new IllustQueueOperations(artworkDownloadExecutor),
                novelQueueOperations));
    }

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        when(novelQueueOperations.queueType()).thenReturn("novel");
        illustDescriptor = descriptor("host-download", "host-download", 0L, 1L, "illust", true);
        novelDescriptor = descriptor("host-novel", "host-novel", 0L, 2L, "novel", true);
        lenient().when(downloadExtensionRegistry.resolveDownloadType("illust"))
                .thenReturn(Optional.of(illustDescriptor));
        lenient().when(downloadExtensionRegistry.resolveDownloadType("novel"))
                .thenReturn(Optional.of(novelDescriptor));
        mockMvc = mockMvcWith(illustAndNovelRegistry());
        clearInvocations(novelQueueOperations);
    }

    // ========== POST /api/cancel/{artworkId} ==========

    @Test
    @DisplayName("POST /api/cancel/{artworkId} 应经注册中心取消插画下载（admin 作用域）")
    void shouldCancelDownload() throws Exception {
        when(requestOwnerIdentityResolver.resolve(any())).thenReturn(RequestOwnerIdentity.adminScope());
        mockMvc.perform(post("/api/cancel/12345").locale(Locale.SIMPLIFIED_CHINESE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("下载任务已取消"));

        // legacy URL 仅 resolve illust，不会把数字 ID 广播到 novel。
        verify(artworkDownloadExecutor).cancelDownload(12345L, null, true);
        verifyNoInteractions(novelQueueOperations);
    }

    @Test
    @DisplayName("新取消入口保留不透明 workKey 并只定向目标 queueType")
    void shouldCancelOnlyResolvedOpaqueQueueType() throws Exception {
        String ownerUuid = "11111111-1111-1111-1111-111111111111";
        String workKey = " opaque/path:part ? # 中文 ";
        when(requestOwnerIdentityResolver.resolve(any()))
                .thenReturn(RequestOwnerIdentity.owner(ownerUuid));

        mockMvc.perform(post("/api/download/queue/novel/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(cancelRequest(workKey, novelDescriptor))
                        .locale(Locale.SIMPLIFIED_CHINESE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(novelQueueOperations).cancel(workKey, ownerUuid, false);
        verifyNoInteractions(artworkDownloadExecutor);
    }

    @Test
    @DisplayName("新取消入口拒绝空白 workKey 且不解析请求 owner")
    void shouldRejectBlankOpaqueWorkKey() throws Exception {
        mockMvc.perform(post("/api/download/queue/novel/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"workKey\":\"   \"}")
                        .locale(Locale.SIMPLIFIED_CHINESE))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(requestOwnerIdentityResolver, novelQueueOperations, artworkDownloadExecutor);
    }

    @Test
    @DisplayName("descriptor 未声明单项取消时拒绝请求且不触达队列实现")
    void descriptorWithoutCancelSupportIsRejected() throws Exception {
        RegisteredDownloadType unsupported = descriptor(
                "host-novel", "host-novel", 0L, 3L, "novel", false);
        when(downloadExtensionRegistry.resolveDownloadType("novel"))
                .thenReturn(Optional.of(unsupported));

        mockMvc.perform(post("/api/download/queue/novel/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(cancelRequest("novel/123", unsupported))
                        .locale(Locale.SIMPLIFIED_CHINESE))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("QUEUE_CANCEL_UNSUPPORTED"));

        verifyNoInteractions(requestOwnerIdentityResolver, novelQueueOperations, artworkDownloadExecutor);
    }

    @Test
    @DisplayName("请求携旧 generation owner 时拒绝改投当前外置操作")
    void staleOwnerRequestCannotReachCurrentExternalOperation() throws Exception {
        QueueOperationRegistry registry = new QueueOperationRegistry(List.of());
        QueueOperations currentRaw = mock(QueueOperations.class);
        QueueOperationOwner currentOperationOwner = new QueueOperationOwner(
                "external", "external", 8L, 11L);
        registry.registerPrepared(
                currentOperationOwner, List.of(prepared("external-work", currentRaw)));
        registry.resolveOwned("external-work", "external", "external", 8L).orElseThrow();
        RegisteredDownloadType staleDescriptor = descriptor(
                "external", "external", 7L, 21L, "external-work", true);
        RegisteredDownloadType currentDescriptor = descriptor(
                "external", "external", 8L, 21L, "external-work", true);
        when(downloadExtensionRegistry.resolveDownloadType("external-work"))
                .thenReturn(Optional.of(currentDescriptor));
        MockMvc external = mockMvcWith(registry);

        external.perform(post("/api/download/queue/external-work/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(cancelRequest("stale/key", staleDescriptor)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("QUEUE_CANCEL_DESCRIPTOR_STALE"));

        verifyNoInteractions(currentRaw, requestOwnerIdentityResolver);
    }

    @Test
    @DisplayName("旧 descriptor 请求跨同 generation publication replacement 时不得触达新操作")
    void staleDescriptorRequestCannotReachReplacementOperation() throws Exception {
        QueueOperationRegistry registry = new QueueOperationRegistry(List.of());
        QueueOperationOwner oldOwner = new QueueOperationOwner("external", "external", 7L, 10L);
        QueueOperationOwner newOwner = new QueueOperationOwner("external", "external", 7L, 11L);
        QueueOperations oldRaw = mock(QueueOperations.class);
        QueueOperations newRaw = mock(QueueOperations.class);
        registry.registerPrepared(oldOwner, List.of(prepared("external-work", oldRaw)));
        RegisteredDownloadType oldDescriptor = descriptor(
                "external", "external", 7L, 20L, "external-work", true);
        RegisteredDownloadType newDescriptor = descriptor(
                "external", "external", 7L, 21L, "external-work", true);
        AtomicInteger reads = new AtomicInteger();
        when(downloadExtensionRegistry.resolveDownloadType("external-work")).thenAnswer(invocation -> {
            if (reads.incrementAndGet() == 1) {
                return Optional.of(oldDescriptor);
            }
            registry.unregisterPrepared(oldOwner);
            registry.registerPrepared(newOwner, List.of(prepared("external-work", newRaw)));
            return Optional.of(newDescriptor);
        });
        MockMvc external = mockMvcWith(registry);

        external.perform(post("/api/download/queue/external-work/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(cancelRequest("old/key 中文", oldDescriptor)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("QUEUE_CANCEL_DESCRIPTOR_STALE"));

        verify(oldRaw, never()).cancel(anyString(), any(), anyBoolean());
        verify(newRaw, never()).cancel(anyString(), any(), anyBoolean());
        verifyNoInteractions(requestOwnerIdentityResolver);
    }

    @Test
    @DisplayName("descriptor 复核后 operation 被同 generation 替换时返回不可用且不改投新实例")
    void operationReplacementAfterDescriptorConfirmationFailsClosed() throws Exception {
        QueueOperationRegistry registry = new QueueOperationRegistry(List.of());
        QueueOperationOwner oldOwner = new QueueOperationOwner("external", "external", 7L, 30L);
        QueueOperationOwner newOwner = new QueueOperationOwner("external", "external", 7L, 31L);
        QueueOperations oldRaw = mock(QueueOperations.class);
        QueueOperations newRaw = mock(QueueOperations.class);
        registry.registerPrepared(oldOwner, List.of(prepared("external-work", oldRaw)));
        RegisteredDownloadType current = descriptor(
                "external", "external", 7L, 40L, "external-work", true);
        when(downloadExtensionRegistry.resolveDownloadType("external-work"))
                .thenReturn(Optional.of(current));
        when(requestOwnerIdentityResolver.resolve(any())).thenAnswer(invocation -> {
            registry.unregisterPrepared(oldOwner);
            registry.registerPrepared(newOwner, List.of(prepared("external-work", newRaw)));
            return RequestOwnerIdentity.owner("11111111-1111-1111-1111-111111111111");
        });
        MockMvc external = mockMvcWith(registry);

        external.perform(post("/api/download/queue/external-work/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(cancelRequest("old/key", current)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("QUEUE_CANCEL_OPERATION_UNAVAILABLE"));

        verify(oldRaw, never()).cancel(anyString(), any(), anyBoolean());
        verify(newRaw, never()).cancel(anyString(), any(), anyBoolean());
    }

    @Test
    @DisplayName("旧 Pixiv URL 对当前 external download-workbench publication 保持可用")
    void legacyPixivUrlSupportsCurrentExternalOperation() throws Exception {
        QueueOperationRegistry registry = new QueueOperationRegistry(List.of());
        QueueOperations externalIllust = new IllustQueueOperations(artworkDownloadExecutor);
        QueueOperationOwner operationOwner = new QueueOperationOwner(
                "download-workbench", "download-workbench", 9L, 50L);
        registry.registerPrepared(operationOwner, List.of(prepared("illust", externalIllust)));
        RegisteredDownloadType externalDescriptor = descriptor(
                "download-workbench", "download-workbench", 9L, 60L, "illust", true);
        when(downloadExtensionRegistry.resolveDownloadType("illust"))
                .thenReturn(Optional.of(externalDescriptor));
        when(requestOwnerIdentityResolver.resolve(any())).thenReturn(RequestOwnerIdentity.adminScope());
        MockMvc external = mockMvcWith(registry);

        external.perform(post("/api/cancel/12345"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(artworkDownloadExecutor).cancelDownload(12345L, null, true);
    }

    @Test
    @DisplayName("旧 Pixiv URL 跨同 generation replacement 时不得改投新操作")
    void legacyPixivUrlFailsClosedAcrossSameGenerationReplacement() throws Exception {
        QueueOperationRegistry registry = new QueueOperationRegistry(List.of());
        QueueOperationOwner oldOwner = new QueueOperationOwner(
                "download-workbench", "download-workbench", 9L, 80L);
        QueueOperationOwner newOwner = new QueueOperationOwner(
                "download-workbench", "download-workbench", 9L, 81L);
        QueueOperations oldRaw = mock(QueueOperations.class);
        QueueOperations newRaw = mock(QueueOperations.class);
        registry.registerPrepared(oldOwner, List.of(prepared("illust", oldRaw)));
        RegisteredDownloadType oldDescriptor = descriptor(
                "download-workbench", "download-workbench", 9L, 90L, "illust", true);
        RegisteredDownloadType newDescriptor = descriptor(
                "download-workbench", "download-workbench", 9L, 91L, "illust", true);
        AtomicInteger reads = new AtomicInteger();
        when(downloadExtensionRegistry.resolveDownloadType("illust")).thenAnswer(invocation -> {
            if (reads.incrementAndGet() == 1) {
                return Optional.of(oldDescriptor);
            }
            registry.unregisterPrepared(oldOwner);
            registry.registerPrepared(newOwner, List.of(prepared("illust", newRaw)));
            return Optional.of(newDescriptor);
        });
        MockMvc external = mockMvcWith(registry);

        external.perform(post("/api/cancel/12345"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("QUEUE_CANCEL_DESCRIPTOR_STALE"));

        verify(oldRaw, never()).cancel(anyString(), any(), anyBoolean());
        verify(newRaw, never()).cancel(anyString(), any(), anyBoolean());
        verifyNoInteractions(requestOwnerIdentityResolver);
    }

    @Test
    @DisplayName("当前 descriptor 缺少同 owner generation 的 operation 时返回稳定非成功机器码")
    void missingOwnedOperationReturnsUnavailableCode() throws Exception {
        RegisteredDownloadType descriptor = descriptor(
                "missing", "missing", 4L, 70L, "missing-work", true);
        when(downloadExtensionRegistry.resolveDownloadType("missing-work"))
                .thenReturn(Optional.of(descriptor));
        MockMvc empty = mockMvcWith(new QueueOperationRegistry(List.of()));

        empty.perform(post("/api/download/queue/missing-work/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(cancelRequest("opaque/key", descriptor)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("QUEUE_CANCEL_OPERATION_UNAVAILABLE"));

        verifyNoInteractions(requestOwnerIdentityResolver);
    }

    @Test
    @DisplayName("POST /api/download/queue/clear 在多人模式下只清除当前 owner（跨插画 + 小说）")
    void shouldClearOnlyCurrentOwnerDownloadsInMultiMode() throws Exception {
        String ownerUuid = "11111111-1111-1111-1111-111111111111";
        when(requestOwnerIdentityResolver.resolve(any()))
                .thenReturn(RequestOwnerIdentity.owner(ownerUuid));
        when(artworkDownloadExecutor.forceClearDownloadsForOwner(ownerUuid)).thenReturn(2);
        when(novelQueueOperations.clearForOwner(ownerUuid)).thenReturn(1);

        mockMvc.perform(post("/api/download/queue/clear")
                        .header("X-User-UUID", ownerUuid)
                        .locale(Locale.SIMPLIFIED_CHINESE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(artworkDownloadExecutor).forceClearDownloadsForOwner(ownerUuid);
        verify(novelQueueOperations).clearForOwner(ownerUuid);
        verify(artworkDownloadExecutor, never()).forceClearDownloads();
        verify(novelQueueOperations, never()).clearAll();
    }

    @Test
    @DisplayName("POST /api/download/queue/clear 在 solo 模式下清除全部后端状态（跨插画 + 小说）")
    void shouldClearAllDownloadsInSoloMode() throws Exception {
        when(requestOwnerIdentityResolver.resolve(any())).thenReturn(RequestOwnerIdentity.adminScope());
        when(artworkDownloadExecutor.forceClearDownloads()).thenReturn(2);
        when(novelQueueOperations.clearAll()).thenReturn(1);

        mockMvc.perform(post("/api/download/queue/clear").locale(Locale.SIMPLIFIED_CHINESE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(artworkDownloadExecutor).forceClearDownloads();
        verify(novelQueueOperations).clearAll();
        verify(artworkDownloadExecutor, never()).forceClearDownloadsForOwner(any());
        verify(novelQueueOperations, never()).clearForOwner(any());
    }

    @Test
    @DisplayName("小说作品类型缺席（插件禁用）时：清空只作用于在场的插画类型，不报错")
    void shouldClearOnlyPresentTypesWhenNovelAbsent() throws Exception {
        // 仅注册插画操作，模拟小说插件被禁 / 卸载后其队列操作缺席。
        MockMvc illustOnly = mockMvcWith(new QueueOperationRegistry(
                List.of(new IllustQueueOperations(artworkDownloadExecutor))));
        when(requestOwnerIdentityResolver.resolve(any())).thenReturn(RequestOwnerIdentity.adminScope());
        when(artworkDownloadExecutor.forceClearDownloads()).thenReturn(2);

        illustOnly.perform(post("/api/download/queue/clear").locale(Locale.SIMPLIFIED_CHINESE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(artworkDownloadExecutor).forceClearDownloads();
        verifyNoInteractions(novelQueueOperations);
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
            String pluginId,
            String packageId,
            long generation,
            long publicationId,
            String type,
            boolean cancel) {
        DownloadTypeDescriptor descriptor = new DownloadTypeDescriptor(
                DownloadTypeDescriptor.CURRENT_CONTRACT_VERSION,
                type,
                "test",
                "type.label",
                0,
                "download",
                "green",
                "/test-download/" + type + ".js",
                List.of(),
                cancel,
                List.of(),
                List.of(),
                "test");
        return new RegisteredDownloadType(
                new DownloadExtensionOwner(pluginId, packageId, generation),
                publicationId,
                descriptor);
    }

    private static String cancelRequest(String workKey, RegisteredDownloadType descriptor) {
        DownloadExtensionOwner owner = descriptor.owner();
        return "{\"workKey\":\"" + workKey + "\",\"owner\":{"
                + "\"pluginId\":\"" + owner.featurePluginId() + "\","
                + "\"packageId\":\"" + owner.packageId() + "\","
                + "\"generation\":" + owner.generation() + ","
                + "\"publicationId\":" + descriptor.publicationId() + "}}";
    }
}
