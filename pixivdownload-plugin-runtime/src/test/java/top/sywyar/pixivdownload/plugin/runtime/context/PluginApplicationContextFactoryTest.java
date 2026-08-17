package top.sywyar.pixivdownload.plugin.runtime.context;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import top.sywyar.pixivdownload.plugin.api.stream.PluginStreamRegistrar;
import top.sywyar.pixivdownload.plugin.api.storage.PluginDataSource;
import top.sywyar.pixivdownload.plugin.api.storage.RuntimePathProvider;
import top.sywyar.pixivdownload.plugin.api.task.PluginRuntimeTaskRegistrar;
import top.sywyar.pixivdownload.plugin.runtime.stream.PluginStreamRegistry;
import top.sywyar.pixivdownload.plugin.runtime.task.PluginRuntimeTaskRegistry;

import java.net.URL;
import java.net.URLClassLoader;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * 每外置插件子 {@code ApplicationContext} 工厂测试：用 synthetic 父 context（暴露一个核心服务接口）+ synthetic 插件
 * 配置类（其 Bean 注入该核心服务），验证子 context 能实例化插件 Bean、向父 context 解析SDK、插件 Bean 不进入
 * 父 context，且子 context 关闭后不再可用——不依赖 PF4J / Spring Boot。
 */
@DisplayName("每外置插件子 ApplicationContext 工厂")
class PluginApplicationContextFactoryTest {

    private final PluginStreamRegistry streamRegistry = new PluginStreamRegistry();
    private final PluginRuntimeTaskRegistry taskRegistry = new PluginRuntimeTaskRegistry();
    private final PluginApplicationContextFactory factory =
            new PluginApplicationContextFactory(streamRegistry, taskRegistry);

    @Test
    @DisplayName("插件配置类在子 context 中实例化 Bean，并注入父 context 暴露的核心服务接口；插件 Bean 不在父 context")
    void instantiatesPluginBeansInjectingParentCoreService() {
        try (AnnotationConfigApplicationContext parent =
                     new AnnotationConfigApplicationContext(ParentCoreConfig.class)) {
            PluginContextModule module = new PluginContextModule(
                    "ext-demo", getClass().getClassLoader(), List.of(PluginConfig.class));

            ConfigurableApplicationContext child = factory.create(parent, module);

            // 子 context 的父就是核心应用 context
            assertThat(child.getParent()).isSameAs(parent);
            // 子 context 创建了插件 Bean
            PluginBean pluginBean = child.getBean(PluginBean.class);
            assertThat(pluginBean).isNotNull();
            // 插件 Bean 注入的核心服务来自父 context（同一实例）——子 context 能拿到父 context 暴露的SDK 服务
            assertThat(pluginBean.coreService()).isSameAs(parent.getBean(CoreApiService.class));
            // 插件 Bean 不出现在父 context（不进入父根扫描 / 父 BeanFactory）
            assertThat(parent.getBeanNamesForType(PluginBean.class)).isEmpty();

            child.close();
        }
    }

    @Test
    @DisplayName("插件停止后子 context 关闭、不再可用（生命周期可观测）")
    void closedChildBecomesInactiveAndUnusable() {
        try (AnnotationConfigApplicationContext parent =
                     new AnnotationConfigApplicationContext(ParentCoreConfig.class)) {
            ConfigurableApplicationContext child = factory.create(parent, new PluginContextModule(
                    "ext-demo", getClass().getClassLoader(), List.of(PluginConfig.class)));
            assertThat(child.isActive()).isTrue();

            child.close();

            assertThat(child.isActive()).isFalse();
            // 关闭后再取 Bean 抛出（子 context 不再可用）
            assertThatThrownBy(() -> child.getBean(PluginBean.class)).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    @DisplayName("子 context 用插件 classloader 作为其 classloader（资源 / 类解析走插件 loader）")
    void childUsesPluginClassLoader() throws Exception {
        try (AnnotationConfigApplicationContext parent =
                     new AnnotationConfigApplicationContext(ParentCoreConfig.class);
             URLClassLoader pluginClassLoader =
                     new URLClassLoader(new URL[0], getClass().getClassLoader())) {
            ConfigurableApplicationContext child = factory.create(parent,
                    new PluginContextModule("ext-demo", pluginClassLoader, List.of(PluginConfig.class)));

            assertThat(child.getClassLoader()).isSameAs(pluginClassLoader);
            assertThat(child.getBeanFactory().getBeanClassLoader()).isSameAs(pluginClassLoader);

            child.close();
        }
    }

    @Test
    @DisplayName("owner 属性源只进入对应插件子 context 且不进入父 context")
    void scopedPropertiesRemainInsideMatchingChildContext() {
        PluginApplicationContextFactory scopedFactory = new PluginApplicationContextFactory(
                owner -> new PluginContextPropertySnapshot(
                        Map.of("fixture.owner", owner), Set.of("fixture.owner")),
                streamRegistry,
                taskRegistry);
        try (AnnotationConfigApplicationContext parent =
                     new AnnotationConfigApplicationContext(ParentCoreConfig.class)) {
            ConfigurableApplicationContext first = scopedFactory.create(parent, new PluginContextModule(
                    "first", getClass().getClassLoader(), List.of(PluginConfig.class)));
            ConfigurableApplicationContext second = scopedFactory.create(parent, new PluginContextModule(
                    "second", getClass().getClassLoader(), List.of(PluginConfig.class)));
            try {
                assertThat(first.getEnvironment().getProperty("fixture.owner")).isEqualTo("first");
                assertThat(second.getEnvironment().getProperty("fixture.owner")).isEqualTo("second");
                assertThat(parent.getEnvironment().getProperty("fixture.owner")).isNull();
            } finally {
                first.close();
                second.close();
            }
        }
    }

    @Test
    @DisplayName("两个 owner 只读取自己的敏感属性且双向遮蔽另一方属性")
    void ownerSensitivePropertiesAreMutuallyIsolated() {
        Set<String> sensitiveKeys = Set.of("fixture.first.secret", "fixture.second.secret");
        PluginApplicationContextFactory scopedFactory = new PluginApplicationContextFactory(
                owner -> switch (owner) {
                    case "first" -> new PluginContextPropertySnapshot(
                            Map.of("fixture.first.secret", "first-value"), sensitiveKeys);
                    case "second" -> new PluginContextPropertySnapshot(
                            Map.of("fixture.second.secret", "second-value"), sensitiveKeys);
                    default -> new PluginContextPropertySnapshot(Map.of(), sensitiveKeys);
                },
                streamRegistry,
                taskRegistry);
        try (AnnotationConfigApplicationContext parent =
                     new AnnotationConfigApplicationContext(ParentCoreConfig.class)) {
            ConfigurableApplicationContext first = scopedFactory.create(parent, new PluginContextModule(
                    "first", getClass().getClassLoader(), List.of(PluginConfig.class)));
            ConfigurableApplicationContext second = scopedFactory.create(parent, new PluginContextModule(
                    "second", getClass().getClassLoader(), List.of(PluginConfig.class)));
            try {
                assertThat(first.getEnvironment().getProperty("fixture.first.secret"))
                        .isEqualTo("first-value");
                assertThat(first.getEnvironment().getProperty("fixture.second.secret")).isEmpty();
                assertThat(Binder.get(first.getEnvironment())
                        .bind("fixture.first.secret", String.class)
                        .orElse(null))
                        .isEqualTo("first-value");
                assertThat(second.getEnvironment().getProperty("fixture.second.secret"))
                        .isEqualTo("second-value");
                assertThat(second.getEnvironment().getProperty("fixture.first.secret")).isEmpty();
                assertThat(Binder.get(second.getEnvironment())
                        .bind("fixture.second.secret", String.class)
                        .orElse(null))
                        .isEqualTo("second-value");
            } finally {
                first.close();
                second.close();
            }
        }
    }

    @Test
    @DisplayName("owner 属性优先于系统与父遗留值且普通父属性仍然可见")
    void ownerPropertiesOverrideSystemAndLegacyValuesWithoutHidingOrdinaryParentProperties() {
        String systemSecretKey = "fixture.owner.system-secret";
        String legacySecretKey = "fixture.owner.legacy-secret";
        String previousSystemValue = System.getProperty(systemSecretKey);
        System.setProperty(systemSecretKey, "system-value");
        try (AnnotationConfigApplicationContext parent =
                     new AnnotationConfigApplicationContext(ParentCoreConfig.class)) {
            parent.getEnvironment().getPropertySources().addFirst(new MapPropertySource(
                    "legacyConfigYaml",
                    Map.of(legacySecretKey, "legacy-value",
                            "fixture.ordinary", "ordinary-value")));
            PluginApplicationContextFactory scopedFactory = new PluginApplicationContextFactory(
                    owner -> new PluginContextPropertySnapshot(
                            Map.of(systemSecretKey, "owner-system-value",
                                    legacySecretKey, "owner-legacy-value"),
                            Set.of(systemSecretKey, legacySecretKey)),
                    streamRegistry,
                    taskRegistry);

            ConfigurableApplicationContext child = scopedFactory.create(parent, new PluginContextModule(
                    "first", getClass().getClassLoader(), List.of(PluginConfig.class)));
            try {
                assertThat(parent.getEnvironment().getProperty(systemSecretKey)).isEqualTo("system-value");
                assertThat(parent.getEnvironment().getProperty(legacySecretKey)).isEqualTo("legacy-value");
                assertThat(child.getEnvironment().getProperty(systemSecretKey))
                        .isEqualTo("owner-system-value");
                assertThat(child.getEnvironment().getProperty(legacySecretKey))
                        .isEqualTo("owner-legacy-value");
                assertThat(child.getEnvironment().getProperty("fixture.ordinary"))
                        .isEqualTo("ordinary-value");
            } finally {
                child.close();
            }
        } finally {
            restoreSystemProperty(systemSecretKey, previousSystemValue);
        }
    }

    @Test
    @DisplayName("owner 缺失的敏感属性由遮罩截断且不会回落父遗留或系统值")
    void missingOwnerSensitivePropertyDoesNotFallBackToParentOrSystem() {
        String systemSecretKey = "fixture.owner.missing-system-secret";
        String legacySecretKey = "fixture.owner.missing-legacy-secret";
        String previousSystemValue = System.getProperty(systemSecretKey);
        System.setProperty(systemSecretKey, "system-value");
        try (AnnotationConfigApplicationContext parent =
                     new AnnotationConfigApplicationContext(ParentCoreConfig.class)) {
            parent.getEnvironment().getPropertySources().addFirst(new MapPropertySource(
                    "legacyConfigYaml", Map.of(legacySecretKey, "legacy-value")));
            PluginApplicationContextFactory scopedFactory = new PluginApplicationContextFactory(
                    owner -> new PluginContextPropertySnapshot(
                            Map.of(), Set.of(systemSecretKey, legacySecretKey)),
                    streamRegistry,
                    taskRegistry);

            ConfigurableApplicationContext child = scopedFactory.create(parent, new PluginContextModule(
                    "first", getClass().getClassLoader(), List.of(PluginConfig.class)));
            try {
                assertThat(parent.getEnvironment().getProperty(systemSecretKey)).isEqualTo("system-value");
                assertThat(parent.getEnvironment().getProperty(legacySecretKey)).isEqualTo("legacy-value");
                assertThat(child.getEnvironment().getProperty(systemSecretKey)).isEmpty();
                assertThat(child.getEnvironment().getProperty(legacySecretKey)).isEmpty();
                assertThat(Binder.get(child.getEnvironment())
                        .bind(systemSecretKey, String.class)
                        .orElse("fallback-marker"))
                        .isEmpty();
                assertThat(Binder.get(child.getEnvironment())
                        .bind(legacySecretKey, String.class)
                        .orElse("fallback-marker"))
                        .isEmpty();
            } finally {
                child.close();
            }
        } finally {
            restoreSystemProperty(systemSecretKey, previousSystemValue);
        }
    }

    @Test
    @DisplayName("替换快照会撤回旧 owner 值且敏感遮罩缩减或清空后仍不回显")
    void replacingSnapshotWithdrawsOldOwnerValuesWithoutShrinkingSensitiveMask() {
        String firstSecret = "fixture.snapshot.first-secret";
        String secondSecret = "fixture.snapshot.second-secret";
        Set<String> sensitiveKeys = Set.of(firstSecret, secondSecret);
        PluginApplicationContextFactory scopedFactory = new PluginApplicationContextFactory(
                owner -> new PluginContextPropertySnapshot(
                        Map.of(firstSecret, "first-value"), sensitiveKeys),
                streamRegistry,
                taskRegistry);
        try (AnnotationConfigApplicationContext parent =
                     new AnnotationConfigApplicationContext(ParentCoreConfig.class)) {
            parent.getEnvironment().getPropertySources().addFirst(new MapPropertySource(
                    "legacyConfigYaml",
                    Map.of(firstSecret, "legacy-first",
                            secondSecret, "legacy-second",
                            "fixture.ordinary", "ordinary-value")));
            ConfigurableApplicationContext child = scopedFactory.create(parent, new PluginContextModule(
                    "first", getClass().getClassLoader(), List.of(PluginConfig.class)));
            try {
                PluginApplicationContextFactory.replaceScopedPropertySources(
                        child.getEnvironment(),
                        "first",
                        new PluginContextPropertySnapshot(
                                Map.of(secondSecret, "second-value"), Set.of(secondSecret)));

                assertThat(child.getEnvironment().getProperty(firstSecret)).isEmpty();
                assertThat(child.getEnvironment().getProperty(secondSecret)).isEqualTo("second-value");
                assertThat(child.getEnvironment().getProperty("fixture.ordinary"))
                        .isEqualTo("ordinary-value");
                assertThat(StreamSupport.stream(
                                child.getEnvironment().getPropertySources().spliterator(), false)
                        .map(propertySource -> propertySource.getName())
                        .toList())
                        .startsWith(
                                PluginApplicationContextFactory.SCOPED_PROPERTY_SOURCE_PREFIX + "first",
                                PluginApplicationContextFactory.SENSITIVE_PROPERTY_MASK_SOURCE_PREFIX + "first");

                PluginApplicationContextFactory.replaceScopedPropertySources(
                        child.getEnvironment(),
                        "first",
                        PluginContextPropertySnapshot.empty());

                assertThat(child.getEnvironment().getProperty(firstSecret)).isEmpty();
                assertThat(child.getEnvironment().getProperty(secondSecret)).isEmpty();
                assertThat(child.getEnvironment().getProperty("fixture.ordinary"))
                        .isEqualTo("ordinary-value");
                assertThat(StreamSupport.stream(
                                child.getEnvironment().getPropertySources().spliterator(), false)
                        .map(propertySource -> propertySource.getName())
                        .toList())
                        .startsWith(
                                PluginApplicationContextFactory.SENSITIVE_PROPERTY_MASK_SOURCE_PREFIX + "first")
                        .doesNotContain(
                                PluginApplicationContextFactory.SCOPED_PROPERTY_SOURCE_PREFIX + "first");
            } finally {
                child.close();
            }
        }
    }

    @Test
    @DisplayName("工厂构造器必须显式接收共享推流与后台任务注册中心且不存在隐式新建入口")
    void factoryRequiresExplicitSharedRuntimeRegistries() {
        assertThat(Arrays.stream(PluginApplicationContextFactory.class.getConstructors())
                .map(constructor -> List.of(constructor.getParameterTypes()))
                .toList())
                .containsExactlyInAnyOrder(
                        List.of(PluginStreamRegistry.class, PluginRuntimeTaskRegistry.class),
                        List.of(PluginContextPropertySourceProvider.class,
                                PluginStreamRegistry.class,
                                PluginRuntimeTaskRegistry.class),
                        List.of(PluginContextPropertySourceProvider.class,
                                PluginStreamRegistry.class,
                                PluginRuntimeTaskRegistry.class,
                                java.util.function.Function.class,
                                java.util.function.Function.class));
    }

    @Test
    @DisplayName("路径与数据库能力由宿主按 child context owner 固化并在关闭时释放")
    void injectsOwnerScopedStorageCapabilitiesAndClosesDataSource() {
        AtomicInteger dataSourceCreations = new AtomicInteger();
        CloseablePluginDataSource dataSource = mock(CloseablePluginDataSource.class);
        PluginApplicationContextFactory storageFactory = new PluginApplicationContextFactory(
                PluginContextPropertySourceProvider.EMPTY,
                streamRegistry,
                taskRegistry,
                TestRuntimePathProvider::new,
                owner -> {
                    assertThat(owner).isEqualTo("storage-owner");
                    dataSourceCreations.incrementAndGet();
                    return dataSource;
                });

        try (AnnotationConfigApplicationContext parent =
                     new AnnotationConfigApplicationContext(ParentCoreConfig.class)) {
            ConfigurableApplicationContext child = storageFactory.create(parent, new PluginContextModule(
                    "storage-owner", getClass().getClassLoader(), List.of(StoragePluginConfig.class)));
            StoragePluginBean bean = child.getBean(StoragePluginBean.class);

            assertThat(bean.paths().configFile("properties"))
                    .isEqualTo(java.nio.file.Path.of("config", "storage-owner.properties"));
            assertThat(bean.paths().stateDirectory())
                    .isEqualTo(java.nio.file.Path.of("state", "storage-owner"));
            assertThat(bean.paths().dataDirectory())
                    .isEqualTo(java.nio.file.Path.of("data", "storage-owner"));
            assertThat(bean.dataSource()).isSameAs(dataSource);
            assertThat(dataSourceCreations).hasValue(1);
            assertThat(parent.getBeanNamesForType(RuntimePathProvider.class)).isEmpty();
            assertThat(parent.getBeanNamesForType(PluginDataSource.class)).isEmpty();

            child.close();
            verify(dataSource).close();
        }
    }

    @Test
    @DisplayName("未使用私有数据库的插件不创建数据源")
    void pluginDataSourceRemainsLazyUntilRequested() {
        AtomicInteger dataSourceCreations = new AtomicInteger();
        CloseablePluginDataSource dataSource = mock(CloseablePluginDataSource.class);
        PluginApplicationContextFactory storageFactory = new PluginApplicationContextFactory(
                PluginContextPropertySourceProvider.EMPTY,
                streamRegistry,
                taskRegistry,
                TestRuntimePathProvider::new,
                owner -> {
                    dataSourceCreations.incrementAndGet();
                    return dataSource;
                });

        try (AnnotationConfigApplicationContext parent =
                     new AnnotationConfigApplicationContext(ParentCoreConfig.class)) {
            ConfigurableApplicationContext child = storageFactory.create(parent, new PluginContextModule(
                    "lazy-owner", getClass().getClassLoader(), List.of()));
            assertThat(dataSourceCreations).hasValue(0);

            assertThat(child.getBean(PluginDataSource.class)).isSameAs(dataSource);
            assertThat(dataSourceCreations).hasValue(1);

            child.close();
            verify(dataSource).close();
        }
    }

    @Test
    @DisplayName("每个子 context 在 refresh 前获得本地 owner-scoped registrar 且同 token 跨 owner 隔离")
    void injectsOwnerScopedStreamRegistrarBeforeRefresh() {
        try (AnnotationConfigApplicationContext parent =
                     new AnnotationConfigApplicationContext(ParentCoreConfig.class)) {
            ConfigurableApplicationContext first = factory.create(parent, new PluginContextModule(
                    "first", getClass().getClassLoader(), List.of(StreamPluginConfig.class)));
            ConfigurableApplicationContext second = factory.create(parent, new PluginContextModule(
                    "second", getClass().getClassLoader(), List.of(StreamPluginConfig.class)));
            try {
                PluginStreamRegistrar firstRegistrar = first.getBean(PluginStreamRegistrar.class);
                PluginStreamRegistrar secondRegistrar = second.getBean(PluginStreamRegistrar.class);

                assertThat(first.getBean(StreamPluginBean.class).streamRegistrar()).isSameAs(firstRegistrar);
                assertThat(second.getBean(StreamPluginBean.class).streamRegistrar()).isSameAs(secondRegistrar);
                assertThat(firstRegistrar).isNotSameAs(secondRegistrar);
                assertThat(parent.getBeanNamesForType(PluginStreamRegistrar.class)).isEmpty();

                AtomicInteger firstCloses = new AtomicInteger();
                AtomicInteger secondCloses = new AtomicInteger();
                firstRegistrar.register("same-token", firstCloses::incrementAndGet);
                secondRegistrar.register("same-token", secondCloses::incrementAndGet);

                assertThat(streamRegistry.activeStreamCount("first")).isOne();
                assertThat(streamRegistry.activeStreamCount("second")).isOne();
                assertThat(streamRegistry.closeForPlugin("first")).isOne();
                assertThat(firstCloses).hasValue(1);
                assertThat(secondCloses).hasValue(0);
                assertThat(streamRegistry.activeStreamCount("second")).isOne();
                assertThat(streamRegistry.closeForPlugin("second")).isOne();
                assertThat(secondCloses).hasValue(1);
            } finally {
                first.close();
                second.close();
            }
        }
    }

    @Test
    @DisplayName("每个子 context 在 refresh 前获得本地 owner-scoped 后台任务 registrar")
    void injectsOwnerScopedRuntimeTaskRegistrarBeforeRefresh() {
        try (AnnotationConfigApplicationContext parent =
                     new AnnotationConfigApplicationContext(ParentCoreConfig.class)) {
            ConfigurableApplicationContext first = factory.create(parent, new PluginContextModule(
                    "first-task-owner", getClass().getClassLoader(), List.of(TaskPluginConfig.class)));
            ConfigurableApplicationContext second = factory.create(parent, new PluginContextModule(
                    "second-task-owner", getClass().getClassLoader(), List.of(TaskPluginConfig.class)));
            try {
                PluginRuntimeTaskRegistrar firstRegistrar = first.getBean(PluginRuntimeTaskRegistrar.class);
                PluginRuntimeTaskRegistrar secondRegistrar = second.getBean(PluginRuntimeTaskRegistrar.class);

                assertThat(first.getBean(TaskPluginBean.class).taskRegistrar()).isSameAs(firstRegistrar);
                assertThat(second.getBean(TaskPluginBean.class).taskRegistrar()).isSameAs(secondRegistrar);
                assertThat(firstRegistrar).isNotSameAs(secondRegistrar);
                assertThat(parent.getBeanNamesForType(PluginRuntimeTaskRegistrar.class)).isEmpty();

                var firstTask = firstRegistrar.registerOneShot(() -> {
                });
                var secondTask = secondRegistrar.registerOneShot(() -> {
                });
                assertThat(taskRegistry.activeTaskCount("first-task-owner")).isOne();
                assertThat(taskRegistry.activeTaskCount("second-task-owner")).isOne();
                var firstTaskDrain = taskRegistry.prepareQuiesce("first-task-owner");
                assertThat(firstTaskDrain.isDrained()).isFalse();
                taskRegistry.cancelQuiescedTasks("first-task-owner", firstTaskDrain);
                firstTask.run();
                assertThat(firstTaskDrain.isDrained()).isTrue();
                assertThat(taskRegistry.activeTaskCount("second-task-owner")).isOne();
                var secondTaskDrain = taskRegistry.prepareQuiesce("second-task-owner");
                assertThat(secondTaskDrain.isDrained()).isFalse();
                taskRegistry.cancelQuiescedTasks("second-task-owner", secondTaskDrain);
                secondTask.run();
                assertThat(secondTaskDrain.isDrained()).isTrue();
            } finally {
                first.close();
                second.close();
            }
        }
    }

    @Test
    @DisplayName("子 context 为插件 @Transactional Bean 创建 CGLIB 代理，并使用父 context 的事务管理器")
    void childCreatesTransactionalProxyUsingParentTransactionManager() {
        try (AnnotationConfigApplicationContext parent =
                     new AnnotationConfigApplicationContext(ParentTransactionConfig.class)) {
            ConfigurableApplicationContext child = factory.create(parent, new PluginContextModule(
                    "ext-demo", getClass().getClassLoader(), List.of(TransactionalPluginConfig.class)));

            Object bean = child.getBean(TransactionalPluginBean.class);
            assertThat(AopUtils.isAopProxy(bean)).isTrue();
            assertThat(AopUtils.isCglibProxy(bean)).isTrue();

            ((TransactionalPluginBean) bean).writeInTransaction();

            RecordingTransactionManager transactionManager = parent.getBean(RecordingTransactionManager.class);
            assertThat(transactionManager.begun).isEqualTo(1);
            assertThat(transactionManager.committed).isEqualTo(1);
            assertThat(transactionManager.rolledBack).isZero();

            child.close();
        }
    }

    // --- 夹具 ---

    /** 父 context 暴露的「SDK / 服务接口」。 */
    interface CoreApiService {
        String describe();
    }

    static final class CoreApiServiceImpl implements CoreApiService {
        @Override
        public String describe() {
            return "core";
        }
    }

    /** 核心应用 context 的占位：只暴露一个核心服务 Bean。 */
    @Configuration
    static class ParentCoreConfig {
        @Bean
        CoreApiService coreApiService() {
            return new CoreApiServiceImpl();
        }
    }

    /** 父 context 暴露的事务管理器。 */
    @Configuration
    static class ParentTransactionConfig {
        @Bean
        RecordingTransactionManager transactionManager() {
            return new RecordingTransactionManager();
        }
    }

    static final class RecordingTransactionManager extends AbstractPlatformTransactionManager {
        private int begun;
        private int committed;
        private int rolledBack;

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            begun++;
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            committed++;
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            rolledBack++;
        }
    }

    /** 插件 Bean：构造期注入父 context 的核心服务接口。 */
    static final class PluginBean {
        private final CoreApiService coreService;

        PluginBean(CoreApiService coreService) {
            this.coreService = coreService;
        }

        CoreApiService coreService() {
            return coreService;
        }
    }

    /** 插件配置类：在子 context 中以 @Bean 装配插件 Bean，注入父 context 的核心服务。 */
    @Configuration
    static class PluginConfig {
        @Bean
        PluginBean pluginBean(CoreApiService coreService) {
            return new PluginBean(coreService);
        }
    }

    static final class StreamPluginBean {
        private final PluginStreamRegistrar streamRegistrar;

        StreamPluginBean(PluginStreamRegistrar streamRegistrar) {
            this.streamRegistrar = streamRegistrar;
        }

        PluginStreamRegistrar streamRegistrar() {
            return streamRegistrar;
        }
    }

    static final class TaskPluginBean {
        private final PluginRuntimeTaskRegistrar taskRegistrar;

        TaskPluginBean(PluginRuntimeTaskRegistrar taskRegistrar) {
            this.taskRegistrar = taskRegistrar;
        }

        PluginRuntimeTaskRegistrar taskRegistrar() {
            return taskRegistrar;
        }
    }

    @Configuration
    static class StreamPluginConfig {
        @Bean
        StreamPluginBean streamPluginBean(PluginStreamRegistrar streamRegistrar) {
            return new StreamPluginBean(streamRegistrar);
        }
    }

    @Configuration
    static class TaskPluginConfig {
        @Bean
        TaskPluginBean taskPluginBean(PluginRuntimeTaskRegistrar taskRegistrar) {
            return new TaskPluginBean(taskRegistrar);
        }
    }

    interface CloseablePluginDataSource extends PluginDataSource {
        void close();
    }

    record TestRuntimePathProvider(String owner) implements RuntimePathProvider {
        @Override
        public java.nio.file.Path configFile(String extension) {
            return java.nio.file.Path.of("config", owner + "." + extension);
        }

        @Override
        public java.nio.file.Path stateDirectory() {
            return java.nio.file.Path.of("state", owner);
        }

        @Override
        public java.nio.file.Path dataDirectory() {
            return java.nio.file.Path.of("data", owner);
        }
    }

    record StoragePluginBean(RuntimePathProvider paths, PluginDataSource dataSource) {
    }

    @Configuration
    static class StoragePluginConfig {
        @Bean
        StoragePluginBean storagePluginBean(RuntimePathProvider paths, PluginDataSource dataSource) {
            return new StoragePluginBean(paths, dataSource);
        }
    }

    public static class TransactionalPluginBean {
        private boolean written;

        @Transactional
        public void writeInTransaction() {
            written = true;
        }
    }

    @Configuration
    static class TransactionalPluginConfig {
        @Bean
        TransactionalPluginBean transactionalPluginBean() {
            return new TransactionalPluginBean();
        }
    }

    private static void restoreSystemProperty(String key, String previousValue) {
        if (previousValue == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, previousValue);
        }
    }
}
