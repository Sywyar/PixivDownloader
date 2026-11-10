package top.sywyar.pixivdownload.core.web;

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
    public static final String HEADER = "X-Acquisition-Credentials";
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
        if (!identities.isAdminAuthenticated(request)) { reject(request, response, 403, "CREDENTIAL_FORBIDDEN", "auth.unauthorized"); return; }
        AcquisitionCredentials credentials;
        try {
            if (selection.length() > 520 || request.getHeader(AcquisitionCredentialResolver.HEADER_NAME) != null
                    || Collections.list(request.getHeaders(HEADER)).size() != 1)
                throw new IllegalArgumentException();
            credentials = library.resolve(List.of(selection.split(",", -1)));
        } catch (IllegalArgumentException invalid) {
            reject(request, response, 400, "CREDENTIAL_SELECTION_REJECTED", "error.request.param.invalid"); return;
        } catch (IOException | AcquisitionCredentialsUnavailableException unavailable) {
            reject(request, response, 503, "ACQUISITION_CREDENTIALS_UNAVAILABLE", "error.acquisition.credentials-unavailable"); return;
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
        response.setStatus(status);
        response.setHeader("Cache-Control", "no-store");
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(json.writeValueAsString(ApiErrorResponse.of(code, messages.get(locales.resolveLocale(request), key))));
    }
}
