package top.sywyar.pixivdownload.core.download;

import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.plugin.api.download.lifecycle.*;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.runtime.ExternalCapabilityOwner;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** 保存宿主代理；回调在锁外执行，生命周期租约由 capability runtime 管理。 */
@Component
public final class DownloadLifecycleRegistry implements DownloadLifecycle {
    public record Contribution(List<DownloadObserver> observers, List<DownloadAdmissionPolicy> policies) {
        public Contribution {
            observers = List.copyOf(observers);
            policies = List.copyOf(policies);
        }
    }

    private final Map<ExternalCapabilityOwner, Contribution> owners = new LinkedHashMap<>();
    private volatile Map<ExternalCapabilityOwner, Contribution> snapshot = Map.of();
    private final AtomicLong observerFailures = new AtomicLong();
    private volatile ExternalCapabilityOwner lastObserverFailure;

    public synchronized void register(ExternalCapabilityOwner owner, Contribution contribution) {
        owners.put(owner, contribution);
        snapshot = Map.copyOf(owners);
    }

    public synchronized void withdraw(ExternalCapabilityOwner owner) {
        owners.remove(owner);
        snapshot = Map.copyOf(owners);
    }

    /** 累计失败诊断不保存异常或插件引用。 */
    public long observerFailureCount() {
        return observerFailures.get();
    }

    public java.util.Optional<ExternalCapabilityOwner> lastObserverFailure() {
        return java.util.Optional.ofNullable(lastObserverFailure);
    }

    @Override
    public void checkAdmission(DownloadAttempt attempt) {
        for (Contribution contribution : snapshot.values()) {
            for (DownloadAdmissionPolicy policy : contribution.policies()) {
                try {
                    if (policy.evaluate(attempt) != DownloadAdmissionPolicy.Decision.ALLOW) {
                        throw new DownloadAdmissionRejectedException();
                    }
                } catch (VirtualMachineError | ThreadDeath fatal) {
                    throw fatal;
                } catch (Throwable rejected) {
                    throw new DownloadAdmissionRejectedException();
                }
            }
        }
    }

    @Override
    public void publish(DownloadEvent event) {
        for (var publication : snapshot.entrySet()) {
            for (DownloadObserver observer : publication.getValue().observers()) {
                try {
                    observer.onDownloadEvent(event);
                } catch (VirtualMachineError | ThreadDeath fatal) {
                    throw fatal;
                } catch (Throwable failure) {
                    lastObserverFailure = publication.getKey();
                    observerFailures.incrementAndGet();
                }
            }
        }
    }
}
