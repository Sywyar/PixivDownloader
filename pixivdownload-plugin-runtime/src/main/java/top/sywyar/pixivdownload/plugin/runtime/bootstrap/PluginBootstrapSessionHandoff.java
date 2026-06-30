package top.sywyar.pixivdownload.plugin.runtime.bootstrap;

import java.util.Objects;

/**
 * GUI bootstrap 会话向 Spring context 交接的中性载体。
 *
 * <p>GUI 进程在 Spring 启动<b>前</b>创建 {@link PluginBootstrapSession}（PROCESS 拥有、已 start），经
 * {@code ApplicationContextInitializer} 把本载体注册为 Spring 单例；{@code PluginRuntimeConfiguration} 检测到本载体时
 * 直接复用其中的会话（不再 new / recover / start），从而保证 GUI bootstrap 与 Spring 共用同一 manager、同一 classloader，
 * 避免重复扫描 / 启动。headless 路径不注册本载体，配置类自行创建 CONTEXT 拥有的会话。
 *
 * <p>本类是 plugin-runtime bootstrap 的中性载体，不引入 static holder / ThreadLocal / 全局可变单例——载体实例由
 * Spring bean 工厂按 context 生命周期管理，随 context 销毁而释放，测试结束关闭 context 即不残留。
 */
public final class PluginBootstrapSessionHandoff {

    private final PluginBootstrapSession session;

    public PluginBootstrapSessionHandoff(PluginBootstrapSession session) {
        this.session = Objects.requireNonNull(session, "session");
    }

    public PluginBootstrapSession session() {
        return session;
    }
}
