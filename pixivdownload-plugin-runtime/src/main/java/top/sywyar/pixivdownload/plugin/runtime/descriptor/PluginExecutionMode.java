package top.sywyar.pixivdownload.plugin.runtime.descriptor;

import java.util.Locale;

/** 插件代码的执行位置与能力边界；未声明的外置包保持宿主进程完全信任兼容。 */
public enum PluginExecutionMode {
    HOST_PROCESS_FULL_TRUST("host-process-full-trust"),
    DECLARATIVE_PROCESS("declarative-process");

    private final String descriptorValue;

    PluginExecutionMode(String descriptorValue) {
        this.descriptorValue = descriptorValue;
    }

    public String descriptorValue() {
        return descriptorValue;
    }

    public static PluginExecutionMode parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return HOST_PROCESS_FULL_TRUST;
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        if ("trusted-in-process".equals(normalized)) {
            return HOST_PROCESS_FULL_TRUST;
        }
        if ("isolated-process".equals(normalized)) {
            return DECLARATIVE_PROCESS;
        }
        for (PluginExecutionMode mode : values()) {
            if (mode.descriptorValue.equals(normalized)) {
                return mode;
            }
        }
        throw new IllegalArgumentException("unsupported plugin execution mode: " + raw);
    }
}
