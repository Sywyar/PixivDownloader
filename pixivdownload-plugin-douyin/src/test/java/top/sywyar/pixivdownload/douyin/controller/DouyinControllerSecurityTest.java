package top.sywyar.pixivdownload.douyin.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import jakarta.servlet.http.HttpServletRequest;
import top.sywyar.pixivdownload.douyin.download.DouyinDownloadService;
import top.sywyar.pixivdownload.douyin.model.DouyinDownloadRequest;
import top.sywyar.pixivdownload.setup.SetupService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("Douyin 控制器凭证传输边界")
class DouyinControllerSecurityTest {

    @Test
    @DisplayName("非 loopback 客户端通过 HTTP 提交 Cookie 时拒绝请求")
    void rejectsCredentialSubmissionOverRemoteHttp() {
        DouyinDownloadService service = mock(DouyinDownloadService.class);
        DouyinController controller = new DouyinController(service, mock(SetupService.class));
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.isSecure()).thenReturn(false);
        when(request.getRemoteAddr()).thenReturn("203.0.113.10");

        var response = controller.download(
                new DouyinDownloadRequest("https://www.douyin.com/video/1", "title", "fixture-credential-7f4c2a91"),
                request);

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        verifyNoInteractions(service);
    }
}
