package top.sywyar.pixivdownload.plugin.api.web;

/**
 * 宿主从当前 HTTP 请求中信任解析出的 owner 作用域。
 *
 * <p>管理员作用域可跨 owner 读写，因此 {@link #ownerUuid()} 固定为 {@code null}；
 * 非管理员作用域必须携带非空的 owner UUID。插件控制器应使用宿主提供的
 * {@link RequestOwnerIdentityResolver}，不得从请求 body 或查询参数信任 owner 身份。
 */
public record RequestOwnerIdentity(String ownerUuid, boolean admin) {

    public RequestOwnerIdentity {
        if (admin) {
            ownerUuid = null;
        } else if (ownerUuid == null || ownerUuid.isBlank()) {
            throw new IllegalArgumentException("non-admin request owner UUID is required");
        } else {
            ownerUuid = ownerUuid.trim();
        }
    }

    /** 构造全 owner 的管理员作用域。 */
    public static RequestOwnerIdentity adminScope() {
        return new RequestOwnerIdentity(null, true);
    }

    /** 构造仅归属某 owner 的访客作用域。 */
    public static RequestOwnerIdentity owner(String ownerUuid) {
        return new RequestOwnerIdentity(ownerUuid, false);
    }
}
