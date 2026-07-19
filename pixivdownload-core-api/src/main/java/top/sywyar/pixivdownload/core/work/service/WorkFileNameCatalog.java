package top.sywyar.pixivdownload.core.work.service;

/**
 * 下载作品文件名模板与合规作者名的核心驻留端口。
 */
public interface WorkFileNameCatalog {

    long getOrCreateTemplateId(String template);

    long getOrCreateAuthorNameId(String normalizedAuthorName);
}
