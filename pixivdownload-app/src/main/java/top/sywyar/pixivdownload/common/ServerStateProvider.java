package top.sywyar.pixivdownload.common;

import java.io.IOException;

/**
 * 服务器安装/运行状态的中立接口，由 setup 侧的 SetupService 实现。
 * gui 侧（GuiStatusController）经此接口读取与变更安装状态，
 * 避免 gui 包直接依赖 setup 包实现类。
 */
public interface ServerStateProvider {

    /** 首次安装是否已完成。 */
    boolean isSetupComplete();

    /** 运行模式：{@code "solo"} | {@code "multi"}；未完成安装时为 null。 */
    String getMode();

    /** 完成首次配置（管理员账号 + 模式）并落盘。 */
    void init(String username, String password, String mode) throws IOException;

    /**
     * 修改管理员密码。旧密码错误抛 {@link IllegalArgumentException}，
     * 未完成安装抛 {@link IllegalStateException}；成功后所有现存 session 失效。
     */
    void changePassword(String oldPassword, String newPassword) throws IOException;
}
