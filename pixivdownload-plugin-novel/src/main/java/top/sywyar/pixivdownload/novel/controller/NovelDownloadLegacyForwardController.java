package top.sywyar.pixivdownload.novel.controller;

import io.swagger.v3.oas.annotations.extensions.ExtensionProperty;
import io.swagger.v3.oas.annotations.extensions.Extension;
import io.swagger.v3.oas.annotations.links.Link;
import io.swagger.v3.oas.annotations.links.LinkParameter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import top.sywyar.pixivdownload.core.web.KnownErrorCodes;
import top.sywyar.pixivdownload.core.web.AcquisitionCredentialResolver;
import top.sywyar.pixivdownload.novel.request.NovelDownloadCommand;
import top.sywyar.pixivdownload.novel.response.NovelAlreadyDownloadedResponse;
import top.sywyar.pixivdownload.novel.response.NovelDownloadResponse;
import top.sywyar.pixivdownload.novel.response.NovelErrorResponse;
import top.sywyar.pixivdownload.novel.response.NovelProxyRateLimitResponse;
import top.sywyar.pixivdownload.novel.response.NovelQuotaExceededResponse;
import top.sywyar.pixivdownload.novel.translation.NovelAutoTranslateService;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginManagedBean;
import top.sywyar.pixivdownload.plugin.api.web.ApiErrorResponse;
import top.sywyar.pixivdownload.plugin.api.web.HttpApiGroup;

import java.io.IOException;

/**
 * 小说下载旧址兼容垫片：把历史 {@code /api/download/**} 下的小说路径服务端 forward 到小说插件自有
 * 前缀 {@code /api/novel/**}（端点已迁，见 {@link NovelDownloadController}）。
 * <p>
 * 用<b>服务端 forward</b>（而非 301/302 重定向）转发：对 {@code POST} 透明保留请求方法与请求体，
 * 对 Tampermonkey 的 {@code GM_xmlhttpRequest} 最稳；{@code AuthFilter}（{@code OncePerRequestFilter}）
 * 在原始旧址上完成一次鉴权后不再对 forward 重复过滤，故访问行为由旧址的路由声明决定（同样
 * {@code AccessPolicy.VISITOR}，与新址一致）。
 * <p>
 * 由 {@link PluginManagedBean} 排除出根包扫描、经 {@code NovelPluginConfiguration} 装配，<b>随小说插件启停</b>：
 * 小说禁用 → 垫片与 {@link NovelDownloadController} 一起不注册 → 新旧小说路径一并 404。油猴脚本与下载页
 * 经此垫片懒迁移，无需强制改脚本。
 */
@HttpApiGroup("novel-downloads")
@RestController
@RequestMapping("/api")
@PluginManagedBean
public class NovelDownloadLegacyForwardController {

    @ApiResponse(responseCode = "200", content = @Content(mediaType = "application/json", schema = @Schema(anyOf = {NovelDownloadResponse.class, NovelAlreadyDownloadedResponse.class})))
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @Content(
            mediaType = "application/json", schema = @Schema(implementation = NovelDownloadCommand.class)))
    @Parameter(name = AcquisitionCredentialResolver.HEADER_NAME, in = ParameterIn.HEADER,
            schema = @Schema(type = "string", maxLength = AcquisitionCredentialResolver.MAX_LENGTH))
    @ApiResponse(responseCode = "400", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @ApiResponse(responseCode = "401", content = @Content(mediaType = "application/json", schema = @Schema(implementation = NovelErrorResponse.class)))
    @ApiResponse(responseCode = "409", content = @Content(mediaType = "application/json", schema = @Schema(anyOf = {NovelDownloadController.PathActionResponse.class, NovelErrorResponse.class})))
    @KnownErrorCodes(status = "429", value = "task.queue.full")
    @ApiResponse(responseCode = "429", links = {
            @Link(name = "inspectArchive", operationRef = "#/paths/~1api~1archive~1status~1%7Btoken%7D/get",
                    parameters = @LinkParameter(name = "token", expression = "$response.body#/archiveToken")),
            @Link(name = "downloadArchive", operationRef = "#/paths/~1api~1archive~1download~1%7Btoken%7D/get",
                    parameters = @LinkParameter(name = "token", expression = "$response.body#/archiveToken"))
    }, content = @Content(mediaType = "application/json", schema = @Schema(anyOf = {NovelQuotaExceededResponse.class, NovelProxyRateLimitResponse.class, ApiErrorResponse.Basic.class})))
    @ApiResponse(responseCode = "502", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @ApiResponse(responseCode = "503", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @Operation(deprecated = true, extensions = @Extension(properties = @ExtensionProperty(name = "x-program-alternative", value = "POST /api/download/tasks")))
    @PostMapping("/download/pixiv/novel")
    public void forwardDownload(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        request.getRequestDispatcher("/api/novel/download").forward(request, response);
    }

    @ApiResponse(responseCode = "200", content = @Content(mediaType = "application/json", schema = @Schema(implementation = NovelDownloadController.NovelDownloadStatusResponse.class)))
    @Operation(deprecated = true)
    @GetMapping("/download/novel/status/{novelId}")
    public void forwardStatus(@PathVariable String novelId,
                              HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        request.getRequestDispatcher("/api/novel/status/" + novelId).forward(request, response);
    }

    @ApiResponse(responseCode = "200", content = @Content(mediaType = "application/json", schema = @Schema(implementation = NovelAutoTranslateService.StatusView.class)))
    @ApiResponse(responseCode = "403", content = @Content)
    @ApiResponse(responseCode = "204", content = @Content)
    @Operation(deprecated = true)
    @GetMapping("/download/novel/translate-status/{novelId}")
    public void forwardTranslateStatus(@PathVariable String novelId,
                                       HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        request.getRequestDispatcher("/api/novel/translate-status/" + novelId).forward(request, response);
    }
}
