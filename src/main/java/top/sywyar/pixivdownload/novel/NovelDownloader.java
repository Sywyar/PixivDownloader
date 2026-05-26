package top.sywyar.pixivdownload.novel;

import top.sywyar.pixivdownload.novel.request.NovelDownloadRequest;

/**
 * 小说下载入口的窄接缝。
 *
 * <p>由 {@link NovelDownloadService} 实现。让调度 / 自动化等上层功能只依赖这一个方法，
 * 而不必注入 {@code NovelDownloadService} 本体（其依赖众多）。与 {@code download/} 的
 * {@link top.sywyar.pixivdownload.download.ArtworkDownloader} 对称。
 * 实现仍是异步执行（{@code @Async}），调用方不应假设方法返回即下载完成。
 */
public interface NovelDownloader {

    /**
     * 异步下载单本小说。语义与 {@link NovelDownloadService#download} 完全一致：
     * {@code request} 必须已带好正文 markup（{@link NovelDownloadRequest#getContent()}）与
     * 元数据（{@link NovelDownloadRequest.Other}）。
     *
     * @param request  含 novelId / title / content / cookie / other 的下载请求
     * @param userUuid 多人模式访客 UUID；管理员 / solo 传 null（不计配额）
     */
    void download(NovelDownloadRequest request, String userUuid);
}
