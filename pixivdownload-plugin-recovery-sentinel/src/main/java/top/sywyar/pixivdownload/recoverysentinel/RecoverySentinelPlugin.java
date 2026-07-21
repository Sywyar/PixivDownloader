package top.sywyar.pixivdownload.recoverysentinel;

import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginKind;

/**
 * 最小功能插件：<b>不贡献任何东西</b>（无 controller / route / static / i18n / navigation / schema / 维护任务 /
 * 调度来源等，全部沿用接口默认空实现），只声明身份（id {@code recovery-sentinel}、类别 {@link PluginKind#FEATURE}）。
 *
 * <p>它的存在只是为了在真实外置插件加载链路上充当一个「可被核心策略声明为必选、又能被开关禁用 / 整包移除」的探针：
 * 核心据此能验证「必选插件缺失 / 被禁用 / 启动失败 / 版本不兼容时进入恢复模式」的判定与访问拦截。
 *
 * <p><b>插件契约不提供自声明必选性的入口</b>。必选性只能由核心侧的必选插件策略对插件 id 提出；默认策略不要求
 * 本插件。只有宿主显式把它加入必选策略后，{@code plugins.recovery-sentinel.enabled=false} 才用于复现
 * 「已安装但被禁用」并触发恢复模式。
 */
public class RecoverySentinelPlugin implements PixivFeaturePlugin {

    private static final String ID = "recovery-sentinel";

    @Override
    public String id() {
        return ID;
    }

    // 展示名 / 简介为纯 i18n key。本探针插件不贡献任何 i18n namespace，故 displayNamespace() 默认返回 null、这些 key
    // 不被解析（它不进入面向用户的页面 / 导航，消费端无 namespace 即回退到插件 id）；其存在仅为加载链路探针。
    @Override
    public String displayName() {
        return "plugin.name";
    }

    @Override
    public String description() {
        return "plugin.summary";
    }

    // 卡片展示用受控 token（非 URL / CSS / 远程资源；由插件管理页本地白名单映射）：恢复模式存在性探针。
    @Override
    public String iconKey() {
        return "shield";
    }

    @Override
    public String colorToken() {
        return "orange";
    }

    @Override
    public PluginKind kind() {
        return PluginKind.FEATURE;
    }

    // 必选性不属于插件契约，只由核心策略声明。
    // schema() / routes() / staticResources() / i18n() / navigation() / ... 全部沿用接口默认空实现：不贡献任何功能。
}
