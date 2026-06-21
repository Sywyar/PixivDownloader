package top.sywyar.pixivdownload.plugin.runtime;

/**
 * 外置插件目录的诊断状态。运行时骨架据此向核心壳报告插件目录的可用性，供后续的插件加载 / 恢复流程
 * 据此判断（运行时骨架只产出状态、不据此改变核心启动）。
 *
 * <ul>
 *   <li>{@link #ABSENT}：插件目录不存在（或路径存在但不是目录）。核心壳照常启动，输出缺失诊断。</li>
 *   <li>{@link #EMPTY}：插件目录存在但没有任何候选插件包（{@code *.jar} / {@code *.zip}）。</li>
 *   <li>{@link #POPULATED}：插件目录存在且至少有一个候选插件包（无论加载是否成功）。</li>
 * </ul>
 */
public enum PluginDirectoryState {

    /** 插件目录不存在，或路径存在但不是目录。 */
    ABSENT,

    /** 插件目录存在但没有任何候选插件包。 */
    EMPTY,

    /** 插件目录存在且至少有一个候选插件包。 */
    POPULATED
}
