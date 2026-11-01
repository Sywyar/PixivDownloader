package top.sywyar.pixivdownload.plugin.web.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import top.sywyar.pixivdownload.plugin.api.web.page.*;
import top.sywyar.pixivdownload.plugin.registry.web.PageContributionRegistry;
import top.sywyar.pixivdownload.plugin.web.page.PagePublicationRequest;
import top.sywyar.pixivdownload.plugin.registry.route.RouteAccessRegistry;
import top.sywyar.pixivdownload.plugin.api.web.*;
import java.util.List;
import java.util.Optional;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@DisplayName("公开页面投影的正式修订")
class PagePublicationControllerTest {
    @Test @DisplayName("原生布局不下载投影但仍携带正式修订，过期文档不能读取新修订")
    void defaultLayoutRetainsRevision() throws Exception {
        var registry = mock(PageContributionRegistry.class);
        when(registry.pages()).thenReturn(List.of(new PageCatalog.AvailablePage(new EditablePage(
                "form:page", "form.title", "form", "/form.html", true, List.of(), "", "", List.of(), List.of()), "owner")));
        var publications = mock(PagePublicationRequest.class);
        var request = new MockHttpServletRequest();
        when(publications.revision(request)).thenReturn("epoch:2");
        when(publications.read("form:page", request)).thenReturn(Optional.of(new PagePublications.Published(
                2, "{}", "{\"defaultLayout\":true}", "")));
        var controller = new PagePublicationController(registry, publications,
                mock(RequestOwnerIdentityResolver.class), mock(RouteAccessRegistry.class));
        var response = controller.published("form:page", request);
        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(response.getHeaders().getFirst("X-Pixiv-Page-Revision")).isEqualTo("epoch:2");
        assertThat(response.getBody()).isNull();
        request.setParameter("revision", "epoch:1");
        assertThat(controller.published("form:page", request).getStatusCode().value()).isEqualTo(409);
        request.removeParameter("revision");
        when(publications.read("form:page", request)).thenThrow(new IllegalStateException("PAGE_COMPONENT_UNAVAILABLE"));
        assertThat(controller.published("form:page", request).getStatusCode().value()).isEqualTo(503);
    }
    @Test @DisplayName("私有投影只向已认证管理员开放，不绕过本机、GUI、缺席或异属主路由")
    void privateProjectionRequiresAuthenticatedAdministratorAndMatchingRoute() throws Exception {
        var registry = mock(PageContributionRegistry.class);
        when(registry.pages()).thenReturn(List.of(new PageCatalog.AvailablePage(new EditablePage(
                "form:page", "form.title", "form", "/form.html", false, List.of(), "", "", List.of(), List.of()), "owner")));
        var publications = mock(PagePublicationRequest.class);
        var identities = mock(RequestOwnerIdentityResolver.class);
        var routes = mock(RouteAccessRegistry.class);
        var request = new MockHttpServletRequest();
        var controller = new PagePublicationController(registry, publications, identities, routes);
        when(routes.resolve("/form.html", HttpMethod.GET)).thenReturn(Optional.of(
                new RouteAccessRegistry.RegisteredRoute("form", WebRouteContribution.admin("/form.html"))));
        assertThat(controller.published("form:page", request).getStatusCode().value()).isEqualTo(404);
        verifyNoInteractions(publications);
        when(identities.isAdminAuthenticated(request)).thenReturn(true);
        when(publications.revision(request)).thenReturn("epoch:2");
        when(publications.read("form:page", request)).thenReturn(Optional.of(new PagePublications.Published(
                2, "{}", "{\"title\":\"Private form\"}", "")));
        assertThat(controller.published("form:page", request).getStatusCode().value()).isEqualTo(200);
        for (var route : List.of(WebRouteContribution.local("/form.html"), WebRouteContribution.gui("/form.html"))) {
            when(routes.resolve("/form.html", HttpMethod.GET)).thenReturn(Optional.of(new RouteAccessRegistry.RegisteredRoute("form", route)));
            assertThat(controller.published("form:page", request).getStatusCode().value()).isEqualTo(404);
        }
        when(routes.resolve("/form.html", HttpMethod.GET)).thenReturn(Optional.empty());
        assertThat(controller.published("form:page", request).getStatusCode().value()).isEqualTo(404);
        when(routes.resolve("/form.html", HttpMethod.GET)).thenReturn(Optional.of(
                new RouteAccessRegistry.RegisteredRoute("other", WebRouteContribution.admin("/form.html"))));
        assertThat(controller.published("form:page", request).getStatusCode().value()).isEqualTo(404);
    }
}
