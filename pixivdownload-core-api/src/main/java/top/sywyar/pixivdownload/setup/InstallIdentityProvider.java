package top.sywyar.pixivdownload.setup;

/**
 * 安装身份标识供给：{@code data/install_identity.txt} 中随机 UUID v4 的只读窄端口。
 *
 * <p>供需要稳定匿名身份的插件在 solo 模式下复用同一安装身份（例如布局调查的
 * distinct_id 与去重键），实现侧由宿主负责生成 / 缓存 / 落盘，本接口不暴露路径
 * 与文件细节。
 */
public interface InstallIdentityProvider {

    /**
     * 返回当前安装身份标识（合法 UUID v4 字符串）。
     *
     * @throws IllegalStateException 安装身份文件损坏且无法生成时抛出
     * @return 方法返回的字符串
     */
    String get();
}
