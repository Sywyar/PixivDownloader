package top.sywyar.pixivdownload.download;

import top.sywyar.pixivdownload.core.pixiv.PixivAjaxException;
import top.sywyar.pixivdownload.download.request.DownloadRequest;
import top.sywyar.pixivdownload.plugin.api.download.lifecycle.DownloadAttempt;
import top.sywyar.pixivdownload.plugin.api.download.submission.DownloadSubmission;
import top.sywyar.pixivdownload.plugin.api.download.submission.DownloadSubmissionHandler;
import top.sywyar.pixivdownload.plugin.api.download.submission.DownloadTaskException;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginManagedBean;

import top.sywyar.pixivdownload.download.media.MediaOutputSettings;
import top.sywyar.pixivdownload.plugin.api.contract.InputFieldException;
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
    @Override public String inputSchemaResource() { return "/META-INF/pixivdownload/submission/artwork.json"; }

    @Override
    public void submit(DownloadSubmission submission, DownloadAttempt attempt, String credential) {
        long id = submission.positiveDecimalWorkId();
        if (!Set.of("fileNameTemplate", "imageFormats", "ugoiraFormats", "pathOverflowAction",
                        "mediaQuality", "mediaWebpLossless", "mediaMaximumEdge", "bookmark", "collectionId",
                        "acquisitionMode", "username", "pages")
                .containsAll(submission.options().keySet()))
            throw new InputFieldException("/options", "unknown_property");
        var options = submission.options();
        var other = new DownloadRequest.Other();
        submission.readOption("pages", value -> {
            var pages = new java.util.HashSet<Integer>();
            for (String token : value.split(",", -1)) {
                if (!token.matches("0|[1-9][0-9]*")) throw new InputFieldException("/options/pages", "invalid_format");
                if (!pages.add(Integer.parseInt(token))) throw new InputFieldException("/options/pages", "duplicate");
            }
            other.setSelectedPages(Set.copyOf(pages));
        });
        other.setFileNameTemplate(options.get("fileNameTemplate"));
        other.setPathOverflowAction(options.get("pathOverflowAction"));
        var media = new MediaOutputSettings();
        submission.readOption("imageFormats", media::setImageFormats);
        submission.readOption("ugoiraFormats", media::setUgoiraFormats);
        submission.readOption("mediaQuality", value -> media.setQuality(Integer.parseInt(value)));
        submission.readOption("mediaMaximumEdge", value -> media.setMaximumEdge(Integer.parseInt(value)));
        other.setImageFormats(options.get("imageFormats"));
        other.setUgoiraFormats(options.get("ugoiraFormats"));
        other.setMediaQuality(media.getQuality());
        other.setMediaMaximumEdge(media.getMaximumEdge());
        submission.readOption("mediaWebpLossless", value -> other.setMediaWebpLossless(booleanOption(value)));
        submission.readOption("bookmark", value -> other.setBookmark(booleanOption(value)));
        submission.readOption("collectionId", value -> {
            long collectionId = Long.parseLong(value);
            if (collectionId <= 0) throw new InputFieldException("/options/collectionId", "out_of_range");
            other.setCollectionId(collectionId);
        });
        submission.readOption("acquisitionMode", value -> {
            top.sywyar.pixivdownload.plugin.api.download.type.DownloadAcquisitionMode.fromCode(value);
            other.setAcquisitionMode(value);
        });
        String username = options.get("username");
        if (username != null && username.length() > 512) throw new InputFieldException("/options/username", "too_large");
        other.setUsername(username);
        try {
            var capture = submissionMetadata(submission.workId(), credential);
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
                if (!other.getSelectedPages().isEmpty()) throw new InputFieldException("/options/pages", "not_applicable");
                var animation = fetch.resolveUgoira(submission.workId(), credential);
                other.setUgoira(true);
                other.setUgoiraZipUrl(animation.zipUrl());
                other.setUgoiraDelays(animation.delays());
                urls = List.of(animation.zipUrl());
            } else {
                urls = fetch.resolveArtworkPages(submission.workId(), credential).urls();
                if (meta.pageCount() > 0 && urls.size() != meta.pageCount())
                    throw new IOException("incomplete upstream artwork page list");
                int pageCount = urls.size();
                if (other.getSelectedPages().stream().anyMatch(page -> page >= pageCount))
                    throw new InputFieldException("/options/pages", "out_of_range");
            }
            other.setResolvedPageList(true);
            other.setSourceUploadDate(capture.uploadDate());
            if (urls.isEmpty()) throw new IllegalArgumentException("artwork has no files");
            executor.submitImages(attempt, id, meta.title(), urls,
                    "https://www.pixiv.net/artworks/" + id, other, credential, null);
        } catch (IOException failure) {
            throw new DownloadTaskException(DownloadTaskException.Code.REJECTED);
        }
    }

    private PixivFetchService.ArtworkMetaCapture submissionMetadata(String workId, String credential) throws IOException {
        try {
            return fetch.fetchArtworkMetaCapture(workId, credential);
        } catch (PixivAjaxException failure) {
            if (failure.statusCode() == 404) throw new DownloadTaskException(DownloadTaskException.Code.WORK_NOT_FOUND);
            throw failure;
        }
    }

    private static boolean booleanOption(String value) {
        if (!Set.of("true", "false").contains(value)) throw new IllegalArgumentException("invalid boolean option");
        return Boolean.parseBoolean(value);
    }
}
