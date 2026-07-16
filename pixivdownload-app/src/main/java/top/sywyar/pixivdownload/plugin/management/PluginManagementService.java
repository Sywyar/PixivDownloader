package top.sywyar.pixivdownload.plugin.management;

import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import top.sywyar.pixivdownload.plugin.PluginToggleProperties;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginKind;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginApiRequirement;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginDependencyRef;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginDescriptor;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginLifecyclePolicy;
import top.sywyar.pixivdownload.plugin.runtime.install.ExternalPluginInstaller;
import top.sywyar.pixivdownload.plugin.runtime.install.model.InstalledPlugin;
import top.sywyar.pixivdownload.plugin.runtime.install.provenance.PluginProvenanceStore;
import top.sywyar.pixivdownload.plugin.runtime.status.PluginDiagnostic;
import top.sywyar.pixivdownload.plugin.runtime.status.PluginStatus;
import top.sywyar.pixivdownload.plugin.runtime.status.RequiredPluginPolicy;
import top.sywyar.pixivdownload.plugin.verification.PluginVerificationProjector;
import top.sywyar.pixivdownload.plugin.verification.PluginVerificationView;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import top.sywyar.pixivdownload.plugin.lifecycle.ClassifiedPluginLifecycleException;
import top.sywyar.pixivdownload.plugin.lifecycle.ExternalPluginLifecycleCoordinator;
import top.sywyar.pixivdownload.plugin.lifecycle.ExternalPluginOperation;
import top.sywyar.pixivdownload.plugin.lifecycle.ExternalPluginOperationSnapshot;
import top.sywyar.pixivdownload.plugin.lifecycle.PluginLifecycleException;
import top.sywyar.pixivdownload.plugin.lifecycle.PluginLifecycleService;
import top.sywyar.pixivdownload.plugin.lifecycle.PluginRuntimePhase;
import top.sywyar.pixivdownload.plugin.recovery.RecoveryModeService;
import top.sywyar.pixivdownload.plugin.BuiltInPlugins;

/**
 * 插件管理后端服务（admin-grade、不依赖任何 UI）：在只读状态报告 {@link PluginStatusService} 之上叠加外置插件
 * 运行期生命周期 {@link PluginLifecycleService} 的当前阶段，综合为「可由后端查询 + 可由管理操作驱动」的管理视图，
 * 并把 load / start / quiesce / stop / unload / remove / restart / reload 八个运行期动词收口为带前置守卫的统一入口
 * （{@link #perform}）。它是 Web / GUI 管理入口共用的后端落点——上层不各自实现插件扫描或生命周期编排。
 *
 * <h2>读模型（{@link #list()}）</h2>
 * 覆盖内置 + 外置 + 必选但未安装的全部插件 id（来自状态报告），每条附上：来源（built-in / external / not-installed）、
 * 运行期阶段（仅受管外置插件有）、是否受管、必选性 / 是否允许停用、可用动词与诊断说明。
 *
 * <h2>运行期动词（{@link #perform}）</h2>
 * 仅作用于<b>受管外置插件</b>（{@link PluginLifecycleService#managedPluginIds()}）。前置守卫：内置插件随主程序编译、
 * <b>不可</b>运行期热启停（拒 409）；经 {@code plugins.<id>.enabled} 配置禁用而未激活的外置插件不在受管范围（拒 409）；
 * 未知 id 拒 404；必选插件（{@link RequiredPluginPolicy} 声明 {@code allowDisable=false}）<b>不允许</b>被停用类动词
 * （quiesce / stop / unload / remove）降级（拒 409）。守卫通过后委托统一生命周期编排器执行；其非法状态流转
 * （{@link PluginLifecycleException}）转 409。
 *
 * <p>本服务只读 + 编排、<b>不持久化</b>启停状态：运行期动词在内存生效、不写配置；跨重启的禁用仍由
 * {@code plugins.<id>.enabled} 配置承载，不在本服务范围内改配置。它不触碰鉴权——HTTP 入口由 {@code AuthFilter}
 * 按 {@code /api/plugins/**} = ADMIN 独立校验。
 */
@Service
public class PluginManagementService {

    private final PluginStatusService pluginStatusService;
    private final PluginLifecycleService pluginLifecycleService;
    private final RequiredPluginPolicy requiredPluginPolicy;
    private final RecoveryModeService recoveryModeService;
    private final PluginToggleProperties pluginToggles;
    private final ExternalPluginLifecycleCoordinator coordinator;
    private final ExternalPluginInstaller installer;
    private final PluginProvenanceStore provenanceStore;

    public PluginManagementService(PluginStatusService pluginStatusService,
                                   PluginLifecycleService pluginLifecycleService,
                                   RequiredPluginPolicy requiredPluginPolicy,
                                   RecoveryModeService recoveryModeService) {
        this(pluginStatusService, pluginLifecycleService, requiredPluginPolicy, recoveryModeService,
                new PluginToggleProperties());
    }

    public PluginManagementService(PluginStatusService pluginStatusService,
                                   PluginLifecycleService pluginLifecycleService,
                                   RequiredPluginPolicy requiredPluginPolicy,
                                   RecoveryModeService recoveryModeService,
                                   PluginToggleProperties pluginToggles) {
        this.pluginStatusService = pluginStatusService;
        this.pluginLifecycleService = pluginLifecycleService;
        this.requiredPluginPolicy = requiredPluginPolicy;
        this.recoveryModeService = recoveryModeService;
        this.pluginToggles = pluginToggles;
        this.coordinator = null;
        this.installer = null;
        this.provenanceStore = null;
    }

    @Autowired
    public PluginManagementService(PluginStatusService pluginStatusService,
                                   PluginLifecycleService pluginLifecycleService,
                                   RequiredPluginPolicy requiredPluginPolicy,
                                   RecoveryModeService recoveryModeService,
                                   ExternalPluginLifecycleCoordinator coordinator,
                                   ExternalPluginInstaller installer,
                                   PluginToggleProperties pluginToggles) {
        this.pluginStatusService = pluginStatusService;
        this.pluginLifecycleService = pluginLifecycleService;
        this.requiredPluginPolicy = requiredPluginPolicy;
        this.recoveryModeService = recoveryModeService;
        this.pluginToggles = pluginToggles;
        this.coordinator = coordinator;
        this.installer = installer;
        this.provenanceStore = new PluginProvenanceStore(installer.pluginsDirectory());
    }

    /**
     * 计算当前插件管理视图：是否处于恢复模式 + 每个插件 id 的状态 / 来源 / 运行期阶段 / 是否受管 / 必选性 /
     * 可用动词 / 诊断说明。每次调用按当前状态报告与生命周期快照重新评估。
     */
    public PluginManagementReport list() {
        Set<String> managedIds = pluginLifecycleService.managedPluginIds();
        List<PluginManagementEntry> entries = new ArrayList<>();
        for (PluginDiagnostic diagnostic : pluginStatusService.report().diagnostics()) {
            entries.add(toEntry(diagnostic, managedIds));
        }
        return new PluginManagementReport(recoveryModeService.isActive(), List.copyOf(entries));
    }

    private PluginManagementEntry toEntry(PluginDiagnostic diagnostic, Set<String> managedIds) {
        String id = diagnostic.id();
        PluginDescriptor descriptor = diagnostic.descriptor();
        PluginRuntimePhase phase = pluginLifecycleService.phase(id).orElse(null);
        boolean builtIn = BuiltInPlugins.isBuiltIn(id);
        PluginLifecyclePolicy lifecyclePolicy = descriptor != null ? descriptor.lifecyclePolicy() : null;
        boolean installedOnly = descriptor != null && !builtIn
                && diagnostic.status() == PluginStatus.INSTALLED && phase == null;
        boolean managed = lifecyclePolicy == PluginLifecyclePolicy.HOT_RELOAD
                && (managedIds.contains(id) || phase == PluginRuntimePhase.UNLOADED
                || installedOnly);
        boolean allowDisable = !builtIn && allowDisable(id);
        boolean toggleable = descriptor != null && !builtIn && !requiredPluginPolicy.isRequired(id);
        ExternalPluginOperationSnapshot operation = coordinator != null
                ? coordinator.operation(id).orElse(null) : null;
        return new PluginManagementEntry(
                id,
                descriptor != null ? descriptor.displayNamespace() : null,
                descriptor != null ? descriptor.displayName() : null,
                descriptor != null ? descriptor.description() : null,
                iconTokenOf(descriptor),
                colorTokenOf(descriptor),
                descriptor != null ? descriptor.version() : null,
                descriptor != null ? descriptor.kind() : null,
                descriptor != null ? PluginApiRequirementView.from(descriptor.requires()) : null,
                dependencyViews(descriptor),
                sourceOf(id, descriptor),
                diagnostic.status(),
                phase,
                managed,
                diagnostic.requiredByPolicy(),
                allowDisable,
                availableActions(managed, phase, allowDisable, installedOnly),
                List.copyOf(diagnostic.messages()),
                verificationOf(id, descriptor),
                pluginLifecycleService.generation(id).orElse(null),
                operation != null ? operation.operation() : ExternalPluginOperation.IDLE,
                operation != null ? operation.transactionId() : null,
                operation != null ? operation.diagnostic() : null,
                lifecyclePolicy,
                pluginToggles.isEnabled(id),
                toggleable);
    }

    /** 描述符的插件间依赖声明投影（未安装的必选项无描述符 → 空列表）。 */
    private static List<PluginDependencyView> dependencyViews(PluginDescriptor descriptor) {
        if (descriptor == null) {
            return List.of();
        }
        return descriptor.dependencies().stream().map(PluginDependencyView::from).toList();
    }

    private static String sourceOf(String id, PluginDescriptor descriptor) {
        if (descriptor == null) {
            return "not-installed"; // 必选策略要求但未安装的 id：只有要求、没有描述符
        }
        return BuiltInPlugins.isBuiltIn(id) ? "built-in" : "external";
    }

    private PluginVerificationView verificationOf(String id, PluginDescriptor descriptor) {
        if (descriptor == null) {
            return PluginVerificationProjector.notInstalled();
        }
        if (BuiltInPlugins.isBuiltIn(id)) {
            return PluginVerificationProjector.builtInOfficial();
        }
        if (provenanceStore == null) {
            return PluginVerificationProjector.unverifiedLocal();
        }
        Optional<Path> artifact = pluginLifecycleService.artifactPath(id).or(() -> installedArtifact(id));
        return artifact.flatMap(path -> provenanceStore.read(path).map(PluginVerificationProjector::fromProvenance))
                .orElseGet(PluginVerificationProjector::unverifiedLocal);
    }

    private Optional<Path> installedArtifact(String id) {
        if (installer == null) {
            return Optional.empty();
        }
        return installer.listInstalled().stream()
                .filter(plugin -> id.equals(plugin.id()))
                .map(InstalledPlugin::path)
                .findFirst();
    }

    /**
     * 展示图标受控 token：取描述符声明的 token；无描述符（未安装的必选项）或包级描述符无 token 时回退到 plugin-api
     * 默认 token（{@link PixivFeaturePlugin#DEFAULT_ICON_KEY}），使每个条目恒有稳定 token（前端再按本地白名单渲染）。
     */
    private static String iconTokenOf(PluginDescriptor descriptor) {
        return descriptor != null && descriptor.iconKey() != null
                ? descriptor.iconKey() : PixivFeaturePlugin.DEFAULT_ICON_KEY;
    }

    /** 卡片强调色受控 token：语义同 {@link #iconTokenOf}，缺省回退到 {@link PixivFeaturePlugin#DEFAULT_COLOR_TOKEN}。 */
    private static String colorTokenOf(PluginDescriptor descriptor) {
        return descriptor != null && descriptor.colorToken() != null
                ? descriptor.colorToken() : PixivFeaturePlugin.DEFAULT_COLOR_TOKEN;
    }

    /**
     * 某插件当前可用的运行期动词（建议性，供管理入口呈现；最终正确性以 {@link #perform} 的守卫与
     * {@link PluginLifecycleService} 的流转校验为准）。不受管（内置 / 未激活外置 / 未安装）无运行期动词；
     * 受管外置插件按当前阶段给出启用类（恢复 / 重建足迹，必选插件也可用）与停用类（降级，必选插件不提供）动词。
     */
    private static List<String> availableActions(boolean managed, PluginRuntimePhase phase, boolean allowDisable,
                                                 boolean installedOnly) {
        if (!managed) {
            return List.of();
        }
        List<String> actions = new ArrayList<>();
        if (installedOnly) {
            actions.add(LifecycleAction.LOAD.token());
            if (allowDisable) {
                actions.add(LifecycleAction.REMOVE.token());
            }
            return List.copyOf(actions);
        }
        if (phase == null) {
            return List.of();
        }
        if (phase == PluginRuntimePhase.STOPPED || phase == PluginRuntimePhase.LOADED) {
            actions.add(LifecycleAction.START.token());
        }
        if (phase == PluginRuntimePhase.UNLOADED) {
            actions.add(LifecycleAction.LOAD.token());
        }
        if (phase != PluginRuntimePhase.UNLOADED) {
            actions.add(LifecycleAction.RESTART.token());
            actions.add(LifecycleAction.RELOAD.token());
        }
        if (allowDisable) {
            if (phase == PluginRuntimePhase.STARTED) {
                actions.add(LifecycleAction.QUIESCE.token());
            }
            if (phase == PluginRuntimePhase.STARTED || phase == PluginRuntimePhase.QUIESCED) {
                actions.add(LifecycleAction.STOP.token());
            }
            if (phase != PluginRuntimePhase.UNLOADED) {
                actions.add(LifecycleAction.UNLOAD.token());
            }
            actions.add(LifecycleAction.REMOVE.token());
        }
        return List.copyOf(actions);
    }

    /**
     * 执行一个运行期生命周期动词。前置守卫（受管 / 内置 / 未激活 / 未知 / 必选不可停用）不满足即抛
     * {@link PluginManagementException}；委托 {@link PluginLifecycleService} 时其非法流转
     * （{@link PluginLifecycleException}）转为 409。成功返回动词执行后的运行期阶段。
     */
    public PluginActionResult perform(String id, LifecycleAction action) {
        requireManaged(id, action);
        if (action.isDisabling() && !allowDisable(id)) {
            throw new PluginManagementException(PluginManagementErrorCode.REQUIRED_PLUGIN, id, action.token(),
                    pluginLifecycleService.phase(id).orElse(null),
                    "Required plugin cannot be disabled: " + id);
        }
        if (action.isEnabling()) {
            requireSatisfiedDependencies(id, action);
        }
        try {
            if (coordinator != null) {
                action.apply(coordinator, id);
            } else {
                action.apply(pluginLifecycleService, id);
            }
        } catch (PluginLifecycleException e) {
            PluginManagementErrorCode code = e instanceof ClassifiedPluginLifecycleException classified
                    ? classified.code() : PluginManagementErrorCode.ILLEGAL_TRANSITION;
            throw new PluginManagementException(code, id, action.token(),
                    pluginLifecycleService.phase(id).orElse(null), e.getMessage());
        }
        return new PluginActionResult(id, action.token(), pluginLifecycleService.phase(id).orElse(null));
    }

    /** 校验 id 是受管外置插件；否则按「内置 / 未激活外置 / 未知」分别给出明确拒绝（附尝试的动词 token 供诊断）。 */
    private void requireManaged(String id, LifecycleAction action) {
        if (BuiltInPlugins.isBuiltIn(id)) {
            throw new PluginManagementException(PluginManagementErrorCode.BUILT_IN_PLUGIN, id, action.token(), null,
                    "Built-in plugin cannot be hot-managed at runtime: " + id);
        }
        var report = pluginStatusService.report();
        var diagnostic = report != null ? report.byId(id) : Optional.<PluginDiagnostic>empty();
        PluginDescriptor descriptor = diagnostic.map(PluginDiagnostic::descriptor).orElse(null);
        if (descriptor != null && descriptor.lifecyclePolicy() != PluginLifecyclePolicy.HOT_RELOAD) {
            throw new PluginManagementException(PluginManagementErrorCode.RESTART_REQUIRED_PLUGIN,
                    id, action.token(), null,
                    "Plugin lifecycle policy does not allow hot management: "
                            + descriptor.lifecyclePolicy().token());
        }
        if (pluginLifecycleService.managedPluginIds().contains(id)) {
            return;
        }
        if (pluginLifecycleService.phase(id).orElse(null) == PluginRuntimePhase.UNLOADED) {
            return;
        }
        if (diagnostic.isPresent()
                && diagnostic.get().descriptor() != null
                && diagnostic.get().status() == PluginStatus.INSTALLED
                && (action == LifecycleAction.LOAD || action == LifecycleAction.REMOVE)) {
            return;
        }
        if (diagnostic.isPresent()) {
            throw new PluginManagementException(PluginManagementErrorCode.INACTIVE_PLUGIN, id, action.token(), null,
                    "External plugin is not currently active (disabled via config); runtime actions unavailable: " + id);
        }
        throw new PluginManagementException(PluginManagementErrorCode.UNKNOWN_PLUGIN, id, action.token(), null,
                "Unknown plugin: " + id);
    }

    private void requireSatisfiedDependencies(String id, LifecycleAction action) {
        var report = pluginStatusService.report();
        if (report == null) {
            return;
        }
        PluginDiagnostic target = report.byId(id).orElse(null);
        if (target == null || target.descriptor() == null) {
            return;
        }
        for (PluginDependencyRef dependency : target.descriptor().dependencies()) {
            if (dependency.optional()) {
                continue;
            }
            PluginDiagnostic depended = report.byId(dependency.pluginId()).orElse(null);
            if (depended == null || depended.descriptor() == null) {
                throw dependencyUnsatisfied(id, action, "missing required dependency: " + dependency.pluginId());
            }
            PluginApiRequirement required = dependency.requirement();
            PluginApiRequirement actual = PluginApiRequirement.parse(depended.descriptor().version());
            if (!required.isSatisfiedBy(actual.major(), actual.minor())) {
                throw dependencyUnsatisfied(id, action,
                        "required dependency " + dependency.pluginId() + " needs version "
                                + required.display() + ", but installed version is "
                                + depended.descriptor().version());
            }
            if (depended.status() != PluginStatus.STARTED) {
                throw dependencyUnsatisfied(id, action,
                        "required dependency " + dependency.pluginId()
                                + " is not available (status " + depended.status() + ")");
            }
        }
    }

    private PluginManagementException dependencyUnsatisfied(String id, LifecycleAction action, String detail) {
        return new PluginManagementException(PluginManagementErrorCode.DEPENDENCY_UNSATISFIED,
                id, action.token(), pluginLifecycleService.phase(id).orElse(null), detail);
    }

    private boolean allowDisable(String id) {
        return requiredPluginPolicy.requirement(id)
                .map(RequiredPluginPolicy.RequiredPlugin::allowDisable)
                .orElse(true);
    }

    /** 运行期生命周期动词（与 {@link PluginLifecycleService} 的核心内部 API 一一对应）。 */
    public enum LifecycleAction {
        LOAD("load", false),
        START("start", false),
        QUIESCE("quiesce", true),
        STOP("stop", true),
        UNLOAD("unload", true),
        REMOVE("remove", true),
        RESTART("restart", false),
        RELOAD("reload", false);

        private final String token;
        private final boolean disabling;

        LifecycleAction(String token, boolean disabling) {
            this.token = token;
            this.disabling = disabling;
        }

        /** 动词在 URL / 响应里的稳定标记（小写）。 */
        public String token() {
            return token;
        }

        /** 是否为停用 / 降级类动词（会让插件离开 {@link PluginRuntimePhase#STARTED}）：必选插件不允许。 */
        public boolean isDisabling() {
            return disabling;
        }

        /** 是否会让插件进入或恢复可服务状态，必须先满足非可选依赖。 */
        public boolean isEnabling() {
            return !disabling;
        }

        void apply(PluginLifecycleService service, String id) {
            switch (this) {
                case LOAD -> service.load(id);
                case START -> service.start(id);
                case QUIESCE -> service.quiesce(id);
                case STOP -> service.stop(id);
                case UNLOAD -> service.unload(id);
                case REMOVE -> throw new PluginLifecycleException("remove requires the external lifecycle coordinator");
                case RESTART -> service.restart(id);
                case RELOAD -> service.reload(id);
            }
        }

        void apply(ExternalPluginLifecycleCoordinator coordinator, String id) {
            switch (this) {
                case LOAD -> coordinator.load(id);
                case START -> coordinator.start(id);
                case QUIESCE -> coordinator.quiesce(id);
                case STOP -> coordinator.stop(id);
                case UNLOAD -> coordinator.unload(id);
                case REMOVE -> coordinator.remove(id);
                case RESTART -> coordinator.restart(id);
                case RELOAD -> coordinator.reload(id);
            }
        }
    }

    /**
     * 插件管理视图（对外）。
     *
     * @param recoveryMode 核心壳当前是否处于恢复模式（存在未满足的必选插件）
     * @param plugins      各插件状态条目（按状态报告评估顺序）
     */
    public record PluginManagementReport(boolean recoveryMode, List<PluginManagementEntry> plugins) {
    }

    /**
     * 单个插件管理条目（对外）。{@code displayNameKey} / {@code descriptionKey} 是<b>纯</b> i18n key、
     * {@code displayNamespace} 是其所在 namespace（前端在该 namespace 按当前语言解析、不在后端 bake 文案）；
     * {@code iconKey} / {@code colorToken} 是<b>受控展示 token</b>（不是 URL / CSS / 远程资源，前端按共享 token 映射、
     * 未知值回退默认），仅供本地卡片展示、非插件市场字段；{@code messages} 是评估器给出的诊断说明（自由文本、供管理诊断）。
     *
     * @param id               插件 id
     * @param displayNamespace 展示名称 / 简介所在 i18n namespace（前端在此 namespace 解析 {@code displayNameKey} / {@code descriptionKey}；未安装的必选项 / 无 namespace 时为 {@code null}）
     * @param displayNameKey   展示名称 i18n key（<b>纯 key</b>；未安装的必选项为 {@code null}）
     * @param descriptionKey   简介 i18n key（<b>纯 key</b>，在 {@code displayNamespace} 内解析；未安装 / 无简介时为 {@code null}，前端优雅回退）
     * @param iconKey          展示图标受控 token（恒非空：缺省回退到 plugin-api 默认 token，前端按本地白名单渲染）
     * @param colorToken       卡片强调色受控 token（恒非空：缺省回退到 plugin-api 默认 token，前端映射到固定 CSS class）
     * @param version          插件版本（未安装的必选项为 {@code null}）
     * @param kind             插件类别（未安装的必选项为 {@code null}）
     * @param apiRequirement   对核心 API 的版本要求投影（未安装的必选项无描述符时为 {@code null}）
     * @param dependencies     对其它插件的依赖声明投影（无描述符 / 无依赖时为空列表）
     * @param source           来源：{@code built-in} / {@code external} / {@code not-installed}
     * @param status           评估状态
     * @param runtimePhase     运行期阶段（仅受管外置插件有，否则 {@code null}）
     * @param managed          是否受运行期生命周期管理（可施加运行期动词）
     * @param requiredByPolicy 是否被必选策略声明为必选
     * @param allowDisable     是否允许被停用（必选且不可停用时为 {@code false}）
     * @param availableActions 当前建议可用的运行期动词（建议性）
     * @param messages         诊断说明
     * @param verification     验签状态投影（前端只消费本字段，不自行推断可信来源）
     * @param lifecyclePolicy  描述符声明的生命周期策略（无描述符时为 {@code null}）
     * @param configuredEnabled 当前配置中的期望启用态（缺项默认 {@code true}）
     * @param toggleable       是否允许管理入口修改期望启用态（内置 / 必选 / 无描述符均为 {@code false}）
     */
    public record PluginManagementEntry(
            String id,
            String displayNamespace,
            String displayNameKey,
            String descriptionKey,
            String iconKey,
            String colorToken,
            String version,
            PluginKind kind,
            PluginApiRequirementView apiRequirement,
            List<PluginDependencyView> dependencies,
            String source,
            PluginStatus status,
            PluginRuntimePhase runtimePhase,
            boolean managed,
            boolean requiredByPolicy,
            boolean allowDisable,
            List<String> availableActions,
            List<String> messages,
            PluginVerificationView verification,
            Long generation,
            ExternalPluginOperation operation,
            String transactionId,
            String operationDiagnostic,
            PluginLifecyclePolicy lifecyclePolicy,
            boolean configuredEnabled,
            boolean toggleable) {

        /** 兼容不关心运行操作元数据的调用方与测试夹具。 */
        public PluginManagementEntry(
                String id,
                String displayNamespace,
                String displayNameKey,
                String descriptionKey,
                String iconKey,
                String colorToken,
                String version,
                PluginKind kind,
                PluginApiRequirementView apiRequirement,
                List<PluginDependencyView> dependencies,
                String source,
                PluginStatus status,
                PluginRuntimePhase runtimePhase,
                boolean managed,
                boolean requiredByPolicy,
                boolean allowDisable,
                List<String> availableActions,
                List<String> messages) {
            this(id, displayNamespace, displayNameKey, descriptionKey, iconKey, colorToken, version, kind,
                    apiRequirement, dependencies, source, status, runtimePhase, managed, requiredByPolicy,
                    allowDisable, availableActions, messages, PluginVerificationProjector.unverifiedLocal(),
                    null, ExternalPluginOperation.IDLE, null, null,
                    PluginLifecyclePolicy.HOT_RELOAD, true, false);
        }

        /** 兼容需要显式断言启用配置与生命周期策略、但不关心运行操作元数据的调用方。 */
        public PluginManagementEntry(
                String id,
                String displayNamespace,
                String displayNameKey,
                String descriptionKey,
                String iconKey,
                String colorToken,
                String version,
                PluginKind kind,
                PluginApiRequirementView apiRequirement,
                List<PluginDependencyView> dependencies,
                String source,
                PluginStatus status,
                PluginRuntimePhase runtimePhase,
                boolean managed,
                boolean requiredByPolicy,
                boolean allowDisable,
                List<String> availableActions,
                List<String> messages,
                PluginLifecyclePolicy lifecyclePolicy,
                boolean configuredEnabled,
                boolean toggleable) {
            this(id, displayNamespace, displayNameKey, descriptionKey, iconKey, colorToken, version, kind,
                    apiRequirement, dependencies, source, status, runtimePhase, managed, requiredByPolicy,
                    allowDisable, availableActions, messages, PluginVerificationProjector.unverifiedLocal(),
                    null, ExternalPluginOperation.IDLE, null, null,
                    lifecyclePolicy, configuredEnabled, toggleable);
        }
    }

    /**
     * 插件对核心 API 的版本要求投影（对外）：从 {@link PluginDescriptor#requires()} 映射，不泄露内部描述符模型。
     *
     * @param specified 是否声明了 {@code requires}（未声明视为兼容任何版本）
     * @param satisfied 当前核心 API 是否满足该要求（未声明恒为 {@code true}，无法解析恒为 {@code false}）
     * @param required  人类可读的版本要求（未声明为 {@code "(unspecified)"}，无法解析时回显原始串）
     */
    public record PluginApiRequirementView(boolean specified, boolean satisfied, String required) {

        static PluginApiRequirementView from(PluginApiRequirement requirement) {
            return new PluginApiRequirementView(
                    requirement.present(), requirement.isSatisfiedByCurrentApi(), requirement.display());
        }
    }

    /**
     * 插件对另一个插件的依赖声明投影（对外）：从 {@link PluginDependencyRef} 映射，不泄露内部描述符模型。
     *
     * @param pluginId       被依赖插件 id
     * @param versionSupport 版本要求声明（{@code *} / 空表示不限版本）
     * @param optional       是否为可选依赖（缺失不阻止依赖方启动）
     */
    public record PluginDependencyView(String pluginId, String versionSupport, boolean optional) {

        public static PluginDependencyView from(PluginDependencyRef dependency) {
            return new PluginDependencyView(
                    dependency.pluginId(), dependency.versionSupport(), dependency.optional());
        }
    }

    /**
     * 运行期动词执行结果（对外）。
     *
     * @param id     插件 id
     * @param action 执行的动词标记
     * @param phase  执行后的运行期阶段（{@code null} 表示未受管，理论上不会出现在成功路径）
     */
    public record PluginActionResult(String id, String action, PluginRuntimePhase phase) {
    }
}
