package top.sywyar.pixivdownload.douyin.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import top.sywyar.pixivdownload.core.web.AcquisitionCredentialResolver;
import top.sywyar.pixivdownload.douyin.download.DouyinDownloadService;
import top.sywyar.pixivdownload.douyin.model.DouyinDownloadRequest;
import top.sywyar.pixivdownload.douyin.model.DouyinListing;
import top.sywyar.pixivdownload.setup.SetupService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

@DisplayName("Douyin 控制器凭证传输边界")
class DouyinControllerSecurityTest {

    private DouyinDownloadService service;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        service = mock(DouyinDownloadService.class);
        DouyinController controller = new DouyinController(service, mock(SetupService.class));
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new IllegalArgumentAdvice())
                .build();
    }

    @Test
    @DisplayName("非 loopback 客户端通过 HTTP 提交 Cookie 时拒绝请求")
    void rejectsCredentialSubmissionOverRemoteHttp() {
        DouyinDownloadService directService = mock(DouyinDownloadService.class);
        DouyinController controller = new DouyinController(directService, mock(SetupService.class));
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.isSecure()).thenReturn(false);
        when(request.getRemoteAddr()).thenReturn("203.0.113.10");

        var response = controller.download(
                new DouyinDownloadRequest("https://www.douyin.com/video/1", "title", "fixture-credential-7f4c2a91"),
                request);

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        verifyNoInteractions(directService);
    }

    @Test
    @DisplayName("通用取得凭证应传给 Douyin series 服务")
    void forwardsGenericCredentialForSeriesPreview() throws Exception {
        when(service.listSeriesWorks("series-1", 1, 24, "generic-cookie"))
                .thenReturn(DouyinListing.empty(1, 24));

        int status = mockMvc.perform(get("/api/douyin/series/series-1")
                        .header(AcquisitionCredentialResolver.HEADER_NAME, " generic-cookie "))
                .andReturn().getResponse().getStatus();

        assertThat(status).isEqualTo(200);
        verify(service).listSeriesWorks("series-1", 1, 24, "generic-cookie");
    }

    @Test
    @DisplayName("旧 Douyin 凭证头仍应传给 series 服务")
    void forwardsLegacyCredentialForSeriesPreview() throws Exception {
        when(service.listSeriesWorks("series-1", 1, 24, "legacy-cookie"))
                .thenReturn(DouyinListing.empty(1, 24));

        int status = mockMvc.perform(get("/api/douyin/series/series-1")
                        .header("X-Douyin-Cookie", " legacy-cookie "))
                .andReturn().getResponse().getStatus();

        assertThat(status).isEqualTo(200);
        verify(service).listSeriesWorks("series-1", 1, 24, "legacy-cookie");
    }

    @Test
    @DisplayName("通用与旧 Douyin 凭证冲突时应返回 400")
    void rejectsConflictingSeriesCredentials() throws Exception {
        int status = mockMvc.perform(get("/api/douyin/series/series-1")
                        .header(AcquisitionCredentialResolver.HEADER_NAME, "generic-cookie")
                        .header("X-Douyin-Cookie", "legacy-cookie"))
                .andReturn().getResponse().getStatus();

        assertThat(status).isEqualTo(400);
        verifyNoInteractions(service);
    }

    @RestControllerAdvice
    static class IllegalArgumentAdvice {

        @ExceptionHandler(IllegalArgumentException.class)
        ResponseEntity<Void> handleIllegalArgument() {
            return ResponseEntity.badRequest().build();
        }
    }
}
