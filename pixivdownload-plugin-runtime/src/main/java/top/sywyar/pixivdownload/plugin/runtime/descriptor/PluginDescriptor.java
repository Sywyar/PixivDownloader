package top.sywyar.pixivdownload.plugin.runtime.descriptor;

import top.sywyar.pixivdownload.plugin.api.PluginApiVersion;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginKind;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 统一插件描述符：把外置插件框架描述符（PF4J {@code plugin.properties} 的 {@code id} / {@code version} /
 * {@code requires} / {@code dependencies} / {@code plugin-class}）与 {@link PixivFeaturePlugin} 元数据
 * （{@code displayName} / {@code kind}）映射到同一个中性、不可变的描述模型，供兼容性校验与状态模型统一消费。
 *
 * <p>本 record 是核心壳内部模型，<b>不</b>跨插件边界传递，因此允许引用 {@code plugin.api}（{@link PluginKind} /
 * {@link PixivFeaturePlugin}）与 JDK；但<b>不</b>引用任何插件加载框架（PF4J）类型——PF4J 描述符到本模型的映射收口在
 * 发现桥接里。
 *
 * <p>同一外置插件包（一个 PF4J pluginId = {@link #sourcePluginId}）可贡献多个 {@link PixivFeaturePlugin}；
 * 本描述符按<b>功能插件粒度</b>建模：{@link #id} 是功能插件 id（核心注册中心去重 / 排序的键），而
 * {@link #version} / {@link #requires} / {@link #dependencies} / {@link #pluginClass} 来自其所属插件包、同一包内
 * 各功能插件共享。内置插件没有独立插件包，{@link #sourcePluginId} 等于 {@link #id}。
 *
 * @param id             插件 id（功能插件 id；小写短横线风格）
 * @param sourcePluginId 承载该功能插件的插件包 id（外置=PF4J pluginId；内置=同 {@link #id}）
 * @param version        插件版本（外置=plugin.properties 版本；内置=核心 API 契约版本）
 * @param requires       所需核心 API 版本要求（解析自 {@code requires}，未声明则 {@link PluginApiRequirement#unspecified()}）
 * @param dependencies   对其它插件的依赖声明（中性载体）
 * @param pluginClass      插件主类全限定名（外置=PF4J {@code Plugin-Class}；内置=插件实现类名；可空）
 * @param displayNamespace 展示名称 / 简介所在的 i18n namespace（来自 {@link PixivFeaturePlugin#displayNamespace()}；可空）
 * @param displayName      展示名称 i18n key（纯 key，来自 {@link PixivFeaturePlugin#displayName()}）
 * @param description      简介 i18n key（纯 key，来自 {@link PixivFeaturePlugin#description()}；在 {@link #displayNamespace} 内解析；无功能插件实例的包级描述符为 {@code null}）
 * @param iconKey          展示图标的受控 token（来自 {@link PixivFeaturePlugin#iconKey()}；包级描述符为 {@code null}，由消费端回退默认）
 * @param colorToken       卡片强调色的受控 token（来自 {@link PixivFeaturePlugin#colorToken()}；包级描述符为 {@code null}，由消费端回退默认）
 * @param kind             插件类别
 */
public record PluginDescriptor(
        String id,
        String sourcePluginId,
        String version,
        PluginApiRequirement requires,
        List<PluginDependencyRef> dependencies,
        String pluginClass,
        String displayNamespace,
        String displayName,
        String description,
        String iconKey,
        String colorToken,
        PluginKind kind) {

    /** 插件 id 规范：小写短横线，如 {@code download-workbench}（与核心注册中心一致）。 */
    public static final Pattern ID_PATTERN = Pattern.compile("[a-z][a-z0-9]*(-[a-z0-9]+)*");

    /** Java 全限定类名规范（用于校验 {@code plugin-class} 合法性）。 */
    private static final Pattern CLASS_NAME_PATTERN =
            Pattern.compile("([A-Za-z_$][A-Za-z0-9_$]*)(\\.[A-Za-z_$][A-Za-z0-9_$]*)*");

    /**
     * 外置插件版本号规范：至少 {@code major.minor.patch} 三段数字的 semver，可选 {@code -prerelease} 段与
     * {@code +build} 元数据段（如 {@code 1.0.0}、{@code 2.3.1-SNAPSHOT}、{@code 1.0.0-rc.1+build.5}）。
     * 外置插件由插件框架据版本号解析依赖与排序，残缺（如 {@code 1.0}）或非版本字符串（如 {@code latest}）
     * 必须在接入前被拒。纯 JDK 正则，不引入第三方 semver 库（描述符模型保持 JDK + {@code plugin.api}）。
     */
    private static final Pattern SEMVER_PATTERN = Pattern.compile(
            "\\d+\\.\\d+\\.\\d+"
                    + "(?:-[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?"
                    + "(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?");

    public PluginDescriptor {
        requires = requires != null ? requires : PluginApiRequirement.unspecified();
        dependencies = dependencies != null ? List.copyOf(dependencies) : List.of();
    }

    /**
     * 从内置功能插件构造描述符：版本取当前核心 API 契约版本、{@code requires} 取当前核心 API（恒兼容）、无插件依赖、
     * {@code pluginClass} 取插件实现类名、{@code displayName} / {@code description} / {@code iconKey} / {@code colorToken} /
     * {@code kind} 取插件自身元数据。内置插件随主程序编译、与核心同契约版本，故恒兼容。
     */
    public static PluginDescriptor forBuiltIn(PixivFeaturePlugin plugin) {
        Objects.requireNonNull(plugin, "plugin");
        return new PluginDescriptor(
                plugin.id(),
                plugin.id(),
                PluginApiVersion.VERSION,
                PluginApiRequirement.of(PluginApiVersion.MAJOR, PluginApiVersion.MINOR),
                List.of(),
                plugin.getClass().getName(),
                plugin.displayNamespace(),
                plugin.displayName(),
                plugin.description(),
                plugin.iconKey(),
                plugin.colorToken(),
                plugin.kind());
    }

    /** 该描述符声明的核心 API 版本要求是否被当前核心满足（{@code requires} 兼容性）。 */
    public boolean isApiCompatible() {
        return requires.isSatisfiedByCurrentApi();
    }

    /**
     * 通用字段校验（内置与外置都适用）：id 规范、displayName 非空、kind 非空、{@code requires} 可解析、
     * {@code plugin-class}（若声明）为合法 FQN、各依赖 id 规范。返回错误列表，空表示通过。
     */
    public List<String> validationErrors() {
        List<String> errors = new ArrayList<>();
        if (id == null || !ID_PATTERN.matcher(id).matches()) {
            errors.add("invalid plugin id: " + id);
        }
        if (displayName == null || displayName.isBlank()) {
            errors.add("displayName must not be blank");
        }
        if (kind == null) {
            errors.add("kind must not be null");
        }
        if (requires != null && requires.present() && !requires.valid()) {
            errors.add("unparseable requires: " + requires.raw());
        }
        if (pluginClass != null && !pluginClass.isBlank() && !CLASS_NAME_PATTERN.matcher(pluginClass).matches()) {
            errors.add("invalid plugin-class: " + pluginClass);
        }
        for (PluginDependencyRef dependency : dependencies) {
            if (dependency.pluginId() == null || !ID_PATTERN.matcher(dependency.pluginId()).matches()) {
                errors.add("invalid dependency plugin id: " + dependency.pluginId());
            }
        }
        return errors;
    }

    /**
     * 外置插件附加完整性校验：在 {@link #validationErrors()} 基础上额外要求 {@code version} 与
     * {@code plugin-class} 必须声明（外置插件由 PF4J 据 {@code plugin-class} 实例化、据 {@code version} 解析依赖）。
     * {@code version} 不仅须非空，还须是合法 semver（至少 {@code major.minor.patch}，见 {@link #SEMVER_PATTERN}）——
     * 残缺或非版本字符串会让插件框架的依赖解析 / 排序失效，故在接入前判为外置完整性错误。
     */
    public List<String> externalValidationErrors() {
        List<String> errors = validationErrors();
        if (version == null || version.isBlank()) {
            errors.add("version must not be blank for an external plugin");
        } else if (!SEMVER_PATTERN.matcher(version.trim()).matches()) {
            errors.add("invalid version for an external plugin (expected semver major.minor.patch): " + version);
        }
        if (pluginClass == null || pluginClass.isBlank()) {
            errors.add("plugin-class must not be blank for an external plugin");
        }
        return errors;
    }
}
