package top.sywyar.pixivdownload.plugin.api.userscript;

import java.util.List;

/**
 * 宿主聚合并物化的当前油猴脚本目录。
 *
 * <p>实现必须返回不可变快照，且 {@link UserscriptArtifact#id()} 在当前快照内全局唯一；
 * 每个 artifact 已包含完整文本，读取侧不得再回访声明方资源或 ClassLoader。
 */
public interface UserscriptCatalog {

    /**
     * 返回脚本列表。
     *
     * @return 方法返回的列表
     */
    List<UserscriptArtifact> scripts();
}
