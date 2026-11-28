package top.sywyar.pixivdownload.openapi;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springdoc.core.customizers.SpringDocCustomizers;
import org.springdoc.core.properties.SpringDocConfigProperties;
import org.springdoc.core.providers.SpringDocProviders;
import org.springdoc.core.service.*;
import org.springdoc.webmvc.api.OpenApiResource;
import org.springframework.beans.factory.ObjectFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import org.springframework.web.util.HtmlUtils;
import top.sywyar.pixivdownload.common.AppInfo;
import top.sywyar.pixivdownload.i18n.LocaleCatalog;
import top.sywyar.pixivdownload.i18n.WebI18nService;
import top.sywyar.pixivdownload.core.download.DownloadLifecycleRegistry;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleCapabilityRegistry;
import top.sywyar.pixivdownload.plugin.api.web.HttpApiGroup;
import top.sywyar.pixivdownload.plugin.api.web.HttpMethod;
import top.sywyar.pixivdownload.plugin.registry.route.RouteAccessRegistry;
import top.sywyar.pixivdownload.plugin.web.mapping.PluginAwareRequestMappingHandlerMapping;
import top.sywyar.pixivdownload.setup.ProgramAccess;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The reference page is a public shell that explains how to read the catalog without running scripts.
 * The catalog contains schemas and declared routes only, and is served to administrator sessions and
 * program keys holding its declared permission.
 */
@RestController
@HttpApiGroup("core-keys")
public final class ApiReferenceController extends OpenApiResource {
    private static final String DOCUMENT_PATH = "/api/openapi.json";
    private static final Pattern PAGE_TOKEN = Pattern.compile("\\{\\{([a-zA-Z.]+)}}");
    private final ObjectProvider<RequestMappingHandlerMapping> mappingProvider;
    private final RouteAccessRegistry routes;
    private final DownloadLifecycleRegistry downloads;
    private final ScheduleCapabilityRegistry schedules;
    private final top.sywyar.pixivdownload.setup.SetupService setup;
    private final WebI18nService i18n;
    private final String pageTemplate;
    private final Object rendererLock = new Object();
    private String scalarEtag;
    private final ApiReferenceCache documents;
    private final LocaleCatalog catalog;

    public ApiReferenceController(ObjectFactory<OpenAPIService> service, AbstractRequestService request,
            GenericResponseService response, OperationService operations, SpringDocConfigProperties properties,
            SpringDocProviders providers, SpringDocCustomizers customizers,
            @Qualifier("requestMappingHandlerMapping") ObjectProvider<RequestMappingHandlerMapping> mappings,
            RouteAccessRegistry routes, WebI18nService i18n, DownloadLifecycleRegistry downloads,
            top.sywyar.pixivdownload.setup.SetupService setup, ScheduleCapabilityRegistry schedules,
            LocaleCatalog catalog) throws IOException {
        super(service, request, response, operations, properties, providers, customizers);
        this.mappingProvider = mappings;
        this.routes = routes;
        this.downloads = downloads;
        this.schedules = schedules;
        this.setup = setup;
        this.i18n = i18n;
        this.catalog = catalog;
        this.pageTemplate = new ClassPathResource("openapi/index.html").getContentAsString(StandardCharsets.UTF_8);
        var locales = new LinkedHashSet<Locale>();
        locales.add(catalog.defaultLocale().toLocale());
        catalog.allLocales().forEach(descriptor -> locales.add(descriptor.toLocale()));
        this.documents = new ApiReferenceCache(List.copyOf(locales), this::inputs, this::generate);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void warmDocuments() { documents.start(); }

    @PreDestroy
    public void stopDocuments() throws InterruptedException { documents.close(); }

    private ApiReferenceCache.Inputs inputs() {
        var mappings = (PluginAwareRequestMappingHandlerMapping) mappingProvider.getObject();
        return new ApiReferenceCache.Inputs(mappings.revision(), routes.routes(), downloads.submissionTypes(),
                schedules.snapshotView().revision(), ApiReferenceSecurity.InstanceAccess.of(setup));
    }

    private ApiReferenceDocument generate(Locale locale, ApiReferenceSecurity.InstanceAccess access) throws JsonProcessingException {
        LocaleContextHolder.setLocale(locale);
        try {
            return ScopedValue.where(ApiReferenceSecurity.InstanceAccess.RENDERING, access).call(() ->
                    new ApiReferenceDocument(super.openapiJson(null, DOCUMENT_PATH, locale)));
        } finally {
            LocaleContextHolder.resetLocaleContext();
        }
    }

    /** Renders the access guidance in the request language; the script replaces it once the catalog loads. */
    @GetMapping(value = "/openapi", produces = "text/html;charset=UTF-8")
    public ResponseEntity<String> page(HttpServletRequest request, Locale locale) {
        boolean admin = setup.isAdminLoggedIn(request);
        Map<String, String> text = i18n.loadBundle("openapi", locale).getMessages();
        // The examples use the address this page was requested through, as normalized by the trusted proxy filter.
        String base = ServletUriComponentsBuilder.fromContextPath(request).build().toUriString();
        Map<String, String> fragments = Map.of(
                "{document}", "<code>GET " + DOCUMENT_PATH + "</code>",
                "{header}", "<code>Authorization: Bearer &lt;key&gt;</code>",
                "{permission}", "<code>" + HtmlUtils.htmlEscape(documentPermission()) + "</code>",
                "{list}", "<code>--program-keys</code>",
                "{revoke}", "<code>--program-key-revoke</code>",
                "{base}", "<code>" + HtmlUtils.htmlEscape(base) + "</code>",
                "{keyVariable}", "<code>PIXIV_PROGRAM_KEY</code>",
                "{baseVariable}", "<code>PIXIV_BASE_URL</code>",
                "{login}", "<a href=\"/login.html?redirect=%2Fopenapi\">"
                        + HtmlUtils.htmlEscape(text.getOrDefault("page.login", "")) + "</a>");
        Map<String, String> values = Map.of(
                "admin", Boolean.toString(admin),
                "lang", locale.toLanguageTag(),
                "self", base + ProgramAccess.SELF_PATH,
                "guide", text.getOrDefault("guide.url", AppInfo.DOCS_URL));
        String html = PAGE_TOKEN.matcher(pageTemplate).replaceAll(token -> {
            String key = token.group(1);
            if (values.containsKey(key)) return Matcher.quoteReplacement(HtmlUtils.htmlEscape(values.get(key)));
            String value = HtmlUtils.htmlEscape(text.getOrDefault(key, ""));
            for (var fragment : fragments.entrySet()) value = value.replace(fragment.getKey(), fragment.getValue());
            return Matcher.quoteReplacement(value);
        });
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(html);
    }

    /** The catalog permission comes from the route declaration, so the page cannot drift from what the filter checks. */
    private String documentPermission() {
        return routes.resolve(DOCUMENT_PATH, HttpMethod.GET)
                .flatMap(route -> Optional.ofNullable(route.route().programPermissions().get(HttpMethod.GET))
                        .map(permission -> route.pluginId() + ":" + permission.id()))
                .orElse("");
    }

    @GetMapping(value = "/openapi/ui.js", produces = "text/javascript")
    public ResponseEntity<Resource> initializer() { return asset("openapi/ui.js"); }

    @GetMapping(value = "/openapi/ui.css", produces = "text/css")
    public ResponseEntity<Resource> stylesheet() { return asset("openapi/ui.css"); }

    @GetMapping(value = "/openapi/scalar.js", produces = "text/javascript")
    public ResponseEntity<Resource> bundle() throws IOException {
        var resource = new ClassPathResource("META-INF/resources/webjars/scalar/scalar.js");
        return ResponseEntity.ok().cacheControl(CacheControl.noCache().cachePublic()).eTag(scalarEtag(resource)).body(resource);
    }

    private String scalarEtag(Resource resource) throws IOException {
        synchronized (rendererLock) {
            if (scalarEtag == null) {
                try (var stream = resource.getInputStream()) {
                    scalarEtag = org.springframework.util.DigestUtils.md5DigestAsHex(stream);
                }
            }
            return scalarEtag;
        }
    }

    private static ResponseEntity<Resource> asset(String path) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new ClassPathResource(path));
    }

    @RequestMapping(value = DOCUMENT_PATH, method = RequestMethod.HEAD, produces = "application/json")
    public void probe(HttpServletResponse response, Locale locale) {
        setDocumentHeaders(response, locale);
        response.setContentType("application/json");
    }

    private static void setDocumentHeaders(HttpServletResponse response, Locale locale) {
        response.setHeader("Cache-Control", "private, no-store");
        response.setHeader("Content-Language", locale.toLanguageTag());
    }

    @ApiResponse(responseCode = "200", content = @Content(mediaType = "application/json",
            schema = @Schema(type = "object", additionalProperties = Schema.AdditionalPropertiesValue.TRUE)))
    @GetMapping(value = DOCUMENT_PATH, produces = "application/json")
    public byte[] document(HttpServletRequest request, HttpServletResponse response, Locale locale) throws InterruptedException {
        setDocumentHeaders(response, locale);
        return documents.read(catalog.resolve(locale).toLocale()).forRequest(request);
    }

    @Override protected boolean isRestController(Map<String, Object> controllers, HandlerMethod method, String path) {
        // External controllers are beans of child plugin contexts, registered in the host MVC mapping.
        return path.startsWith("/api/") && routes.isDeclared(path);
    }

    @Override protected String getServerUrl(HttpServletRequest request, String docsUrl) { return "/"; }
}
