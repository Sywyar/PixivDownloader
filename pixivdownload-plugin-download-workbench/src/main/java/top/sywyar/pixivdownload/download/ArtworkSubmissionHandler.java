package top.sywyar.pixivdownload.download;

import top.sywyar.pixivdownload.download.request.DownloadRequest;
import top.sywyar.pixivdownload.plugin.api.download.lifecycle.DownloadAttempt;
import top.sywyar.pixivdownload.plugin.api.download.submission.DownloadSubmission;
import top.sywyar.pixivdownload.plugin.api.download.submission.DownloadSubmissionHandler;
import top.sywyar.pixivdownload.plugin.api.download.submission.DownloadTaskException;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginManagedBean;

import java.io.IOException;
import java.util.List;
import java.util.Set;

/** 公开命令只接收作品身份和输出选择，下载元数据由 Pixiv 读取。 */
@PluginManagedBean
public final class ArtworkSubmissionHandler implements DownloadSubmissionHandler {
    private final PixivFetchService fetch;
    private final ArtworkDownloadExecutor executor;

    public ArtworkSubmissionHandler(PixivFetchService fetch, ArtworkDownloadExecutor executor) {
        this.fetch = fetch;
        this.executor = executor;
    }

    @Override public String workType() { return "artwork"; }

    @Override
    public void submit(DownloadSubmission submission, DownloadAttempt attempt, String credential) {
        if (!submission.workId().matches("[1-9][0-9]{0,18}")
                || !Set.of("fileNameTemplate", "imageFormats", "ugoiraFormats", "pathOverflowAction")
                .containsAll(submission.options().keySet()))
            throw new IllegalArgumentException("invalid artwork submission");
        long id = Long.parseLong(submission.workId());
        var options = submission.options();
        var other = new DownloadRequest.Other();
        other.setFileNameTemplate(options.get("fileNameTemplate"));
        other.setImageFormats(options.get("imageFormats"));
        other.setUgoiraFormats(options.get("ugoiraFormats"));
        other.setPathOverflowAction(options.get("pathOverflowAction"));
        other.resolveMediaOutputSettings();
        try {
            var capture = fetch.fetchArtworkMetaCapture(submission.workId(), credential);
            var meta = capture.meta();
            other.setAuthorId(meta.authorId());
            other.setAuthorName(meta.authorName());
            other.setXRestrict(meta.xRestrict());
            other.setAi(meta.ai());
            other.setDescription(meta.description());
            other.setTags(meta.tags());
            other.setSeriesId(meta.seriesId());
            other.setSeriesOrder(meta.seriesOrder());
            other.setSeriesTitle(meta.seriesTitle());
            other.setIllustType(meta.illustType());
            other.setRawMetaJson(capture.body().toString());
            List<String> urls;
            if (meta.isUgoira()) {
                var animation = fetch.resolveUgoira(submission.workId(), credential);
                other.setUgoira(true);
                other.setUgoiraZipUrl(animation.zipUrl());
                other.setUgoiraDelays(animation.delays());
                urls = List.of(animation.zipUrl());
            } else {
                urls = fetch.resolveArtworkPages(submission.workId(), credential).urls();
            }
            if (urls.isEmpty()) throw new IllegalArgumentException("artwork has no files");
            executor.submitImages(attempt, id, meta.title(), urls,
                    "https://www.pixiv.net/artworks/" + id, other, credential, null);
        } catch (IOException failure) {
            throw new DownloadTaskException(DownloadTaskException.Code.REJECTED);
        }
    }
}
