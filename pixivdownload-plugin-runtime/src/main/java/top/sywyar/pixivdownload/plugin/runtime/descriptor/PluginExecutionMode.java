package top.sywyar.pixivdownload.plugin.runtime.descriptor;

import java.util.Locale;

/** 插件代码的执行位置与能力边界。外置包必须显式声明。 */
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
            throw new IllegalArgumentException("plugin execution mode is required");
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
