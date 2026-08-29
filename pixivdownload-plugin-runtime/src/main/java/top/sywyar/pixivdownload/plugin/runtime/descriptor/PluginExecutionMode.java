package top.sywyar.pixivdownload.plugin.runtime.descriptor;

import java.util.Locale;

/** 插件代码的进程隔离级别；未声明的外置包按隔离进程处理。 */
public enum PluginExecutionMode {
    ISOLATED_PROCESS("isolated-process"),
    TRUSTED_IN_PROCESS("trusted-in-process");

    private final String descriptorValue;

    PluginExecutionMode(String descriptorValue) {
        this.descriptorValue = descriptorValue;
    }

    public String descriptorValue() {
        return descriptorValue;
    }

    public static PluginExecutionMode parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return ISOLATED_PROCESS;
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        for (PluginExecutionMode mode : values()) {
            if (mode.descriptorValue.equals(normalized)) {
                return mode;
            }
        }
        throw new IllegalArgumentException("unsupported plugin execution mode: " + raw);
    }
}
