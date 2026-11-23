package top.sywyar.pixivdownload.core.web;

import top.sywyar.pixivdownload.web.HttpParameterBinding;
import top.sywyar.pixivdownload.web.ApiErrorWriter;
import top.sywyar.pixivdownload.setup.ProgramAccess;
import top.sywyar.pixivdownload.setup.AuthFilter.ProgramCredentialError;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import top.sywyar.pixivdownload.config.credential.AcquisitionCredentialLibrary;
import top.sywyar.pixivdownload.plugin.api.download.context.AcquisitionCredentials;
import top.sywyar.pixivdownload.plugin.api.web.RequestOwnerIdentityResolver;

import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.i18n.AppLocaleResolver;
import top.sywyar.pixivdownload.plugin.api.web.ApiErrorResponse;
import top.sywyar.pixivdownload.plugin.api.download.context.AcquisitionCredentialsUnavailableException;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.*;

/** 鉴权后把不含 secret 的选择解析为当前请求作用域，保留旧单凭据调用方合同。 */
@Component
@Order(4)
public final class AcquisitionCredentialsFilter extends OncePerRequestFilter {
    public static final String CREDENTIAL_FORBIDDEN = "CREDENTIAL_FORBIDDEN";
    public static final String CREDENTIAL_SELECTION_REJECTED = "CREDENTIAL_SELECTION_REJECTED";
    public static final String ACQUISITION_CREDENTIALS_UNAVAILABLE = "ACQUISITION_CREDENTIALS_UNAVAILABLE";
    public static final String HEADER = "X-Acquisition-Credentials";
    public static final int MAX_SELECTION_LENGTH = 520;
    private final AcquisitionCredentialLibrary library;
    private final RequestOwnerIdentityResolver identities;
    private final AppMessages messages;
    private final AppLocaleResolver locales;
    private final ObjectMapper json;
    public AcquisitionCredentialsFilter(AcquisitionCredentialLibrary library, RequestOwnerIdentityResolver identities,
                                        AppMessages messages, AppLocaleResolver locales, ObjectMapper json) {
        this.library = library;
        this.identities = identities;
        this.messages = messages;
        this.locales = locales;
        this.json = json;
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String selection = request.getHeader(HEADER);
        if (selection == null) { chain.doFilter(request, response); return; }
        var program = ProgramAccess.identity(request);
        boolean allowed = program
                .map(identity -> identity.permits("core", "credentials.use"))
                .orElseGet(() -> identities.isAdminAuthenticated(request));
        if (!allowed) {
            if (program.isPresent()) {
                String permission = "core:credentials.use";
                String message = messages.get(locales.resolveLocale(request), "auth.program.forbidden.permission", permission);
                response.setHeader("Cache-Control", "no-store");
                ApiErrorWriter.write(response, 403, new ProgramCredentialError(CREDENTIAL_FORBIDDEN, message, permission));
            } else reject(request, response, 403, CREDENTIAL_FORBIDDEN, "auth.unauthorized");
            return;
        }
        AcquisitionCredentials credentials;
        try {
            if (selection.length() > MAX_SELECTION_LENGTH || request.getHeader(AcquisitionCredentialResolver.HEADER_NAME) != null
                    || Collections.list(request.getHeaders(HEADER)).size() != 1)
                throw new IllegalArgumentException();
            credentials = library.resolve(List.of(selection.split(",", -1)));
        } catch (IllegalArgumentException invalid) {
            String reason = selection.length() > MAX_SELECTION_LENGTH ? "out_of_range" : "invalid_value";
            writeError(response, 400, HttpParameterBinding.error(CREDENTIAL_SELECTION_REJECTED,
                    "header", HEADER, reason, messages, locales.resolveLocale(request)));
            return;
        } catch (IOException | AcquisitionCredentialsUnavailableException unavailable) {
            reject(request, response, 503, ACQUISITION_CREDENTIALS_UNAVAILABLE, "error.acquisition.credentials-unavailable"); return;
        }
        HttpServletRequest wrapped = new HttpServletRequestWrapper(request) {
            @Override public String getHeader(String name) {
                return AcquisitionCredentialResolver.HEADER_NAME.equalsIgnoreCase(name) ? credentials.primary() : super.getHeader(name);
            }
            @Override public Enumeration<String> getHeaders(String name) {
                return AcquisitionCredentialResolver.HEADER_NAME.equalsIgnoreCase(name)
                        ? Collections.enumeration(List.of(credentials.primary())) : super.getHeaders(name);
            }
            @Override public Enumeration<String> getHeaderNames() {
                var names = new ArrayList<>(Collections.list(super.getHeaderNames()));
                names.add(AcquisitionCredentialResolver.HEADER_NAME);
                return Collections.enumeration(names);
            }
        };
        try { credentials.call(() -> { chain.doFilter(wrapped, response); return null; }); }
        catch (IOException | ServletException | RuntimeException failure) { throw failure; }
        catch (Exception failure) { throw new ServletException("Acquisition request failed"); }
    }
    private void reject(HttpServletRequest request, HttpServletResponse response, int status, String code, String key) throws IOException {
        writeError(response, status, ApiErrorResponse.of(code, messages.get(locales.resolveLocale(request), key)));
    }

    private void writeError(HttpServletResponse response, int status, Object body) throws IOException {
        response.setStatus(status);
        response.setHeader("Cache-Control", "no-store");
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(json.writeValueAsString(body));
    }
}
