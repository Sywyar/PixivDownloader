package top.sywyar.pixivdownload.novel.download;

import top.sywyar.pixivdownload.core.work.importing.*;
import top.sywyar.pixivdownload.core.work.model.WorkType;
import top.sywyar.pixivdownload.core.work.service.WorkFileNameCatalog;
import top.sywyar.pixivdownload.novel.db.NovelDatabase;

/** 小说正文和标签仍由小说 owner 登记，源文件由宿主作为只读引用保存。 */
public final class NovelFileImportHandler implements WorkFileImportHandler {
    private final NovelDatabase database;
    private final WorkFileNameCatalog names;
    public NovelFileImportHandler(NovelDatabase database, WorkFileNameCatalog names) {
        this.database = database; this.names = names;
    }
    @Override public WorkType workType() { return WorkType.NOVEL; }
    @Override public boolean register(WorkFileImportRequest r) {
        if (database.getNovel(r.workId()) != null) return false;
        String name = r.pageFiles().get(0).getFileName().toString();
        String ext = name.substring(name.lastIndexOf('.') + 1).toLowerCase(java.util.Locale.ROOT);
        Long author = r.authorName() == null ? null : names.getOrCreateAuthorNameId(r.authorName());
        database.insertNovel(r.workId(), r.title(), r.sourceRoot().toString(), 1, ext,
                database.getUniqueTime(), r.restriction(), r.aiGenerated(), r.authorId(), r.description(),
                names.getOrCreateTemplateId("{id}_p{page}"), author, r.seriesId(), r.seriesOrder(),
                null, r.novelContent().length(), null, 1, null, null, r.novelContent(), null);
        database.saveNovelTags(r.workId(), r.tags());
        return true;
    }
}
