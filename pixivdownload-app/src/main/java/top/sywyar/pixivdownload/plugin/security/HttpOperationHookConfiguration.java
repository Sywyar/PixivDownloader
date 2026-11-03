package top.sywyar.pixivdownload.plugin.security;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.*;

/** 在宿主 MVC 分发表统一安装，覆盖以后动态登记的外置控制器。 */
@Configuration(proxyBeanMethods = false)
public class HttpOperationHookConfiguration implements WebMvcConfigurer {
    private final ObjectProvider<HttpOperationHooks> hooks;
    public HttpOperationHookConfiguration(ObjectProvider<HttpOperationHooks> hooks) { this.hooks = hooks; }
    @org.springframework.context.annotation.Bean
    org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor operationHookExecutor() {
        var executor = new org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor();
        executor.setCorePoolSize(8); executor.setMaxPoolSize(8); executor.setQueueCapacity(64);
        executor.setThreadNamePrefix("operation-hook-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        return executor;
    }
    @Override public void addInterceptors(InterceptorRegistry registry) { registry.addInterceptor(hooks.getObject()); }
    @org.springframework.context.annotation.Bean
    org.springframework.boot.web.servlet.ServletListenerRegistrationBean<HttpOperationHooks> hookCompletionListener() {
        return new org.springframework.boot.web.servlet.ServletListenerRegistrationBean<>(hooks.getObject());
    }
}
