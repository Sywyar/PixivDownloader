package top.sywyar.pixivdownload.plugin;

/**
 * 外置插件 controller 动态注册被拒绝 / 失败时抛出。典型原因有二：① 某个 controller 映射（path + HTTP 方法）
 * 没有任何匹配的 {@code WebRouteContribution} 声明覆盖——这会让该路由「裸奔」（运行期被 {@code AuthFilter} 统一
 * 404），故注册期直接拒绝并给出清晰诊断，而不是带病注册一条不可达的 handler；② 逐条注册时某条
 * {@code registerMapping} 失败（典型：与父分发表已有 handler 冲突），此时本次已成功注册的映射会先全部回滚再带原因抛出。
 * 注册是按插件原子的：任一映射未声明、或任一 {@code registerMapping} 失败，都使整插件一条不注册。
 */
public class PluginControllerRegistrationException extends RuntimeException {

    public PluginControllerRegistrationException(String message) {
        super(message);
    }

    public PluginControllerRegistrationException(String message, Throwable cause) {
        super(message, cause);
    }
}
