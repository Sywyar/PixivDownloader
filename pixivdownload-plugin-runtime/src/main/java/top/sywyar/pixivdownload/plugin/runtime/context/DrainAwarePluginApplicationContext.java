package top.sywyar.pixivdownload.plugin.runtime.context;

import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.LifecycleProcessor;

/** 刷新失败也可能留有在途插件任务，Bean 销毁须等宿主完成撤回和真实 drain。 */
final class DrainAwarePluginApplicationContext extends AnnotationConfigApplicationContext {
    private final DeferredDestructionBeanFactory factory;
    private boolean closingDeferred;

    DrainAwarePluginApplicationContext() {
        this(new DeferredDestructionBeanFactory());
    }

    private DrainAwarePluginApplicationContext(DeferredDestructionBeanFactory factory) {
        super(factory);
        this.factory = factory;
    }

    @Override
    public void refresh() {
        factory.refreshing = true;
        try {
            super.refresh();
            if (factory.destructionDeferred) {
                // 初始化器即使吞掉 Bean 创建失败，也不能发布保留着失败依赖的 context。
                throw new IllegalStateException("plugin context has pending initialization cleanup");
            }
        } finally {
            factory.refreshing = false;
        }
    }

    @Override
    protected void initLifecycleProcessor() {
        factory.lifecycleProcessorThread = Thread.currentThread();
        try {
            super.initLifecycleProcessor();
            if (factory.lifecycleProcessor == null) {
                factory.lifecycleProcessor = (LifecycleProcessor) factory.getSingleton(LIFECYCLE_PROCESSOR_BEAN_NAME);
            }
        } finally {
            factory.lifecycleProcessorThread = null;
        }
    }

    @Override
    protected void doClose() {
        if (closingDeferred) return;
        // 取消刷新后的 inactive context 不会执行 Spring 的停止与销毁流程。
        if (factory.destructionDeferred && !isActive()) {
            closingDeferred = true;
            try {
                if (factory.lifecycleProcessor != null) {
                    factory.lifecycleProcessor.onClose();
                    factory.lifecycleProcessor = null;
                }
                super.destroyBeans();
            } finally {
                closingDeferred = false;
            }
        }
        super.doClose();
        factory.lifecycleProcessor = null;
        factory.destructionDeferred = false;
    }

    private static final class DeferredDestructionBeanFactory extends DefaultListableBeanFactory {
        private boolean refreshing;
        private boolean destructionDeferred;
        private Thread lifecycleProcessorThread;
        private LifecycleProcessor lifecycleProcessor;

        @Override
        public <T> T getBean(String name, Class<T> requiredType) {
            boolean capture = lifecycleProcessorThread == Thread.currentThread() && requiredType == LifecycleProcessor.class
                    && LIFECYCLE_PROCESSOR_BEAN_NAME.equals(name);
            if (capture) lifecycleProcessorThread = null;
            T bean = super.getBean(name, requiredType);
            // 原型及非单例 FactoryBean 也须保存 Spring 本次取得的精确处理器。
            if (capture) lifecycleProcessor = (LifecycleProcessor) bean;
            return bean;
        }

        @Override
        public void destroySingleton(String beanName) {
            if (refreshing) destructionDeferred = true;
            else super.destroySingleton(beanName);
        }

        @Override
        public void destroySingletons() {
            if (refreshing) destructionDeferred = true;
            else super.destroySingletons();
        }
    }
}
