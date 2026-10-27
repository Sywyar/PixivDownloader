package top.sywyar.pixivdownload.plugin.web.remote;

import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockHttpServletRequest;
import top.sywyar.pixivdownload.i18n.AppLocaleResolver;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.plugin.api.service.*;
import top.sywyar.pixivdownload.plugin.api.web.*;
import top.sywyar.pixivdownload.plugin.lifecycle.*;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.runtime.*;
import top.sywyar.pixivdownload.plugin.registry.route.RouteAccessRegistry;
import top.sywyar.pixivdownload.setup.CsrfProtectionFilter;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("生成远程入口保持请求开始时的精确服务代理")
class GeneratedRemoteOperationsTest {
    public interface Api {
        Value execute(Value value);
        default Value execute(String localOnly) { throw new AssertionError("内部重载不得经远程分派"); }
        default Value localOnly(Value value) { throw new AssertionError("未声明方法不得经远程分派"); }
    }
    public record Value(String text) { }
    private static final ServiceKey<Api> KEY = new ServiceKey<>("remote-test-contract", Api.class);

    @Test @DisplayName("读取请求体时 provider 被替换，旧请求不能触达新 Bean")
    void replacementWhileReadingBody() throws Exception {
        var admission = new PluginLifecycleState();
        var registry = new ExternalCapabilityInvocationRegistry(admission);
        var consumer = registry.allocatePreparation("consumer", "consumer", 1); registry.installPreparation(consumer);
        PluginServices services = registry.servicesFor(consumer, List.of(KEY));
        registry.publish(consumer); admission.set("consumer", PluginRuntimePhase.STARTED);
        var oldCalls = new AtomicInteger(); var newCalls = new AtomicInteger();
        var provider = registry.allocatePreparation("provider", "provider", 1); registry.installPreparation(provider);
        registry.prepareService(provider, KEY, input -> { oldCalls.incrementAndGet(); return input; });
        var publication = registry.publish(provider); admission.set("provider", PluginRuntimePhase.STARTED);
        var identities = mock(RequestOwnerIdentityResolver.class);
        when(identities.isAdminAuthenticated(any())).thenReturn(true);
        var csrf = mock(CsrfProtectionFilter.class); when(csrf.allowsWrite(any())).thenReturn(true);
        var routes = mock(RouteAccessRegistry.class);
        when(routes.resolve("/api/remote-test", HttpMethod.POST)).thenReturn(Optional.of(new RouteAccessRegistry.RegisteredRoute(
                "consumer", new WebRouteContribution("/api/remote-test", AccessPolicy.ADMIN, Set.of(HttpMethod.POST), false))));
        var messages = mock(AppMessages.class); var locales = mock(AppLocaleResolver.class);
        var remote = new GeneratedRemoteOperations(identities, routes, csrf, messages, locales).create("consumer", services);

        var schema = RemoteValues.JSON.createObjectNode();
        String type = Value.class.getCanonicalName();
        schema.put("$ref", "#/$defs/" + type);
        var value = schema.putObject("$defs").putObject(type); value.put("type", "object");
        value.putObject("properties").putObject("text").put("type", "string");
        value.putArray("required").add("text"); value.put("additionalProperties", false);
        var contract = RemoteValues.JSON.createObjectNode().put("format", 1).put("service", Api.class.getName()).put("owner", KEY.contractId());
        var operation = contract.putObject("operations").putObject("execute");
        operation.put("path", "/api/remote-test").put("method", "POST").put("access", "ADMIN");
        operation.set("request", schema); operation.set("response", schema);
        String identity = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(RemoteValues.JSON.writeValueAsBytes(contract)));
        contract.put("identity", identity);
        Path resource = Path.of(getClass().getProtectionDomain().getCodeSource().getLocation().toURI())
                .resolve("META-INF/pixivdownload/schema/" + Api.class.getName() + ".remote.json");
        Files.createDirectories(resource.getParent()); Files.writeString(resource, contract.toString(), StandardCharsets.UTF_8);
        var request = new MockHttpServletRequest("POST", "/api/remote-test") {
            @Override public jakarta.servlet.ServletInputStream getInputStream() {
                var drain = registry.withdraw(publication).orElseThrow();
                assertThat(drain.isDrained()).isTrue(); registry.retireDrained(drain); registry.acknowledgeRetired(drain);
                var next = registry.allocatePreparation("provider", "provider", 2); registry.installPreparation(next);
                registry.prepareService(next, KEY, input -> { newCalls.incrementAndGet(); return input; }); registry.publish(next);
                return super.getInputStream();
            }
        };
        request.setContentType("application/json"); request.addHeader("X-Pixiv-Contract", identity);
        request.setContent("{\"text\":\"safe\"}".getBytes(StandardCharsets.UTF_8));
        try {
            assertThatThrownBy(() -> remote.invoke(KEY, "execute", identity)).hasMessage("NOT_ACTIVE");
            var valid = new MockHttpServletRequest("POST", "/api/remote-test");
            valid.setContentType("application/json"); valid.addHeader("X-Pixiv-Contract", identity);
            valid.setContent("{\"text\":\"safe\"}".getBytes(StandardCharsets.UTF_8));
            org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(
                    new org.springframework.web.context.request.ServletRequestAttributes(valid));
            var result = remote.invoke(KEY, "execute", identity);
            assertThat(result.status()).isEqualTo(200);
            assertThat(result.json()).isEqualTo("{\"text\":\"safe\"}");
            assertThat(oldCalls).hasValue(1);
            valid.setContent("\"localOnly\"".getBytes(StandardCharsets.UTF_8));
            assertThat(remote.invoke(KEY, "execute", identity).status()).isEqualTo(400);
            assertThat(remote.invoke(KEY, "localOnly", identity).status()).isEqualTo(404);
            assertThat(oldCalls).hasValue(1); oldCalls.set(0);
            org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(
                    new org.springframework.web.context.request.ServletRequestAttributes(request));
            var response = remote.invoke(KEY, "execute", identity);
            assertThat(response.status()).isEqualTo(503); assertThat(response.json()).contains("REMOTE_UNAVAILABLE");
            assertThat(oldCalls).hasValue(0); assertThat(newCalls).hasValue(0);
            assertThat(services.require(KEY).execute(new Value("new"))).isEqualTo(new Value("new"));
            assertThat(newCalls).hasValue(1);
        } finally {
            org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();
            Files.deleteIfExists(resource);
        }
    }
}
