package top.sywyar.pixivdownload.download.state;

import top.sywyar.pixivdownload.plugin.api.storage.RuntimePathProvider;

import java.nio.file.Path;
import java.util.Objects;

/**
 * 布局偏好调查的服务端状态文件布局：{@code state/download-workbench/layout-feedback-state.json}。
 *
 * <p>solo 模式下调查的「稍后再说 / 不再询问 / 已提交」状态与已体验布局清单由服务端持久化，
 * 使同一安装的多个浏览器 / 设备共享去重结论；multi 模式不启用（前端回退 localStorage）。
 */
public final class LayoutFeedbackStateFiles {

    static final String STATE_FILE_NAME = "layout-feedback-state.json";

    private final Path stateFile;

    public LayoutFeedbackStateFiles(RuntimePathProvider runtimePathProvider) {
        Objects.requireNonNull(runtimePathProvider, "runtimePathProvider");
        this.stateFile = runtimePathProvider
                .stateDirectory()
                .resolve(STATE_FILE_NAME)
                .normalize();
    }

    public Path stateFile() {
        return stateFile;
    }
}
