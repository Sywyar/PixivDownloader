package top.sywyar.pixivdownload.plugin.api.storage;

import java.nio.file.Path;

/** 插件配置初始化与桌面保存共用的进程内互斥；按路径分段，锁数量不随插件或文件数增长。 */
public final class PluginConfigFileLocks {
    private static final Object[] LOCKS = java.util.stream.IntStream.range(0, 64)
            .mapToObj(index -> new Object()).toArray();
    private PluginConfigFileLocks() {}

    /**
     * @param path 插件配置文件路径
     * @return 该绝对路径对应的互斥对象，调用方在其内部完成读取与原子保存
     */
    public static Object forPath(Path path) {
        return LOCKS[Math.floorMod(path.toAbsolutePath().normalize().hashCode(), LOCKS.length)];
    }
}
