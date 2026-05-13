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
        if (!NetworkUtils.isLocalRequest(httpRequest)) {
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
        setupService.init(request.getUsername(), request.getPassword(), request.getMode());
        return new SetupInitResponse(true, request.getMode());
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
        ResponseCookie cookie = ResponseCookie.from("pixiv_session", token)
                .path("/")
                .httpOnly(true)
                .sameSite("Strict")
                .maxAge(request.isRememberMe() ? Duration.ofDays(30) : Duration.ZERO)
                .build();

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
