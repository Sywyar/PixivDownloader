package top.sywyar.pixivdownload.guicompose.model;

import java.util.LinkedHashMap;
import java.util.Map;
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode.OnboardingProxySettings;

/** 安全页面与提交入口共用本地检查，当前密码仍由服务端验证。 */
public final class SecurityInputValidation {
    private SecurityInputValidation() {}

    public static Map<String, String> passwordErrors(String current, String next, String confirm, int minimum) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (current.isEmpty()) errors.put("current", "current-required");
        if (next.isBlank()) errors.put("new", "new-required");
        else if (next.length() < minimum) errors.put("new", "invalid-length");
        else if (next.equals(current)) errors.put("new", "same-password");
        if (confirm.isEmpty()) errors.put("confirm", "confirm-required");
        else if (!next.equals(confirm)) errors.put("confirm", "mismatch");
        return errors;
    }

    public static Map<String, String> connectionErrors(Map<String, String> fields, boolean keyStoreConfigured) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (!OnboardingProxySettings.validHost(fields.getOrDefault("domain", ""))) errors.put("domain", "invalid-domain");
        if (!OnboardingProxySettings.validPort(fields.getOrDefault("port", ""))) errors.put("port", "invalid-port");
        boolean cert = !fields.getOrDefault("certificate", "").isBlank();
        boolean key = !fields.getOrDefault("private-key", "").isBlank();
        if (Boolean.parseBoolean(fields.getOrDefault("https", "false")) && (!(cert && key) && (!keyStoreConfigured || cert || key)))
            errors.put(cert ? "private-key" : "certificate", "certificate-required");
        return errors;
    }
}
