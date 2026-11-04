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
import java.util.Map;
import java.util.Set;
import top.sywyar.pixivdownload.plugin.api.operation.*;
import top.sywyar.pixivdownload.plugin.security.OperationEndpoints;
import top.sywyar.pixivdownload.plugin.web.page.WebPublicationStore;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@DisplayName("公开页面投影的正式修订")
class PagePublicationControllerTest {
    @Test @DisplayName("浏览器准备字段只包含必要前置输入，后置观察不增加业务正文限制")
    void preparationExcludesObserverFields() {
        var registry = mock(PageContributionRegistry.class);
        var target = new PageInteraction.Target("submit", "form.submit", "core:save", "form", "{}", List.of(), 16);
        when(registry.pages()).thenReturn(List.of(new PageCatalog.AvailablePage(new EditablePage(
                "core:page", "form.title", "form", "/form.html", true, List.of(), "", "", List.of(), List.of(target)), "core")));
        var publications = mock(PagePublicationRequest.class);
        var endpoints = mock(OperationEndpoints.class);
        var store = mock(WebPublicationStore.class);
        var endpoint = new OperationTargets.Endpoint("core:http-save", "core:save", "core", "core:1", "catalog",
                List.of(new OperationTargets.Route(HttpMethod.POST, "/save")), Set.of(OperationHook.Phase.values()), "", "",
                List.of(), List.of(), List.of(), List.of(), "Fixture#save", "SYNC");
        when(endpoints.resolve(null, new OperationTargets.Reference(OperationTargets.Kind.FORM_ACTION, "core:page", "submit")))
                .thenReturn(new OperationTargets.Resolution(OperationTargets.Status.AVAILABLE, "catalog", List.of("core:save"), List.of(endpoint)));
        var before = new OperationBinding("gate", "fixture:hook", "", endpoint.id(), OperationHook.Phase.BEFORE_INVOKE,
                0, OperationHook.Completion.ALL, "{}", List.of(new OperationBinding.Field(OperationBinding.Source.QUERY, "marker", "marker")));
        var after = new OperationBinding("observer", "fixture:hook", "", endpoint.id(), OperationHook.Phase.AFTER_COMPLETION,
                0, OperationHook.Completion.ALL, "{}", List.of(new OperationBinding.Field(OperationBinding.Source.JSON, "/revision", "revision")));
        var request = new MockHttpServletRequest();
        var controller = new PagePublicationController(registry, publications, mock(RequestOwnerIdentityResolver.class),
                mock(RouteAccessRegistry.class), endpoints, store);
        for (var bindings : List.of(List.of(after), List.of(before, after))) {
            var requirements = bindings.stream().map(binding -> new WebPublicationStore.HookRequirement(
                    binding, "core:save", "", "", "fixture", "fixture", null, "catalog")).toList();
            var snapshot = new WebPublicationStore.Snapshot(1, java.util.UUID.randomUUID().toString(), Map.of(),
                    new WebPublicationStore.HookState(new WebPublicationStore.HookSettings(Map.of(), bindings), requirements, List.of()));
            when(store.snapshot()).thenReturn(snapshot);
            when(publications.revision(request)).thenReturn(snapshot.identity());
            var response = controller.targets("core:page", request);
            assertThat(response.getStatusCode().value()).isEqualTo(200);
            assertThat(response.getBody().targets().get(0).requests().get(0).fields())
                    .isEqualTo(bindings.contains(before) ? before.fields() : List.of());
        }
    }
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
                mock(RequestOwnerIdentityResolver.class), mock(RouteAccessRegistry.class),
                mock(top.sywyar.pixivdownload.plugin.security.OperationEndpoints.class), mock(top.sywyar.pixivdownload.plugin.web.page.WebPublicationStore.class));
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
        var controller = new PagePublicationController(registry, publications, identities, routes,
                mock(top.sywyar.pixivdownload.plugin.security.OperationEndpoints.class), mock(top.sywyar.pixivdownload.plugin.web.page.WebPublicationStore.class));
        when(routes.resolve("/form.html", HttpMethod.GET)).thenReturn(Optional.of(
                new RouteAccessRegistry.RegisteredRoute("form", WebRouteContribution.admin("/form.html"))));
        assertThat(controller.published("form:page", request).getStatusCode().value()).isEqualTo(404);
        assertThat(controller.targets("form:page", request).getStatusCode().value()).isEqualTo(404);
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
