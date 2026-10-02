package top.sywyar.pixivdownload.plugin.api.gui;

import java.util.Optional;

/**
 * 可选的桌面配置辅助能力，随来源插件的精确生命周期发布和撤回。
 * 来源缺席、失败或撤回时不显示提示，也不授予文件访问权限。
 * 来源最多贡献一个候选目录，目标必须是已声明、非敏感、无条件显示且
 * 具有 HOT_RELOAD 效果的 PATH_DIR 字段。使用前必须读取持久化配置；
 * 观察或展示候选目录都不会修改配置。
 */
@FunctionalInterface
public interface DesktopDirectorySuggestionSource {
    /** @return 当前候选目录；已有配置或尚未观察到候选目录时为空 */
    Optional<DesktopDirectorySuggestion> directorySuggestion();
}
