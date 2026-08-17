package top.sywyar.pixivdownload.plugin.api.storage;

import java.nio.file.Path;

/**
 * 仅向当前插件子上下文提供的 owner 作用域运行期路径。
 *
 * <p>宿主在注入前固定 owner，因此插件不需要提供插件 ID，也无法通过该能力意外解析其它插件的
 * 配置、状态或数据根目录。
 */
public interface RuntimePathProvider {

    /**
     * 解析插件配置文件，但不创建文件。
     *
     * @param extension 不带前导点的文件扩展名
     * @return {@code config/plugins/{owner}.{extension}}
     */
    Path configFile(String extension);

    /**
     * 解析插件的受管状态目录。
     *
     * @return {@code state/{owner}/}
     */
    Path stateDirectory();

    /**
     * 解析插件的受管数据目录。
     *
     * @return {@code data/{owner}/}
     */
    Path dataDirectory();
}
