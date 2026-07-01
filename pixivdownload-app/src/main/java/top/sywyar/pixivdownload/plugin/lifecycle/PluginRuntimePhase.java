package top.sywyar.pixivdownload.plugin.lifecycle;


/**
 * 外置插件在核心壳<b>服务侧</b>的运行期生命周期阶段：描述一个已接入插件此刻是否在对外服务、是否被静默
 * （quiesce）、是否已停止服务、或已从核心注册中心卸下。它是运行期热启停 / quiesce 状态机
 * （{@link PluginLifecycleState} / {@link PluginLifecycleService}）的状态，与描述符 / 兼容性视角的
 * {@code PluginStatus}（plugin-runtime）<b>正交</b>——后者描述「核心眼中插件包的健康 / 兼容状态」，本枚举只描述
 * 「服务侧此刻是否接收请求」。
 *
 * <p>合法流转（见 {@link #canTransitionTo}）：
 * <pre>
 *   load:    UNLOADED → LOADED
 *   start:   LOADED / STOPPED → STARTED
 *   quiesce: STARTED → QUIESCED
 *   stop:    STARTED / QUIESCED → STOPPED
 *   unload:  STOPPED / LOADED → UNLOADED
 *   reload:  STARTED / QUIESCED / STOPPED →（stop 后再 start）→ STARTED
 * </pre>
 */
public enum PluginRuntimePhase {

    /** 已从核心注册中心卸下（不在活动 / 安装快照中）。需先 load 才能再次启动。 */
    UNLOADED,

    /** 已接入核心注册中心、可被启动，但服务足迹（子 context / controller / web 贡献）尚未建立。 */
    LOADED,

    /** 正在对外服务：子 context 已建立、controller 与 web 贡献已注册，接收新请求。 */
    STARTED,

    /** 已静默：服务足迹仍在，但不再接收新请求（命中其路由的新请求被网关转为「插件不可用」），是停止前的过渡态。 */
    QUIESCED,

    /** 已停止服务：服务足迹已拆除（controller / web 贡献已注销、子 context 已关闭），但仍在核心注册中心、可再次 start。 */
    STOPPED;

    /** 是否接收新请求（仅 {@link #STARTED}）。{@link #QUIESCED} 起即不再接收。 */
    public boolean acceptsNewRequests() {
        return this == STARTED;
    }

    /** 是否处于「已声明路由仍在、但应转为插件不可用」的静默态（请求网关据此拒绝新请求）。 */
    public boolean isQuiesced() {
        return this == QUIESCED;
    }

    /** 该阶段是否允许直接流转到 {@code target}（状态机合法边）。 */
    public boolean canTransitionTo(PluginRuntimePhase target) {
        if (target == null) {
            return false;
        }
        return switch (this) {
            case UNLOADED -> target == LOADED;
            case LOADED -> target == STARTED || target == UNLOADED;
            case STARTED -> target == QUIESCED || target == STOPPED;
            case QUIESCED -> target == STOPPED;
            case STOPPED -> target == STARTED || target == UNLOADED;
        };
    }
}
