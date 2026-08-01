package top.sywyar.pixivdownload.download.schedule.credential;

import top.sywyar.pixivdownload.download.schedule.network.PixivScheduledRouteScope;
import top.sywyar.pixivdownload.download.schedule.PixivScheduleSettings;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginManagedBean;
import top.sywyar.pixivdownload.plugin.api.schedule.credential.ScheduledCredentialBindResult;
import top.sywyar.pixivdownload.plugin.api.schedule.credential.ScheduledCredentialAccountActionPlan;
import top.sywyar.pixivdownload.plugin.api.schedule.credential.ScheduledCredentialAccountActionRequest;
import top.sywyar.pixivdownload.plugin.api.schedule.credential.ScheduledCredentialAccountIncident;
import top.sywyar.pixivdownload.plugin.api.schedule.credential.ScheduledCredentialContext;
import top.sywyar.pixivdownload.plugin.api.schedule.credential.ScheduledCredentialIncidentPresentation;
import top.sywyar.pixivdownload.plugin.api.schedule.credential.ScheduledCredentialPolicy;
import top.sywyar.pixivdownload.plugin.api.schedule.credential.ScheduledCredentialProbeResult;
import top.sywyar.pixivdownload.plugin.api.schedule.credential.ScheduledCredentialTaskPresentation;
import top.sywyar.pixivdownload.plugin.api.schedule.credential.ScheduledCredentialTaskSnapshot;
import top.sywyar.pixivdownload.plugin.api.schedule.credential.ScheduledCredentialTaskStateUpdate;
import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledExecutionException;
import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledFailure;
import top.sywyar.pixivdownload.plugin.api.schedule.guard.ScheduledGuardDecision;
import top.sywyar.pixivdownload.plugin.api.schedule.guard.ScheduledGuardEvidence;
import top.sywyar.pixivdownload.plugin.api.schedule.guard.ScheduledGuardResult;
import top.sywyar.pixivdownload.download.schedule.credential.OveruseWarningService;
import top.sywyar.pixivdownload.download.schedule.persistence.PixivSchedulePersistenceCodec;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pixiv Cookie 的格式、账号键与绑定时主动探活策略。 */
@PluginManagedBean
public final class PixivScheduledCredentialPolicy implements ScheduledCredentialPolicy {

    private static final Pattern PHPSESSID = Pattern.compile("(?:^|;\\s*)PHPSESSID=([^;\\s]+)");
    public static final String POLICY_ID = PixivSchedulePersistenceCodec.CREDENTIAL_POLICY_ID;
    public static final String ACTION_IGNORE_RISK = "ignore";
    public static final String ACTION_DEFER = "defer";
    public static final String OVERUSE_REASON_CODE = "PIXIV_OVERUSE";
    public static final int MIN_DEFER_MINUTES = 60;
    private static final String STATUS_AUTH_EXPIRED = "AUTH_EXPIRED";
    private static final String STATUS_OVERUSE_PAUSED = "OVERUSE_PAUSED";
    private static final String NOTIFICATION_SCENARIO = "overuse-paused";

    private final OveruseWarningService overuseWarningService;
    private final PixivSchedulePersistenceCodec persistenceCodec;
    private final PixivScheduleSettings settings;

    public PixivScheduledCredentialPolicy(
            OveruseWarningService overuseWarningService,
            PixivSchedulePersistenceCodec persistenceCodec,
            PixivScheduleSettings settings) {
        this.overuseWarningService = Objects.requireNonNull(
                overuseWarningService, "overuseWarningService");
        this.persistenceCodec = Objects.requireNonNull(persistenceCodec, "persistenceCodec");
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    @Override
    public String policyId() {
        return POLICY_ID;
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
                        OVERUSE_REASON_CODE, 0L),
                new ScheduledGuardEvidence(Map.of(
                        "modifiedAt", Long.toString(result.modifiedAt()),
                        "excerpt", result.excerpt())))
                : null;
        return bindResult(
                ScheduledCredentialProbeResult.valid(candidate.accountKey()), postBind);
    }

    @Override
    public ScheduledCredentialTaskPresentation taskPresentation(
            ScheduledCredentialTaskSnapshot task) {
        String statusCode = task.credentialSuspended()
                ? STATUS_AUTH_EXPIRED
                : task.policySuspended() && OVERUSE_REASON_CODE.equals(task.suspendCode())
                ? STATUS_OVERUSE_PAUSED
                : null;
        Long acknowledgedEventTime = null;
        if (task.policyStateJson() != null) {
            try {
                acknowledgedEventTime = persistenceCodec.decodeAcknowledgedWarningTime(
                        task.policyStateJson());
            } catch (IllegalArgumentException ignored) {
                // 损坏或未来版本状态不伪造兼容确认时间；宿主仍返回原始中性机器态。
            }
        }
        return new ScheduledCredentialTaskPresentation(statusCode, acknowledgedEventTime);
    }

    @Override
    public Optional<ScheduledCredentialAccountActionPlan> prepareAccountAction(
            ScheduledCredentialAccountActionRequest request) {
        List<ScheduledCredentialTaskSnapshot> tasks = request.tasks();
        if (tasks.stream().noneMatch(task -> task.policySuspended()
                && OVERUSE_REASON_CODE.equals(task.suspendCode()))) {
            return Optional.empty();
        }
        if (tasks.stream().anyMatch(ScheduledCredentialTaskSnapshot::busy)) {
            throw new IllegalArgumentException("Pixiv credential account still has a busy task");
        }
        long nextRunTime = switch (request.actionId()) {
            case ACTION_IGNORE_RISK -> request.requestedAt();
            case ACTION_DEFER -> deferredRunTime(request);
            default -> throw new IllegalArgumentException("unsupported Pixiv credential account action");
        };
        long acknowledgedAt = tasks.stream()
                .filter(task -> task.policySuspended()
                        && OVERUSE_REASON_CODE.equals(task.suspendCode()))
                .map(ScheduledCredentialTaskSnapshot::suspendDetailJson)
                .map(persistenceCodec::decodeOveruseWarningTime)
                .filter(java.util.Objects::nonNull)
                .mapToLong(Long::longValue)
                .max()
                .orElse(request.requestedAt());
        List<ScheduledCredentialTaskStateUpdate> updates = tasks.stream()
                .map(task -> {
                    String oldState = task.policyStateJson() == null
                            ? persistenceCodec.encodePolicyState(null)
                            : task.policyStateJson();
                    return new ScheduledCredentialTaskStateUpdate(
                            task.taskId(), task.stateVersion(), oldState,
                            persistenceCodec.withAcknowledgedWarningTime(
                                    oldState, acknowledgedAt));
                })
                .toList();
        return Optional.of(new ScheduledCredentialAccountActionPlan(
                OVERUSE_REASON_CODE, nextRunTime, updates));
    }

    @Override
    public ScheduledCredentialIncidentPresentation incidentPresentation(
            ScheduledCredentialAccountIncident incident) {
        if (!OVERUSE_REASON_CODE.equals(incident.reasonCode())) {
            return ScheduledCredentialIncidentPresentation.empty();
        }
        String excerpt = incident.evidence().attributes().getOrDefault("excerpt", "");
        Long warningTime = parseNonNegativeLong(
                incident.evidence().attributes().get("modifiedAt"));
        return new ScheduledCredentialIncidentPresentation(
                NOTIFICATION_SCENARIO,
                Map.of("warning_excerpt", excerpt),
                warningTime == null ? Map.of() : Map.of("warning_time", warningTime));
    }

    private long deferredRunTime(ScheduledCredentialAccountActionRequest request) {
        String configured = request.parameters().get("minutes");
        int minutes;
        try {
            minutes = configured == null || configured.isBlank()
                    ? settings.getOveruseDeferDefaultMinutes()
                    : Integer.parseInt(configured);
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException("Pixiv defer minutes are invalid", failure);
        }
        if (minutes < MIN_DEFER_MINUTES) {
            throw new IllegalArgumentException("Pixiv defer minutes are below the minimum");
        }
        try {
            return Math.addExact(request.requestedAt(), Math.multiplyExact(minutes, 60_000L));
        } catch (ArithmeticException failure) {
            throw new IllegalArgumentException("Pixiv defer time exceeds supported range", failure);
        }
    }

    private static Long parseNonNegativeLong(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            long parsed = Long.parseLong(value);
            return parsed < 0 ? null : parsed;
        } catch (NumberFormatException ignored) {
            return null;
        }
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
