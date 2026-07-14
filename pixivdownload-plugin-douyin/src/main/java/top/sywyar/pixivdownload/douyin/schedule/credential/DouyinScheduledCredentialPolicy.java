package top.sywyar.pixivdownload.douyin.schedule.credential;

import top.sywyar.pixivdownload.douyin.client.DouyinClient;
import top.sywyar.pixivdownload.douyin.client.DouyinClientErrorCode;
import top.sywyar.pixivdownload.douyin.client.DouyinClientException;
import top.sywyar.pixivdownload.douyin.client.DouyinCookieValidator;
import top.sywyar.pixivdownload.douyin.model.DouyinAccount;
import top.sywyar.pixivdownload.douyin.schedule.failure.DouyinScheduledFailureMapper;
import top.sywyar.pixivdownload.douyin.schedule.network.DouyinScheduledRouteScope;
import top.sywyar.pixivdownload.douyin.schedule.source.DouyinScheduledSourceDescriptors;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginManagedBean;
import top.sywyar.pixivdownload.plugin.api.schedule.credential.ScheduledCredentialContext;
import top.sywyar.pixivdownload.plugin.api.schedule.credential.ScheduledCredentialPolicy;
import top.sywyar.pixivdownload.plugin.api.schedule.credential.ScheduledCredentialProbeResult;
import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledExecutionException;
import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledFailure;
import top.sywyar.pixivdownload.plugin.api.schedule.security.ScheduledCredentialText;

import java.util.Arrays;
import java.util.Objects;

/** 抖音 Cookie 的格式校验、主动账号探活与非敏感账号键策略。 */
@PluginManagedBean
public final class DouyinScheduledCredentialPolicy implements ScheduledCredentialPolicy {

    private final DouyinClient client;

    public DouyinScheduledCredentialPolicy(DouyinClient client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    @Override
    public String policyId() {
        return DouyinScheduledSourceDescriptors.CREDENTIAL_POLICY_ID;
    }

    @Override
    public ScheduledCredentialProbeResult probe(ScheduledCredentialContext context)
            throws ScheduledExecutionException {
        if (context == null || context.route() == null || !context.route().isResolved()) {
            throw new ScheduledExecutionException(
                    ScheduledFailure.Category.INVALID_DEFINITION,
                    "douyin.schedule.route-unresolved");
        }
        if (context.credential() == null || !context.credential().isPresent()) {
            return ScheduledCredentialProbeResult.invalid(
                    "douyin.credential.required");
        }
        char[] secret = context.credential().copySecret();
        try {
            String cookie = new String(secret);
            DouyinCookieValidator.Validation validation = DouyinCookieValidator.validate(cookie);
            if (!validation.usable()) {
                return ScheduledCredentialProbeResult.invalid(
                        validation.empty()
                                ? "douyin.credential.required"
                                : "douyin.credential.fields-missing");
            }
            context.cancellation().throwIfCancellationRequested();
            DouyinAccount account = DouyinScheduledRouteScope.call(
                    context.route(), () -> client.resolveAccount(cookie));
            context.cancellation().throwIfCancellationRequested();
            String accountKey = safeAccountKey(account);
            if (accountKey == null) {
                return ScheduledCredentialProbeResult.invalid(
                        "douyin.credential.account-missing");
            }
            return ScheduledCredentialProbeResult.valid(accountKey);
        } catch (DouyinClientException failure) {
            if (isCredentialInvalid(failure.code())) {
                return ScheduledCredentialProbeResult.invalid(
                        "douyin.credential.invalid");
            }
            if (failure.code() == DouyinClientErrorCode.HTTP_RATE_LIMITED
                    || failure.code() == DouyinClientErrorCode.RATE_LIMITED) {
                return ScheduledCredentialProbeResult.retryLater(
                        "douyin.credential.probe-rate-limited",
                        DouyinScheduledFailureMapper.RATE_LIMIT_RETRY_MILLIS);
            }
            throw DouyinScheduledFailureMapper.fromClient(failure);
        } catch (ScheduledExecutionException failure) {
            throw failure;
        } catch (Exception failure) {
            throw DouyinScheduledFailureMapper.networkFailure();
        } finally {
            Arrays.fill(secret, '\0');
        }
    }

    private static boolean isCredentialInvalid(DouyinClientErrorCode code) {
        return code == DouyinClientErrorCode.COOKIE_REQUIRED
                || code == DouyinClientErrorCode.COOKIE_MISSING_FIELDS
                || code == DouyinClientErrorCode.COOKIE_EXPIRED;
    }

    private static String safeAccountKey(DouyinAccount account) {
        if (account == null || account.accountKey() == null) {
            return null;
        }
        String value = account.accountKey().trim();
        if (value.isEmpty() || value.length() > 256
                || value.chars().anyMatch(Character::isISOControl)
                || ScheduledCredentialText.containsCredentialMaterial(value)) {
            return null;
        }
        return value;
    }
}
