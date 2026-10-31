package top.sywyar.pixivdownload.pageeditor;

import org.springframework.context.annotation.*;
import top.sywyar.pixivdownload.plugin.api.storage.RuntimePathProvider;
import top.sywyar.pixivdownload.plugin.api.web.page.PageCatalog;
import top.sywyar.pixivdownload.pageeditor.components.*;

/** 仅注入公开目录与当前 owner 的持久数据路径。 */
@Configuration(proxyBeanMethods = false)
public class PageEditorConfiguration {
    @Bean ComponentLibrary componentLibrary(RuntimePathProvider paths) { return new ComponentLibrary(paths.dataDirectory().resolve("components")); }
    @Bean ComponentPreviews componentPreviews() { return new ComponentPreviews(); }
    @Bean CustomComponentContribution customComponentContribution(ComponentLibrary library) { return new CustomComponentContribution(library); }
    @Bean ComponentLibraryController componentLibraryController(ComponentLibrary library, ComponentPreviews previews,
            top.sywyar.pixivdownload.i18n.MessageResolver messages) { return new ComponentLibraryController(library, previews, messages); }
    @Bean ComponentSandboxController componentSandboxController(ComponentLibrary library, ComponentPreviews previews) {
        return new ComponentSandboxController(library, previews);
    }
    @Bean PageDocuments pageDocuments(PageCatalog catalog, ComponentLibrary library) { return new PageDocuments(catalog, library); }
    @Bean PageStore pageStore(RuntimePathProvider paths, PageDocuments documents) { return new PageStore(paths.dataDirectory().resolve("pages"), documents); }
    @Bean PageEditorController pageEditorController(PageCatalog catalog, PageStore store, top.sywyar.pixivdownload.i18n.MessageResolver messages,
            ComponentLibrary library) {
        return new PageEditorController(catalog, store, messages, library);
    }
}
