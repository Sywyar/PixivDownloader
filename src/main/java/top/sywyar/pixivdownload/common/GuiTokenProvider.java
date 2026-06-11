package top.sywyar.pixivdownload.common;

/**
 * GUI 认证令牌的中立读取接口，由 gui 侧的令牌服务实现。
 * setup 侧（AuthFilter）经此接口校验 {@code /api/gui/**} 请求头中的令牌，
 * 避免 setup 包直接依赖 gui 包实现类。
 */
public interface GuiTokenProvider {

    /** GUI 令牌请求头名。 */
    String HEADER_NAME = "X-GUI-Token";

    /** 当前进程的一次性 GUI 令牌；Spring 初始化完成前可能为 null。 */
    String getToken();
}
