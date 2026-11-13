package top.sywyar.pixivdownload.schedule;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.config.OutboundProxyOverride;
import top.sywyar.pixivdownload.core.schedule.ScheduledTask;
import top.sywyar.pixivdownload.core.schedule.ScheduledTaskStore;
import top.sywyar.pixivdownload.plugin.api.download.context.AcquisitionCredentials;
import top.sywyar.pixivdownload.plugin.api.schedule.capability.ScheduleCapabilityAccess;
import top.sywyar.pixivdownload.plugin.api.schedule.capability.SchedulePlanningLease;
import top.sywyar.pixivdownload.plugin.api.schedule.management.ScheduleManagementException;
import top.sywyar.pixivdownload.plugin.api.schedule.network.ScheduledNetworkRoute;
import top.sywyar.pixivdownload.schedule.execution.ScheduleNetworkRouteResolver;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.CancellationException;

/** 管理员保存前查询的短作用域；凭据只在服务端使用，不修改任务或凭据绑定。 */
@Component
@RequiredArgsConstructor
public final class ScheduleRequestScope {
    private final ScheduledTaskStore store;
    private final ScheduleCapabilityAccess capabilities;
    private final ScheduleNetworkRouteResolver routes;

    public record Request(@NotBlank String sourceType, @NotBlank String activationToken,
                          @Positive Long taskId, @PositiveOrZero Long expectedStateVersion,
                          boolean useTaskCredential, @Size(max = 512) String proxy) {}

    @FunctionalInterface
    public interface Query<T> { T read(String credential) throws IOException; }

    public <T> T read(Request request, String suppliedCredential, ScheduledNetworkRoute sourceDefaultRoute,
                      Query<T> query) throws IOException {
        if (request == null || (request.taskId() == null) != (request.expectedStateVersion() == null)
                || request.useTaskCredential() && (request.taskId() == null || suppliedCredential != null
                || AcquisitionCredentials.current() != null)) {
            throw ScheduleManagementException.badRequest("error.request.param.invalid", "Invalid schedule request context");
        }
        try (var source = capabilities.prepareSource(request.sourceType()).orElseThrow(ScheduleRequestScope::stale)) {
            if (!Objects.equals(source.activationToken(), request.activationToken()) || !capabilities.activate(source)) throw stale();
            ScheduledTask task = currentTask(request, source);
            ScheduledNetworkRoute route;
            try { route = routes.resolve(request.proxy(), sourceDefaultRoute); }
            catch (IllegalArgumentException invalid) {
                throw ScheduleManagementException.badRequest("schedule.error.proxy-format", "Invalid schedule proxy");
            }
            boolean previousActive = OutboundProxyOverride.isActive();
            var previous = OutboundProxyOverride.current();
            try {
                if (route.mode() == ScheduledNetworkRoute.Mode.DIRECT) OutboundProxyOverride.setDirect();
                else OutboundProxyOverride.set(route.proxyHost() + ":" + route.proxyPort());
                T result;
                if (request.useTaskCredential()) {
                    if (task.credentialPolicyId() == null || !source.descriptor().orElseThrow(ScheduleRequestScope::stale)
                            .credentialPolicyIds().contains(task.credentialPolicyId())) throw stale();
                    try (var policy = capabilities.prepareCredentialPolicy(task.credentialPolicyId()).orElseThrow(ScheduleRequestScope::stale)) {
                        if (!Objects.equals(policy.owner().featurePluginId(), task.credentialPolicyOwnerPluginId())
                                || !capabilities.activate(policy)) throw stale();
                        if (source.cancellation().isCancellationRequested() || policy.cancellation().isCancellationRequested()) throw stale();
                        var values = store.findCredentialCandidates(task.id(), task.credentialPolicyOwnerPluginId(), task.credentialPolicyId());
                        if (values.isEmpty()) throw ScheduleManagementException.badRequest(
                                "schedule.error.credential-empty", "Schedule credential is unavailable");
                        var credentials = new AcquisitionCredentials(values.stream().map(value -> value.secret()).toList())
                                .withCancellation(() -> source.cancellation().isCancellationRequested()
                                        || policy.cancellation().isCancellationRequested());
                        credentials.checkCancelled();
                        result = credentials.call(() -> query.read(credentials.primary()));
                        credentials.checkCancelled();
                    }
                } else {
                    var selected = AcquisitionCredentials.current();
                    if (selected == null && suppliedCredential != null) selected = new AcquisitionCredentials(java.util.List.of(suppliedCredential));
                    if (selected == null) result = query.read(null);
                    else {
                        var credentials = selected.withCancellation(() -> source.cancellation().isCancellationRequested());
                        credentials.checkCancelled();
                        result = credentials.call(() -> query.read(credentials.primary()));
                        credentials.checkCancelled();
                    }
                }
                currentTask(request, source);
                return result;
            } finally {
                if (!previousActive) OutboundProxyOverride.clear();
                else if (previous == null) OutboundProxyOverride.setDirect();
                else OutboundProxyOverride.set(previous.hostName() + ":" + previous.port());
            }
        } catch (CancellationException cancelled) { throw stale(); }
    }

    private ScheduledTask currentTask(Request request, SchedulePlanningLease source) {
        if (source.cancellation().isCancellationRequested()) throw stale();
        if (request.taskId() == null) return null;
        ScheduledTask task = store.findById(request.taskId());
        if (task == null || task.stateVersion() != request.expectedStateVersion()
                || !Objects.equals(task.sourceType(), source.sourceType())
                || !Objects.equals(task.sourceOwnerPluginId(), source.owner().featurePluginId())) throw stale();
        return task;
    }

    private static ScheduleManagementException stale() {
        return new ScheduleManagementException(ScheduleManagementException.Code.CONFLICT,
                "schedule.error.source-publication-changed", "Schedule source or task changed; refresh and retry");
    }
}
