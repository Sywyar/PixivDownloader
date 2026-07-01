package top.sywyar.pixivdownload.plugin.runtime.install.model;

import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginDescriptor;

import java.nio.file.Path;
import java.util.Objects;
import top.sywyar.pixivdownload.plugin.runtime.discovery.PluginInstallation;

/**
 * 安装目录中一个已落盘的外置插件包：其包级描述符 + 落盘文件路径。由
 * {@link ExternalPluginInstaller#listInstalled()} 读出，用于重复 / 升级 / 降级判定与清理被取代的旧版本。
 *
 * <p>区别于运行期的 {@link top.sywyar.pixivdownload.plugin.runtime.discovery.PluginInstallation}：后者是「已启动功能插件」
 * 的运行期视图（带 classloader + 实例），本记录是「磁盘上的安装包」的静态视图。
 *
 * @param descriptor 包级描述符（{@code id == sourcePluginId}）
 * @param path       安装目录下的插件包文件（{@code .jar} / {@code .zip}）
 */
public record InstalledPlugin(PluginDescriptor descriptor, Path path) {

    public InstalledPlugin {
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(path, "path");
    }

    public String id() {
        return descriptor.id();
    }

    public String version() {
        return descriptor.version();
    }
}
