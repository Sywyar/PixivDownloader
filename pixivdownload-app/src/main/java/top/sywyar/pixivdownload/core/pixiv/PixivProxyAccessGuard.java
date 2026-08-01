package top.sywyar.pixivdownload.core.pixiv;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import top.sywyar.pixivdownload.core.appconfig.MultiModeConfig;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.quota.UserQuotaService;
import top.sywyar.pixivdownload.setup.SetupService;

@Service
@RequiredArgsConstructor
public class PixivProxyAccessGuard implements PixivProxyAccessPolicy {

    private final SetupService setupService;
    private final UserQuotaService userQuotaService;
    private final MultiModeConfig multiModeConfig;
    private final AppMessages messages;

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
