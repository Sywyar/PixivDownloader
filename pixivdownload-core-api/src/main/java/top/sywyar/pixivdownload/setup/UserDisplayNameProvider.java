package top.sywyar.pixivdownload.setup;

/**
 * 向可选插件提供已配置的用户显示名称。
 */
public interface UserDisplayNameProvider {

    /**
     * 返回显示名称。
     *
     * @return 方法返回的字符串
     */
    String getDisplayName();
}
