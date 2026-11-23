package top.sywyar.pixivdownload.novel.download;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.core.JacksonException;
import top.sywyar.pixivdownload.core.pixiv.PixivAjaxClient;
import top.sywyar.pixivdownload.core.pixiv.PixivAjaxException;
import top.sywyar.pixivdownload.novel.request.NovelDownloadRequestFactory;
import top.sywyar.pixivdownload.novel.schedule.PixivNovelMetadata;
import top.sywyar.pixivdownload.plugin.api.download.lifecycle.DownloadAttempt;
import top.sywyar.pixivdownload.plugin.api.download.submission.DownloadSubmission;
import top.sywyar.pixivdownload.plugin.api.download.submission.DownloadSubmissionHandler;
import top.sywyar.pixivdownload.plugin.api.download.submission.DownloadTaskException;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginManagedBean;

import top.sywyar.pixivdownload.plugin.api.contract.InputFieldException;
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
    @Override public String inputSchemaResource() { return "/META-INF/pixivdownload/submission/novel.json"; }

    @Override
    public void submit(DownloadSubmission submission, DownloadAttempt attempt, String credential) {
        var options = submission.options();
        long id = submission.positiveDecimalWorkId();
        if (!Set.of("fileNameTemplate", "format", "pathOverflowAction", "bookmark", "collectionId",
                        "autoTranslate", "autoTranslateLanguage", "autoTranslateSegmentSize", "autoTranslateMerge",
                        "autoTranslateMergeFormat", "acquisitionMode", "username").containsAll(options.keySet()))
            throw new InputFieldException("/options", "unknown_property");
        if (options.containsKey("format") && !Set.of("txt", "html", "epub").contains(options.get("format")))
            throw new InputFieldException("/options/format", "invalid_value");
        var choice = new top.sywyar.pixivdownload.novel.request.NovelDownloadCommand.Other();
        choice.setFileNameTemplate(options.get("fileNameTemplate"));
        choice.setPathOverflowAction(options.get("pathOverflowAction"));
        choice.setFormat(options.get("format"));
        choice.setAcquisitionMode(options.get("acquisitionMode"));
        choice.setUsername(options.get("username"));
        submission.readOption("bookmark", value -> choice.setBookmark(booleanOption(value)));
        submission.readOption("collectionId", value -> choice.setCollectionId(Long.parseLong(value)));
        submission.readOption("autoTranslate", value -> choice.setAutoTranslate(booleanOption(value)));
        choice.setAutoTranslateLanguage(options.get("autoTranslateLanguage"));
        submission.readOption("autoTranslateSegmentSize", value -> choice.setAutoTranslateSegmentSize(Integer.parseInt(value)));
        submission.readOption("autoTranslateMerge", value -> choice.setAutoTranslateMerge(booleanOption(value)));
        choice.setAutoTranslateMergeFormat(options.get("autoTranslateMergeFormat"));
        validator.validate(choice).stream().map(violation -> violation.getPropertyPath().toString()).sorted().findFirst()
                .ifPresent(name -> { throw new InputFieldException("/options/" + name, "invalid_value"); });
        try {
            var response = mapper.readTree(submissionMetadata(id, credential));
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

    private String submissionMetadata(long id, String credential) {
        try {
            return client.get(URI.create("https://www.pixiv.net/ajax/novel/" + id + "?lang=zh"), credential);
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
