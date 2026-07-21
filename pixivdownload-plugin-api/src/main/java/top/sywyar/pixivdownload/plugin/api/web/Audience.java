package top.sywyar.pixivdownload.plugin.api.web;

/**
 * Web UI contribution 与业务落点消费的<b>页面身份</b>。
 * <p>
 * 本枚举只表达宿主当前能够从页面请求中真实解析出的身份，不试图覆盖路由鉴权的全部流程状态。路由仍通过
 * {@link AccessPolicy} 声明，由宿主鉴权流程按策略执行；只有 {@link AccessPolicy#supportsUiVisibility()} 的策略
 * 才能投影到本身份模型，用于导航、页面区块和下钻链接的显隐。
 * <p>
 * 与历史「monitor 清单 / 访客白名单」这类按 <em>实现机制</em> 命名的口径不同，本枚举按 <em>页面身份语义</em> 命名：
 * <ul>
 *   <li>{@link #VISITOR}：{@code multi} 模式下未登录、无邀请码的匿名访问者（按 UUID / 人均配额限流）。</li>
 *   <li>{@link #INVITED_GUEST}：持邀请会话（{@code pixiv_invite_token}）的受邀访客（按邀请码限流），
 *       与 {@link #VISITOR} 是不同身份（不同 cookie、不同限流、相反的可达模型）。</li>
 *   <li>{@link #ADMIN}：经 setup 用户名登录的管理员，不受 {@code solo} / {@code multi} 模式限制。</li>
 * </ul>
 * {@code PUBLIC} 是「对全部页面身份可见」的策略而不是独立身份；本机来源、GUI token 与 actuator 探针则是
 * 路由流程条件，不属于页面身份。
 * 纯 JDK 类型，不依赖 Spring / Jackson / JDBC，保持 {@code plugin.api} 轻量。
 */
public enum Audience {

    /** {@code multi} 模式下未登录、无邀请码的匿名访问者（UUID / 人均配额）。 */
    VISITOR,

    /** 持邀请会话的受邀访客（按邀请码限流）。 */
    INVITED_GUEST,

    /** 经 setup 登录的管理员（不限模式）。 */
    ADMIN
}
