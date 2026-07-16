package top.sywyar.pixivdownload.plugin.runtime.descriptor;

/**
 * 插件包声明的运行期生效策略。稳定 token 写入 {@code plugin.properties}，宿主只消费策略、
 * 不按具体插件 id 推断生命周期边界。
 */
public enum PluginLifecyclePolicy {

    HOT_RELOAD("hot-reload"),
    BACKEND_RESTART("backend-restart"),
    PROCESS_RESTART("process-restart");

    private final String token;

    PluginLifecyclePolicy(String token) {
        this.token = token;
    }

    /** 写入插件清单的稳定 token。 */
    public String token() {
        return token;
    }

    /** 当前策略是否要求某一级重启后才能完成生效。 */
    public boolean requiresRestart() {
        return this != HOT_RELOAD;
    }

    /** 当前策略是否要求重启整个软件进程。 */
    public boolean requiresProcessRestart() {
        return this == PROCESS_RESTART;
    }

    /**
     * 解析清单 token。旧插件未声明或只声明空白值时保持热重载兼容；显式未知值必须拒绝，
     * 避免拼写错误静默降级成错误的生效策略。
     */
    public static PluginLifecyclePolicy parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return HOT_RELOAD;
        }
        String normalized = raw.trim();
        for (PluginLifecyclePolicy policy : values()) {
            if (policy.token.equals(normalized)) {
                return policy;
            }
        }
        throw new IllegalArgumentException("unsupported plugin lifecycle policy: " + normalized);
    }
}
