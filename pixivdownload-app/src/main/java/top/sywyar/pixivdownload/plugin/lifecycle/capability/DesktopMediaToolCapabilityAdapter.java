package top.sywyar.pixivdownload.plugin.lifecycle.capability;

import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.gui.media.DesktopMediaToolRegistry;
import top.sywyar.pixivdownload.plugin.api.gui.media.DesktopMediaTool;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.runtime.ExternalCapabilityInvocationRegistry;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.runtime.ExternalCapabilityOwner;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.runtime.ExternalCapabilityPreparation;

/** 原生媒体工具沿用外置 capability 的准入、撤回和在途调用 drain。 */
@Component
public final class DesktopMediaToolCapabilityAdapter implements ExternalRuntimeCapabilityAdapter {
    private record Prepared(ExternalCapabilityOwner owner, DesktopMediaTool.Description description,
                            DesktopMediaTool.Source source) implements PreparedContribution {}
    private final DesktopMediaToolRegistry registry;
    private final ExternalCapabilityInvocationRegistry invocations;

    public DesktopMediaToolCapabilityAdapter(DesktopMediaToolRegistry registry, ExternalCapabilityInvocationRegistry invocations) {
        this.registry = registry;
        this.invocations = invocations;
    }

    @Override public String capabilityName() { return DesktopMediaTool.Source.class.getName(); }

    @Override public PreparedContribution prepare(ExternalCapabilityPreparation preparation, ConfigurableApplicationContext context) {
        var beans = context.getBeansOfType(DesktopMediaTool.Source.class);
        if (beans.size() > 1) throw new IllegalArgumentException("multiple media tool sources");
        if (beans.isEmpty()) return new Prepared(preparation.owner(), null, null);
        var source = beans.values().iterator().next();
        var proxy = source instanceof DesktopMediaTool.CompletionSource completionSource
                ? prepareCompletionSource(preparation, completionSource)
                : invocations.prepareProxy(preparation, DesktopMediaTool.Source.class, source);
        var description = invocations.captureMetadata(preparation, DesktopMediaTool.Source.class,
                "description", source::description);
        return new Prepared(preparation.owner(), description, proxy);
    }

    private DesktopMediaTool.CompletionSource prepareCompletionSource(
            ExternalCapabilityPreparation preparation, DesktopMediaTool.CompletionSource target) {
        // 同步准入栈只借用观察者；完成后清除它，返回句柄只持有可撤回代理和宿主上下文。
        var observer = new ThreadLocal<CompletionDelivery>();
        var bridge = new DesktopMediaTool.Source() {
            public DesktopMediaTool.Description description() { return target.description(); }
            public DesktopMediaTool.Result<DesktopMediaTool.Preview> preview(DesktopMediaTool.Request request) { return target.preview(request); }
            public DesktopMediaTool.Status status() { return target.status(); }
            public void cancel() { target.cancel(); }
            public DesktopMediaTool.Result<DesktopMediaTool.Report> capabilities() { return target.capabilities(); }
            public DesktopMediaTool.Result<DesktopMediaTool.Status> start(String token) {
                return target.start(token, java.util.Objects.requireNonNull(observer.get())::complete);
            }
        };
        var proxy = invocations.prepareProxy(preparation, DesktopMediaTool.Source.class, bridge);
        var owner = preparation.owner();
        return new DesktopMediaTool.CompletionSource() {
            public DesktopMediaTool.Description description() { return proxy.description(); }
            public DesktopMediaTool.Result<DesktopMediaTool.Preview> preview(DesktopMediaTool.Request request) { return proxy.preview(request); }
            public DesktopMediaTool.Status status() { return proxy.status(); }
            public void cancel() { proxy.cancel(); }
            public DesktopMediaTool.Result<DesktopMediaTool.Report> capabilities() { return proxy.capabilities(); }
            public DesktopMediaTool.Result<DesktopMediaTool.Status> start(String token, java.util.function.Consumer<DesktopMediaTool.Status> completion) {
                var delivery = new CompletionDelivery(owner, java.util.Objects.requireNonNull(completion));
                var previous = observer.get();
                observer.set(delivery);
                try {
                    var result = proxy.start(token);
                    delivery.admitted(result.error() == null);
                    return result;
                } finally {
                    delivery.admitted(false);
                    if (previous == null) observer.remove();
                    else observer.set(previous);
                }
            }
        };
    }

    /** 同步终态必须等完整准入调用成功返回，才能进入宿主持久化观察者。 */
    private static final class CompletionDelivery {
        private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(CompletionDelivery.class);
        private final ExternalCapabilityOwner owner;
        private java.util.function.Consumer<DesktopMediaTool.Status> observer;
        private DesktopMediaTool.Status pending;
        private boolean decided;

        private CompletionDelivery(ExternalCapabilityOwner owner, java.util.function.Consumer<DesktopMediaTool.Status> observer) {
            this.owner = owner;
            this.observer = observer;
        }

        void complete(DesktopMediaTool.Status status) {
            java.util.function.Consumer<DesktopMediaTool.Status> consumer;
            synchronized (this) {
                if (observer == null || pending != null) return;
                if (status == null || !("completed".equals(status.state()) || "cancelled".equals(status.state())
                        || "failed".equals(status.state()) || "unavailable".equals(status.state()))
                        || status.total() < 0 || status.completed() < 0 || status.completed() > status.total()
                        || status.failed() < 0 || status.failed() > status.completed()) {
                    LOG.warn("media-completion-invalid owner={}", owner);
                    return;
                }
                if (!decided) {
                    pending = status;
                    return;
                }
                consumer = observer;
                observer = null;
            }
            deliver(consumer, status);
        }

        void admitted(boolean accepted) {
            java.util.function.Consumer<DesktopMediaTool.Status> consumer;
            DesktopMediaTool.Status status;
            synchronized (this) {
                if (decided) return;
                decided = true;
                status = pending;
                pending = null;
                consumer = observer;
                if (!accepted || status != null) observer = null;
            }
            if (accepted && status != null) deliver(consumer, status);
        }

        private void deliver(java.util.function.Consumer<DesktopMediaTool.Status> consumer, DesktopMediaTool.Status status) {
            try { consumer.accept(status); }
            catch (VirtualMachineError | ThreadDeath fatal) { throw fatal; }
            catch (Throwable failure) {
                LOG.warn("media-completion-observer-failed owner={} failureType={}", owner, failure.getClass().getName());
            }
        }
    }

    @Override public void publish(PreparedContribution contribution) {
        if (!(contribution instanceof Prepared prepared)) throw new IllegalArgumentException("invalid media tool contribution");
        if (prepared.source() != null) registry.register(prepared.owner(), prepared.description(), prepared.source());
    }

    @Override public void withdraw(ExternalCapabilityOwner owner) { registry.unregister(owner); }
}
