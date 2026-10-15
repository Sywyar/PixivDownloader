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
        return new Prepared(preparation.owner(), source.description(), proxy);
    }

    private DesktopMediaTool.CompletionSource prepareCompletionSource(
            ExternalCapabilityPreparation preparation, DesktopMediaTool.CompletionSource target) {
        // 同步准入栈只借用观察者；完成后清除它，返回句柄只持有可撤回代理和宿主上下文。
        var observer = new ThreadLocal<java.util.function.Consumer<DesktopMediaTool.Status>>();
        var bridge = new DesktopMediaTool.Source() {
            public DesktopMediaTool.Description description() { return target.description(); }
            public DesktopMediaTool.Result<DesktopMediaTool.Preview> preview(DesktopMediaTool.Request request) { return target.preview(request); }
            public DesktopMediaTool.Status status() { return target.status(); }
            public void cancel() { target.cancel(); }
            public DesktopMediaTool.Result<DesktopMediaTool.Report> capabilities() { return target.capabilities(); }
            public DesktopMediaTool.Result<DesktopMediaTool.Status> start(String token) {
                var pending = new java.util.concurrent.atomic.AtomicReference<>(java.util.Objects.requireNonNull(observer.get()));
                boolean accepted = false;
                try {
                    var result = target.start(token, status -> {
                        var consumer = pending.getAndSet(null);
                        if (consumer != null) consumer.accept(new DesktopMediaTool.Status(status.state(), status.total(),
                                status.completed(), status.failed(), status.failures()));
                    });
                    accepted = result.error() == null;
                    return result;
                } finally {
                    if (!accepted) pending.set(null);
                }
            }
        };
        var proxy = invocations.prepareProxy(preparation, DesktopMediaTool.Source.class, bridge);
        return new DesktopMediaTool.CompletionSource() {
            public DesktopMediaTool.Description description() { return proxy.description(); }
            public DesktopMediaTool.Result<DesktopMediaTool.Preview> preview(DesktopMediaTool.Request request) { return proxy.preview(request); }
            public DesktopMediaTool.Status status() { return proxy.status(); }
            public void cancel() { proxy.cancel(); }
            public DesktopMediaTool.Result<DesktopMediaTool.Report> capabilities() { return proxy.capabilities(); }
            public DesktopMediaTool.Result<DesktopMediaTool.Status> start(String token, java.util.function.Consumer<DesktopMediaTool.Status> completion) {
                observer.set(java.util.Objects.requireNonNull(completion));
                try { return proxy.start(token); }
                finally { observer.remove(); }
            }
        };
    }

    @Override public void publish(PreparedContribution contribution) {
        if (!(contribution instanceof Prepared prepared)) throw new IllegalArgumentException("invalid media tool contribution");
        if (prepared.source() != null) registry.register(prepared.owner(), prepared.description(), prepared.source());
    }

    @Override public void withdraw(ExternalCapabilityOwner owner) { registry.unregister(owner); }
}
