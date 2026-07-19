package top.sywyar.pixivdownload.download.schedule.guard;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import top.sywyar.pixivdownload.schedule.OveruseWarningService;
import top.sywyar.pixivdownload.schedule.persistence.PixivSchedulePersistenceCodec;
import top.sywyar.pixivdownload.schedule.snapshot.ScheduleTaskSnapshot;

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
        char[] secret = context.credential().copySecret();
        String cookie;
        try {
            cookie = new String(secret);
        } finally {
            Arrays.fill(secret, '\0');
        }
        Long acknowledged = acknowledgedWarningTime(context);
        OveruseWarningService.Result result;
        try {
            result = PixivScheduledRouteScope.call(context.route(), () ->
                    overuseWarningService.check(cookie, acknowledged, System.currentTimeMillis()));
        } catch (Exception failure) {
            throw new ScheduledExecutionException(
                    ScheduledFailure.Category.RETRYABLE_NETWORK,
                    "pixiv.guard.check-failed");
        }
        if (result.isWarned()) {
            ScheduledGuardDecision decision = new ScheduledGuardDecision(
                    ScheduledGuardDecision.Action.SUSPEND_POLICY_ACCOUNT,
                    "PIXIV_OVERUSE", 0L);
            return new ScheduledGuardResult(decision, new ScheduledGuardEvidence(Map.of(
                    "modifiedAt", Long.toString(result.modifiedAt()),
                    "excerpt", result.excerpt())));
        }
        if (result.isCookieDead() && context.point() == ScheduledGuardPoint.RUN_START) {
            ScheduleTaskSnapshot snapshot = parseSnapshot(context);
            boolean credentialRequired = snapshot.cookieDependent()
                    || ACCOUNT_SCOPED_SOURCES.contains(context.task().sourceType());
            ScheduledGuardDecision.Action action = credentialRequired
                    ? ScheduledGuardDecision.Action.SUSPEND_CREDENTIAL
                    : ScheduledGuardDecision.Action.REVOKE_CREDENTIAL_AND_CONTINUE;
            return ScheduledGuardResult.decision(new ScheduledGuardDecision(
                    action, "COOKIE_DEAD", 0L));
        }
        return ScheduledGuardResult.decision(ScheduledGuardDecision.proceed());
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
