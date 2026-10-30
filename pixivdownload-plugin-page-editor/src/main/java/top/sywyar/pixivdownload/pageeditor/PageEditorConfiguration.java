package top.sywyar.pixivdownload.pageeditor;

import org.springframework.context.annotation.*;
import top.sywyar.pixivdownload.plugin.api.storage.RuntimePathProvider;
import top.sywyar.pixivdownload.plugin.api.web.page.PageCatalog;

/** 仅注入公开目录与当前 owner 的持久数据路径。 */
@Configuration(proxyBeanMethods = false)
public class PageEditorConfiguration {
    @Bean PageDocuments pageDocuments(PageCatalog catalog) { return new PageDocuments(catalog); }
    @Bean PageStore pageStore(RuntimePathProvider paths, PageDocuments documents) { return new PageStore(paths.dataDirectory().resolve("pages"), documents); }
    @Bean PageEditorController pageEditorController(PageCatalog catalog, PageStore store, top.sywyar.pixivdownload.i18n.MessageResolver messages) {
        return new PageEditorController(catalog, store, messages);
    }
}
