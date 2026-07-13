package top.sywyar.pixivdownload.download.schedule.credential;

import top.sywyar.pixivdownload.download.schedule.network.PixivScheduledRouteScope;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginManagedBean;
import top.sywyar.pixivdownload.plugin.api.schedule.credential.ScheduledCredentialBindResult;
import top.sywyar.pixivdownload.plugin.api.schedule.credential.ScheduledCredentialContext;
import top.sywyar.pixivdownload.plugin.api.schedule.credential.ScheduledCredentialPolicy;
import top.sywyar.pixivdownload.plugin.api.schedule.credential.ScheduledCredentialProbeResult;
import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledExecutionException;
import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledFailure;
import top.sywyar.pixivdownload.plugin.api.schedule.guard.ScheduledGuardDecision;
import top.sywyar.pixivdownload.plugin.api.schedule.guard.ScheduledGuardEvidence;
import top.sywyar.pixivdownload.plugin.api.schedule.guard.ScheduledGuardResult;
import top.sywyar.pixivdownload.schedule.OveruseWarningService;
import top.sywyar.pixivdownload.schedule.persistence.PixivSchedulePersistenceCodec;

import java.util.Arrays;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pixiv Cookie 的格式、账号键与绑定时主动探活策略。 */
@PluginManagedBean
public final class PixivScheduledCredentialPolicy implements ScheduledCredentialPolicy {

    private static final Pattern PHPSESSID = Pattern.compile("(?:^|;\\s*)PHPSESSID=([^;\\s]+)");

    private final OveruseWarningService overuseWarningService;
    private final PixivSchedulePersistenceCodec persistenceCodec;

    public PixivScheduledCredentialPolicy(
            OveruseWarningService overuseWarningService,
            PixivSchedulePersistenceCodec persistenceCodec) {
        this.overuseWarningService = overuseWarningService;
        this.persistenceCodec = persistenceCodec;
    }

    @Override
    public String policyId() {
        return PixivSchedulePersistenceCodec.CREDENTIAL_POLICY_ID;
    }

    @Override
    public ScheduledCredentialProbeResult probe(ScheduledCredentialContext context)
            throws ScheduledExecutionException {
        if (context.purpose() == ScheduledCredentialContext.Purpose.BIND) {
            return probeForBinding(context).probeResult();
        }
        CredentialCandidate candidate = candidate(context);
        return candidate.valid()
                ? ScheduledCredentialProbeResult.valid(candidate.accountKey())
                : ScheduledCredentialProbeResult.invalid("pixiv.credential.phpsessid-missing");
    }

    @Override
    public ScheduledCredentialBindResult probeForBinding(ScheduledCredentialContext context)
            throws ScheduledExecutionException {
        if (context == null || context.purpose() != ScheduledCredentialContext.Purpose.BIND) {
            throw new IllegalArgumentException("Pixiv credential binding requires BIND purpose");
        }
        CredentialCandidate candidate = candidate(context);
        if (!candidate.valid()) {
            return bindResult(ScheduledCredentialProbeResult.invalid(
                    "pixiv.credential.phpsessid-missing"), null);
        }
        OveruseWarningService.Result result;
        try {
            result = PixivScheduledRouteScope.call(
                    context.route(), () -> overuseWarningService.probe(
                            candidate.cookie(), System.currentTimeMillis()));
        } catch (OveruseWarningService.CredentialProbeException failure) {
            throw new ScheduledExecutionException(
                    ScheduledFailure.Category.RETRYABLE_NETWORK,
                    "pixiv.credential.probe-unavailable");
        } catch (ScheduledExecutionException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new ScheduledExecutionException(
                    ScheduledFailure.Category.RETRYABLE_NETWORK,
                    "pixiv.credential.probe-failed");
        }
        if (result.isCookieDead()) {
            return bindResult(ScheduledCredentialProbeResult.invalid(
                    "pixiv.credential.invalid"), null);
        }
        ScheduledGuardResult postBind = result.isWarned()
                ? new ScheduledGuardResult(
                new ScheduledGuardDecision(
                        ScheduledGuardDecision.Action.SUSPEND_POLICY_TASK,
                        "PIXIV_OVERUSE", 0L),
                new ScheduledGuardEvidence(Map.of(
                        "modifiedAt", Long.toString(result.modifiedAt()),
                        "excerpt", result.excerpt())))
                : null;
        return bindResult(
                ScheduledCredentialProbeResult.valid(candidate.accountKey()), postBind);
    }

    private ScheduledCredentialBindResult bindResult(
            ScheduledCredentialProbeResult probe,
            ScheduledGuardResult postBind) {
        return new ScheduledCredentialBindResult(
                probe, persistenceCodec.encodePolicyState(null), postBind);
    }

    private static CredentialCandidate candidate(ScheduledCredentialContext context) {
        char[] secret = context.credential().copySecret();
        String cookie;
        try {
            cookie = new String(secret);
        } finally {
            Arrays.fill(secret, '\0');
        }
        Matcher matcher = PHPSESSID.matcher(cookie);
        if (!matcher.find()) {
            return new CredentialCandidate(cookie, null, false);
        }
        String accountKey = accountKey(matcher.group(1), context.task().taskId());
        return new CredentialCandidate(cookie, accountKey, true);
    }

    private static String accountKey(String session, long taskId) {
        int underscore = session.indexOf('_');
        String candidate = underscore > 0 ? session.substring(0, underscore) : session;
        if (candidate.chars().allMatch(Character::isDigit) && !candidate.isBlank()) {
            return candidate;
        }
        return "pixiv-task-" + taskId;
    }

    private record CredentialCandidate(String cookie, String accountKey, boolean valid) {
    }
}
