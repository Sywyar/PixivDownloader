package top.sywyar.pixivdownload.plugin;

import org.springframework.boot.autoconfigure.web.servlet.WebMvcRegistrations;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * 经 Spring Boot 的 {@link WebMvcRegistrations} 扩展点，把核心壳的 {@link RequestMappingHandlerMapping} 替换为
 * {@link PluginAwareRequestMappingHandlerMapping}（它额外暴露 handler 判定 / 映射计算两个桥接方法，供
 * {@link PluginControllerRegistrar} 动态注册外置插件 controller）。
 *
 * <p>本扩展点是 Boot MVC 自动装配在<b>不</b>引入 {@code @EnableWebMvc} 时定制 {@code RequestMappingHandlerMapping}
 * 的官方途径：返回的实例直接成为名为 {@code requestMappingHandlerMapping} 的 Bean，对既有 controller 检测 / 分发
 * 行为零改动（子类只增加无副作用的桥接方法）。
 */
@Component
public class PluginMvcRegistrations implements WebMvcRegistrations {

    @Override
    public RequestMappingHandlerMapping getRequestMappingHandlerMapping() {
        return new PluginAwareRequestMappingHandlerMapping();
    }
}
