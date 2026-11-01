package top.sywyar.pixivdownload.setup;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import java.util.List;
import java.util.Map;
import top.sywyar.pixivdownload.plugin.api.web.page.*;
import top.sywyar.pixivdownload.plugin.registry.web.PageContributionRegistry;
import top.sywyar.pixivdownload.plugin.web.page.*;

@DisplayName("统一浏览器安全响应头")
class SecurityResponseHeadersFilterTest {
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory;

    @org.junit.jupiter.api.AfterEach
    void cleanupDirectory() throws Exception {
        top.sywyar.pixivdownload.testsupport.TestDirectoryCleanup.delete(directory);
        assertThat(directory).doesNotExist();
    }

    private final SecurityResponseHeadersFilter filter = new SecurityResponseHeadersFilter(
            org.mockito.Mockito.mock(top.sywyar.pixivdownload.plugin.registry.web.PageContributionRegistry.class),
            org.mockito.Mockito.mock(top.sywyar.pixivdownload.plugin.web.page.PagePublicationRequest.class));
    private final FilterChain chain = (request, response) -> response.setContentType("text/html");

    @Test
    @DisplayName("普通页面禁止嵌入并限制脚本和浏览器能力")
    void normalNavigationUsesStrictHeaders() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/index.html");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, chain);

        assertThat(response.getHeader("Content-Security-Policy"))
                .contains("default-src 'self'", "object-src 'none'", "base-uri 'self'",
                        "script-src 'self'", "script-src-attr 'none'", "frame-ancestors 'none'",
                        "https://layout-survey.sywyar.top", "https://us.i.posthog.com",
                        "https://eu.i.posthog.com");
        assertThat(response.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(response.getHeader("Referrer-Policy")).isEqualTo("no-referrer");
        assertThat(response.getHeader("Permissions-Policy"))
                .contains("camera=()", "geolocation=()", "microphone=()", "payment=()", "usb=()");
        assertThat(response.getHeaderValues("Vary")).contains("Sec-Fetch-Dest");
    }

    @Test
    @DisplayName("同源 iframe 导航保留站内信嵌入能力")
    void iframeNavigationAllowsOnlySameOriginParent() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/embedded.html");
        request.addHeader("Sec-Fetch-Dest", "iframe");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, chain);

        assertThat(response.getHeader("Content-Security-Policy"))
                .contains("frame-ancestors 'self'")
                .doesNotContain("frame-ancestors 'none'");
    }

    @Test @DisplayName("资源授权只作用于本页，响应固定同一修订且未知状态不扩展 CSP")
    void pagePermissionsAreScopedAndSnapshotConsistent() throws Exception {
        var store = new WebPublicationStore(directory.resolve("publications"), directory.resolve("config/enrolled"));
        var permission = new PagePublications.Permission("verifier", "proof", new PageResourceProfile("widget", "r1", List.of(
                new PageResourceProfile.Source(PageResourceProfile.Kind.SCRIPT, "https://verify.example"),
                new PageResourceProfile.Source(PageResourceProfile.Kind.FRAME, "https://verify.example"),
                new PageResourceProfile.Source(PageResourceProfile.Kind.WORKER, "self"))));
        var page = new WebPublicationStore.Page("{}", "{}", "", List.of(), List.of(permission));
        store.commit(0, new WebPublicationStore.Snapshot(1, store.snapshot().epoch(), Map.of("owner:page", page)));
        var requestPublications = new PagePublicationRequest(store, mock(WebPublications.class));
        var pages = mock(PageContributionRegistry.class);
        when(pages.pages()).thenReturn(List.of(new PageCatalog.AvailablePage(new EditablePage("owner:page", "page", "owner",
                "/form.html", true, List.of(), "", "", List.of(), List.of()), "owner-publication")));
        var scoped = new SecurityResponseHeadersFilter(pages, requestPublications);
        var request = new MockHttpServletRequest("GET", "/form.html");
        var response = new MockHttpServletResponse();
        scoped.doFilterInternal(request, response, (incoming, outgoing) -> {
            store.commit(1, new WebPublicationStore.Snapshot(2, store.snapshot().epoch(), Map.of()));
            assertThat(requestPublications.revision(request)).isEqualTo(store.snapshot().epoch() + ":1");
            assertThat(requestPublications.permissions("owner:page", request)).containsExactly(permission);
        });
        assertThat(response.getHeader("X-Pixiv-Page-Revision")).isEqualTo(store.snapshot().epoch() + ":1");
        assertThat(response.getHeader("Content-Security-Policy"))
                .contains("script-src 'self' https://verify.example", "frame-src 'self' https://verify.example", "worker-src 'self'")
                .doesNotContain("script-src 'unsafe-inline'", "unsafe-eval", "*");
        var unrelated = new MockHttpServletResponse();
        scoped.doFilterInternal(new MockHttpServletRequest("GET", "/other.html"), unrelated, chain);
        assertThat(unrelated.getHeader("Content-Security-Policy")).doesNotContain("verify.example").contains("worker-src 'none'");
        var latest = new MockHttpServletResponse();
        scoped.doFilterInternal(new MockHttpServletRequest("GET", "/form.html"), latest, chain);
        assertThat(latest.getHeader("X-Pixiv-Page-Revision")).isEqualTo(store.snapshot().epoch() + ":2");
        assertThat(latest.getHeader("Content-Security-Policy")).doesNotContain("verify.example");
        java.nio.file.Files.delete(directory.resolve("publications/current.json"));
        var unknown = new MockHttpServletResponse();
        scoped.doFilterInternal(new MockHttpServletRequest("GET", "/form.html"), unknown, chain);
        assertThat(unknown.getHeader("X-Pixiv-Page-State")).isEqualTo("unavailable");
        assertThat(unknown.getHeader("Content-Security-Policy")).doesNotContain("verify.example").contains("worker-src 'none'");
    }
}
