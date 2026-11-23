package top.sywyar.pixivdownload.openapi;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import top.sywyar.pixivdownload.common.GuiTokenProvider;
import top.sywyar.pixivdownload.common.SessionUtils;
import top.sywyar.pixivdownload.plugin.api.web.AccessPolicy;
import top.sywyar.pixivdownload.plugin.api.web.HttpApiAccess.Condition;
import top.sywyar.pixivdownload.plugin.api.web.HttpMethod;
import top.sywyar.pixivdownload.plugin.api.web.WebRouteContribution;
import top.sywyar.pixivdownload.setup.AuthFilter;
import top.sywyar.pixivdownload.setup.guest.GuestInviteSession;

/** OpenAPI credential alternatives for an effective route, method and any additional handler check. */
final class ApiReferenceSecurity {
    private ApiReferenceSecurity() {}

    /** Immutable access policy for a single background render. */
    record InstanceAccess(boolean setupComplete, boolean multi) {
        static final ScopedValue<InstanceAccess> RENDERING = ScopedValue.newInstance();

        static InstanceAccess of(top.sywyar.pixivdownload.setup.SetupService setup) {
            return new InstanceAccess(setup.isSetupComplete(), "multi".equals(setup.getMode()));
        }
    }

    static void schemes(Components components, Map<String, String> text) {
        var converters = io.swagger.v3.core.converter.ModelConverters.getInstance(true);
        for (var type : List.of(top.sywyar.pixivdownload.plugin.api.web.ApiErrorResponse.Basic.class,
                AuthFilter.ProgramCredentialError.class)) {
            var schema = converters.readAllAsResolvedSchema(type).schema;
            schema.setRequired(type == AuthFilter.ProgramCredentialError.class
                    ? List.of("code", "error", "programPermission") : List.of("code", "error"));
            components.addSchemas(type.getCanonicalName(), schema);
        }
        HttpBindingDocumentation.fixedError(components, "ProgramKeyInvalid", "auth.program.invalid");
        HttpBindingDocumentation.fixedError(components, "ProgramKeyRequired", "auth.program.required");
        HttpBindingDocumentation.fixedError(components, "ProgramKeyUnsupported", "auth.program.unsupported");
        components.addSecuritySchemes("programKey", new SecurityScheme().type(SecurityScheme.Type.HTTP)
                .scheme("bearer").description(text.get("key")));
        components.addSecuritySchemes("adminSessionHeader", apiKey(SecurityScheme.In.HEADER,
                SessionUtils.HEADER_NAME, text.get("credential.adminHeader")));
        components.addSecuritySchemes("adminSessionCookie", apiKey(SecurityScheme.In.COOKIE,
                ApiReferenceDocument.cookieName(SessionUtils.COOKIE_NAME), text.get("credential.adminCookie")));
        components.addSecuritySchemes("guestInviteCookie", apiKey(SecurityScheme.In.COOKIE,
                ApiReferenceDocument.cookieName(GuestInviteSession.COOKIE_NAME), text.get("credential.invite")));
        components.addSecuritySchemes("guiToken", apiKey(SecurityScheme.In.HEADER,
                GuiTokenProvider.HEADER_NAME, text.get("credential.gui")));
    }

    private static SecurityScheme apiKey(SecurityScheme.In in, String name, String description) {
        return new SecurityScheme().type(SecurityScheme.Type.APIKEY).in(in).name(name).description(description);
    }

    static void errors(Operation operation, boolean supportsKey, boolean self, Map<String, String> text) {
        // programPermission is present only on a permission challenge; invalid tokens use the basic JSON shape.
        String error = supportsKey && !self ? "#/components/schemas/" + AuthFilter.ProgramCredentialError.class.getCanonicalName()
                : HttpBindingDocumentation.BASIC;
        HttpBindingDocumentation.addError(operation, "401", error, text.get("response.unauthorized"));
        HttpBindingDocumentation.addError(operation, "401", "#/components/schemas/ProgramKeyInvalid", null);
        if (self) HttpBindingDocumentation.addError(operation, "401", "#/components/schemas/ProgramKeyRequired", null);
        String forbidden = text.get("response.forbidden");
        if (!supportsKey) forbidden += " `auth.program.unsupported` — " + text.get("key.unsupported");
        HttpBindingDocumentation.addError(operation, "403", error, forbidden);
        if (supportsKey && !self) {
            HttpBindingDocumentation.addError(operation, "401", HttpBindingDocumentation.BASIC, null);
            HttpBindingDocumentation.addError(operation, "403", HttpBindingDocumentation.BASIC, null);
        } else if (!supportsKey) {
            HttpBindingDocumentation.addError(operation, "403", "#/components/schemas/ProgramKeyUnsupported", null);
        }
        operation.getResponses().get("401").addHeaderObject("WWW-Authenticate", new io.swagger.v3.oas.models.headers.Header()
                .description(text.get("response.bearerChallenge")).schema(new io.swagger.v3.oas.models.media.StringSchema()));
    }

    static String describe(Operation operation, WebRouteContribution route, String path,
                           HttpMethod method, InstanceAccess instance, Map<String, String> text) {
        var policy = route.accessPolicy();
        var alternatives = new ArrayList<SecurityRequirement>();
        if (route.programPermissions().containsKey(method)) require(alternatives, "programKey");
        boolean invited = AuthFilter.acceptsGuestInvite(route, method);
        boolean sessions = policy == AccessPolicy.ADMIN || policy == AccessPolicy.INVITED_GUEST
                || policy == AccessPolicy.VISITOR || policy == AccessPolicy.VISITOR_AND_INVITED_GUEST
                || policy == AccessPolicy.LOCAL;
        boolean optional = switch (policy) {
            case PUBLIC, LOCAL, ACTUATOR_PUBLIC -> true;
            case VISITOR, VISITOR_AND_INVITED_GUEST, GUI -> instance.multi();
            case ADMIN, INVITED_GUEST -> false;
        };
        String guidance = policy.name();
        if (policy == AccessPolicy.GUI && !path.startsWith("/api/gui/")) {
            sessions = true;
            guidance = "VISITOR";
        }
        if (policy == AccessPolicy.INVITED_GUEST && !invited) guidance = "ADMIN";
        if (policy == AccessPolicy.VISITOR_AND_INVITED_GUEST && !invited) guidance = "VISITOR";
        if (policy == AccessPolicy.VISITOR && path.startsWith("/api/setup/")) {
            optional = !instance.setupComplete() || instance.multi();
            guidance = "SETUP";
        }
        if (policy == AccessPolicy.LOCAL && AuthFilter.isLocalOnlyMove(path, method)) {
            sessions = false;
            guidance = "LOCAL_ONLY";
        }

        String extra = "";
        Object value = operation.getExtensions() == null ? null : operation.getExtensions().get("x-handler-access");
        if (value != null) {
            var condition = Condition.valueOf(value.toString());
            extra = text.get("condition." + condition.name());
            switch (condition) {
                case ADMIN_SESSION -> {
                    sessions = true;
                    optional = false;
                    invited = false;
                    if (policy == AccessPolicy.ADMIN) extra = "";
                    else if (policy != AccessPolicy.PUBLIC) guidance = "ADMIN";
                }
                case OPTIONAL_ADMIN, PAGE_VISIBILITY -> { sessions = true; optional = true; invited = false; }
                case SOLO_OR_ADMIN -> { sessions = true; optional = !instance.multi(); invited = false; }
                case OPTIONAL_SESSION -> { sessions = true; optional = true; invited = true; }
                case LOCAL_ONLY, SOLO_LOCAL_ONLY -> {
                    if (policy == AccessPolicy.LOCAL) {
                        sessions = false;
                        guidance = condition == Condition.SOLO_LOCAL_ONLY ? "SOLO_LOCAL_ONLY" : "LOCAL_ONLY";
                        extra = "";
                    }
                }
            }
        }
        // AuthFilter checks the GUI path before public/session fallback.
        if (path.startsWith("/api/gui/")) {
            require(alternatives, "guiToken");
            guidance = "GUI";
        } else {
            if (sessions) {
                require(alternatives, "adminSessionHeader");
                require(alternatives, "adminSessionCookie");
            }
            if (invited) require(alternatives, "guestInviteCookie");
            if (optional) alternatives.add(new SecurityRequirement());
        }
        operation.setSecurity(alternatives);
        String explanation = text.get("access." + guidance);
        if (extra != null && !extra.isBlank() && !extra.equals(explanation)) explanation += " " + extra;
        return text.get("access").replace("{policy}", policy.name()) + " " + explanation;
    }

    private static void require(List<SecurityRequirement> alternatives, String scheme) {
        alternatives.add(new SecurityRequirement().addList(scheme));
    }
}
