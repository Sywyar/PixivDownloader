package top.sywyar.pixivdownload.core.pixiv;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import top.sywyar.pixivdownload.common.ErrorResponse;
import top.sywyar.pixivdownload.common.UuidUtils;
import top.sywyar.pixivdownload.core.appconfig.MultiModeConfig;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.quota.UserQuotaService;
import top.sywyar.pixivdownload.quota.response.ProxyRateLimitResponse;
import top.sywyar.pixivdownload.setup.SetupService;

@Service
@RequiredArgsConstructor
public class PixivProxyAccessGuard implements PixivProxyAccessPolicy {

    private final SetupService setupService;
    private final UserQuotaService userQuotaService;
    private final MultiModeConfig multiModeConfig;
    private final AppMessages messages;

    /**
     * 多人模式访问控制：
     * - 要求 UUID 已存在（cookie 或 X-User-UUID 请求头），不接受自动生成的匿名访问
     * - 在 resetPeriodHours 窗口内最多 maxArtworks 次代理请求
     * 返回 null 表示校验通过；返回 ResponseEntity 表示应直接返回该错误。
     * solo 模式已由 AuthFilter 完成认证，直接返回 null。
     */
    public ResponseEntity<?> checkMultiModeAccess(HttpServletRequest request) {
        if (!"multi".equals(setupService.getMode())) {
            return null;
        }
        PixivProxyAccessDecision decision = evaluate(
                UuidUtils.extractExistingUuid(request),
                setupService.isAdminLoggedIn(request));
        return switch (decision.outcome()) {
            case ALLOWED -> null;
            case OWNER_REQUIRED -> ResponseEntity.status(401)
                    .body(new ErrorResponse(decision.errorMessage()));
            case RATE_LIMITED -> ResponseEntity.status(429)
                    .body(new ProxyRateLimitResponse(
                            decision.errorMessage(), decision.maxRequests(), decision.windowHours()));
        };
    }

    public int resolveSearchFillLimitPage(HttpServletRequest request) {
        if (!"multi".equals(setupService.getMode())) {
            return 0;
        }
        return resolveSearchFillLimitPage(setupService.isAdminLoggedIn(request));
    }

    @Override
    public PixivProxyAccessDecision evaluate(String existingOwnerUuid, boolean adminAuthenticated) {
        if (!"multi".equals(setupService.getMode())) {
            return new PixivProxyAccessDecision(PixivProxyAccessOutcome.ALLOWED, null, 0, 0);
        }
        if (adminAuthenticated) {
            return new PixivProxyAccessDecision(PixivProxyAccessOutcome.ALLOWED, null, 0, 0);
        }
        if (existingOwnerUuid == null) {
            return new PixivProxyAccessDecision(
                    PixivProxyAccessOutcome.OWNER_REQUIRED,
                    messages.get("pixiv.proxy.user-uuid.missing"),
                    0,
                    0);
        }
        if (!userQuotaService.checkAndReserveProxy(existingOwnerUuid)) {
            int max = multiModeConfig.getQuota().getMaxProxyRequests();
            int hours = multiModeConfig.getQuota().getResetPeriodHours();
            return new PixivProxyAccessDecision(
                    PixivProxyAccessOutcome.RATE_LIMITED,
                    messages.get("pixiv.proxy.rate-limit.exceeded", hours, max),
                    max,
                    hours);
        }
        return new PixivProxyAccessDecision(PixivProxyAccessOutcome.ALLOWED, null, 0, 0);
    }

    @Override
    public int resolveSearchFillLimitPage(boolean adminAuthenticated) {
        if (!"multi".equals(setupService.getMode()) || adminAuthenticated) {
            return 0;
        }
        return Math.max(0, multiModeConfig.getLimitPage());
    }
}
