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
public class PixivProxyAccessGuard {

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
        if (setupService.isAdminLoggedIn(request)) {
            return null;
        }
        String uuid = UuidUtils.extractExistingUuid(request);
        if (uuid == null) {
            return ResponseEntity.status(401).body(new ErrorResponse(messages.get("pixiv.proxy.user-uuid.missing")));
        }
        if (!userQuotaService.checkAndReserveProxy(uuid)) {
            int max = multiModeConfig.getQuota().getMaxProxyRequests();
            int hours = multiModeConfig.getQuota().getResetPeriodHours();
            return ResponseEntity.status(429).body(new ProxyRateLimitResponse(
                    messages.get("pixiv.proxy.rate-limit.exceeded", hours, max),
                    max, hours));
        }
        return null;
    }

    public int resolveSearchFillLimitPage(HttpServletRequest request) {
        if (!"multi".equals(setupService.getMode())) {
            return 0;
        }
        if (setupService.isAdminLoggedIn(request)) {
            return 0;
        }
        return Math.max(0, multiModeConfig.getLimitPage());
    }
}
