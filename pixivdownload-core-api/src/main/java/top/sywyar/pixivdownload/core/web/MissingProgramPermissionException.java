package top.sywyar.pixivdownload.core.web;

/** 程序密钥缺少请求对应操作效果所需的附加权限。 */
public final class MissingProgramPermissionException extends RuntimeException {
    /** 操作所需的完整权限标识。 */
    private final String permission;

    /**
     * 创建缺少指定权限的错误。
     * @param permission 操作所需的完整权限标识，包含所属模块
     */
    public MissingProgramPermissionException(String permission) {
        super("Missing program permission: " + permission);
        this.permission = permission;
    }

    /** {@return 操作所需的完整权限标识} */
    public String permission() {
        return permission;
    }
}
