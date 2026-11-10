package top.sywyar.pixivdownload.download.schedule.guard;

import tools.jackson.databind.ObjectMapper;
import top.sywyar.pixivdownload.download.schedule.network.PixivScheduledRouteScope;
import top.sywyar.pixivdownload.download.schedule.source.descriptor.PixivScheduledSourceDescriptors;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginManagedBean;
import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledExecutionException;
import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledFailure;
import top.sywyar.pixivdownload.plugin.api.schedule.guard.ScheduledExecutionGuard;
import top.sywyar.pixivdownload.plugin.api.schedule.guard.ScheduledGuardContext;
import top.sywyar.pixivdownload.plugin.api.schedule.guard.ScheduledGuardDecision;
import top.sywyar.pixivdownload.plugin.api.schedule.guard.ScheduledGuardEvidence;
import top.sywyar.pixivdownload.plugin.api.schedule.guard.ScheduledGuardPoint;
import top.sywyar.pixivdownload.plugin.api.schedule.guard.ScheduledGuardResult;
import top.sywyar.pixivdownload.download.schedule.credential.OveruseWarningService;
import top.sywyar.pixivdownload.download.schedule.persistence.PixivSchedulePersistenceCodec;
import top.sywyar.pixivdownload.download.schedule.snapshot.ScheduleTaskSnapshot;

import java.util.Arrays;
import java.util.Map;
import java.util.Set;

/** Pixiv 站内信风险与失效 Cookie 在宿主固定检查点上的决定适配器。 */
@PluginManagedBean
public final class PixivOveruseExecutionGuard implements ScheduledExecutionGuard {

    private static final Set<String> ACCOUNT_SCOPED_SOURCES = Set.of(
            "my-bookmarks", "follow-latest", "collection");

    private final OveruseWarningService overuseWarningService;
    private final PixivSchedulePersistenceCodec persistenceCodec;
    private final ObjectMapper objectMapper;

    public PixivOveruseExecutionGuard(
            OveruseWarningService overuseWarningService,
            PixivSchedulePersistenceCodec persistenceCodec,
            ObjectMapper objectMapper) {
        this.overuseWarningService = overuseWarningService;
        this.persistenceCodec = persistenceCodec;
        this.objectMapper = objectMapper;
    }

    @Override
    public String guardId() {
        return PixivScheduledSourceDescriptors.OVERUSE_GUARD_ID;
    }

    @Override
    public ScheduledGuardResult evaluate(ScheduledGuardContext context)
            throws ScheduledExecutionException {
        if (!context.credential().isPresent()) {
            return ScheduledGuardResult.decision(ScheduledGuardDecision.proceed());
        }
        Long acknowledged = acknowledgedWarningTime(context);
        var candidates = context.credential().copyCandidates();
        boolean primaryDead = false;
        boolean usable = false;
        boolean sameAccountUsable = false;
        long retryAfter = Long.MAX_VALUE;
        String primaryAccount = context.credential().accountKey();
        try {
            for (int i = 0; i < candidates.size(); i++) {
                context.cancellation().throwIfCancellationRequested();
                var candidate = candidates.get(i);
                char[] secret = candidate.copySecret();
                String cookie;
                try { cookie = new String(secret); }
                finally { Arrays.fill(secret, '\0'); }
                boolean sameAccount = i == 0 || primaryAccount != null && primaryAccount.equals(candidate.accountKey());
                OveruseWarningService.Result result;
                try {
                    if (candidates.size() == 1) {
                        result = PixivScheduledRouteScope.call(context.route(), () ->
                                overuseWarningService.check(cookie, acknowledged, System.currentTimeMillis()));
                    } else {
                        var scope = new top.sywyar.pixivdownload.plugin.api.download.context.AcquisitionCredentials(java.util.List.of(cookie))
                                .withCancellation(context.cancellation()::isCancellationRequested);
                        result = scope.call(() -> PixivScheduledRouteScope.call(context.route(), () ->
                                overuseWarningService.checkVerified(cookie, sameAccount ? acknowledged : null,
                                        System.currentTimeMillis())));
                    }
                } catch (top.sywyar.pixivdownload.plugin.api.download.context.AcquisitionCredentialsUnavailableException unavailable) {
                    retryAfter = Math.min(retryAfter, unavailable.retryAfterMillis());
                    continue;
                } catch (java.util.concurrent.CancellationException cancelled) {
                    throw ScheduledExecutionException.cancelled();
                } catch (Exception failure) {
                    throw new ScheduledExecutionException(ScheduledFailure.Category.RETRYABLE_NETWORK,
                            "pixiv.guard.check-failed");
                }
                context.cancellation().throwIfCancellationRequested();
                if (result.isWarned()) {
                    ScheduledGuardDecision decision = new ScheduledGuardDecision(sameAccount
                            ? ScheduledGuardDecision.Action.SUSPEND_POLICY_ACCOUNT
                            : ScheduledGuardDecision.Action.SUSPEND_POLICY_TASK, "PIXIV_OVERUSE", 0L);
                    return new ScheduledGuardResult(decision, new ScheduledGuardEvidence(Map.of(
                            "modifiedAt", Long.toString(result.modifiedAt()), "excerpt", result.excerpt())));
                }
                if (i == 0) primaryDead = result.isCookieDead();
                if (!result.isCookieDead()) {
                    usable = true;
                    sameAccountUsable |= sameAccount;
                }
            }
            boolean personal = ACCOUNT_SCOPED_SOURCES.contains(context.task().sourceType());
            if (candidates.size() > 1 && !(personal ? sameAccountUsable : usable)) {
                if (retryAfter != Long.MAX_VALUE)
                    return ScheduledGuardResult.decision(new ScheduledGuardDecision(
                            ScheduledGuardDecision.Action.RETRY_LATER, "ACQUISITION_CREDENTIALS_UNAVAILABLE", retryAfter));
                return ScheduledGuardResult.decision(new ScheduledGuardDecision(
                        ScheduledGuardDecision.Action.SUSPEND_CREDENTIAL, "COOKIE_DEAD", 0L));
            }
            if (candidates.size() == 1 && primaryDead && context.point() == ScheduledGuardPoint.RUN_START) {
                boolean required = parseSnapshot(context).cookieDependent() || personal;
                return ScheduledGuardResult.decision(new ScheduledGuardDecision(required
                        ? ScheduledGuardDecision.Action.SUSPEND_CREDENTIAL
                        : ScheduledGuardDecision.Action.REVOKE_CREDENTIAL_AND_CONTINUE, "COOKIE_DEAD", 0L));
            }
            return ScheduledGuardResult.decision(ScheduledGuardDecision.proceed());
        } finally {
            candidates.forEach(top.sywyar.pixivdownload.plugin.api.schedule.credential.ScheduledCredentialCandidate::close);
        }
    }

    private Long acknowledgedWarningTime(ScheduledGuardContext context)
            throws ScheduledExecutionException {
        String state = context.credentialPolicyStateJson().orElse(null);
        if (state == null || state.isBlank()) {
            return null;
        }
        try {
            return persistenceCodec.decodeAcknowledgedWarningTime(state);
        } catch (IllegalArgumentException failure) {
            throw new ScheduledExecutionException(
                    ScheduledFailure.Category.INVALID_DEFINITION,
                    "pixiv.credential.policy-state-invalid");
        }
    }

    private ScheduleTaskSnapshot parseSnapshot(ScheduledGuardContext context)
            throws ScheduledExecutionException {
        try {
            return ScheduleTaskSnapshot.parse(objectMapper, context.task().definitionJson());
        } catch (Exception failure) {
            throw new ScheduledExecutionException(
                    ScheduledFailure.Category.INVALID_DEFINITION,
                    "pixiv.schedule.definition-invalid");
        }
    }
}
