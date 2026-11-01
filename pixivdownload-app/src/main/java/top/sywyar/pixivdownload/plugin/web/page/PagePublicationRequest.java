package top.sywyar.pixivdownload.plugin.web.page;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.plugin.api.web.page.PagePublications;
import java.util.List;
import java.util.Optional;

/** HTML、标题、投影、缓存和 CSP 共用请求开始时固定的同一快照。 */
@Component
public final class PagePublicationRequest {
    private static final String ATTRIBUTE = PagePublicationRequest.class.getName();
    private final WebPublicationStore store;
    private final WebPublications publications;
    public PagePublicationRequest(WebPublicationStore store, WebPublications publications) {
        this.store = store; this.publications = publications;
    }
    private WebPublicationStore.Snapshot snapshot(HttpServletRequest request) {
        Object existing = request.getAttribute(ATTRIBUTE);
        if (existing instanceof WebPublicationStore.Snapshot snapshot) return snapshot;
        if (existing instanceof RuntimeException failure) throw failure;
        try {
            var snapshot = store.snapshot(); request.setAttribute(ATTRIBUTE, snapshot); return snapshot;
        } catch (RuntimeException failure) { request.setAttribute(ATTRIBUTE, failure); throw failure; }
    }
    public String revision(HttpServletRequest request) { return snapshot(request).identity(); }
    public Optional<PagePublications.Published> read(String id, HttpServletRequest request) {
        return publications.render(id, snapshot(request));
    }
    public List<PagePublications.Permission> permissions(String id, HttpServletRequest request) {
        var page = snapshot(request).pages().get(id);
        return page == null ? List.of() : page.permissions();
    }
}
