package top.sywyar.pixivdownload.plugin.api.web;

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

    /** 受邀访客可读（仍需经 invite session 校验）；同时受 monitor 管控（既在 monitor 清单又在访客白名单）。 */
    GUEST_READ,

    /**
     * 受邀访客可读、但<b>不</b>受 monitor 管控：不进入 monitor 清单，对非访客回退到常规会话鉴权，
     * 因此 multi 模式普通访客的 GET 也可访问。用于跨页共享静态依赖（i18n / 主题 / 侧边模块 / 翻译等
     * 脚本样式）与只读代理 / 下载状态轮询端点（{@code /api/pixiv/**}、{@code /api/download/status/} 等）。
     * 与 {@link #GUEST_READ} 的区别即「是否进入 monitor 清单」。
     */
    GUEST_READ_OPEN,

    /** 需携带本机 GUI token（{@code /api/gui/**} 双重校验）。 */
    GUI_LOCAL_TOKEN,

    /** actuator 公开端点。 */
    ACTUATOR_PUBLIC
}
