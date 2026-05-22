package top.sywyar.pixivdownload.setup;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import top.sywyar.pixivdownload.common.NetworkUtils;
import top.sywyar.pixivdownload.common.SessionUtils;
import top.sywyar.pixivdownload.config.ProxyConfig;
import top.sywyar.pixivdownload.i18n.LocalizedException;
import top.sywyar.pixivdownload.quota.MultiModeConfig;
import top.sywyar.pixivdownload.setup.request.LoginRequest;
import top.sywyar.pixivdownload.setup.request.SetupInitRequest;
import top.sywyar.pixivdownload.setup.response.AuthCheckResponse;
import top.sywyar.pixivdownload.setup.response.AuthResponse;
import top.sywyar.pixivdownload.setup.response.SetupInitResponse;
import top.sywyar.pixivdownload.setup.response.SetupStatusResponse;

import java.io.IOException;
import java.time.Duration;

@RestController
@Slf4j
@RequiredArgsConstructor
public class SetupController {

    private final SetupService setupService;
    private final LoginRateLimitService loginRateLimitService;
    private final MultiModeConfig multiModeConfig;
    private final ProxySetupService proxySetupService;

    @GetMapping("/api/setup/status")
    public SetupStatusResponse status() {
        return new SetupStatusResponse(
                setupService.isSetupComplete(),
                setupService.getMode() != null ? setupService.getMode() : "",
                Math.max(0, multiModeConfig.getLimitPage())
        );
    }

    @PostMapping("/api/setup/init")
    public SetupInitResponse init(@Valid @RequestBody SetupInitRequest request,
                                  HttpServletRequest httpRequest) throws IOException {
        if (!NetworkUtils.isTrustedLocalRequest(httpRequest)) {
            throw new LocalizedException(
                    HttpStatus.FORBIDDEN,
                    "auth.local-only",
                    "Forbidden: local access only"
            );
        }
        if (setupService.isSetupComplete()) {
            throw new LocalizedException(
                    HttpStatus.FORBIDDEN,
                    "setup.init.already-completed",
                    "Setup already completed"
            );
        }
        // 先校验代理输入（无效则整体拒绝，不写入 setup_config.json），再完成安装并落盘代理配置
        ProxySettings proxy = resolveProxy(request);
        setupService.init(request.getUsername(), request.getPassword(), request.getMode());
        if (proxy != null) {
            proxySetupService.applyAndReload(proxy.enabled(), proxy.host(), proxy.port());
        }
        return new SetupInitResponse(true, request.getMode());
    }

    /**
     * 解析并校验请求中的可选代理配置。{@code proxyEnabled} 为 null 时返回 null（本次安装不改动代理默认值）。
     * 启用代理时主机不能为空、端口必须在 1-65535；未启用时缺省回退到默认值以便后续开启复用。
     */
    private ProxySettings resolveProxy(SetupInitRequest request) {
        if (request.getProxyEnabled() == null) {
            return null;
        }
        boolean enabled = request.getProxyEnabled();
        String host = request.getProxyHost() == null ? "" : request.getProxyHost().trim();
        Integer portObj = request.getProxyPort();
        int port = portObj == null ? ProxyConfig.DEFAULT_PORT : portObj;

        if (enabled) {
            if (host.isBlank()) {
                throw new LocalizedException(HttpStatus.BAD_REQUEST,
                        "setup.init.proxy.invalid-host", "Proxy host is required when proxy is enabled");
            }
            if (port < 1 || port > 65535) {
                throw new LocalizedException(HttpStatus.BAD_REQUEST,
                        "setup.init.proxy.invalid-port", "Proxy port must be between 1 and 65535");
            }
        } else {
            if (host.isBlank()) {
                host = ProxyConfig.DEFAULT_HOST;
            }
            if (port < 1 || port > 65535) {
                port = ProxyConfig.DEFAULT_PORT;
            }
        }
        return new ProxySettings(enabled, host, port);
    }

    private record ProxySettings(boolean enabled, String host, int port) {
    }

    @PostMapping("/api/auth/login")
    public ResponseEntity<AuthResponse> login(@RequestBody LoginRequest request, HttpServletRequest httpRequest) {
        String clientIp = getClientIp(httpRequest);
        if (!loginRateLimitService.isAllowed(clientIp)) {
            throw new LocalizedException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "setup.login.rate-limit.exceeded",
                    "Too many login attempts. Please try again later."
            );
        }
        if (!setupService.checkLogin(request.getUsername(), request.getPassword())) {
            throw new LocalizedException(
                    HttpStatus.UNAUTHORIZED,
                    "setup.login.invalid-credentials",
                    "Invalid username or password"
            );
        }

        String token = setupService.createSession(request.isRememberMe());
        ResponseCookie.ResponseCookieBuilder cookieBuilder = ResponseCookie.from("pixiv_session", token)
                .path("/")
                .httpOnly(true)
                .sameSite("Strict");
        if (request.isRememberMe()) {
            cookieBuilder.maxAge(Duration.ofDays(30));
        }
        ResponseCookie cookie = cookieBuilder.build();

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .body(new AuthResponse(true));
    }

    @PostMapping("/api/auth/logout")
    public ResponseEntity<AuthResponse> logout(HttpServletRequest request) {
        String token = SessionUtils.extractToken(request);
        setupService.removeSession(token);

        ResponseCookie cookie = ResponseCookie.from("pixiv_session", "")
                .path("/")
                .httpOnly(true)
                .sameSite("Strict")
                .maxAge(0)
                .build();

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .body(new AuthResponse(true));
    }

    @GetMapping("/api/auth/check")
    public AuthCheckResponse check(HttpServletRequest request) {
        return new AuthCheckResponse(setupService.isAdminLoggedIn(request));
    }

    private String getClientIp(HttpServletRequest request) {
        return request.getRemoteAddr();
    }
}
