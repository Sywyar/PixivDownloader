package top.sywyar.pixivdownload.plugin.runtime.descriptor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/** 插件描述符中的声明权限；只用于风险说明与信任连续性，不声称在 full-trust 模式中强制隔离。 */
public record PluginPermissionDeclaration(boolean declared, List<String> permissions) {

    private static final int MAX_PERMISSIONS = 64;
    private static final int MAX_TOKEN_LENGTH = 64;
    private static final Pattern TOKEN = Pattern.compile("[a-z][a-z0-9]*(?:-[a-z0-9]+)*");
    /** 历史信任记录使用的空集摘要；现明确解释为“未声明，按完全访问处理”。 */
    public static final String UNDECLARED_PERMISSION_DIGEST =
            "4f53cda18c2baa0c0354bb5f9a3ecbe5ed12ab4d8e11ba873c2f11161202b945";

    public PluginPermissionDeclaration {
        permissions = permissions == null ? List.of() : permissions.stream()
                .map(value -> Objects.requireNonNull(value, "permission").trim().toLowerCase(Locale.ROOT))
                .toList();
        if (!declared && !permissions.isEmpty()) {
            throw new IllegalArgumentException("undeclared permissions must be empty");
        }
        if (permissions.size() > MAX_PERMISSIONS) {
            throw new IllegalArgumentException("plugin permissions exceed the supported count");
        }
        if (permissions.stream().anyMatch(value -> value.length() > MAX_TOKEN_LENGTH
                || !TOKEN.matcher(value).matches())) {
            throw new IllegalArgumentException("plugin permission contains an invalid token");
        }
        permissions = permissions.stream().distinct().sorted().toList();
    }

    public static PluginPermissionDeclaration undeclared() {
        return new PluginPermissionDeclaration(false, List.of());
    }

    public static PluginPermissionDeclaration declared(List<String> permissions) {
        return new PluginPermissionDeclaration(true, permissions);
    }

    /** 缺少声明按完全访问处理；显式权限集合只可无确认地缩小，不能扩大或退回未声明。 */
    public boolean isNoMorePrivilegedThan(PluginPermissionDeclaration previous) {
        Objects.requireNonNull(previous, "previous");
        if (!previous.declared) {
            return true;
        }
        return declared && previous.permissions.containsAll(permissions);
    }

    /** 对声明状态和规范化权限集合计算稳定摘要，供持久化信任决定绑定。 */
    public String digest() {
        if (!declared) {
            return UNDECLARED_PERMISSION_DIGEST;
        }
        String canonical = "declared\n" + String.join("\n", permissions);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }
}
