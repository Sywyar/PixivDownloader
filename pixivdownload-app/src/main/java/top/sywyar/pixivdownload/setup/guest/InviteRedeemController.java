package top.sywyar.pixivdownload.setup.guest;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import top.sywyar.pixivdownload.i18n.LocalizedException;
import top.sywyar.pixivdownload.plugin.LandingRegistry;
import top.sywyar.pixivdownload.plugin.api.web.Audience;
import top.sywyar.pixivdownload.setup.AuthFilter;
import top.sywyar.pixivdownload.setup.LoginRateLimitService;

import java.util.Map;
import java.util.Optional;

/**
 * 公开的邀请码兑换端点。每次提交无论成败都计入登录限流，防止暴力枚举 code。
 *
 * <p>下发的 {@code pixiv_invite_token} cookie 与 {@code AuthFilter} 的清除 / GET {@code /invite} 兑换路径
 * 保持一致的安全属性：HttpOnly + SameSite=Strict + 当 {@code server.ssl.enabled} 为 true 时附加 Secure。</p>
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class InviteRedeemController {

    private final GuestInviteService guestInviteService;
    private final LoginRateLimitService loginRateLimitService;
    private final LandingRegistry landingRegistry;

    @Value("${server.ssl.enabled:false}")
    private boolean sslEnabled;

    @PostMapping("/api/auth/invite-redeem")
    public ResponseEntity<Map<String, Object>> redeem(@RequestBody RedeemRequest body,
                                                     HttpServletRequest request) {
        String ip = clientIp(request);
        if (!loginRateLimitService.isAllowed(ip)) {
            throw new LocalizedException(HttpStatus.TOO_MANY_REQUESTS,
                    "setup.login.rate-limit.exceeded",
                    "登录尝试过于频繁，请稍后再试");
        }
        String code = body == null ? null : body.code();
        if (code == null || code.isBlank()) {
            throw new LocalizedException(HttpStatus.BAD_REQUEST,
                    "guest.invite.code.required", "请输入邀请码");
        }
        Optional<GuestInviteSession> session = guestInviteService.resolveByCode(code);
        if (session.isEmpty()) {
            throw new LocalizedException(HttpStatus.UNAUTHORIZED,
                    "guest.invite.code.invalid", "邀请码无效或已失效");
        }
        ResponseCookie cookie = ResponseCookie.from(AuthFilter.INVITE_COOKIE, session.get().code())
                .path("/").httpOnly(true).secure(sslEnabled).sameSite("Strict").build();
        // 落点经独立的 LandingRegistry 按受邀访客的落点优先级解析（画廊 priority 20 优先，禁用则回退小说 30），
        // 都禁用则回登录页提示。与导航排序解耦：第三方插件无法借更小的导航 order 改变邀请落点。
        String redirect = landingRegistry.resolve(Audience.INVITED_GUEST)
                .orElse("/login.html?inviteError=1");
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .body(Map.of("success", true, "redirect", redirect));
    }

    private String clientIp(HttpServletRequest request) {
        return request.getRemoteAddr();
    }

    public record RedeemRequest(String code) {}
}
