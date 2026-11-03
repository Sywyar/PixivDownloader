package top.sywyar.pixivdownload.plugin.web.mapping;

import org.springframework.lang.Nullable;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.lang.reflect.Method;
import top.sywyar.pixivdownload.plugin.lifecycle.ExternalPluginContextManager;
import top.sywyar.pixivdownload.plugin.web.registration.PluginControllerRegistrar;

/**
 * 核心壳的 {@link RequestMappingHandlerMapping}：在标准行为之外，把两个 {@code protected} 钩子方法以 public 桥接
 * 暴露出来，供 {@link PluginControllerRegistrar} 在外置插件子 {@code ApplicationContext} 的 controller 上复用——
 * 计算其 {@link RequestMappingInfo}、判定其是否为 handler，再把它动态注册进本（父 context 的）请求分发表。
 *
 * <h2>为什么要子类化</h2>
 * 外置插件的 controller Bean 住在每插件子 context（见 {@code ExternalPluginContextManager}），<b>不</b>在核心应用
 * （父）context 的根扫描里，故父 context 的标准 {@code RequestMappingHandlerMapping} 启动期不会自动检测到它们。
 * 要把它们接入同一张分发表，必须用与核心 controller <b>完全一致</b>的口径计算 {@code RequestMappingInfo}（路径匹配、
 * 内容协商、方法 / 参数 / 头条件等都取自本 mapping 的 {@code BuilderConfiguration}）。复用 {@link #getMappingForMethod}
 * 是唯一不重复实现这套语义的方式；它 {@code protected}，故经本子类的 public 桥接暴露。{@link #isHandler} 同理用于
 * 判定子 context 中哪些 Bean 是 controller。
 *
 * <p>同时保留可信注册 owner 和按方法索引的原始映射，供接口目录消费；匹配与分发仍由 Spring 完成。
 * 它经 Spring Boot 的 {@code WebMvcRegistrations}（{@link PluginMvcRegistrations}）替换默认实例，不引入
 * {@code @EnableWebMvc}、不改动任何 MVC 自动装配。动态注册 / 注销经基类已有的 public
 * {@link #registerMapping} / {@link #unregisterMapping}。
 */
public class PluginAwareRequestMappingHandlerMapping extends RequestMappingHandlerMapping {
    public static final String MATCH_ATTRIBUTE = PluginAwareRequestMappingHandlerMapping.class.getName() + ".match";
    private static final String CANDIDATES_ATTRIBUTE = MATCH_ATTRIBUTE + ".candidates";
    private final java.util.Map<RequestMappingInfo, String> owners = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Map<RequestMappingInfo, Method> methods = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Map<Method, java.util.List<RequestMappingInfo>> byMethod = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile long revision;
    public long revision() { return revision; }

    @Override protected synchronized void registerHandlerMethod(Object handler, Method method, RequestMappingInfo mapping) {
        super.registerHandlerMethod(handler, method, mapping); remember(mapping, method);
    }

    @Override public synchronized void registerMapping(RequestMappingInfo mapping, Object handler, Method method) {
        super.registerMapping(mapping, handler, method); remember(mapping, method);
    }

    private void remember(RequestMappingInfo mapping, Method method) {
        methods.put(mapping, method);
        byMethod.compute(method, (key, values) -> {
            var copy = new java.util.ArrayList<>(values == null ? java.util.List.of() : values);
            if (!copy.contains(mapping)) copy.add(mapping);
            return java.util.List.copyOf(copy);
        });
        revision++;
    }

    public java.util.List<RequestMappingInfo> mappingsFor(Method method) { return byMethod.getOrDefault(method, java.util.List.of()); }

    /** 身份来自宿主的插件注册流程，不能由 HTTP 或 controller 自报。 */
    public synchronized void registerPluginMapping(String owner, RequestMappingInfo mapping, Object handler, Method method) {
        if (owners.putIfAbsent(mapping, owner) != null) throw new IllegalStateException("PLUGIN_HANDLER_ALREADY_REGISTERED");
        try { registerMapping(mapping, handler, method); }
        catch (RuntimeException | Error failure) { owners.remove(mapping, owner); throw failure; }
    }

    public String ownerOf(RequestMappingInfo mapping) { return owners.getOrDefault(mapping, "core"); }

    @Override protected org.springframework.web.method.HandlerMethod getHandlerInternal(jakarta.servlet.http.HttpServletRequest request) throws Exception {
        request.removeAttribute(MATCH_ATTRIBUTE);
        request.setAttribute(CANDIDATES_ATTRIBUTE, new java.util.IdentityHashMap<RequestMappingInfo, RequestMappingInfo>());
        try { return super.getHandlerInternal(request); }
        finally { request.removeAttribute(CANDIDATES_ATTRIBUTE); }
    }

    @Override @SuppressWarnings("unchecked")
    protected RequestMappingInfo getMatchingMapping(RequestMappingInfo info, jakarta.servlet.http.HttpServletRequest request) {
        var matched = super.getMatchingMapping(info, request);
        var candidates = (java.util.Map<RequestMappingInfo, RequestMappingInfo>) request.getAttribute(CANDIDATES_ATTRIBUTE);
        if (matched != null && candidates != null) candidates.put(matched, info);
        return matched;
    }

    @Override @SuppressWarnings("unchecked")
    protected void handleMatch(RequestMappingInfo info, String lookupPath, jakarta.servlet.http.HttpServletRequest request) {
        super.handleMatch(info, lookupPath, request);
        // Spring 已完成条件缩减与优先级选择；取回该精确候选的完整声明，不另行选路。
        request.setAttribute(MATCH_ATTRIBUTE, ((java.util.Map<RequestMappingInfo, RequestMappingInfo>) request.getAttribute(CANDIDATES_ATTRIBUTE)).get(info));
    }

    @Override public synchronized void unregisterMapping(RequestMappingInfo mapping) {
        super.unregisterMapping(mapping);
        owners.remove(mapping);
        Method method = methods.remove(mapping);
        if (method != null) byMethod.computeIfPresent(method, (key, values) -> {
            var remaining = values.stream().filter(value -> !value.equals(mapping)).toList();
            return remaining.isEmpty() ? null : remaining;
        });
        revision++;
    }

    /**
     * 是否把该 Bean 类型视为 handler（即 controller）。直接转发基类的 {@code protected} 判定
     * （{@code @Controller} / {@code @RequestMapping} 注解），口径与父 context 根扫描完全一致。
     */
    public boolean isPluginHandlerType(Class<?> beanType) {
        return super.isHandler(beanType);
    }

    /**
     * 用本 mapping 的配置计算某 handler 方法的 {@link RequestMappingInfo}（无 {@code @RequestMapping} 时返回 null）。
     * 直接转发基类的 {@code protected} 实现，使外置插件 controller 的映射与核心 controller 同口径构建。
     */
    @Nullable
    public RequestMappingInfo mappingForHandlerMethod(Method method, Class<?> handlerType) {
        return super.getMappingForMethod(method, handlerType);
    }
}
