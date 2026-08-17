package top.sywyar.pixivdownload.core.work.service;

/**
 * 下载作品文件名模板与合规作者名的核心驻留端口。
 */
public interface WorkFileNameCatalog {

    /**
     * 返回对应值。
     *
     * @param template 模板
     * @return 方法返回的数值
     */
    long getOrCreateTemplateId(String template);

    /**
     * 返回对应值。
     *
     * @param normalizedAuthorName 规范化值作者名称
     * @return 方法返回的数值
     */
    long getOrCreateAuthorNameId(String normalizedAuthorName);
}
