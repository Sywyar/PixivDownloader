package top.sywyar.pixivdownload;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import top.sywyar.pixivdownload.common.ErrorResponse;
import top.sywyar.pixivdownload.i18n.TestI18nBeans;
import top.sywyar.pixivdownload.plugin.api.download.queue.QueueNotAcceptingException;
import top.sywyar.pixivdownload.core.pixiv.PixivAjaxException;
import top.sywyar.pixivdownload.core.pixiv.PixivAjaxFailure;
import top.sywyar.pixivdownload.core.work.model.WorkType;
import top.sywyar.pixivdownload.core.work.service.WorkDeletionException;
import top.sywyar.pixivdownload.core.work.service.WorkVisibilityDeniedException;

import java.io.IOException;
import java.util.Locale;

import static org.assertj.core.api.Assertions.*;

@DisplayName("GlobalExceptionHandler 单元测试")
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler(TestI18nBeans.appMessages());

    @Test
    @DisplayName("SecurityException 应返回 400 和错误消息")
    void shouldHandle400ForSecurityException() {
        SecurityException ex = new SecurityException("只允许 HTTPS 协议的下载 URL");

        ResponseEntity<ErrorResponse> response = handler.handleSecurity(ex);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getError()).isEqualTo("只允许 HTTPS 协议的下载 URL");
    }

    @Test
    @DisplayName("通用异常应返回 500 和错误消息")
    void shouldHandle500ForGenericException() {
        Exception ex = new RuntimeException("意外错误");

        ResponseEntity<ErrorResponse> response = handler.handleGeneric(ex, Locale.SIMPLIFIED_CHINESE);

        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getError()).isEqualTo("意外错误");
    }

    @Test
    @DisplayName("异常消息为 null 时应返回本地化默认消息")
    void shouldHandleNullMessage() {
        Exception ex = new RuntimeException();

        ResponseEntity<ErrorResponse> response = handler.handleGeneric(ex, Locale.SIMPLIFIED_CHINESE);

        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getError()).isEqualTo("发生未处理异常");
    }

    @Test
    @DisplayName("客户端断开导致的 IO 异常应视为无响应体的正常断连")
    void shouldTreatClientDisconnectAsNoContent() {
        IOException ex = new IOException("你的主机中的软件中止了一个已建立的连接。");

        ResponseEntity<?> response = handler.handleIOException(ex, Locale.SIMPLIFIED_CHINESE);

        assertThat(response.getStatusCode().value()).isEqualTo(204);
        assertThat(response.getBody()).isNull();
    }

    @Test
    @DisplayName("队列清退竞态应返回本地化 503 而不是裸 500")
    void shouldHandleQuiescedQueueAsServiceUnavailable() {
        ResponseEntity<ErrorResponse> response = handler.handleQueueNotAccepting(
                new QueueNotAcceptingException("illust"), Locale.SIMPLIFIED_CHINESE);

        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getError()).isEqualTo("插件正在停用中，暂时不可用，请稍后重试");
    }

    @Test
    @DisplayName("插画与小说可见性领域失败应映射为各自本地化 403")
    void shouldMapWorkVisibilityDeniedToLocalizedForbidden() {
        ResponseEntity<ErrorResponse> artwork = handler.handleWorkVisibilityDenied(
                new WorkVisibilityDeniedException(WorkType.ARTWORK, 42L),
                Locale.SIMPLIFIED_CHINESE);
        ResponseEntity<ErrorResponse> novel = handler.handleWorkVisibilityDenied(
                new WorkVisibilityDeniedException(WorkType.NOVEL, 43L),
                Locale.SIMPLIFIED_CHINESE);

        assertThat(artwork.getStatusCode().value()).isEqualTo(403);
        assertThat(artwork.getBody()).isNotNull();
        assertThat(artwork.getBody().getError()).isEqualTo("该作品不在你的可见范围内");
        assertThat(novel.getStatusCode().value()).isEqualTo(403);
        assertThat(novel.getBody()).isNotNull();
        assertThat(novel.getBody().getError()).isEqualTo("该小说不在你的可见范围内");
    }

    @Test
    @DisplayName("作品本地文件删除失败应在 Web 层映射为本地化 409")
    void shouldMapWorkDeletionFailureToLocalizedConflict() {
        ResponseEntity<ErrorResponse> response = handler.handleWorkDeletion(
                new WorkDeletionException(
                        WorkDeletionException.Reason.LOCAL_FILE_DELETE_FAILED,
                        WorkType.NOVEL,
                        42L),
                Locale.SIMPLIFIED_CHINESE);

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getError())
                .isEqualTo("小说 42 的磁盘文件未能全部删除（被锁定或权限不足），"
                        + "已中止数据库清理，请稍后重试或检查文件占用情况");
    }

    @Test
    @DisplayName("Pixiv 稳定端口的上游状态应保持本地化 502 映射")
    void shouldMapPixivAjaxHttpFailureToLocalizedBadGateway() {
        ResponseEntity<ErrorResponse> response = handler.handlePixivAjax(
                new PixivAjaxException(PixivAjaxFailure.HTTP_STATUS, 403),
                Locale.SIMPLIFIED_CHINESE);

        assertThat(response.getStatusCode().value()).isEqualTo(502);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getError())
                .contains("Pixiv 拒绝了请求")
                .doesNotContain("403");
    }
}
