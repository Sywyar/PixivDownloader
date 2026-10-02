package top.sywyar.pixivdownload.novel.download;

import com.fasterxml.jackson.databind.ObjectMapper;
import top.sywyar.pixivdownload.core.pixiv.PixivAjaxClient;
import top.sywyar.pixivdownload.novel.request.NovelDownloadRequestFactory;
import top.sywyar.pixivdownload.novel.schedule.PixivNovelMetadata;
import top.sywyar.pixivdownload.plugin.api.download.lifecycle.DownloadAttempt;
import top.sywyar.pixivdownload.plugin.api.download.submission.DownloadSubmission;
import top.sywyar.pixivdownload.plugin.api.download.submission.DownloadSubmissionHandler;
import top.sywyar.pixivdownload.plugin.api.download.submission.DownloadTaskException;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginManagedBean;

import java.io.IOException;
import java.net.URI;
import java.util.Set;

/** 小说公开提交仍从受控 Pixiv 客户端取得正文，不接受调用方伪造作品内容。 */
@PluginManagedBean
public final class NovelSubmissionHandler implements DownloadSubmissionHandler {
    private final PixivAjaxClient client;
    private final ObjectMapper mapper;
    private final NovelDownloadService downloads;

    public NovelSubmissionHandler(PixivAjaxClient client, ObjectMapper mapper, NovelDownloadService downloads) {
        this.client = client;
        this.mapper = mapper;
        this.downloads = downloads;
    }

    @Override public String workType() { return "novel"; }

    @Override
    public void submit(DownloadSubmission submission, DownloadAttempt attempt, String credential) {
        var options = submission.options();
        if (!submission.workId().matches("[1-9][0-9]{0,18}")
                || !Set.of("fileNameTemplate", "format", "pathOverflowAction").containsAll(options.keySet())
                || (options.containsKey("format") && !Set.of("txt", "html", "epub").contains(options.get("format"))))
            throw new IllegalArgumentException("invalid novel submission");
        long id = Long.parseLong(submission.workId());
        try {
            var response = mapper.readTree(client.get(
                    URI.create("https://www.pixiv.net/ajax/novel/" + id + "?lang=zh"), credential));
            if (response == null || !response.isObject() || response.path("error").asBoolean(false)
                    || !response.path("body").isObject())
                throw new IllegalArgumentException("invalid Pixiv novel response");
            var body = response.path("body");
            var request = NovelDownloadRequestFactory.fromPixiv(PixivNovelMetadata.parse(id, body), null,
                    credential, NovelDownloadRequestFactory.boundedRawMetadata(mapper, body));
            request.getOther().setFileNameTemplate(options.get("fileNameTemplate"));
            request.getOther().setPathOverflowAction(options.get("pathOverflowAction"));
            if (options.containsKey("format")) request.getOther().setFormat(options.get("format"));
            downloads.submit(attempt, request, null);
        } catch (IOException failure) {
            throw new DownloadTaskException(DownloadTaskException.Code.REJECTED);
        }
    }
}
