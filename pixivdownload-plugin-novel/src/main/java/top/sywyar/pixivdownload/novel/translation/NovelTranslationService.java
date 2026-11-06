package top.sywyar.pixivdownload.novel.translation;

import top.sywyar.pixivdownload.plugin.api.service.*;
import top.sywyar.pixivdownload.translation.api.*;

/** 小说路由的翻译能力适配；业务由活动提供者承担。 */
public class NovelTranslationService {
    public static final ServiceKey<NovelTranslationOperations> KEY = new ServiceKey<>(NovelTranslationDocuments.CONTRACT_ID, NovelTranslationOperations.class);
    private final PluginServices services;
    public NovelTranslationService(PluginServices services) { this.services = services; }
    public NovelTranslationOperations.Result translateChapter(long id, String target, int size, boolean overwrite, String hint, Long glossary,
                                   boolean body, boolean title, boolean description) {
        return services.require(KEY).translate(new NovelTranslationOperations.Request(id, target, size, overwrite,
                hint, glossary, body, title, description, null));
    }
    public String resolveLangCode(String target) { return services.require(KEY).resolveLanguage(target, null); }
    public NovelTranslationOperations.SeriesResult translateSeriesTitle(long id, String target, String hint, Long glossary, boolean title, boolean description) {
        return services.require(KEY).translateSeries(new NovelTranslationOperations.SeriesRequest(id, target, hint, glossary, title, description, null));
    }
}
