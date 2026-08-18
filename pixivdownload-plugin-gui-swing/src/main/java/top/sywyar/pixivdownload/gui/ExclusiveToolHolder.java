package top.sywyar.pixivdownload.gui;

/**
 * 记录当前独占后端（已停止后端以取得 SQLite 独占访问）的工具名称，供状态页展示。
 *
 * <p>{@code ToolsPanel} 在启动 / 结束独占工具时写入；{@code StatusPanel} 在后端处于
 * 停止 / 正在停止态时读取，用于把「已停止」细化为「使用某工具导致后端停止」，并按
 * {@link #startedAtMillis()} 展示该工具已占用后端的实时秒数。
 * 两个面板分处不同实例、且工具完成回调可能绑定在被语言热重载替换掉的旧面板上，
 * 故用进程内静态持有者作为跨面板的单一事实源。
 */
public final class ExclusiveToolHolder {

    private static volatile String toolName;
    private static volatile long startedAtMillis;

    private ExclusiveToolHolder() {
    }

    /** 当前独占工具的显示名；无则返回 {@code null}。 */
    public static String get() {
        return toolName;
    }

    /** 当前独占工具开始占用后端的时间（epoch millis）；无则返回 0。 */
    public static long startedAtMillis() {
        return startedAtMillis;
    }

    public static void set(String toolName) {
        if (toolName != null && !toolName.isBlank()) {
            // 仅在从「无工具」切换到「有工具」时记录起点，避免同一工具被重复 set 时重置计时。
            if (ExclusiveToolHolder.toolName == null) {
                startedAtMillis = System.currentTimeMillis();
            }
            ExclusiveToolHolder.toolName = toolName;
        } else {
            ExclusiveToolHolder.toolName = null;
            startedAtMillis = 0L;
        }
    }
}
