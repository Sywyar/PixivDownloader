package aotfixture.host;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;
import org.springframework.context.annotation.ScopedProxyMode;
import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginManagedBean;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/** 与宿主根扫描隔离的行为夹具，不依赖实际业务 Bean 的数量和命名。 */
public final class Components {
    public static final class Lifecycle {
        public final AtomicInteger initialized = new AtomicInteger();
        public final AtomicInteger destroyed = new AtomicInteger();
    }

    @Component
    public static class Stable {
        public final String value;
        @Autowired Lifecycle lifecycle;
        public Stable(@Value("${fixture.value}") String value) { this.value = value; }
        @PostConstruct void start() { lifecycle.initialized.incrementAndGet(); }
        @PreDestroy void stop() { lifecycle.destroyed.incrementAndGet(); }
    }

    @Component
    @ConditionalOnProperty(name = "fixture.enabled", havingValue = "true")
    public static class ConditionalComponent {}

    @Configuration(proxyBeanMethods = false)
    public static class Factory {
        @Bean
        @ConditionalOnProperty(name = "fixture.enabled", havingValue = "true")
        String conditionalValue(@Value("${fixture.value}") String value) { return value; }
    }

    @Component
    @Scope(value = "prototype", proxyMode = ScopedProxyMode.TARGET_CLASS)
    public static class Scoped {
        private final String id = UUID.randomUUID().toString();
        public String id() { return id; }
    }

    @Component
    @PluginManagedBean
    public static class PluginOwned {}
}
