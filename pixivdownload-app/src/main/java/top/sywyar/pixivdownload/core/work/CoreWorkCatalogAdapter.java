package top.sywyar.pixivdownload.core.work;

import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.core.db.PixivDatabase;
import top.sywyar.pixivdownload.core.work.service.WorkFileNameCatalog;
import top.sywyar.pixivdownload.core.work.service.WorkTagCatalog;

/**
 * 将共享作品字典端口适配到宿主核心数据库实现。
 */
@Component
public class CoreWorkCatalogAdapter implements WorkTagCatalog, WorkFileNameCatalog {

    private final PixivDatabase pixivDatabase;

    public CoreWorkCatalogAdapter(PixivDatabase pixivDatabase) {
        this.pixivDatabase = pixivDatabase;
    }

    @Override
    public Long getOrCreateTagId(String name, String translatedName) {
        return pixivDatabase.upsertTagAndGetId(name, translatedName);
    }

    @Override
    public long getOrCreateTemplateId(String template) {
        return pixivDatabase.getOrCreateFileNameTemplateId(template);
    }

    @Override
    public long getOrCreateAuthorNameId(String normalizedAuthorName) {
        return pixivDatabase.getOrCreateFileAuthorNameId(normalizedAuthorName);
    }
}
