package top.sywyar.pixivdownload.guicompose.model;

import java.util.LinkedHashMap;
import java.util.Map;
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode.OnboardingProxySettings;

/** 工具表单与动作入口共用的输入检查。 */
public final class ToolInputValidation {
    private ToolInputValidation() {}

    public static Map<String, String> errors(String tool, Map<String, String> values) {
        Map<String, String> errors = new LinkedHashMap<>();
        String prefix = "tools." + tool + ".";
        if (values.getOrDefault(prefix + "db", "").isBlank())
            errors.put(prefix + "db", "gui.tools.validation.database-path.required");
        if (tool.equals("migration") && values.getOrDefault(prefix + "root", "").isBlank())
            errors.put(prefix + "root", "gui.tools.validation.root-folder.required");
        if (tool.equals("backfill")) {
            if (Boolean.parseBoolean(values.getOrDefault(prefix + "proxy", "true"))) {
                if (!OnboardingProxySettings.validHost(values.getOrDefault(prefix + "proxy-host", "")))
                    errors.put(prefix + "proxy-host", "gui.compose.tools.workspace.invalid-proxy");
                if (!OnboardingProxySettings.validPort(values.getOrDefault(prefix + "proxy-port", "")))
                    errors.put(prefix + "proxy-port", "gui.compose.tools.workspace.invalid-proxy");
            }
            for (String field : new String[]{"delay", "limit"}) {
                try {
                    if (Integer.parseInt(values.getOrDefault(prefix + field, "").trim()) < 0)
                        throw new NumberFormatException();
                } catch (NumberFormatException invalid) {
                    errors.put(prefix + field, "gui.compose.tools.workspace.invalid-number");
                }
            }
        }
        return Map.copyOf(errors);
    }
}
