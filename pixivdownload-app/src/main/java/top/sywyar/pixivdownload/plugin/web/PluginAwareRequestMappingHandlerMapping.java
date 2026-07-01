package top.sywyar.pixivdownload.plugin.web;

import org.springframework.lang.Nullable;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.lang.reflect.Method;
import top.sywyar.pixivdownload.plugin.lifecycle.ExternalPluginContextManager;

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
 * <p>本子类<b>只</b>增加这两个无副作用的桥接方法，对全部既有（父 context 根扫描到的）核心 controller 行为零改动；
 * 它经 Spring Boot 的 {@code WebMvcRegistrations}（{@link PluginMvcRegistrations}）替换默认实例，不引入
 * {@code @EnableWebMvc}、不改动任何 MVC 自动装配。动态注册 / 注销经基类已有的 public
 * {@link #registerMapping} / {@link #unregisterMapping}。
 */
public class PluginAwareRequestMappingHandlerMapping extends RequestMappingHandlerMapping {

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
