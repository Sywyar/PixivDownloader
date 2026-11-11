package top.sywyar.pixivdownload.setup.guest.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import top.sywyar.pixivdownload.common.InstanceCookies;
import top.sywyar.pixivdownload.i18n.LocalizedException;
import top.sywyar.pixivdownload.plugin.registry.web.LandingRegistry;
import top.sywyar.pixivdownload.plugin.api.web.Audience;
import top.sywyar.pixivdownload.plugin.api.operation.*;
import top.sywyar.pixivdownload.setup.*;
import top.sywyar.pixivdownload.setup.guest.GuestInviteService;
import java.io.IOException;

/** 宿主限流和有界输入校验先于 Hook；凭据只在受控业务动作提交后签发。 */
@RestController
@RequiredArgsConstructor
public class InviteRedeemController {
    private final GuestInviteService guestInviteService;
    private final LandingRegistry landingRegistry;

    @Value("${server.ssl.enabled:false}")
    private boolean sslEnabled;

    @PostMapping("/api/auth/invite-redeem")
    @OperationEndpoint(operation = "core:invite-redeem")
    public ResponseEntity<?> redeem(@Valid @RequestBody RedeemRequest body, HttpServletRequest request) throws IOException {
        String code = validate(body.code());
        String token = guestInviteService.redeem(code).orElseThrow(() -> new LocalizedException(
                HttpStatus.UNAUTHORIZED, "guest.invite.code.invalid", null));
        ResponseCookie cookie = ResponseCookie.from(InstanceCookies.name(request, AuthFilter.INVITE_COOKIE), token)
                .path("/").httpOnly(true).secure(sslEnabled).sameSite("Strict").build();
        String redirect = landingRegistry.resolve(Audience.INVITED_GUEST).orElse("/login.html?inviteError=1");
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, cookie.toString()).body(new RedeemResponse(true, redirect));
    }

    private String validate(String code) {
        String normalized = code == null ? "" : code.trim().toUpperCase(java.util.Locale.ROOT);
        if (!normalized.matches("[A-HJ-NP-Z2-9]{24}"))
            throw new LocalizedException(HttpStatus.BAD_REQUEST, "guest.invite.code.invalid", null);
        return normalized;
    }

    public record RedeemRequest(@NotBlank @Size(max = 64) String code) { }
    public record RedeemResponse(boolean success, String redirect) { }
}
