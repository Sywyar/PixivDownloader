package top.sywyar.pixivdownload.download.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import top.sywyar.pixivdownload.download.testsupport.WorkbenchTestMessages;
import top.sywyar.pixivdownload.i18n.MessageResolver;
import top.sywyar.pixivdownload.plugin.api.download.control.DownloadControlPlane;
import top.sywyar.pixivdownload.plugin.api.download.control.DownloadExtensionIdentity;
import top.sywyar.pixivdownload.plugin.api.download.control.DownloadQueueCancelCommand;
import top.sywyar.pixivdownload.plugin.api.download.control.DownloadQueueCancelResult;
import top.sywyar.pixivdownload.plugin.api.web.RequestOwnerIdentity;
import top.sywyar.pixivdownload.plugin.api.web.RequestOwnerIdentityResolver;

import java.util.Locale;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link DownloadQueueController} HTTP 投影测试。descriptor currentness、命令对象 replacement 与跨队列清空
 * 由宿主 {@link DownloadControlPlane} 实现负责，本类只验证请求校验、可信身份解析接线与稳定 HTTP 结果。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DownloadQueueController 单元测试")
class DownloadQueueControllerTest {

    private static final MessageResolver MESSAGES = WorkbenchTestMessages.messages();
    private static final DownloadExtensionIdentity NOVEL_PUBLICATION =
            new DownloadExtensionIdentity("novel", "novel", 7L, 21L);

    @Mock
    private DownloadControlPlane downloadControlPlane;
    @Mock
    private RequestOwnerIdentityResolver requestOwnerIdentityResolver;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        DownloadQueueController controller = new DownloadQueueController(
                downloadControlPlane, requestOwnerIdentityResolver, MESSAGES);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/cancel/12345",
            "/api/download/cancel/12345",
            "/api/cancel/not-a-number",
            "/api/download/cancel/not-a-number"
    })
    @DisplayName("无 publication 的旧请求固定返回冲突且不解析当前队列")
    void identityLessLegacyRequestAlwaysReturnsStableConflict(String path) throws Exception {
        mockMvc.perform(post(path).locale(Locale.US))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("QUEUE_CANCEL_DESCRIPTOR_STALE"))
                .andExpect(jsonPath("$.error").value(
                        "This cancel request is outdated. Refresh the download page and try again"));

        verifyNoInteractions(downloadControlPlane, requestOwnerIdentityResolver);
    }

    @Test
    @DisplayName("精确取消保留不透明 workKey、完整 publication 与可信 owner")
    void exactCancelPreservesOpaqueWorkKeyPublicationAndRequestOwner() throws Exception {
        String ownerUuid = "11111111-1111-1111-1111-111111111111";
        String workKey = " opaque/path:part ? # 中文 ";
        when(requestOwnerIdentityResolver.resolve(any()))
                .thenReturn(RequestOwnerIdentity.owner(ownerUuid));
        when(downloadControlPlane.cancelExact(any(), any())).thenAnswer(invocation -> {
            DownloadQueueCancelCommand command = invocation.getArgument(0);
            Supplier<RequestOwnerIdentity> owner = invocation.getArgument(1);
            assertThat(command.queueType()).isEqualTo("novel");
            assertThat(command.workKey()).isEqualTo(workKey);
            assertThat(command.expectedPublication()).isEqualTo(NOVEL_PUBLICATION);
            assertThat(owner.get()).isEqualTo(RequestOwnerIdentity.owner(ownerUuid));
            return DownloadQueueCancelResult.CANCELLED;
        });

        mockMvc.perform(post("/api/download/queue/novel/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(cancelRequest(workKey, NOVEL_PUBLICATION))
                        .locale(Locale.US))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(downloadControlPlane).cancelExact(any(), any());
    }

    @Test
    @DisplayName("空白 workKey 在控制面调用与 owner 解析前返回 400")
    void blankOpaqueWorkKeyIsRejectedBeforeControlPlane() throws Exception {
        mockMvc.perform(post("/api/download/queue/novel/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"workKey\":\"   \"}")
                        .locale(Locale.SIMPLIFIED_CHINESE))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("QUEUE_CANCEL_REQUEST_INVALID"));

        verifyNoInteractions(downloadControlPlane, requestOwnerIdentityResolver);
    }

    @Test
    @DisplayName("空白 queueType 在控制面调用与 owner 解析前返回稳定 400")
    void blankQueueTypeIsRejectedBeforeControlPlane() throws Exception {
        mockMvc.perform(post("/api/download/queue/{queueType}/cancel", " ")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(cancelRequest("novel/1", NOVEL_PUBLICATION))
                        .locale(Locale.US))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("QUEUE_CANCEL_REQUEST_INVALID"))
                .andExpect(jsonPath("$.error").value("Invalid request parameters"));

        verifyNoInteractions(downloadControlPlane, requestOwnerIdentityResolver);
    }

    @Test
    @DisplayName("非法 publication 身份在控制面调用前返回 400")
    void invalidPublicationIdentityIsRejectedBeforeControlPlane() throws Exception {
        mockMvc.perform(post("/api/download/queue/novel/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"workKey":"novel/1","owner":{
                                  "pluginId":"novel","packageId":"novel",
                                  "generation":7,"publicationId":0
                                }}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("QUEUE_CANCEL_REQUEST_INVALID"));

        verifyNoInteractions(downloadControlPlane, requestOwnerIdentityResolver);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cancelFailures")
    @DisplayName("控制面取消失败结果保持既有 HTTP 状态与机器码")
    void cancelFailuresKeepStableHttpProjection(
            String description,
            DownloadQueueCancelResult result,
            int expectedStatus,
            String expectedCode) throws Exception {
        when(downloadControlPlane.cancelExact(any(), any())).thenReturn(result);

        mockMvc.perform(post("/api/download/queue/novel/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(cancelRequest("novel/123", NOVEL_PUBLICATION))
                        .locale(Locale.SIMPLIFIED_CHINESE))
                .andExpect(status().is(expectedStatus))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value(expectedCode));

        verify(requestOwnerIdentityResolver, never()).resolve(any());
    }

    @Test
    @DisplayName("非管理员清空把可信 owner 作用域交给控制面")
    void clearQueueUsesCurrentOwnerScope() throws Exception {
        RequestOwnerIdentity owner =
                RequestOwnerIdentity.owner("11111111-1111-1111-1111-111111111111");
        when(requestOwnerIdentityResolver.resolve(any())).thenReturn(owner);
        when(downloadControlPlane.clearQueues(owner)).thenReturn(3);

        mockMvc.perform(post("/api/download/queue/clear")
                        .locale(Locale.SIMPLIFIED_CHINESE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(downloadControlPlane).clearQueues(owner);
    }

    @Test
    @DisplayName("管理员清空把全 owner 作用域交给控制面")
    void clearQueueUsesAdminScope() throws Exception {
        RequestOwnerIdentity admin = RequestOwnerIdentity.adminScope();
        when(requestOwnerIdentityResolver.resolve(any())).thenReturn(admin);
        when(downloadControlPlane.clearQueues(admin)).thenReturn(2);

        mockMvc.perform(post("/api/download/queue/clear")
                        .locale(Locale.SIMPLIFIED_CHINESE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(downloadControlPlane).clearQueues(admin);
    }

    private static Stream<Arguments> cancelFailures() {
        return Stream.of(
                Arguments.of(
                        "descriptor 缺失",
                        DownloadQueueCancelResult.DESCRIPTOR_NOT_FOUND,
                        404,
                        "QUEUE_CANCEL_DESCRIPTOR_NOT_FOUND"),
                Arguments.of(
                        "descriptor 已过期",
                        DownloadQueueCancelResult.DESCRIPTOR_STALE,
                        409,
                        "QUEUE_CANCEL_DESCRIPTOR_STALE"),
                Arguments.of(
                        "descriptor 不支持取消",
                        DownloadQueueCancelResult.UNSUPPORTED,
                        409,
                        "QUEUE_CANCEL_UNSUPPORTED"),
                Arguments.of(
                        "operation 不可用",
                        DownloadQueueCancelResult.OPERATION_UNAVAILABLE,
                        503,
                        "QUEUE_CANCEL_OPERATION_UNAVAILABLE"));
    }

    private static String cancelRequest(
            String workKey,
            DownloadExtensionIdentity publication) {
        return "{\"workKey\":\"" + workKey + "\",\"owner\":{"
                + "\"pluginId\":\"" + publication.pluginId() + "\","
                + "\"packageId\":\"" + publication.packageId() + "\","
                + "\"generation\":" + publication.generation() + ","
                + "\"publicationId\":" + publication.publicationId() + "}}";
    }
}
