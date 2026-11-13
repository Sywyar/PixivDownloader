package top.sywyar.pixivdownload.novel.download;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.core.JacksonException;
import top.sywyar.pixivdownload.core.pixiv.PixivAjaxClient;
import top.sywyar.pixivdownload.novel.request.NovelDownloadRequestFactory;
import top.sywyar.pixivdownload.novel.schedule.PixivNovelMetadata;
import top.sywyar.pixivdownload.plugin.api.download.lifecycle.DownloadAttempt;
import top.sywyar.pixivdownload.plugin.api.download.submission.DownloadSubmission;
import top.sywyar.pixivdownload.plugin.api.download.submission.DownloadSubmissionHandler;
import top.sywyar.pixivdownload.plugin.api.download.submission.DownloadTaskException;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginManagedBean;

import java.net.URI;
import java.util.Set;

/** 小说公开提交仍从受控 Pixiv 客户端取得正文，不接受调用方伪造作品内容。 */
@PluginManagedBean
public final class NovelSubmissionHandler implements DownloadSubmissionHandler {
    private final PixivAjaxClient client;
    private final ObjectMapper mapper;
    private final NovelDownloadService downloads;
    private final jakarta.validation.Validator validator;

    public NovelSubmissionHandler(PixivAjaxClient client, ObjectMapper mapper, NovelDownloadService downloads,
                                  jakarta.validation.Validator validator) {
        this.client = client;
        this.mapper = mapper;
        this.downloads = downloads;
        this.validator = validator;
    }

    @Override public String workType() { return "novel"; }

    @Override
    public void submit(DownloadSubmission submission, DownloadAttempt attempt, String credential) {
        var options = submission.options();
        if (!submission.workId().matches("[1-9][0-9]{0,18}")
                || !Set.of("fileNameTemplate", "format", "pathOverflowAction", "bookmark", "collectionId",
                        "autoTranslate", "autoTranslateLanguage", "autoTranslateSegmentSize", "autoTranslateMerge",
                        "autoTranslateMergeFormat", "acquisitionMode", "username").containsAll(options.keySet())
                || (options.containsKey("format") && !Set.of("txt", "html", "epub").contains(options.get("format"))))
            throw new IllegalArgumentException("invalid novel submission");
        long id = Long.parseLong(submission.workId());
        var choice = new top.sywyar.pixivdownload.novel.request.NovelDownloadCommand.Other();
        choice.setFileNameTemplate(options.get("fileNameTemplate"));
        choice.setPathOverflowAction(options.get("pathOverflowAction"));
        choice.setFormat(options.get("format"));
        choice.setAcquisitionMode(options.get("acquisitionMode"));
        choice.setUsername(options.get("username"));
        choice.setBookmark(booleanOption(options.get("bookmark")));
        if (options.containsKey("collectionId")) choice.setCollectionId(Long.parseLong(options.get("collectionId")));
        choice.setAutoTranslate(booleanOption(options.get("autoTranslate")));
        choice.setAutoTranslateLanguage(options.get("autoTranslateLanguage"));
        if (options.containsKey("autoTranslateSegmentSize"))
            choice.setAutoTranslateSegmentSize(Integer.parseInt(options.get("autoTranslateSegmentSize")));
        choice.setAutoTranslateMerge(booleanOption(options.get("autoTranslateMerge")));
        choice.setAutoTranslateMergeFormat(options.get("autoTranslateMergeFormat"));
        if (!validator.validate(choice).isEmpty()) throw new IllegalArgumentException("invalid novel options");
        try {
            var response = mapper.readTree(client.get(
                    URI.create("https://www.pixiv.net/ajax/novel/" + id + "?lang=zh"), credential));
            if (response == null || !response.isObject() || response.path("error").asBoolean(false)
                    || !response.path("body").isObject())
                throw new IllegalArgumentException("invalid Pixiv novel response");
            var body = response.path("body");
            var request = NovelDownloadRequestFactory.fromPixiv(PixivNovelMetadata.parse(id, body), null,
                    credential, NovelDownloadRequestFactory.boundedRawMetadata(mapper, body));
            choice.applyTo(request.getOther());
            downloads.submit(attempt, request, null);
        } catch (JacksonException failure) {
            throw new DownloadTaskException(DownloadTaskException.Code.REJECTED);
        }
    }

    private static boolean booleanOption(String value) {
        if (value == null) return false;
        if (!Set.of("true", "false").contains(value)) throw new IllegalArgumentException("invalid boolean option");
        return Boolean.parseBoolean(value);
    }
}
