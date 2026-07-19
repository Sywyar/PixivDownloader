package top.sywyar.pixivdownload.setup.guest;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/** 注册作品可见性作用域参数解析器；动态注册的外置插件 controller 共用父 HandlerAdapter。 */
@Configuration(proxyBeanMethods = false)
@RequiredArgsConstructor
public class GuestWorkVisibilityWebConfiguration implements WebMvcConfigurer {

    private final GuestWorkVisibilityScopeArgumentResolver argumentResolver;

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(argumentResolver);
    }
}
