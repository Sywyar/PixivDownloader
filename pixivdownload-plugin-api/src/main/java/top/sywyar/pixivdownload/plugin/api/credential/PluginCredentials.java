package top.sywyar.pixivdownload.plugin.api.credential;

import java.util.Map;
import java.util.Optional;

/**
 * 子 context 注入的当前 owner、generation 凭据入口；调用方不能选择 owner 或文件路径。
 * 键由 {@code PixivFeaturePlugin.credentialKeys()} 或既有 GUI 敏感字段声明。
 * 读写仅触达宿主加密存储；已有 @Value 与配置对象不自动重新绑定。
 * 撤回后旧句柄拒绝新操作，已接纳操作参加原有资源 drain。
 */
public interface PluginCredentials {
    /**
     * {@return 已声明键的当前值；未保存时为空，未声明键抛出 UNDECLARED}
     * @param key 当前 owner 声明的键
     */
    Optional<String> read(String key);

    /**
     * 在一个 owner 存储锁内原子合并；省略表示保留，空白值表示删除。
     * 至多 128 个键，每值 UTF-8 至多 64 KiB，一次更新累计至多 1 MiB。
     * @param updates 不含 null 键或值的更新
     */
    void update(Map<String, String> updates);

    /**
     * 删除已声明键；不存在时幂等。
     * @param key 当前 owner 声明的键
     */
    default void delete(String key) {
        update(Map.of(key, ""));
    }
}
