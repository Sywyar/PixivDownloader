package top.sywyar.pixivdownload.setup;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@CrossOrigin(origins = "*")
@Slf4j
public class SetupController {

    @Autowired
    private SetupService setupService;

    // ---- Setup endpoints -----------------------------------------------

    @GetMapping("/api/setup/status")
    public Map<String, Object> status() {
        return Map.of(
            "setupComplete", setupService.isSetupComplete(),
            "mode", setupService.getMode() != null ? setupService.getMode() : ""
        );
    }

    @PostMapping("/api/setup/init")
    public ResponseEntity<Map<String, Object>> init(@RequestBody Map<String, String> body) {
        if (setupService.isSetupComplete()) {
            return ResponseEntity.status(403).body(Map.of("error", "已完成配置，不可重复初始化"));
        }
        String username = body.get("username");
        String password = body.get("password");
        String mode     = body.get("mode");

        if (username == null || username.isBlank())
            return ResponseEntity.badRequest().body(Map.of("error", "用户名不能为空"));
        if (password == null || password.length() < 6)
            return ResponseEntity.badRequest().body(Map.of("error", "密码长度至少 6 位"));
        if (!"solo".equals(mode) && !"multi".equals(mode))
            return ResponseEntity.badRequest().body(Map.of("error", "无效的使用模式"));

        try {
            setupService.init(username, password, mode);
            return ResponseEntity.ok(Map.of("ok", true, "mode", mode));
        } catch (Exception e) {
            log.error("Setup init failed", e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    // ---- Auth endpoints ------------------------------------------------

    @PostMapping("/api/auth/login")
    public ResponseEntity<Map<String, Object>> login(
            @RequestBody Map<String, Object> body,
            HttpServletResponse response) {
        String username  = (String) body.get("username");
        String password  = (String) body.get("password");
        boolean remember = Boolean.TRUE.equals(body.get("rememberMe"));

        if (!setupService.checkLogin(username, password)) {
            return ResponseEntity.status(401).body(Map.of("error", "用户名或密码错误"));
        }

        String token = setupService.createSession(remember);
        Cookie cookie = new Cookie("pixiv_session", token);
        cookie.setPath("/");
        cookie.setHttpOnly(true);
        if (remember) cookie.setMaxAge(30 * 24 * 3600);
        response.addCookie(cookie);

        return ResponseEntity.ok(Map.of("ok", true));
    }

    @PostMapping("/api/auth/logout")
    public ResponseEntity<Map<String, Object>> logout(
            HttpServletRequest request,
            HttpServletResponse response) {
        String token = extractToken(request);
        setupService.removeSession(token);

        Cookie cookie = new Cookie("pixiv_session", "");
        cookie.setPath("/");
        cookie.setMaxAge(0);
        response.addCookie(cookie);

        return ResponseEntity.ok(Map.of("ok", true));
    }

    @GetMapping("/api/auth/check")
    public Map<String, Object> check(HttpServletRequest request) {
        return Map.of("valid", setupService.isValidSession(extractToken(request)));
    }

    // ---- 工具 ----------------------------------------------------------

    private String extractToken(HttpServletRequest req) {
        Cookie[] cookies = req.getCookies();
        if (cookies != null) {
            for (Cookie c : cookies) {
                if ("pixiv_session".equals(c.getName())) return c.getValue();
            }
        }
        return req.getHeader("X-Session-Token");
    }
}
