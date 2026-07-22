package top.sywyar.pixivdownload;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.config.PluginCredentialStore;
import top.sywyar.pixivdownload.plugin.catalog.PluginCatalogProperties;
import top.sywyar.pixivdownload.plugin.catalog.repository.PluginRepositoryRegistry;
import top.sywyar.pixivdownload.plugin.PluginRuntimeConfiguration;
import top.sywyar.pixivdownload.plugin.PluginToggleProperties;
import top.sywyar.pixivdownload.plugin.runtime.PluginRuntimeManager;
import top.sywyar.pixivdownload.plugin.runtime.PluginRuntimeStatus;
import top.sywyar.pixivdownload.plugin.runtime.bootstrap.PluginBootstrapSession;
import top.sywyar.pixivdownload.plugin.runtime.bootstrap.PluginEnabledSnapshot;
import top.sywyar.pixivdownload.plugin.runtime.install.ExternalPluginInstaller;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Spring handoff 装配测试：用真实 {@link PixivDownloadApplication.PluginBootstrapHandoffInitializer}（经
 * {@code ApplicationContextInitializer} 注册 PROCESS 会话）+ 真实 {@link PluginRuntimeConfiguration} 证明：
 * <ul>
 *   <li>GUI（PROCESS）路径：注入的 session / manager / status / installer 是同一组实例（config 不再 new 第二个 manager）；</li>
 *   <li>Spring context 关闭不关闭 PROCESS 会话（closeForContext 对 PROCESS 为 no-op）；</li>
 *   <li>headless（CONTEXT）路径：无 handoff 时 config 自建 CONTEXT 会话并 start；context 关闭释放运行时。</li>
 * </ul>
 * 用聚焦的 {@link AnnotationConfigApplicationContext}（只装 {@link PluginRuntimeConfiguration} +
 * {@link PluginToggleProperties}）而非全量 app context，精确验证会话交接与所有权语义、不被其它 Bean 干扰。
 * 本类位于 app 根包（与 {@link PixivDownloadApplication} 同包），以访问包级可见的 handoff initializer。
 */
@DisplayName("Spring handoff：PROCESS 复用同一 manager / CONTEXT 自建并随 context 释放")
class PluginBootstrapHandoffTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void pinPluginsDir() {
        // CONTEXT 路径经 RuntimeFiles.pluginsDirectory() 解析目录；指向不存在的临时路径，确保 ABSENT、不扫描。
        System.setProperty(RuntimeFiles.PLUGINS_DIR_PROPERTY, tempDir.resolve("absent-plugins").toString());
    }

    @AfterEach
    void clearPluginsDir() {
        System.clearProperty(RuntimeFiles.PLUGINS_DIR_PROPERTY);
    }

    @Test
    @DisplayName("GUI handoff：initializer 注册 PROCESS 会话，config 复用同一 manager/status/installer，context 关闭不关 PROCESS")
    void guiHandoffReusesProcessSession() {
        Path pluginsDir = tempDir.resolve("process-plugins");
        PluginBootstrapSession session = PluginBootstrapSession.createProcess(pluginsDir, PluginEnabledSnapshot.empty());
        session.start();

        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            // 等价于 PixivDownloadApplication.start(args, session) 经 ApplicationContextInitializer 注册交接载体
            new PixivDownloadApplication.PluginBootstrapHandoffInitializer(session).initialize(ctx);
            registerRuntimeConfig(ctx);
            ctx.refresh();

            // 注入的 session 与其派生 Bean 全部来自同一 PROCESS 会话（config 未 new 第二个 manager）
            assertThat(ctx.getBean(PluginBootstrapSession.class)).isSameAs(session);
            assertThat(ctx.getBean(PluginRuntimeManager.class)).isSameAs(session.manager());
            assertThat(ctx.getBean(PluginRuntimeStatus.class)).isSameAs(session.status());
            assertThat(ctx.getBean(ExternalPluginInstaller.class)).isSameAs(session.installer());

            ctx.close(); // destroyMethod=closeForContext → PROCESS 为 no-op
        }

        // PROCESS 会话在 Spring context 关闭后仍未被关闭（closeForContext 对 PROCESS 为 no-op）
        assertThat(session.isClosed()).isFalse();
        session.close();
        assertThat(session.isClosed()).isTrue();
    }

    @Test
    @DisplayName("GUI handoff：恢复门封闭时核心 Spring context 仍启动并注入 inert manager")
    void guiHandoffStartsCoreContextWhenRecoveryIsBlocked() throws Exception {
        Path pluginsDir = tempDir.resolve("blocked-process-plugins");
        Path retained = pluginsDir.resolve(".staging").resolve("orphaned")
                .resolve("removed").resolve("evidence.jar");
        Files.createDirectories(retained.getParent());
        Files.writeString(retained, "only-copy", StandardCharsets.UTF_8);

        PluginBootstrapSession session = PluginBootstrapSession.createProcess(
                pluginsDir, PluginEnabledSnapshot.empty());
        try {
            session.start();
            PluginRuntimeManager inertManager = session.manager();

            assertThat(session.status().hasFailures()).isTrue();
            assertThat(inertManager.pluginManager()).isEmpty();

            try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
                new PixivDownloadApplication.PluginBootstrapHandoffInitializer(session).initialize(ctx);
                registerRuntimeConfig(ctx);
                ctx.refresh();

                assertThat(ctx.getBean(PluginBootstrapSession.class)).isSameAs(session);
                assertThat(ctx.getBean(PluginRuntimeManager.class)).isSameAs(inertManager);
                assertThat(ctx.getBean(PluginRuntimeStatus.class)).isSameAs(session.status());
                assertThat(ctx.getBean(ExternalPluginInstaller.class)).isSameAs(session.installer());
                assertThatThrownBy(inertManager::start)
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("recovery is unsafe");
            }

            assertThat(session.isClosed()).isFalse();
        } finally {
            session.close();
        }
        assertThat(session.isClosed()).isTrue();
    }

    @Test
    @DisplayName("headless：无 handoff，config 创建 CONTEXT 会话并 start，context 关闭释放运行时")
    void headlessCreatesContextSessionAndReleasesOnContextClose() {
        PluginBootstrapSession session;
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            registerRuntimeConfig(ctx);
            ctx.refresh();

            session = ctx.getBean(PluginBootstrapSession.class);
            assertThat(session.ownership()).isEqualTo(PluginBootstrapSession.Ownership.CONTEXT);
            assertThat(session.isStarted()).isTrue();
            assertThat(ctx.getBean(PluginRuntimeManager.class)).isSameAs(session.manager());
            assertThat(ctx.getBean(PluginRuntimeStatus.class)).isSameAs(session.status());
            assertThat(ctx.getBean(ExternalPluginInstaller.class)).isSameAs(session.installer());

            ctx.close(); // destroyMethod=closeForContext → CONTEXT 关闭运行时
        }
        // CONTEXT 会话随 context 关闭已释放（closeForContext 对 CONTEXT 真正关闭）
        assertThat(session.isClosed()).isTrue();
    }

    @Test
    @DisplayName("config 不再 new / recover / start：PROCESS 路径 manager 实例在 refresh 前后不变（未二次构造 / 重扫）")
    void configDoesNotConstructSecondManager() {
        Path pluginsDir = tempDir.resolve("stable-plugins");
        PluginBootstrapSession session = PluginBootstrapSession.createProcess(pluginsDir, PluginEnabledSnapshot.empty());
        session.start();
        PluginRuntimeManager originalManager = session.manager();

        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            new PixivDownloadApplication.PluginBootstrapHandoffInitializer(session).initialize(ctx);
            registerRuntimeConfig(ctx);
            ctx.refresh();
            // Spring 注入的 manager 就是 bootstrap manager 本身（同一引用，未 new 第二个）
            assertThat(ctx.getBean(PluginRuntimeManager.class)).isSameAs(originalManager);
            // PROCESS 会话经 context refresh / 关闭未被关闭（无二次 new / start 触发 shutdown）
            assertThat(session.isClosed()).isFalse();
        }
        session.close();
        assertThat(session.isClosed()).isTrue();
    }

    // ── Spring shutdown hook 所有权（GUI 单一 owner / headless 默认）──────────────

    @Test
    @DisplayName("GUI buildGuiApplication：禁用 Spring 自动 shutdown hook（coordinator 单一所有者）+ 注入 handoff 载体")
    void guiApplicationDisablesSpringShutdownHook() {
        PluginBootstrapSession session = PluginBootstrapSession.createProcess(
                tempDir.resolve("p"), PluginEnabledSnapshot.empty());
        SpringApplication app = PixivDownloadApplication.buildGuiApplication(session);

        assertThat(readRegisterShutdownHook(app))
                .as("GUI 路径必须禁用 Spring 自动 shutdown hook，由 BackendShutdownCoordinator 单一所有，避免二者并发关同一 context")
                .isFalse();
        assertThat(app.getInitializers())
                .as("GUI 路径必须注入 bootstrap 会话交接载体")
                .anyMatch(PixivDownloadApplication.PluginBootstrapHandoffInitializer.class::isInstance);
    }

    @Test
    @DisplayName("headless buildHeadlessApplication：保留 Spring 默认 shutdown hook（Spring 自有 lifecycle）")
    void headlessApplicationKeepsSpringShutdownHook() {
        SpringApplication app = PixivDownloadApplication.buildHeadlessApplication();

        assertThat(readRegisterShutdownHook(app))
                .as("headless 路径保留 Spring 默认 shutdown hook，退出关闭由 Spring lifecycle 独占")
                .isTrue();
    }

    /**
     * 读取 {@link SpringApplication} 的 {@code registerShutdownHook} 标志（无公开 getter，经反射）。
     * 这是「GUI 禁用 / headless 保留 Spring 自动 shutdown hook」的结构守卫：字段缺失（Spring 升级改名）时
     * 显式失败而非静默通过。
     */
    private static boolean readRegisterShutdownHook(SpringApplication app) {
        try {
            Field propertiesField = SpringApplication.class.getDeclaredField("properties");
            propertiesField.setAccessible(true);
            Object properties = propertiesField.get(app);
            var getter = properties.getClass().getDeclaredMethod("isRegisterShutdownHook");
            getter.setAccessible(true);
            return (boolean) getter.invoke(properties);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(
                    "SpringApplication shutdown-hook property not accessible (Spring 升级改名？): " + e.getMessage(), e);
        }
    }

    /** 注册聚焦 handoff 测试所需的最小运行时配置 Bean。 */
    private static void registerRuntimeConfig(AnnotationConfigApplicationContext ctx) {
        ctx.register(PluginRuntimeConfiguration.class, PluginToggleProperties.class,
                PluginCatalogProperties.class, PluginRepositoryRegistry.class,
                PluginCredentialStore.class);
    }
}
