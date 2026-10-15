package top.sywyar.pixivdownload.guiswing;

import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiContext;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiHost;

import java.util.Objects;

/** Process-lifetime context installed before any Swing component is created. */
public final class SwingHost {
    private static volatile DesktopUiContext context;

    private SwingHost() {}

    public static void install(DesktopUiContext value) {
        context = Objects.requireNonNull(value, "value");
    }

    public static boolean installed() {
        return context != null;
    }

    public static DesktopUiContext context() {
        DesktopUiContext value = context;
        if (value == null) throw new IllegalStateException("Swing desktop UI host is not installed");
        return value;
    }

    public static DesktopUiHost host() {
        return context().host();
    }

    /** 读取已保存的宿主配置，缺项沿用字段目录的默认值，不产生写入。 */
    public static java.util.Map<String, String> readConfiguration(java.util.Collection<String> keys) throws java.io.IOException {
        var values = new java.util.LinkedHashMap<String, String>();
        for (var field : host().coreConfigFields()) {
            if (keys.contains(field.key()) && field.defaultValue() != null)
                values.put(field.key(), field.defaultValue());
        }
        values.putAll(host().applicationConfig().readAll(keys));
        return values;
    }
}
