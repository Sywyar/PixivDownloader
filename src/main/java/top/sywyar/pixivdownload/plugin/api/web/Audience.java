package top.sywyar.pixivdownload.plugin.api.web;

/**
 * 可访问某条路由的<b>身份</b>（access policy 的构成原子）。
 * <p>
 * 路由不直接声明身份集合，而是声明一个 {@link AccessPolicy}（常用身份组合的命名策略）；
 * 每个 {@link AccessPolicy} 映射到它所放行的 {@code Audience} 集合（见 {@link AccessPolicy#audiences()}），
 * 由 {@code AuthFilter} 在解析出当前请求身份后据此判定。
 * <p>
 * 与历史「monitor 清单 / 访客白名单」这类按 <em>实现机制</em> 命名的口径不同，本枚举按 <em>身份语义</em> 命名：
 * <ul>
 *   <li>{@link #PUBLIC}：任何人，无需鉴权。</li>
 *   <li>{@link #VISITOR}：{@code multi} 模式下未登录、无邀请码的匿名访问者（按 UUID / 人均配额限流）。</li>
 *   <li>{@link #INVITED_GUEST}：持邀请会话（{@code pixiv_invite_token}）的受邀访客（按邀请码限流），
 *       与 {@link #VISITOR} 是不同身份（不同 cookie、不同限流、相反的可达模型）。</li>
 *   <li>{@link #USER_SESSION}：任意合法登录会话用户。当前与 {@link #ADMIN} 重合（仅管理员可登录），
 *       保留为将来「普通登录用户」的扩展点。</li>
 *   <li>{@link #ADMIN}：经 setup 用户名登录的管理员，不受 {@code solo} / {@code multi} 模式限制。</li>
 *   <li>{@link #LOCAL}：来自本机回环地址的可信本地请求。</li>
 *   <li>{@link #GUI}：本机可信请求 + 有效 GUI token（{@code /api/gui/**} 双重校验）。</li>
 * </ul>
 * 纯 JDK 类型，不依赖 Spring / Jackson / JDBC，保持 {@code plugin.api} 轻量。
 */
public enum Audience {

    /** 任何人，无需鉴权。 */
    PUBLIC,

    /** {@code multi} 模式下未登录、无邀请码的匿名访问者（UUID / 人均配额）。 */
    VISITOR,

    /** 持邀请会话的受邀访客（按邀请码限流）。 */
    INVITED_GUEST,

    /** 任意合法登录会话用户（当前与 {@link #ADMIN} 重合，留作扩展点）。 */
    USER_SESSION,

    /** 经 setup 登录的管理员（不限模式）。 */
    ADMIN,

    /** 本机回环地址的可信本地请求。 */
    LOCAL,

    /** 本机可信请求 + 有效 GUI token。 */
    GUI
}
