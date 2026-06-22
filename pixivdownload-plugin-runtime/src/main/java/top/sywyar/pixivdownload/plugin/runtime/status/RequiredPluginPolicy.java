package top.sywyar.pixivdownload.plugin.runtime.status;

import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginApiRequirement;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 必选插件策略（数据模型）：声明哪些 pluginId 是核心 / 发行 / catalog 视角下<b>必选</b>的，以及其兼容版本范围、
 * 是否允许禁用、缺失时的提示文案键。
 *
 * <p><b>必选性不是插件自声明的</b>——它由核心策略、发行元数据或 catalog policy 对 pluginId 提出要求，而非由外置
 * 插件在自己的描述符里宣称（外置插件无法自封必选）。{@link PluginStatusEvaluator} 据本策略把「必选但未安装 /
 * 已安装但不兼容」的 pluginId 标为 {@link PluginStatus#MISSING_REQUIRED} / {@link PluginStatus#INCOMPATIBLE_REQUIRED}。
 *
 * <p>本类只承载策略数据与诊断推导；它<b>不</b>自行改变核心启动 / 路由开放——是否据未满足的必选项进入恢复模式由
 * 上层访问控制消费方判定。
 */
public final class RequiredPluginPolicy {

    /**
     * 单条必选插件要求。
     *
     * @param pluginId          必选插件 id
     * @param compatibleVersion 兼容版本范围（{@link PluginApiRequirement#unspecified()} 表示不限版本）
     * @param allowDisable      是否允许被禁用（必选插件通常为 {@code false}）
     * @param missingMessageKey 缺失 / 不兼容时给用户的提示文案 i18n key
     */
    public record RequiredPlugin(
            String pluginId,
            PluginApiRequirement compatibleVersion,
            boolean allowDisable,
            String missingMessageKey) {

        public RequiredPlugin {
            Objects.requireNonNull(pluginId, "pluginId");
            compatibleVersion = compatibleVersion != null ? compatibleVersion : PluginApiRequirement.unspecified();
        }
    }

    private static final RequiredPluginPolicy EMPTY = new RequiredPluginPolicy(List.of());

    private final Map<String, RequiredPlugin> byId;

    private RequiredPluginPolicy(List<RequiredPlugin> required) {
        Map<String, RequiredPlugin> map = new LinkedHashMap<>();
        for (RequiredPlugin requiredPlugin : required) {
            map.put(requiredPlugin.pluginId(), requiredPlugin);
        }
        this.byId = Map.copyOf(map);
    }

    /** 空策略：无任何 pluginId 被声明为必选。 */
    public static RequiredPluginPolicy empty() {
        return EMPTY;
    }

    /** 以给定必选项构造策略。 */
    public static RequiredPluginPolicy of(List<RequiredPlugin> required) {
        if (required == null || required.isEmpty()) {
            return EMPTY;
        }
        return new RequiredPluginPolicy(required);
    }

    /** 全部必选项（按声明顺序）。 */
    public List<RequiredPlugin> required() {
        return List.copyOf(byId.values());
    }

    /** 给定 id 是否为必选。 */
    public boolean isRequired(String pluginId) {
        return byId.containsKey(pluginId);
    }

    /** 给定 id 的必选要求（非必选时为空）。 */
    public Optional<RequiredPlugin> requirement(String pluginId) {
        return Optional.ofNullable(byId.get(pluginId));
    }

    /** 是否未声明任何必选插件。 */
    public boolean isEmpty() {
        return byId.isEmpty();
    }
}
