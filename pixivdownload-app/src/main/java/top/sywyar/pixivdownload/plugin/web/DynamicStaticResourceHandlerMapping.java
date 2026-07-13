package top.sywyar.pixivdownload.plugin.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.BeanInitializationException;
import org.springframework.core.Ordered;
import org.springframework.core.io.Resource;
import org.springframework.web.accept.ContentNegotiationManager;
import org.springframework.web.servlet.handler.AbstractHandlerMapping;
import org.springframework.web.servlet.handler.SimpleUrlHandlerMapping;
import org.springframework.web.servlet.resource.ResourceHttpRequestHandler;
import top.sywyar.pixivdownload.plugin.api.web.StaticResourceContribution;

import java.lang.ref.WeakReference;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import top.sywyar.pixivdownload.plugin.registry.StaticResourceRegistry;

/**
 * 查询期静态资源映射。每次请求都与 {@link StaticResourceRegistry#resources()} 的当前不可变快照对账；
 * 快照引用变化时，为新快照构建一份完整的 Spring {@link ResourceHttpRequestHandler} 映射后整体替换。
 * <p>
 * 这使运行期插件注册 / 注销无需再次刷新 ApplicationContext，同时避免保留已注销插件的
 * ClassLoader。缓存只弱引用 registry 快照作为身份标记；已建 handler 只持有 owner-only {@link Resource}，因此注销后即使
 * 没有下一次静态请求触发重建，旧快照的 owner / plugin / classloader 也可回收。资源解析、MIME、条件请求、Range 和目录穿越防护仍由
 * Spring 原生处理器负责。
 */
final class DynamicStaticResourceHandlerMapping extends AbstractHandlerMapping {

    private static final int ORDER = Ordered.LOWEST_PRECEDENCE - 2;

    private final StaticResourceRegistry staticResourceRegistry;
    private final ContentNegotiationManager contentNegotiationManager;
    private final Object refreshLock = new Object();

    private volatile MappingSnapshot mappingSnapshot;

    DynamicStaticResourceHandlerMapping(StaticResourceRegistry staticResourceRegistry,
                                        ContentNegotiationManager contentNegotiationManager) {
        this.staticResourceRegistry = staticResourceRegistry;
        this.contentNegotiationManager = contentNegotiationManager;
        setOrder(ORDER);
    }

    @Override
    protected Object getHandlerInternal(HttpServletRequest request) throws Exception {
        return currentMapping().getHandler(request);
    }

    private SimpleUrlHandlerMapping currentMapping() {
        List<StaticResourceRegistry.RegisteredStaticResource> resources = staticResourceRegistry.resources();
        MappingSnapshot current = mappingSnapshot;
        if (current != null && current.matches(resources)) {
            return current.mapping();
        }
        synchronized (refreshLock) {
            // 锁外读到的 A 可能在等待 refreshLock 时已被新快照 B 替换；锁内必须重读，避免迟到的 A 覆盖已建 B。
            resources = staticResourceRegistry.resources();
            current = mappingSnapshot;
            if (current == null || !current.matches(resources)) {
                current = new MappingSnapshot(new WeakReference<>(resources), buildMapping(resources));
                mappingSnapshot = current;
            }
            return current.mapping();
        }
    }

    private SimpleUrlHandlerMapping buildMapping(
            List<StaticResourceRegistry.RegisteredStaticResource> resources) {
        Map<String, Object> handlers = new LinkedHashMap<>();
        for (StaticResourceRegistry.RegisteredStaticResource registered : resources) {
            StaticResourceContribution contribution = registered.contribution();
            String pattern = contribution.exactFile()
                    ? contribution.publicPathPrefix()
                    : contribution.publicPathPrefix() + "**";
            handlers.put(pattern, createHandler(registered));
        }

        SimpleUrlHandlerMapping mapping = new SimpleUrlHandlerMapping(handlers, getOrder());
        mapping.setPatternParser(getPatternParser());
        mapping.setServletContext(getServletContext());
        mapping.setApplicationContext(obtainApplicationContext());
        return mapping;
    }

    private ResourceHttpRequestHandler createHandler(
            StaticResourceRegistry.RegisteredStaticResource registered) {
        Resource location = registered.location();
        ResourceHttpRequestHandler handler = new ResourceHttpRequestHandler();
        handler.setLocations(List.of(location));
        handler.setContentNegotiationManager(contentNegotiationManager);
        handler.setServletContext(getServletContext());
        handler.setApplicationContext(obtainApplicationContext());
        try {
            handler.afterPropertiesSet();
        } catch (Exception e) {
            throw new BeanInitializationException(
                    "Failed to initialize static resources for plugin: " + registered.pluginId(), e);
        }
        return handler;
    }

    private record MappingSnapshot(
            WeakReference<List<StaticResourceRegistry.RegisteredStaticResource>> resources,
            SimpleUrlHandlerMapping mapping) {
        private boolean matches(List<StaticResourceRegistry.RegisteredStaticResource> current) {
            return resources.get() == current;
        }
    }
}
