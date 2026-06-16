package top.sywyar.pixivdownload.novel.controller;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginManagedBean;

import java.io.IOException;

/**
 * 小说下载旧址兼容垫片：把历史 {@code /api/download/**} 下的小说路径服务端 forward 到小说插件自有
 * 前缀 {@code /api/novel/**}（端点已迁，见 {@link NovelDownloadController}）。
 * <p>
 * 用<b>服务端 forward</b>（而非 301/302 重定向）转发：对 {@code POST} 透明保留请求方法与请求体，
 * 对 Tampermonkey 的 {@code GM_xmlhttpRequest} 最稳；{@code AuthFilter}（{@code OncePerRequestFilter}）
 * 在原始旧址上完成一次鉴权后不再对 forward 重复过滤，故访问行为由旧址的路由声明决定（同样
 * {@code SESSION_OR_VISITOR}，与新址一致）。
 * <p>
 * 由 {@link PluginManagedBean} 排除出根包扫描、经 {@code NovelPluginConfiguration} 装配，<b>随小说插件启停</b>：
 * 小说禁用 → 垫片与 {@link NovelDownloadController} 一起不注册 → 新旧小说路径一并 404。油猴脚本与下载页
 * 经此垫片懒迁移，无需强制改脚本。
 */
@RestController
@RequestMapping("/api")
@PluginManagedBean
@RequiredArgsConstructor
public class NovelDownloadLegacyForwardController {

    @PostMapping("/download/pixiv/novel")
    public void forwardDownload(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        request.getRequestDispatcher("/api/novel/download").forward(request, response);
    }

    @GetMapping("/download/novel/status/{novelId}")
    public void forwardStatus(@PathVariable String novelId,
                              HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        request.getRequestDispatcher("/api/novel/status/" + novelId).forward(request, response);
    }

    @GetMapping("/download/novel/translate-status/{novelId}")
    public void forwardTranslateStatus(@PathVariable String novelId,
                                       HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        request.getRequestDispatcher("/api/novel/translate-status/" + novelId).forward(request, response);
    }
}
