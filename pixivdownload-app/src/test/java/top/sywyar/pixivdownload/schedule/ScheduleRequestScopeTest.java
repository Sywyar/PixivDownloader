package top.sywyar.pixivdownload.schedule;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import top.sywyar.pixivdownload.config.ProxyConfig;
import top.sywyar.pixivdownload.config.RestTemplateConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;
import top.sywyar.pixivdownload.config.OutboundProxyOverride;
import top.sywyar.pixivdownload.config.OutboundProxySettings;
import top.sywyar.pixivdownload.config.credential.AcquisitionCredentialLibrary;
import top.sywyar.pixivdownload.core.pixiv.*;
import top.sywyar.pixivdownload.core.pixiv.thumbnail.PixivThumbnailFetcher;
import top.sywyar.pixivdownload.core.pixiv.web.PixivSharedProxyController;
import top.sywyar.pixivdownload.core.schedule.*;
import top.sywyar.pixivdownload.core.web.AcquisitionCredentialsFilter;
import top.sywyar.pixivdownload.i18n.*;
import top.sywyar.pixivdownload.plugin.api.download.context.AcquisitionCredentials;
import top.sywyar.pixivdownload.plugin.api.schedule.capability.*;
import top.sywyar.pixivdownload.plugin.api.schedule.credential.ScheduledCredentialPolicy;
import top.sywyar.pixivdownload.plugin.api.schedule.management.ScheduleManagementException;
import top.sywyar.pixivdownload.plugin.api.schedule.network.ScheduledNetworkRoute;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledSourceDescriptor;
import top.sywyar.pixivdownload.plugin.api.web.RequestOwnerIdentityResolver;
import top.sywyar.pixivdownload.plugin.api.web.AccessPolicy;
import top.sywyar.pixivdownload.plugin.api.web.HttpMethod;
import top.sywyar.pixivdownload.plugin.BuiltInPlugins;
import top.sywyar.pixivdownload.plugin.registry.PluginRegistry;
import top.sywyar.pixivdownload.plugin.registry.route.RouteAccessRegistry;
import top.sywyar.pixivdownload.schedule.execution.ScheduleNetworkRouteResolver;
import top.sywyar.pixivdownload.testsupport.HostTestMessages;

import java.io.IOException;
import java.net.URI;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@DisplayName("保存前查询沿用计划凭据与网络路由")
class ScheduleRequestScopeTest {
    private final ScheduledTaskStore store = mock(ScheduledTaskStore.class);
    private final ScheduleCapabilityAccess capabilities = mock(ScheduleCapabilityAccess.class);
    private final SchedulePlanningLease source = mock(SchedulePlanningLease.class);
    @SuppressWarnings("unchecked")
    private final ScheduleCapabilityLease<ScheduledCredentialPolicy> policy = mock(ScheduleCapabilityLease.class);
    private final ScheduledTask task = mock(ScheduledTask.class);
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicBoolean policyCancelled = new AtomicBoolean();
    private final OutboundProxySettings settings = mock(OutboundProxySettings.class);
    private final ScheduleRequestScope scope = new ScheduleRequestScope(store, capabilities, new ScheduleNetworkRouteResolver(settings));
    private final PixivAjaxClient ajax = mock(PixivAjaxClient.class);
    private final RequestOwnerIdentityResolver identities = mock(RequestOwnerIdentityResolver.class);
    private final AcquisitionCredentialLibrary library = mock(AcquisitionCredentialLibrary.class);
    private final JsonMapper json = JsonMapper.builderWithJackson2Defaults().build();
    private MockMvc mvc;

    @BeforeEach void setup() throws Exception {
        doReturn(Optional.of(source)).when(capabilities).prepareSource("external-source");
        when(capabilities.activate(source)).thenReturn(true);
        when(source.activationToken()).thenReturn("publication-a");
        when(source.sourceType()).thenReturn("external-source");
        when(source.owner()).thenReturn(new ScheduleCapabilityOwner("source-owner", "source-package", 1));
        when(source.cancellation()).thenReturn(cancelled::get);
        var descriptor = mock(ScheduledSourceDescriptor.class);
        when(descriptor.credentialPolicyIds()).thenReturn(Set.of("external-policy"));
        when(source.descriptor()).thenReturn(Optional.of(descriptor));
        doReturn(Optional.of(policy)).when(capabilities).prepareCredentialPolicy("external-policy");
        when(capabilities.activate(policy)).thenReturn(true);
        when(policy.owner()).thenReturn(new ScheduleCapabilityOwner("policy-owner", "policy-package", 2));
        when(policy.cancellation()).thenReturn(policyCancelled::get);
        when(store.findById(17)).thenReturn(task);
        when(task.id()).thenReturn(17L);
        when(task.stateVersion()).thenReturn(9L);
        when(task.sourceType()).thenReturn("external-source");
        when(task.sourceOwnerPluginId()).thenReturn("source-owner");
        when(task.credentialPolicyId()).thenReturn("external-policy");
        when(task.credentialPolicyOwnerPluginId()).thenReturn("policy-owner");
        when(store.findCredentialCandidates(17, "policy-owner", "external-policy")).thenReturn(List.of(
                new ScheduledCredentialValue("account-a", "bound-first"), new ScheduledCredentialValue("account-b", "bound-second")));
        when(settings.isEnabled()).thenReturn(true);
        when(settings.getHost()).thenReturn("global.proxy");
        when(settings.getPort()).thenReturn(9000);
        when(identities.isAdminAuthenticated(any())).thenReturn(true);
        when(identities.resolveExistingOwnerUuid(any())).thenReturn(Optional.of("admin"));
        mvc = metadataEndpoint(ajax);
    }

    private MockMvc metadataEndpoint(PixivAjaxClient client) {
        var access = mock(PixivProxyAccessPolicy.class);
        when(access.evaluate("admin", true)).thenReturn(new PixivProxyAccessDecision(PixivProxyAccessOutcome.ALLOWED, null, 0, 0));
        var controller = new PixivSharedProxyController(json, client, mock(PixivThumbnailFetcher.class), access,
                identities, HostTestMessages.messages(), scope);
        var filter = new AcquisitionCredentialsFilter(library, identities,
                new AppMessages(new org.springframework.context.support.StaticMessageSource()),
                new AppLocaleResolver(LocaleCatalog.defaultCatalog()), json);
        return MockMvcBuilders.standaloneSetup(controller).addFilters(filter).build();
    }

    @AfterEach void clearRoute() { OutboundProxyOverride.clear(); }

    @Test @DisplayName("计划查询路由仅管理员可用，手动查询保持访客策略")
    void routePolicySeparatesScheduleFromManualQuery() {
        var routes = new RouteAccessRegistry(new PluginRegistry(BuiltInPlugins.createAll()));
        assertThat(routes.resolve("/api/pixiv/user/7/meta/schedule", HttpMethod.POST))
                .get().satisfies(route -> assertThat(route.route().accessPolicy())
                        .isEqualTo(AccessPolicy.ADMIN));
        assertThat(routes.resolve("/api/pixiv/user/7/meta", HttpMethod.GET))
                .get().satisfies(route -> assertThat(route.route().accessPolicy())
                        .isEqualTo(AccessPolicy.VISITOR));
    }

    private ScheduleRequestScope.Request context(boolean bound, String proxy) {
        return new ScheduleRequestScope.Request("external-source", "publication-a", bound ? 17L : null,
                bound ? 9L : null, bound, proxy);
    }

    @ParameterizedTest @ValueSource(ints = {401, 429})
    @DisplayName("保存前用户名查询经真实客户端切换不同账号备用，后续查询遵守已有冷却")
    void metadataRetriesBoundCandidates(int failureStatus) throws Exception {
        String first = "PHPSESSID=1_synthetic";
        String fallback = "PHPSESSID=2_synthetic";
        when(store.findCredentialCandidates(17, "policy-owner", "external-policy")).thenReturn(List.of(
                new ScheduledCredentialValue("1", first), new ScheduledCredentialValue("2", fallback)));
        var retry = new PixivCredentialRetry();
        var transport = new RestTemplateConfig(new ProxyConfig()).pixivCredentialRestTemplate();
        var upstream = MockRestServiceServer.bindTo(transport).build();
        var endpoint = metadataEndpoint(new PixivAjaxProxyClient(transport, retry));
        String uri = "https://www.pixiv.net/ajax/user/8?lang=zh";
        upstream.expect(requestTo(uri)).andExpect(header("Cookie", first))
                .andRespond(withStatus(HttpStatus.valueOf(failureStatus)).header("Retry-After", "120"));
        for (int request = 0; request < 2; request++) {
            upstream.expect(requestTo(uri)).andExpect(header("Cookie", fallback)).andExpect(ignored -> {
                assertThat(OutboundProxyOverride.current().hostName()).isEqualTo("plan.proxy");
                assertThat(OutboundProxyOverride.current().port()).isEqualTo(8080);
                assertThat(AcquisitionCredentials.current().primary()).isEqualTo(first);
            }).andRespond(withSuccess("{\"body\":{\"name\":\"Selected user\"}}", MediaType.APPLICATION_JSON));
        }
        try {
            for (int request = 0; request < 2; request++) {
                endpoint.perform(post("/api/pixiv/user/8/meta/schedule").contentType("application/json")
                                .content(json.writeValueAsString(context(true, "plan.proxy:8080"))))
                        .andExpect(status().isOk()).andExpect(content().json("{\"name\":\"Selected user\",\"userId\":\"8\"}"));
                assertThat(AcquisitionCredentials.current()).isNull();
                assertThat(OutboundProxyOverride.isActive()).isFalse();
            }
            upstream.verify();
            verifyNoInteractions(library);
        } finally {
            retry.destroy();
            ((org.springframework.beans.factory.DisposableBean) transport).destroy();
        }
    }

    @Test @DisplayName("首选失败时来源撤回会阻止备用请求，过期任务版本也不能再发送查询")
    void metadataFallbackHonorsCancellationAndVersion() throws Exception {
        String first = "PHPSESSID=1_synthetic";
        when(store.findCredentialCandidates(17, "policy-owner", "external-policy")).thenReturn(List.of(
                new ScheduledCredentialValue("1", first), new ScheduledCredentialValue("2", "PHPSESSID=2_synthetic")));
        var retry = new PixivCredentialRetry();
        var transport = new RestTemplateConfig(new ProxyConfig()).pixivCredentialRestTemplate();
        var upstream = MockRestServiceServer.bindTo(transport).build();
        var endpoint = metadataEndpoint(new PixivAjaxProxyClient(transport, retry));
        upstream.expect(requestTo("https://www.pixiv.net/ajax/user/8?lang=zh"))
                .andExpect(header("Cookie", first)).andRespond(request -> {
                    cancelled.set(true);
                    return withStatus(HttpStatus.UNAUTHORIZED).createResponse(request);
                });
        try {
            endpoint.perform(post("/api/pixiv/user/8/meta/schedule").contentType("application/json")
                            .content(json.writeValueAsString(context(true, "plan.proxy:8080"))))
                    .andExpect(status().isConflict());
            cancelled.set(false);
            when(task.stateVersion()).thenReturn(10L);
            endpoint.perform(post("/api/pixiv/user/8/meta/schedule").contentType("application/json")
                            .content(json.writeValueAsString(context(true, "plan.proxy:8080"))))
                    .andExpect(status().isConflict());
            upstream.verify();
            assertThat(AcquisitionCredentials.current()).isNull();
            assertThat(OutboundProxyOverride.isActive()).isFalse();
        } finally {
            retry.destroy();
            ((org.springframework.beans.factory.DisposableBean) transport).destroy();
        }
    }

    @Test @DisplayName("已绑定计划使用独立候选及专用代理，返回名称且不访问手动凭据库")
    void boundCredentialsThroughHttp() throws Exception {
        OutboundProxyOverride.set("manual.proxy:1234");
        when(ajax.get(any(URI.class), eq("bound-first"))).thenAnswer(invocation -> {
            assertThat(OutboundProxyOverride.current().hostName()).isEqualTo("plan.proxy");
            assertThat(OutboundProxyOverride.current().port()).isEqualTo(8080);
            assertThat(AcquisitionCredentials.candidates("bound-first")).containsExactly("bound-first", "bound-second");
            return "{\"body\":{\"name\":\"Selected user\"}}";
        });
        mvc.perform(post("/api/pixiv/user/8/meta/schedule").contentType("application/json")
                        .content(json.writeValueAsString(context(true, "plan.proxy:8080"))))
                .andExpect(status().isOk()).andExpect(content().json("{\"name\":\"Selected user\",\"userId\":\"8\"}"));
        verify(ajax).get(URI.create("https://www.pixiv.net/ajax/user/8?lang=zh"), "bound-first");
        verifyNoInteractions(library);
        assertThat(OutboundProxyOverride.current().hostName()).isEqualTo("manual.proxy");
        assertThat(AcquisitionCredentials.current()).isNull();
        verify(source).close(); verify(policy).close();
    }

    @Test @DisplayName("本次选择由真实过滤器解析并保留候选顺序，不读取计划旧凭据")
    void selectedCredentialsThroughFilter() throws Exception {
        when(library.resolve(List.of("selected-b", "selected-a")))
                .thenReturn(new AcquisitionCredentials(List.of("draft-b", "draft-a")));
        when(ajax.get(any(URI.class), eq("draft-b"))).thenAnswer(invocation -> {
            assertThat(AcquisitionCredentials.candidates("draft-b")).containsExactly("draft-b", "draft-a");
            assertThat(OutboundProxyOverride.current().hostName()).isEqualTo("plan.proxy");
            return "{\"body\":{\"name\":\"New user\"}}";
        });
        mvc.perform(post("/api/pixiv/user/7/meta/schedule").contentType("application/json")
                        .header(AcquisitionCredentialsFilter.HEADER, "selected-b,selected-a")
                        .content(json.writeValueAsString(context(false, "plan.proxy:8080"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("New user"));
        verifyNoInteractions(store);
        assertThat(AcquisitionCredentials.current()).isNull();
        assertThat(OutboundProxyOverride.isActive()).isFalse();
    }

    @Test @DisplayName("新计划的临时凭据或匿名查询均按本次路由执行")
    void draftAndAnonymousRoute() throws Exception {
        for (String credential : Arrays.asList("inline-secret", null)) {
            scope.read(context(false, null), credential, ScheduledNetworkRoute.direct(), actual -> {
                assertThat(actual).isEqualTo(credential);
                assertThat(OutboundProxyOverride.isActive()).isTrue();
                assertThat(OutboundProxyOverride.current()).isNull();
                return "name";
            });
        }
        scope.read(context(false, null), null, ScheduledNetworkRoute.inherit(), actual -> {
            assertThat(OutboundProxyOverride.current().hostName()).isEqualTo("global.proxy");
            return "name";
        });
        verifyNoInteractions(store, library);
    }

    @Test @DisplayName("非管理员、非法代理与超长代理在读取计划 secret 前拒绝")
    void rejectsInvalidHttpInputBeforeSecrets() throws Exception {
        for (String proxy : List.of("invalid", "a".repeat(513))) {
            mvc.perform(post("/api/pixiv/user/7/meta/schedule").contentType("application/json")
                            .content(json.writeValueAsString(context(true, proxy))))
                    .andExpect(status().isBadRequest());
        }
        when(identities.isAdminAuthenticated(any(HttpServletRequest.class))).thenReturn(false);
        mvc.perform(post("/api/pixiv/user/7/meta/schedule").contentType("application/json")
                        .content(json.writeValueAsString(context(true, null))))
                .andExpect(status().isForbidden());
        verify(store, never()).findCredentialCandidates(anyLong(), anyString(), anyString());
        verifyNoInteractions(ajax, library);
    }

    @Test @DisplayName("过期来源、任务版本、owner 或凭据策略拒绝后不读取 secret")
    void staleIdentityFailsClosed() {
        when(source.activationToken()).thenReturn("replacement");
        assertStale();
        when(source.activationToken()).thenReturn("publication-a");
        when(task.stateVersion()).thenReturn(10L);
        assertStale();
        when(task.stateVersion()).thenReturn(9L);
        when(task.sourceOwnerPluginId()).thenReturn("other-owner");
        assertStale();
        when(task.sourceOwnerPluginId()).thenReturn("source-owner");
        when(policy.owner()).thenReturn(new ScheduleCapabilityOwner("other-owner", "other-package", 1));
        assertStale();
        verify(store, never()).findCredentialCandidates(anyLong(), anyString(), anyString());
    }

    private void assertStale() {
        assertThatThrownBy(() -> scope.read(context(true, null), null, ScheduledNetworkRoute.inherit(),
                ignored -> { throw new AssertionError("Must not query"); }))
                .isInstanceOfSatisfying(ScheduleManagementException.class,
                        failure -> assertThat(failure.code()).isEqualTo(ScheduleManagementException.Code.CONFLICT));
    }

    @Test @DisplayName("来源撤回或凭据策略撤回时丢弃迟到结果并恢复请求作用域")
    void cancellationDropsLateResult() {
        for (AtomicBoolean cancellation : List.of(cancelled, policyCancelled)) {
            assertThatThrownBy(() -> scope.read(context(true, "plan.proxy:8080"), null, ScheduledNetworkRoute.inherit(), ignored -> {
                cancellation.set(true);
                return "late name";
            })).isInstanceOfSatisfying(ScheduleManagementException.class,
                    failure -> assertThat(failure.code()).isEqualTo(ScheduleManagementException.Code.CONFLICT));
            assertThat(OutboundProxyOverride.isActive()).isFalse();
            assertThat(AcquisitionCredentials.current()).isNull();
            cancellation.set(false);
        }
    }

    @Test @DisplayName("来源失活通过真实 HTTP 返回冲突，缺少绑定凭据不回落匿名")
    void cancellationAndMissingCredentialHttpResponses() throws Exception {
        when(ajax.get(any(URI.class), eq("bound-first"))).thenAnswer(invocation -> {
            cancelled.set(true);
            return "{\"body\":{\"name\":\"Late user\"}}";
        });
        mvc.perform(post("/api/pixiv/user/7/meta/schedule").contentType("application/json")
                        .content(json.writeValueAsString(context(true, null))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("schedule.error.source-publication-changed"));
        cancelled.set(false);
        clearInvocations(ajax);
        when(store.findCredentialCandidates(17, "policy-owner", "external-policy")).thenReturn(List.of());
        mvc.perform(post("/api/pixiv/user/7/meta/schedule").contentType("application/json")
                        .content(json.writeValueAsString(context(true, null))))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(ajax);
    }

    @Test @DisplayName("网络失败保留原始失败且释放租约与临时代理")
    void networkFailureRestoresScope() {
        OutboundProxyOverride.setDirect();
        assertThatThrownBy(() -> scope.read(context(true, "plan.proxy:8080"), null, ScheduledNetworkRoute.inherit(),
                ignored -> { throw new IOException("controlled transport failure"); }))
                .isInstanceOf(IOException.class);
        assertThat(OutboundProxyOverride.isActive()).isTrue();
        assertThat(OutboundProxyOverride.current()).isNull();
        assertThat(AcquisitionCredentials.current()).isNull();
        verify(source).close(); verify(policy).close();
    }
}
