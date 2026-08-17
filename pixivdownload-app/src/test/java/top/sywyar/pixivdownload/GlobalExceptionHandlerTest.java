package top.sywyar.pixivdownload;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.http.ResponseEntity;
import top.sywyar.pixivdownload.plugin.api.web.ApiErrorResponse;
import top.sywyar.pixivdownload.core.asset.StagedFileDeletion.UnsafeDeletionPathException;
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

        ResponseEntity<ApiErrorResponse> response = handler.handleSecurity(ex);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("error.request.security");
        assertThat(response.getBody().error()).isEqualTo("只允许 HTTPS 协议的下载 URL");
    }

    @Test
    @DisplayName("非法参数应返回 400 并保留具体错误消息")
    void shouldHandle400ForIllegalArgumentException() {
        IllegalArgumentException ex = new IllegalArgumentException(
                "Conflicting acquisition credential headers");

        ResponseEntity<ApiErrorResponse> response = handler.handleIllegalArgument(ex, Locale.US);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("error.request.param.invalid");
        assertThat(response.getBody().error())
                .isEqualTo("Conflicting acquisition credential headers");
    }

    @Test
    @DisplayName("通用异常应返回 500 和错误消息")
    void shouldHandle500ForGenericException() {
        Exception ex = new RuntimeException("意外错误");

        ResponseEntity<ApiErrorResponse> response = handler.handleGeneric(ex, Locale.SIMPLIFIED_CHINESE);

        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("error.unexpected");
        assertThat(response.getBody().error()).isEqualTo("意外错误");
    }

    @Test
    @DisplayName("异常消息为 null 时应返回本地化默认消息")
    void shouldHandleNullMessage() {
        Exception ex = new RuntimeException();

        ResponseEntity<ApiErrorResponse> response = handler.handleGeneric(ex, Locale.SIMPLIFIED_CHINESE);

        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("error.unexpected");
        assertThat(response.getBody().error()).isEqualTo("发生未处理异常");
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
        ResponseEntity<ApiErrorResponse> response = handler.handleQueueNotAccepting(
                new QueueNotAcceptingException("illust"), Locale.SIMPLIFIED_CHINESE);

        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("plugin.unavailable.quiesced");
        assertThat(response.getBody().error()).isEqualTo("插件正在停用中，暂时不可用，请稍后重试");
    }

    @Test
    @DisplayName("下载队列已满时应返回本地化 429")
    void shouldHandleFullDownloadQueueAsTooManyRequests() {
        ResponseEntity<ApiErrorResponse> response = handler.handleQueueFull(
                new TaskRejectedException("full"), Locale.SIMPLIFIED_CHINESE);

        assertThat(response.getStatusCode().value()).isEqualTo(429);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("task.queue.full");
        assertThat(response.getBody().error()).isEqualTo("任务排队已满，请稍后重试");
    }

    @Test
    @DisplayName("插画与小说可见性领域失败应映射为各自本地化 403")
    void shouldMapWorkVisibilityDeniedToLocalizedForbidden() {
        ResponseEntity<ApiErrorResponse> artwork = handler.handleWorkVisibilityDenied(
                new WorkVisibilityDeniedException(WorkType.ARTWORK, 42L),
                Locale.SIMPLIFIED_CHINESE);
        ResponseEntity<ApiErrorResponse> novel = handler.handleWorkVisibilityDenied(
                new WorkVisibilityDeniedException(WorkType.NOVEL, 43L),
                Locale.SIMPLIFIED_CHINESE);

        assertThat(artwork.getStatusCode().value()).isEqualTo(403);
        assertThat(artwork.getBody()).isNotNull();
        assertThat(artwork.getBody().code()).isEqualTo("guest.invite.forbidden");
        assertThat(artwork.getBody().error()).isEqualTo("该作品不在你的可见范围内");
        assertThat(novel.getStatusCode().value()).isEqualTo(403);
        assertThat(novel.getBody()).isNotNull();
        assertThat(novel.getBody().code()).isEqualTo("guest.invite.novel.forbidden");
        assertThat(novel.getBody().error()).isEqualTo("该小说不在你的可见范围内");
    }

    @Test
    @DisplayName("作品本地文件删除失败应在 Web 层映射为本地化 409")
    void shouldMapWorkDeletionFailureToLocalizedConflict() {
        ResponseEntity<ApiErrorResponse> response = handler.handleWorkDeletion(
                new WorkDeletionException(
                        WorkDeletionException.Reason.LOCAL_FILE_DELETE_FAILED,
                        WorkType.NOVEL,
                        42L),
                Locale.SIMPLIFIED_CHINESE);

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("work.delete.file-failed");
        assertThat(response.getBody().error())
                .isEqualTo("小说 42 的磁盘文件未能全部删除（被锁定或权限不足），"
                        + "已中止数据库清理，请稍后重试或检查文件占用情况");
    }

    @Test
    @DisplayName("不安全删除路径应映射为包含具体路径的本地化 409")
    void shouldMapUnsafeDeletionPathToLocalizedConflict() {
        ResponseEntity<ApiErrorResponse> response = handler.handleUnsafeDeletionPath(
                new UnsafeDeletionPathException("C:\\downloads\\linked"),
                Locale.SIMPLIFIED_CHINESE);

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("work.delete.path-unsafe");
        assertThat(response.getBody().error())
                .isEqualTo("删除目标路径不安全，已中止文件与数据库清理: C:\\downloads\\linked");
    }

    @Test
    @DisplayName("Pixiv 稳定端口的上游状态应保持本地化 502 映射")
    void shouldMapPixivAjaxHttpFailureToLocalizedBadGateway() {
        ResponseEntity<ApiErrorResponse> response = handler.handlePixivAjax(
                new PixivAjaxException(PixivAjaxFailure.HTTP_STATUS, 403),
                Locale.SIMPLIFIED_CHINESE);

        assertThat(response.getStatusCode().value()).isEqualTo(502);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("error.pixiv.upstream.unauthorized");
        assertThat(response.getBody().error())
                .contains("Pixiv 拒绝了请求")
                .doesNotContain("403");
    }

    @Test
    @DisplayName("Pixiv 响应超过安全上限时应返回明确的本地化 502")
    void shouldMapOversizedPixivResponseToLocalizedBadGateway() {
        ResponseEntity<ApiErrorResponse> response = handler.handlePixivAjax(
                new PixivAjaxException(PixivAjaxFailure.RESPONSE_TOO_LARGE, 0),
                Locale.US);

        assertThat(response.getStatusCode().value()).isEqualTo(502);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("error.pixiv.response.too-large");
        assertThat(response.getBody().error())
                .isEqualTo("The Pixiv response exceeded the safe size limit and was rejected.");
    }
}
