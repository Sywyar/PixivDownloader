package top.sywyar.pixivdownload.download.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import top.sywyar.pixivdownload.GlobalExceptionHandler;
import top.sywyar.pixivdownload.core.download.queue.QueueOperationRegistry;
import top.sywyar.pixivdownload.core.download.queue.QueueOperations;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.i18n.TestI18nBeans;
import top.sywyar.pixivdownload.download.ArtworkDownloadExecutor;
import top.sywyar.pixivdownload.download.IllustQueueOperations;
import top.sywyar.pixivdownload.setup.SetupService;

import java.util.List;
import java.util.Locale;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * {@link DownloadQueueController} 单元测试。控制器经核心队列宿主注册中心
 * {@link QueueOperationRegistry} 跨类型派发，不再直接依赖具体下载服务——这里用真实操作适配器
 *（插画侧 {@link IllustQueueOperations} + 小说侧中性 {@link QueueOperations} fake）装配注册中心，
 * 验证取消 / 清空经注册中心落到对应执行器，且 solo / multi 归属语义逐字保持。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DownloadQueueController 单元测试")
class DownloadQueueControllerTest {
    private static final AppMessages APP_MESSAGES = TestI18nBeans.appMessages();

    @Mock
    private ArtworkDownloadExecutor artworkDownloadExecutor;
    @Mock
    private SetupService setupService;
    @Mock
    private QueueOperations novelQueueOperations;

    /** 用真实操作适配器（插画 + 小说）包裹 mock 执行器建注册中心，再装配控制器。 */
    private MockMvc mockMvcWith(QueueOperationRegistry registry) {
        DownloadQueueController controller = new DownloadQueueController(registry, setupService, APP_MESSAGES);
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
        mockMvc = mockMvcWith(illustAndNovelRegistry());
        clearInvocations(novelQueueOperations);
    }

    // ========== POST /api/cancel/{artworkId} ==========

    @Test
    @DisplayName("POST /api/cancel/{artworkId} 应经注册中心取消插画下载（admin 作用域）")
    void shouldCancelDownload() throws Exception {
        when(setupService.hasAdminScope(any())).thenReturn(true);
        mockMvc.perform(post("/api/cancel/12345").locale(Locale.SIMPLIFIED_CHINESE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("下载任务已取消"));

        // 插画适配器把 admin 作用域取消转发到执行器三参重载；其他已注册队列也收到中性取消通知。
        verify(artworkDownloadExecutor).cancelDownload(12345L, null, true);
        verify(novelQueueOperations).cancel(12345L, null, true);
        verifyNoMoreInteractions(novelQueueOperations);
    }

    @Test
    @DisplayName("POST /api/download/queue/clear 在多人模式下只清除当前 owner（跨插画 + 小说）")
    void shouldClearOnlyCurrentOwnerDownloadsInMultiMode() throws Exception {
        String ownerUuid = "11111111-1111-1111-1111-111111111111";
        when(setupService.getMode()).thenReturn("multi");
        when(setupService.isAdminLoggedIn(any())).thenReturn(false);
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
        when(setupService.getMode()).thenReturn("solo");
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
        when(setupService.getMode()).thenReturn("solo");
        when(artworkDownloadExecutor.forceClearDownloads()).thenReturn(2);

        illustOnly.perform(post("/api/download/queue/clear").locale(Locale.SIMPLIFIED_CHINESE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(artworkDownloadExecutor).forceClearDownloads();
        verifyNoInteractions(novelQueueOperations);
    }
}
