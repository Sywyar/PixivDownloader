package top.sywyar.pixivdownload.plugin.api;

/**
 * 路由 / 静态资源 / 导航项的访问级别。
 * 与 {@code AuthFilter} 的访问判定语义一一对应。
 */
public enum AccessLevel {

    /** 无需任何鉴权即可访问。 */
    PUBLIC,

    /** 仅允许本机回环地址访问。 */
    LOCAL_ONLY,

    /** 仅管理员（multi 模式登录管理员）可访问。 */
    ADMIN,

    /** solo 模式会话用户或 multi 模式管理员可访问。 */
    ADMIN_OR_SOLO,

    /** 受邀访客可读（仍需经 invite session 校验）。 */
    GUEST_READ,

    /** 需携带本机 GUI token（{@code /api/gui/**} 双重校验）。 */
    GUI_LOCAL_TOKEN,

    /** actuator 公开端点。 */
    ACTUATOR_PUBLIC
}
