package top.sywyar.pixivdownload.setup;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import top.sywyar.pixivdownload.common.SessionUtils;
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

    // ---- Setup endpoints -----------------------------------------------

    @GetMapping("/api/setup/status")
    public SetupStatusResponse status() {
        return new SetupStatusResponse(
                setupService.isSetupComplete(),
                setupService.getMode() != null ? setupService.getMode() : ""
        );
    }

    @PostMapping("/api/setup/init")
    public SetupInitResponse init(@Valid @RequestBody SetupInitRequest request) throws IOException {
        if (setupService.isSetupComplete()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "已完成配置，不可重复初始化");
        }
        setupService.init(request.getUsername(), request.getPassword(), request.getMode());
        return new SetupInitResponse(true, request.getMode());
    }

    // ---- Auth endpoints ------------------------------------------------

    @PostMapping("/api/auth/login")
    public ResponseEntity<AuthResponse> login(@RequestBody LoginRequest request) {
        if (!setupService.checkLogin(request.getUsername(), request.getPassword())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "用户名或密码错误");
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
        return new AuthCheckResponse(setupService.isValidSession(SessionUtils.extractToken(request)));
    }
}
