package top.sywyar.pixivdownload.plugin;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;
import org.springframework.core.env.Environment;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.config.PluginCredentialStore;
import top.sywyar.pixivdownload.plugin.runtime.discovery.PluginDiscoveryResult;
import top.sywyar.pixivdownload.plugin.runtime.discovery.PluginInventory;
import top.sywyar.pixivdownload.plugin.runtime.PluginRuntimeManager;
import top.sywyar.pixivdownload.plugin.runtime.PluginRuntimeStatus;
import top.sywyar.pixivdownload.plugin.runtime.bootstrap.PluginBootstrapSession;
import top.sywyar.pixivdownload.plugin.runtime.bootstrap.PluginBootstrapSessionHandoff;
import top.sywyar.pixivdownload.plugin.runtime.bootstrap.PluginEnabledSnapshot;
import top.sywyar.pixivdownload.plugin.runtime.context.PluginApplicationContextFactory;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginApiRequirement;
import top.sywyar.pixivdownload.plugin.runtime.install.ExternalPluginInstaller;
import top.sywyar.pixivdownload.plugin.runtime.status.RequiredPluginPolicy;
import top.sywyar.pixivdownload.plugin.catalog.PluginCatalogTrustStores;
import top.sywyar.pixivdownload.plugin.catalog.repository.PluginRepositoryRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import top.sywyar.pixivdownload.plugin.lifecycle.ExternalPluginContextManager;
import top.sywyar.pixivdownload.plugin.management.PluginStatusService;
import top.sywyar.pixivdownload.plugin.recovery.RecoveryModeGate;
import top.sywyar.pixivdownload.plugin.recovery.RecoveryModeService;
import top.sywyar.pixivdownload.plugin.registry.PluginRegistry;

/**
 * 核心壳侧装配 PF4J 外置插件运行时。本配置不再自行 {@code new PluginRuntimeManager} / 调用
 * {@code recoverPendingTransactions()} / 调用 {@code start()}——恢复 + 构造唯一管理器 + 一次扫描 start 已收口到
 * {@link PluginBootstrapSession}（住 {@code pixivdownload-plugin-runtime} 模块、Spring-free、对 app 不可见地封装 PF4J）。
 *
 * <p>两条启动路径都经同一会话取得 manager / installer / status，避免重复扫描 / 启动 / 第二套 classloader：
 * <ul>
 *   <li>GUI 路径：进程在 Spring 启动前创建 PROCESS 拥有的会话（已 start），经 {@link PluginBootstrapSessionHandoff}
 *       交接给 Spring——本配置检测到交接载体即直接复用，不再 recover / start。</li>
 *   <li>headless 路径：无交接载体，本配置创建 CONTEXT 拥有的会话并 start（恢复事务 + 一次扫描）。</li>
 * </ul>
 *
 * <p>插件目录路径经 {@link RuntimeFiles#pluginsDirectory()} 解析。插件目录缺失 / 空 / 含坏包都<b>不</b>致核心壳启动失败
 *（由会话收敛为诊断）；外置插件经 {@link PluginRegistry} 与内置插件统一注册（来源标记区分），但<b>不</b>改变内置插件
 * 注册 / 禁用 / required 语义；外置 pluginId 与内置冲突由 {@link PluginRegistry} fail-fast。
 */
@Configuration
public class PluginRuntimeConfiguration {

    private static final int DOWNLOAD_WORKBENCH_REQUIRED_MAJOR = 1;
    private static final int DOWNLOAD_WORKBENCH_REQUIRED_MINOR = 0;

    /**
     * 唯一 bootstrap 会话 Bean。
     * <ul>
     *   <li>检测到 {@link PluginBootstrapSessionHandoff}（GUI 经 ApplicationContextInitializer 注册的 PROCESS 会话）→ 直接复用，
     *       不再 new / recover / start；</li>
     *   <li>headless 无交接载体 → 创建 CONTEXT 拥有的会话并 start（恢复事务 + 一次扫描）。</li>
     * </ul>
     * {@code destroyMethod = "closeForContext"}：Spring context 关闭（含启动失败 {@code destroyBeans}）时只关 CONTEXT 会话；
     * PROCESS（GUI）会话的 {@code closeForContext} 为 no-op，进程退出时另由 GUI 关闭。显式指定 destroyMethod 同时
     * 阻止 Spring 推断 {@code close()}（那会误关 PROCESS 会话）。
     */
    @Bean(destroyMethod = "closeForContext")
    public PluginBootstrapSession pluginBootstrapSession(
            ObjectProvider<PluginBootstrapSessionHandoff> handoff,
            PluginToggleProperties toggles,
            PluginRepositoryRegistry repositoryRegistry) {
        PluginBootstrapSessionHandoff existing = handoff.getIfAvailable();
        var verifierResolver = PluginCatalogTrustStores.verifierResolver(repositoryRegistry);
        if (existing != null) {
            existing.session().updateVerifierResolver(verifierResolver);
            return existing.session();
        }
        PluginBootstrapSession session = PluginBootstrapSession.createContext(
                RuntimeFiles.pluginsDirectory(), headlessEnabledSnapshot(toggles), verifierResolver);
        session.start();
        // 启动期快照持有插件实例 / classloader 引用，仅启动前短生命周期消费。headless 无主题消费者，接线完成后释放。
        session.releaseStartupSnapshot();
        return session;
    }

    /** headless：从 Spring 绑定的 plugins 开关表（缺项默认启用）解析启用快照，与 GUI 路径语义一致。 */
    private static PluginEnabledSnapshot headlessEnabledSnapshot(PluginToggleProperties toggles) {
        List<String> disabled = new ArrayList<>();
        for (Map.Entry<String, PluginToggleProperties.PluginToggle> entry : toggles.entrySet()) {
            if (entry.getValue() != null && !entry.getValue().isEnabled()) {
                disabled.add(entry.getKey());
            }
        }
        return PluginEnabledSnapshot.ofDisabled(disabled, List.of());
    }

    /**
     * 唯一 PF4J 运行时管理器（来自会话）。{@code destroyMethod = ""} 显式禁用 Spring 自动推断销毁方法——
     * {@link PluginRuntimeManager} 有 {@code shutdown()}，若不显式置空，Spring 会在每次 context 关闭（后端 stop / restart）
     * 自动调 {@code shutdown()}，从而销毁 PROCESS（GUI）路径下被复用的同一管理器 / 插件 / classloader。管理器的生命周期
     * 统一由会话所有（{@link PluginBootstrapSession#closeForContext()} / {@link PluginBootstrapSession#close()}），Spring
     * context 不得单独销毁它。
     */
    @Bean(destroyMethod = "")
    public PluginRuntimeManager pluginRuntimeManager(PluginBootstrapSession session) {
        return session.manager();
    }

    /** 启动扫描产出的运行时状态（来自会话的 start 结果）。 */
    @Bean(destroyMethod = "")
    public PluginRuntimeStatus pluginRuntimeStatus(PluginBootstrapSession session) {
        return session.status();
    }

    /**
     * 清点已启动外置插件的功能插件安装条目（统一描述符 + 兼容性基线状态 + classloader + 实例）与失败诊断，供
     * {@link PluginDiscoveryResult}（注册接入）与 {@link PluginStatusService}（状态报告）共用同一次清点结果。
     * 形参 {@code pluginRuntimeStatus} 仅用于排序（确保 start 先完成、PF4J 实例已就绪）。prototype——每次注入从
     * 同一 manager 动态清点，后端 restart 后拿到当前 inventory、不永远复用过期的初始快照，也不重新扫描 / start。
     */
    @Bean
    @Scope("prototype")
    public PluginInventory pluginInventory(PluginRuntimeManager pluginRuntimeManager,
                                           PluginRuntimeStatus pluginRuntimeStatus) {
        return pluginRuntimeManager.inspectPlugins();
    }

    /**
     * 投影出可接入 {@link PluginRegistry} 的外置功能插件发现结果：仅核心 API 兼容且已启动者进入 {@code discovered}，
     * 不兼容 / 失败者并入 {@code failures}（拒绝接入）。prototype——从同一 manager 动态清点。
     */
    @Bean
    @Scope("prototype")
    public PluginDiscoveryResult pluginDiscoveryResult(PluginRuntimeManager pluginRuntimeManager,
                                                       PluginRuntimeStatus pluginRuntimeStatus) {
        return pluginRuntimeManager.discoverFeaturePlugins();
    }

    /**
     * 必选插件策略：声明核心 / 发行视角下必须在场的插件 id 及其兼容版本范围、是否允许禁用、缺失 / 不兼容时的提示文案键。
     * {@link RecoveryModeService} 据本策略与插件状态报告判定是否进入恢复模式；本配置只声明策略数据，不在启动期据此
     * 拦截请求（拦截由恢复模式访问控制 {@link RecoveryModeGate} 执行）。必选性由本策略声明，而非由插件自称。
     * <ul>
     *   <li>下载工作台 {@code download-workbench} 恒为必选——它是官方 required 外置插件，由发行元数据和本核心策略
     *       约束，不依赖插件自称必选；缺失 / 不兼容 / 验签失败时核心进入恢复模式。</li>
     *   <li>{@code recovery-sentinel} 仅当显式开关 {@code pixivdownload.recovery-sentinel.required=true} 打开时才追加为
     *       必选项（默认关闭、不参与判定，故默认配置下核心不因它进入恢复模式）。它是一个不贡献任何功能的最小外置插件：
     *       开关打开后，若该外置插件缺失（未放入 {@code plugins/}）或被 {@code plugins.recovery-sentinel.enabled=false}
     *       禁用，核心即进入恢复模式——用于在真实外置插件加载链路上验证恢复模式判定与访问拦截。</li>
     * </ul>
     */
    @Bean
    public RequiredPluginPolicy requiredPluginPolicy(Environment environment) {
        List<RequiredPluginPolicy.RequiredPlugin> required = new ArrayList<>();
        required.add(new RequiredPluginPolicy.RequiredPlugin(
                "download-workbench",
                // 这里约束 download-workbench 自身版本，不是宿主 Plugin API 版本；
                // 宿主 API 升级不能把仍兼容的既有下载工作台误判为过期。
                PluginApiRequirement.of(
                        DOWNLOAD_WORKBENCH_REQUIRED_MAJOR,
                        DOWNLOAD_WORKBENCH_REQUIRED_MINOR),
                false,
                "plugin.recovery.missing.download-workbench"));
        if (environment.getProperty("pixivdownload.recovery-sentinel.required", Boolean.class, false)) {
            // 不约束插件自身版本（unspecified）：只要求它在场且启动即可——它是「存在性 / 启用性」探针，
            // 版本无关。其对核心 API 的 requires 兼容仍由发现桥接的接入兼容门独立校验。
            required.add(new RequiredPluginPolicy.RequiredPlugin(
                    "recovery-sentinel",
                    PluginApiRequirement.unspecified(),
                    false,
                    "plugin.recovery.blocked"));
        }
        return RequiredPluginPolicy.of(required);
    }

    /**
     * 外置插件安装器（来自会话，与运行时管理器同出一会话、共用同一插件目录）。处理上传 {@code .zip} / {@code .jar} 包的
     * Zip Slip 防护、布局校验、核心 API 兼容门与重复 / 升级 / 降级；POJO、构造无副作用、不创建目录。
     * {@code destroyMethod = ""}：会话派生对象，生命周期归会话所有，Spring context 不得单独销毁。
     */
    @Bean(destroyMethod = "")
    public ExternalPluginInstaller externalPluginInstaller(PluginBootstrapSession session) {
        return session.installer();
    }

    /**
     * 每外置插件子 {@code ApplicationContext} 工厂（无状态 POJO，住 {@code pixivdownload-plugin-runtime}）：为每个
     * 外置插件包建立子 context、父 context 为核心应用，在其中实例化插件声明的 {@code @Configuration} 配置类。
     * 子 context 的生命周期由 {@link ExternalPluginContextManager} 持有并与外置插件启停对齐。
     */
    @Bean
    public PluginApplicationContextFactory pluginApplicationContextFactory(PluginCredentialStore credentialStore) {
        return new PluginApplicationContextFactory(ownerPluginId -> {
            try {
                Map<String, Object> scoped = new java.util.LinkedHashMap<>();
                scoped.putAll(credentialStore.readAll(ownerPluginId));
                return scoped;
            } catch (java.io.IOException e) {
                throw new IllegalStateException(
                        "Failed to load plugin credentials for owner: " + ownerPluginId, e);
            }
        });
    }
}
