package top.sywyar.pixivdownload.plugin.lifecycle.capability;

import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.core.download.DownloadLifecycleRegistry;
import top.sywyar.pixivdownload.plugin.api.download.lifecycle.DownloadAdmissionPolicy;
import top.sywyar.pixivdownload.plugin.api.download.lifecycle.DownloadObserver;
import top.sywyar.pixivdownload.plugin.api.download.lifecycle.DownloadOptionsHook;
import top.sywyar.pixivdownload.plugin.api.download.submission.DownloadSubmissionHandler;
import java.util.LinkedHashMap;
import java.io.IOException;
import java.io.UncheckedIOException;
import org.springframework.aop.support.AopUtils;
import io.swagger.v3.core.util.Json31;
import io.swagger.v3.oas.models.media.Schema;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.runtime.ExternalCapabilityInvocationRegistry;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.runtime.ExternalCapabilityOwner;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.runtime.ExternalCapabilityPreparation;

/** 复用精确 publication 的准入、撤回和在途 drain。 */
@Component
public final class DownloadLifecycleCapabilityAdapter implements ExternalRuntimeCapabilityAdapter {
    private record Prepared(ExternalCapabilityOwner owner,
                            DownloadLifecycleRegistry.Contribution value) implements PreparedContribution {}

    private final DownloadLifecycleRegistry registry;
    private final ExternalCapabilityInvocationRegistry invocations;

    public DownloadLifecycleCapabilityAdapter(DownloadLifecycleRegistry registry,
                                              ExternalCapabilityInvocationRegistry invocations) {
        this.registry = registry;
        this.invocations = invocations;
    }

    @Override
    public String capabilityName() {
        return DownloadObserver.class.getName();
    }

    @Override
    public PreparedContribution prepare(ExternalCapabilityPreparation preparation,
                                        ConfigurableApplicationContext context) {
        var observers = context.getBeansOfType(DownloadObserver.class).values().stream()
                .map(bean -> invocations.prepareProxy(preparation, DownloadObserver.class, bean)).toList();
        var policies = context.getBeansOfType(DownloadAdmissionPolicy.class).values().stream()
                .map(bean -> invocations.prepareProxy(preparation, DownloadAdmissionPolicy.class, bean)).toList();
        var hooks = context.getBeansOfType(DownloadOptionsHook.class).entrySet().stream()
                .map(entry -> new DownloadLifecycleRegistry.Hook(entry.getValue().order(), entry.getKey(),
                        invocations.prepareProxy(preparation, DownloadOptionsHook.class, entry.getValue()))).toList();
        var handlers = new LinkedHashMap<String, DownloadSubmissionHandler>();
        var schemas = new LinkedHashMap<String, String>();
        for (var bean : context.getBeansOfType(DownloadSubmissionHandler.class).values()) {
            String type = bean.workType();
            if (type == null || !type.matches("[a-z][a-z0-9-]{0,63}")
                    || handlers.putIfAbsent(type, invocations.prepareProxy(
                            preparation, DownloadSubmissionHandler.class, bean)) != null)
                throw new IllegalArgumentException("invalid or duplicate download submission type");
            String resource = bean.inputSchemaResource();
            if (resource != null) {
                if (!resource.startsWith("/")) throw new IllegalArgumentException("expected absolute schema resource");
                try (var input = AopUtils.getTargetClass(bean).getResourceAsStream(resource)) {
                    if (input == null) throw new IllegalArgumentException("missing download submission schema: " + type);
                    var mapper = Json31.mapper();
                    var schema = mapper.readTree(input);
                    if (schema == null || !schema.isObject() || !schema.path("properties").isObject())
                        throw new IllegalArgumentException("invalid download submission schema: " + type);
                    // Reject an unreadable contract before publishing it to every API reference client.
                    mapper.treeToValue(schema, Schema.class);
                    schemas.put(type, schema.toString());
                } catch (IOException failure) {
                    throw new UncheckedIOException("cannot read download submission schema: " + type, failure);
                }
            }
        }
        var owner = preparation.owner();
        return new Prepared(owner, new DownloadLifecycleRegistry.Contribution(observers, policies, hooks, handlers,
                schemas, () -> invocations.acceptsInvocations(owner)));
    }

    @Override
    public void publish(PreparedContribution contribution) {
        if (!(contribution instanceof Prepared prepared)) {
            throw new IllegalArgumentException("invalid download lifecycle contribution");
        }
        registry.register(prepared.owner(), prepared.value());
    }

    @Override
    public void withdraw(ExternalCapabilityOwner owner) {
        registry.withdraw(owner);
    }
}
