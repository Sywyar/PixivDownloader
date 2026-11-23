package top.sywyar.pixivdownload.novel.translation;

import io.swagger.v3.oas.annotations.media.Schema;
import top.sywyar.pixivdownload.plugin.api.web.HttpApiNullable;
import top.sywyar.pixivdownload.plugin.api.service.PluginServices;
import top.sywyar.pixivdownload.translation.api.NovelTranslationOperations;

/** 下载完成后提交翻译；不持有外部插件的队列、线程或任务。 */
public class NovelAutoTranslateService {
    private final PluginServices services;
    public NovelAutoTranslateService(PluginServices services) { this.services = services; }
    public record StatusView(@Schema(examples = {"QUEUED", "TRANSLATING", "SAME_LANGUAGE", "DONE", "FAILED"}) String phase,
                             long elapsedSeconds, int seriesPending, @HttpApiNullable String langCode,
                             boolean done, boolean failed, @HttpApiNullable @Schema(examples = {"translation.paused", "translation.cancelled", "translation.interrupted"}) String failureReason) {}
    public void submit(long id, Long series, String target, int size, boolean merge, String format) {
        services.require(NovelTranslationService.KEY).submit(new NovelTranslationOperations.AutoRequest(id, series, target, size, merge, format));
    }
    public StatusView getStatus(long id) {
        var service = services.find(NovelTranslationService.KEY);
        if (service.isEmpty()) return null;
        var status = service.get().status(id);
        return status == null ? null : new StatusView(status.phase(), status.elapsedSeconds(), status.seriesPending(),
                status.langCode(), status.done(), status.failed(), status.failureReason());
    }
}
