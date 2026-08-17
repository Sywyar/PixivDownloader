package top.sywyar.pixivdownload.setup;

/**
 * 提供已配置的应用模式，同时不公开初始化持久化或鉴权服务。
 */
public interface ApplicationModeProvider {

    /**
     * 返回 {@code "solo"}、{@code "multi"}；初始化完成前返回 {@code null}。
     *
     * @return 方法返回的字符串
     */
    String getMode();
}
