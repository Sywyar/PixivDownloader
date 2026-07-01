package top.sywyar.pixivdownload.plugin.runtime.lifecycle;

import java.nio.file.Path;

/** 物理卸载完成后可安全长期保留的纯值结果，不携带任何插件对象或 classloader。 */
public record UnloadedPluginPackage(String packageId, Path artifactPath, String version, long generation) {
}
