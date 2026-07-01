package top.sywyar.pixivdownload.plugin.web;

import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationContext;
import org.springframework.core.MethodIntrospector;
import org.springframework.stereotype.Component;
import org.springframework.util.ClassUtils;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import top.sywyar.pixivdownload.plugin.api.web.HttpMethod;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import top.sywyar.pixivdownload.plugin.lifecycle.ExternalPluginContextManager;
import top.sywyar.pixivdownload.plugin.registry.RouteAccessRegistry;

/**
 * 把外置插件子 {@code ApplicationContext} 中的 controller 动态注册进核心壳（父 context）的请求分发表
 * （{@link PluginAwareRequestMappingHandlerMapping}），并支持按 pluginId 注销。供
 * {@code ExternalPluginContextManager} 在每个外置插件子 context 建立 / 关闭时调用。
 *
 * <h2>注册前的路由声明校验（拒绝裸奔路由）</h2>
 * 每个 controller 方法的映射（path + HTTP 方法）在注册前都必须已被某条 {@code WebRouteContribution} 声明覆盖
 *（经 {@link RouteAccessRegistry#isDeclared(String, HttpMethod)} 判定，与 {@code AuthFilter} 的「未声明即 404」
 * 同口径、也与全 URL 声明守卫 {@code RouteDeclarationCoverageTest} 同口径）。任一映射缺声明即<b>拒绝整插件注册</b>
 *（{@link PluginControllerRegistrationException}）并列出违例项——否则注册出来的 handler 会被 {@code AuthFilter}
 * 统一 404，是带病的不可达路由。注册按插件<b>原子</b>：先收集 + 全量校验，全部通过才逐条注册，任一未声明则一条不注册；
 * 逐条注册过程同样原子——某条 {@link RequestMappingHandlerMapping#registerMapping registerMapping} 失败（典型：与父
 * 分发表已有 handler 冲突）时，本次已成功注册的映射全部回滚后再抛出（带 pluginId 与失败映射诊断），绝不给父分发表留下
 * 半注册的 handler（否则子 context 被关闭后，父分发表会残留指向已关闭子 context bean 的陈旧 handler）。
 *
 * <h2>可逆与生命周期</h2>
 * 按 pluginId 记录已注册的 {@link RequestMappingInfo}，{@link #unregisterControllers(String)} 据此逐条
 * {@code unregisterMapping}，使「注册 → 注销」后该插件的映射从分发表彻底消失（同 URL 不再命中 controller）。
 * 注册 / 注销在本对象的锁内串行，写入分发表复用其内部的注册表锁。本类<b>不</b>触碰 {@code AuthFilter} / 鉴权——
 * 它只增删请求分发表里的 handler 映射，访问控制仍由 {@code AuthFilter} 按 {@link RouteAccessRegistry} 独立执行。
 */
@Slf4j
@Component
public class PluginControllerRegistrar {

    /** Spring 为 scoped proxy 注册的目标 Bean 名前缀；同 {@code AbstractHandlerMethodMapping} 一样跳过，避免重复检测。 */
    private static final String SCOPED_TARGET_NAME_PREFIX = "scopedTarget.";

    private final PluginAwareRequestMappingHandlerMapping handlerMapping;
    private final RouteAccessRegistry routeAccessRegistry;

    private final Object lock = new Object();
    /** 按 pluginId 记录已注册的映射，供按插件注销。仅在 {@link #lock} 内变更。 */
    private final Map<String, List<RequestMappingInfo>> registeredByPlugin = new LinkedHashMap<>();

    /**
     * 注入核心壳的请求分发表（名为 {@code requestMappingHandlerMapping} 的 Bean）。声明类型用基类
     * {@link RequestMappingHandlerMapping}（该 Bean 由 Boot 工厂方法以基类返回类型登记，按子类型注入不稳定；且
     * actuator 另有 {@code controllerEndpointHandlerMapping} 同属该基类——故用 {@code @Qualifier} 锁定主 Bean），
     * 再校验其为 {@link PluginAwareRequestMappingHandlerMapping}（经 {@link PluginMvcRegistrations} 注入）后取其桥接能力。
     */
    public PluginControllerRegistrar(
            @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping handlerMapping,
            RouteAccessRegistry routeAccessRegistry) {
        if (!(handlerMapping instanceof PluginAwareRequestMappingHandlerMapping pluginAware)) {
            throw new IllegalStateException("core RequestMappingHandlerMapping is not plugin-aware: "
                    + handlerMapping.getClass().getName() + " — expected "
                    + PluginAwareRequestMappingHandlerMapping.class.getName() + " via PluginMvcRegistrations");
        }
        this.handlerMapping = pluginAware;
        this.routeAccessRegistry = routeAccessRegistry;
    }

    /**
     * 扫描子 context 中的全部 controller，校验其每个映射都已被路由声明覆盖后，逐条注册进父 context 的请求分发表。
     *
     * @param pluginId 外置插件包 id（注销键）
     * @param context  该插件的子 {@code ApplicationContext}
     * @return 实际注册的映射条数
     * @throws PluginControllerRegistrationException 该 pluginId 已注册过，或任一 controller 映射缺路由声明（整插件不注册）
     */
    public int registerControllers(String pluginId, ApplicationContext context) {
        List<HandlerMethodMapping> detected = detectHandlerMethods(context);
        List<String> undeclared = new ArrayList<>();
        for (HandlerMethodMapping mapping : detected) {
            undeclared.addAll(undeclaredEndpoints(mapping.info()));
        }
        synchronized (lock) {
            if (registeredByPlugin.containsKey(pluginId)) {
                throw new PluginControllerRegistrationException(
                        "controllers already registered for plugin: " + pluginId);
            }
            if (!undeclared.isEmpty()) {
                throw new PluginControllerRegistrationException(
                        "refusing to register controllers for plugin '" + pluginId
                                + "': the following mapping(s) have no matching WebRouteContribution and would be "
                                + "404'd by AuthFilter — declare them in the plugin's routes(): " + undeclared);
            }
            List<RequestMappingInfo> registered = new ArrayList<>();
            for (HandlerMethodMapping mapping : detected) {
                try {
                    handlerMapping.registerMapping(mapping.info(), mapping.handler(), mapping.method());
                } catch (RuntimeException e) {
                    // registerMapping 过程也按插件原子：本次已成功注册的映射全部回滚（逐条 unregister），再抛出带
                    // pluginId + 失败映射诊断的异常。否则前几条已注册、后续某条冲突失败时，registeredByPlugin 不会记录
                    // 本插件、unregisterControllers 成空操作，父分发表会留下指向即将关闭的子 context bean 的陈旧 handler。
                    rollbackRegistered(pluginId, registered);
                    throw new PluginControllerRegistrationException(
                            "failed to register controller mapping " + mapping.info() + " for plugin '" + pluginId
                                    + "' (rolled back " + registered.size() + " mapping(s) already registered in this "
                                    + "attempt; likely a path/method conflict with an existing handler): "
                                    + e.getMessage(), e);
                }
                registered.add(mapping.info());
            }
            if (!registered.isEmpty()) {
                registeredByPlugin.put(pluginId, List.copyOf(registered));
            }
            log.info("Registered {} controller mapping(s) for plugin '{}': {}", registered.size(), pluginId, registered);
            return registered.size();
        }
    }

    /**
     * 回滚本次注册尝试中已成功注册进父分发表的映射（逐条 {@code unregisterMapping}）：某条 {@code registerMapping}
     * 失败时把前面已注册的映射全部撤掉，保证逐条注册过程也按插件原子，不给父分发表留下半注册的 handler。已在
     * {@link #lock} 内调用；不复用 {@link #unregisterControllers}（那条按 pluginId 查表，本次尚未写入 registeredByPlugin）。
     */
    private void rollbackRegistered(String pluginId, List<RequestMappingInfo> registered) {
        for (RequestMappingInfo info : registered) {
            try {
                handlerMapping.unregisterMapping(info);
            } catch (RuntimeException e) {
                log.warn("Error rolling back controller mapping {} for plugin '{}' after a failed registration: {}",
                        info, pluginId, e.toString());
            }
        }
    }

    /**
     * 注销某插件先前注册的全部 controller 映射。统一卸载流程会对每个插件调用，故对未注册过的 pluginId 静默返回。
     */
    public void unregisterControllers(String pluginId) {
        List<RequestMappingInfo> infos;
        synchronized (lock) {
            infos = registeredByPlugin.remove(pluginId);
        }
        if (infos == null || infos.isEmpty()) {
            return;
        }
        for (RequestMappingInfo info : infos) {
            try {
                handlerMapping.unregisterMapping(info);
            } catch (RuntimeException e) {
                log.warn("Error unregistering controller mapping {} for plugin '{}': {}", info, pluginId, e.toString());
            }
        }
        log.info("Unregistered {} controller mapping(s) for plugin '{}'.", infos.size(), pluginId);
    }

    /** 当前注册了 controller 映射的插件 id（只读，供可观测 / 测试）。 */
    public Set<String> registeredPluginIds() {
        synchronized (lock) {
            return Set.copyOf(registeredByPlugin.keySet());
        }
    }

    // --- 内部：检测 + 校验 ---

    /** 一条待注册映射：{@link RequestMappingInfo} + 处理它的 handler 实例（来自子 context）+ 可调用方法。 */
    private record HandlerMethodMapping(RequestMappingInfo info, Object handler, Method method) {
    }

    /**
     * 扫描子 context 中的 controller Bean、计算其每个 handler 方法的映射，口径与 Spring 自身的
     * {@code AbstractHandlerMethodMapping#initHandlerMethods} 一致（{@link MethodIntrospector} 选方法、
     * {@link AopUtils#selectInvocableMethod} 取可调用方法）。
     */
    private List<HandlerMethodMapping> detectHandlerMethods(ApplicationContext context) {
        List<HandlerMethodMapping> result = new ArrayList<>();
        for (String beanName : context.getBeanNamesForType(Object.class)) {
            if (beanName.startsWith(SCOPED_TARGET_NAME_PREFIX)) {
                continue;
            }
            Class<?> beanType;
            try {
                beanType = context.getType(beanName);
            } catch (Throwable ex) {
                // 无法确定类型的 Bean（同 Spring 一样）跳过，不参与 controller 检测。
                continue;
            }
            if (beanType == null || !handlerMapping.isPluginHandlerType(beanType)) {
                continue;
            }
            Object handler = context.getBean(beanName);
            Class<?> userType = ClassUtils.getUserClass(handler);
            Map<Method, RequestMappingInfo> methods = MethodIntrospector.selectMethods(userType,
                    (MethodIntrospector.MetadataLookup<RequestMappingInfo>) method ->
                            handlerMapping.mappingForHandlerMethod(method, userType));
            methods.forEach((method, info) -> result.add(
                    new HandlerMethodMapping(info, handler, AopUtils.selectInvocableMethod(method, userType))));
        }
        return result;
    }

    /**
     * 返回该映射中未被任何路由声明覆盖的 (方法, 路径) 违例项（空 = 全部已声明）。判定口径与全 URL 声明守卫
     * {@code RouteDeclarationCoverageTest} 逐字一致：含 {@code {var}} 的模式按占位归一后判定；无方法限定的映射
     * 以 GET 代表「至少有声明覆盖」。
     */
    private List<String> undeclaredEndpoints(RequestMappingInfo info) {
        List<String> violations = new ArrayList<>();
        Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
        for (String pattern : info.getPatternValues()) {
            String concrete = toConcretePath(pattern);
            if (methods.isEmpty()) {
                if (!routeAccessRegistry.isDeclared(concrete, HttpMethod.GET)) {
                    violations.add("* " + pattern);
                }
            } else {
                for (RequestMethod requestMethod : methods) {
                    HttpMethod httpMethod = toHttpMethod(requestMethod);
                    if (httpMethod != null && !routeAccessRegistry.isDeclared(concrete, httpMethod)) {
                        violations.add(requestMethod + " " + pattern);
                    }
                }
            }
        }
        return violations;
    }

    /** 把含 {@code {placeholder}} 的映射模式转成一条具体路径，供 registry 的 startsWith / 精确匹配判定。 */
    private static String toConcretePath(String pattern) {
        return pattern.replaceAll("\\{[^/]+}", "x");
    }

    /** Spring {@link RequestMethod} → 本项目 {@link HttpMethod}；未建模的方法（如 TRACE）返回 null（跳过）。 */
    private static HttpMethod toHttpMethod(RequestMethod requestMethod) {
        try {
            return HttpMethod.valueOf(requestMethod.name());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
