package top.sywyar.pixivdownload.plugin.api.web;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 宿主认证后的程序身份；权限只表示明确授权，不代表管理员会话。
 * @param id 非秘密的程序身份
 * @param permissions 已授予的完整 owner:permission 集合
 */
public record ProgramIdentity(UUID id, Set<String> permissions) {
    /** 宿主写入已认证身份的请求属性名。 */
    public static final String REQUEST_ATTRIBUTE = ProgramIdentity.class.getName();
    /** 校验身份并复制权限集合。 */
    public ProgramIdentity {
        Objects.requireNonNull(id);
        permissions = Set.copyOf(permissions);
    }
    /**
     * 从宿主请求属性读取程序身份。
     * @param attribute 请求中由宿主设置的 {@link #REQUEST_ATTRIBUTE} 属性
     * @return 宿主已经认证的程序身份
     */
    public static Optional<ProgramIdentity> from(Object attribute) {
        return attribute instanceof ProgramIdentity identity
                ? Optional.of(identity) : Optional.empty();
    }
    /**
     * 检查精确的 owner 与局部权限组合。
     * @param owner 可信功能 owner
     * @param permission 局部权限 ID
     * @return 是否具有该精确授权
     */
    public boolean permits(String owner, String permission) {
        return permissions.contains(owner + ":" + permission);
    }
}
