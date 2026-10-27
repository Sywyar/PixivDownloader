package top.sywyar.pixivdownload.plugin.runtime.context;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.context.LifecycleProcessor;
import org.springframework.context.ApplicationListener;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.support.DefaultLifecycleProcessor;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("插件刷新回滚保留 Spring 关闭顺序")
class DrainAwarePluginApplicationContextTest {
    @Test
    @DisplayName("非单例 FactoryBean 的停止处理器使用刷新原实例并允许关闭重入")
    void factoryBeanProcessorClosesExactInstance() {
        var events = new ArrayList<String>();
        try (var context = new DrainAwarePluginApplicationContext()) {
            context.getBeanFactory().registerSingleton("lifecycleProcessor", new FactoryBean<LifecycleProcessor>() {
                private int sequence;
                @Override public LifecycleProcessor getObject() {
                    int id = ++sequence;
                    events.add("created:" + id);
                    return new LifecycleProcessor() {
                        @Override public void onRefresh() { events.add("refresh:" + id); }
                        @Override public void onClose() { context.close(); events.add("close:" + id); }
                        @Override public void start() { }
                        @Override public void stop() { }
                        @Override public boolean isRunning() { return true; }
                    };
                }
                @Override public Class<?> getObjectType() { return LifecycleProcessor.class; }
                @Override public boolean isSingleton() { return false; }
            });
            context.registerBean(DisposableBean.class, () -> () -> events.add("destroy"));
            context.addApplicationListener((ApplicationListener<ContextRefreshedEvent>) event -> {
                throw new IllegalStateException("fixture-late-refresh");
            });
            assertThatThrownBy(context::refresh).hasMessage("fixture-late-refresh");
            context.close();
            context.close();
            assertThat(events).containsExactly("created:1", "refresh:1", "close:1", "destroy");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("晚期刷新失败保持停止处理器，关闭时先 stop 后销毁且重复关闭不重放")
    void lateRefreshFailureStopsBeforeDestroy(boolean reentrant) {
        var events = new Events(false);
        try (var context = new DrainAwarePluginApplicationContext()) {
            if (reentrant) events.afterStop = context::close;
            context.registerBean(Events.class, () -> events);
            context.register(CatchingConfiguration.class);
            context.addApplicationListener((ApplicationListener<ContextRefreshedEvent>) event -> {
                throw new IllegalStateException("fixture-late-refresh");
            });
            assertThatThrownBy(context::refresh).hasMessage("fixture-late-refresh");
            SmartLifecycle lifecycle = context.getBeanFactory().getBean(SmartLifecycle.class);
            assertThat(context.isActive()).isFalse();
            assertThat(lifecycle.isRunning()).isTrue();
            assertThat(events.order).containsExactly("start");
            context.close();
            assertThat(lifecycle.isRunning()).isFalse();
            assertThat(events.order).containsExactly("start", "stop", "destroy");
            context.close();
            assertThat(events.order).hasSize(3);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("正常与晚期失败关闭均沿用 Spring 对单 Bean 停止异常的隔离")
    void beanStopFailureKeepsSpringSemantics(boolean late) {
        var events = new Events(false);
        events.failStop = true;
        try (var context = new DrainAwarePluginApplicationContext()) {
            context.registerBean(Events.class, () -> events);
            context.register(CatchingConfiguration.class);
            if (late) {
                context.addApplicationListener((ApplicationListener<ContextRefreshedEvent>) event -> {
                    throw new IllegalStateException("fixture-late-refresh");
                });
                assertThatThrownBy(context::refresh).hasMessage("fixture-late-refresh");
            } else {
                context.refresh();
            }
            SmartLifecycle lifecycle = context.getBeanFactory().getBean(SmartLifecycle.class);
            context.close();
            if (late) assertThat(events.order).containsExactly("start", "stop", "destroy");
            else assertThat(events.order).containsExactly("start", "event", "stop", "destroy");
            // Spring 的 processor 隔离单 Bean 错误；调用 onClose 不证明每个 Bean 已停止。
            assertThat(lifecycle.isRunning()).isTrue();
            int completedEvents = events.order.size();
            context.close();
            assertThat(events.order).hasSize(completedEvents);
        }
    }

    @Test
    @DisplayName("早期刷新失败不会初始化停止处理器，保留 Bean 至最终关闭")
    void earlyFailureDoesNotInitializeLifecycleProcessor() {
        var events = new ArrayList<String>();
        try (var context = new DrainAwarePluginApplicationContext()) {
            context.registerBean("lifecycleProcessor", DefaultLifecycleProcessor.class, () -> {
                events.add("processor-created");
                return new DefaultLifecycleProcessor();
            }, definition -> definition.setLazyInit(true));
            context.registerBean("disposable", DisposableBean.class, () -> () -> events.add("destroy"));
            context.registerBean("failure", String.class, () -> {
                throw new IllegalStateException("fixture-early-refresh");
            }, definition -> definition.setDependsOn("disposable"));
            assertThatThrownBy(context::refresh).hasRootCauseMessage("fixture-early-refresh");
            assertThat(events).isEmpty();
            context.close();
            context.close();
            assertThat(events).containsExactly("destroy");
        }
    }

    @Test
    @DisplayName("晚期回滚的停止处理器失败保留 Bean，重试使用原处理器而不重建原型")
    void failedProcessorCloseCanRetryExactInstance() {
        var events = new Events(false);
        try (var context = new DrainAwarePluginApplicationContext()) {
            context.registerBean(Events.class, () -> events);
            context.register(CatchingConfiguration.class);
            var definition = BeanDefinitionBuilder.genericBeanDefinition(DefaultLifecycleProcessor.class, () -> {
                events.order.add("processor-created");
                return new DefaultLifecycleProcessor() {
                    private boolean fail = true;
                    @Override public void onClose() {
                        events.order.add("processor-close");
                        if (fail) { fail = false; throw new IllegalStateException("fixture-stop"); }
                        super.onClose();
                    }
                };
            }).setScope("prototype").getBeanDefinition();
            context.registerBeanDefinition("lifecycleProcessor", definition);
            context.addApplicationListener((ApplicationListener<ContextRefreshedEvent>) event -> {
                throw new IllegalStateException("fixture-late-refresh");
            });
            assertThatThrownBy(context::refresh).hasMessage("fixture-late-refresh");
            assertThatThrownBy(context::close).hasMessage("fixture-stop");
            assertThat(events.order).containsExactly("processor-created", "start", "processor-close");
            context.close();
            context.close();
            assertThat(events.order).containsExactly("processor-created", "start", "processor-close", "processor-close", "stop", "destroy");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("初始化器吞掉 Bean 失败仍拒绝发布，正常与失败刷新都先停生命周期再销毁")
    void caughtBeanFailureDoesNotPublishAndClosePreservesOrder(boolean fail) {
        var events = new Events(fail);
        try (var context = new DrainAwarePluginApplicationContext()) {
            context.registerBean(Events.class, () -> events);
            context.register(CatchingConfiguration.class);
            if (fail) {
                assertThatThrownBy(context::refresh).hasMessageContaining("pending initialization cleanup");
            } else {
                context.refresh();
            }
            assertThat(events.order).containsExactly("start");
            context.close();
            assertThat(events.order).containsExactly("start", "event", "stop", "destroy");
            context.close();
            assertThat(events.order).hasSize(4);
        }
    }

    static final class Events {
        final boolean fail;
        boolean failStop;
        Runnable afterStop = () -> { };
        final List<String> order = new ArrayList<>();
        Events(boolean fail) { this.fail = fail; }
    }

    static final class Failing { }

    @Configuration(proxyBeanMethods = false)
    static class CatchingConfiguration {
        @Bean @Lazy Failing failing() { throw new IllegalStateException("fixture"); }
        @Bean String catcher(ObjectProvider<Failing> provider, Events events) {
            if (events.fail) {
                try { provider.getObject(); } catch (RuntimeException expected) { return "caught"; }
            }
            return "healthy";
        }
        @Bean DisposableBean disposable(Events events) { return () -> events.order.add("destroy"); }
        @Bean ApplicationListener<ContextClosedEvent> closed(Events events) {
            return event -> events.order.add("event");
        }
        @Bean SmartLifecycle lifecycle(Events events) {
            return new SmartLifecycle() {
                private boolean running;
                @Override public void start() { running = true; events.order.add("start"); }
                @Override public void stop() {
                    events.order.add("stop");
                    if (events.failStop) throw new IllegalStateException("fixture-bean-stop");
                    running = false;
                    events.afterStop.run();
                }
                @Override public boolean isRunning() { return running; }
            };
        }
    }
}
