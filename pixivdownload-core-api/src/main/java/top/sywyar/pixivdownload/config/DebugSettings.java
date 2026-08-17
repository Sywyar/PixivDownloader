package top.sywyar.pixivdownload.config;

/**
 * 跨插件边界公开的只读宿主调试开关。
 */
public interface DebugSettings {

    /**
     * 判断启用状态是否满足条件。
     *
     * @return 满足条件时返回 {@code true}，否则返回 {@code false}
     */
    boolean isEnabled();
}
