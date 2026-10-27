package top.sywyar.pixivdownload.plugin.web.remote;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.common.web.SafeRequestPath;
import top.sywyar.pixivdownload.plugin.api.service.*;
import top.sywyar.pixivdownload.plugin.api.web.*;
import top.sywyar.pixivdownload.plugin.registry.route.RouteAccessRegistry;
import top.sywyar.pixivdownload.plugin.runtime.context.PluginRemoteOperationsFactory;
import top.sywyar.pixivdownload.setup.CsrfProtectionFilter;

import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** 宿主只认识生成操作格式；契约、方法和业务类型均由已准入插件声明。 */
@Component
public final class GeneratedRemoteOperations implements PluginRemoteOperationsFactory {
    private final RequestOwnerIdentityResolver identities;
    private final RouteAccessRegistry routes;
    private final CsrfProtectionFilter csrf;
    private final top.sywyar.pixivdownload.i18n.AppMessages messages;
    private final top.sywyar.pixivdownload.i18n.AppLocaleResolver locales;

    public GeneratedRemoteOperations(RequestOwnerIdentityResolver identities, RouteAccessRegistry routes, CsrfProtectionFilter csrf,
            top.sywyar.pixivdownload.i18n.AppMessages messages, top.sywyar.pixivdownload.i18n.AppLocaleResolver locales) {
        this.identities = identities; this.routes = routes; this.csrf = csrf;
        this.messages = messages; this.locales = locales;
    }

    @Override public RemoteOperations create(String pluginId, PluginServices services) {
        return (key, operation, identity) -> {
            var attributes = org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
            if (!(attributes instanceof org.springframework.web.context.request.ServletRequestAttributes current))
                throw new ServiceAccessException(ServiceAccessException.Reason.NOT_ACTIVE);
            return invoke(pluginId, services, key, operation, identity, current.getRequest());
        };
    }

    private RemoteOperations.Response invoke(String pluginId, PluginServices services, ServiceKey<?> key,
            String operation, String identity, HttpServletRequest request) {
        String verifiedIdentity = "";
        try {
            if (!identities.isAdminAuthenticated(request) || !csrf.allowsWrite(request)) return error(request, 403, "REMOTE_FORBIDDEN", "");
            String path = SafeRequestPath.resolve(request).orElse("");
            if (!"POST".equals(request.getMethod())) return error(request, 404, "REMOTE_UNDECLARED", "");
            var route = routes.resolve(path, HttpMethod.POST).orElse(null);
            if (route == null || !route.pluginId().equals(pluginId) || route.route().accessPolicy() != AccessPolicy.ADMIN
                    || !route.route().pathPattern().equals(path) || !route.route().methods().equals(Set.of(HttpMethod.POST)))
                return error(request, 404, "REMOTE_UNDECLARED", "");

            // 先取得精确代理：读取请求体期间 provider 撤回后，本次请求也不得转接替代者。
            Object service = services.require(key);
            ObjectNode contract;
            String resource = "/META-INF/pixivdownload/schema/" + key.type().getName() + ".remote.json";
            try (InputStream input = key.type().getResourceAsStream(resource)) {
                if (input == null) return error(request, 404, "REMOTE_UNDECLARED", "");
                JsonNode document = RemoteValues.parse(input);
                if (!(document instanceof ObjectNode object)) return error(request, 404, "REMOTE_UNDECLARED", "");
                contract = object;
            }
            JsonNode declaredIdentity = contract.remove("identity");
            verifiedIdentity = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(RemoteValues.JSON.writeValueAsBytes(contract)));
            if (declaredIdentity == null || !declaredIdentity.asText().equals(verifiedIdentity)
                    || !verifiedIdentity.equals(identity) || !verifiedIdentity.equals(request.getHeader("X-Pixiv-Contract")))
                return error(request, 409, "REMOTE_CONTRACT_MISMATCH", verifiedIdentity);
            JsonNode declaration = contract.path("operations").path(operation);
            if (contract.path("format").asInt() != 1 || !contract.path("service").asText().equals(key.type().getName())
                    || !contract.path("owner").asText().equals(key.contractId())
                    || !declaration.path("path").asText().equals(path) || !declaration.path("method").asText().equals("POST")
                    || !declaration.path("access").asText().equals("ADMIN")) return error(request, 404, "REMOTE_UNDECLARED", verifiedIdentity);
            Method method = null;
            for (Method candidate : key.type().getMethods()) {
                if (candidate.getName().equals(operation) && candidate.getParameterCount() == 1
                        && candidate.getParameterTypes()[0].isRecord() && candidate.getReturnType().isRecord()
                        && declaration.path("request").path("$ref").asText().equals("#/$defs/" + candidate.getParameterTypes()[0].getCanonicalName())
                        && declaration.path("response").path("$ref").asText().equals("#/$defs/" + candidate.getReturnType().getCanonicalName())) {
                    if (method != null) return error(request, 404, "REMOTE_UNDECLARED", verifiedIdentity);
                    method = candidate;
                }
            }
            if (method == null) return error(request, 404, "REMOTE_UNDECLARED", verifiedIdentity);
            String contentType = request.getContentType();
            if (contentType == null || !contentType.split(";", 2)[0].trim().equalsIgnoreCase("application/json"))
                return error(request, 415, "REMOTE_VALUE_INVALID", verifiedIdentity);
            if (request.getContentLengthLong() > RemoteValues.MAX_BYTES) return error(request, 413, "REMOTE_BUDGET", verifiedIdentity);
            JsonNode input = RemoteValues.parse(request.getInputStream());
            RemoteValues.validate(declaration.path("request"), input);
            Object argument = RemoteValues.decode(input, method.getGenericParameterTypes()[0]);
            Object result;
            try { result = method.invoke(service, argument); }
            catch (InvocationTargetException failure) {
                if (failure.getCause() instanceof VirtualMachineError fatal) throw fatal;
                if (failure.getCause() instanceof ThreadDeath fatal) throw fatal;
                if (failure.getCause() instanceof ServiceAccessException unavailable) throw unavailable;
                return error(request, 500, "REMOTE_FAILED", verifiedIdentity);
            }
            JsonNode output;
            try {
                output = RemoteValues.encode(result, method.getGenericReturnType());
                RemoteValues.validate(declaration.path("response"), output);
            } catch (RemoteValues.Invalid invalid) { return error(request, 500, "REMOTE_FAILED", verifiedIdentity); }
            byte[] bytes = RemoteValues.JSON.writeValueAsBytes(output);
            if (bytes.length > RemoteValues.MAX_BYTES) return error(request, 500, "REMOTE_FAILED", verifiedIdentity);
            return new RemoteOperations.Response(200, new String(bytes, StandardCharsets.UTF_8), verifiedIdentity);
        } catch (ServiceAccessException unavailable) {
            return error(request, unavailable.reason() == ServiceAccessException.Reason.INVOCATION_FAILED ? 500 : 503,
                    unavailable.reason() == ServiceAccessException.Reason.INVOCATION_FAILED ? "REMOTE_FAILED" : "REMOTE_UNAVAILABLE", verifiedIdentity);
        } catch (RemoteValues.Invalid invalid) {
            return error(request, invalid.code.equals("REMOTE_BUDGET") ? 413 : 400, invalid.code, verifiedIdentity);
        } catch (Exception failure) {
            return error(request, 500, "REMOTE_FAILED", verifiedIdentity);
        }
    }

    private RemoteOperations.Response error(HttpServletRequest request, int status, String code, String identity) {
        var body = RemoteValues.JSON.createObjectNode().put("code", code)
                .put("error", messages.get(locales.resolveLocale(request), "plugin.remote.failed"));
        return new RemoteOperations.Response(status, body.toString(), identity);
    }
}
