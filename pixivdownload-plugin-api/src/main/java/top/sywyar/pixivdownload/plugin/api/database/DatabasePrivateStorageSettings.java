package top.sywyar.pixivdownload.plugin.api.database;

import java.nio.file.Path;
import java.util.Objects;

/**
 * 宿主交给数据库 provider 的私有存储身份，不向业务插件注入。
 * @param owner 固定的插件 owner
 * @param directory 经宿主校验的 owner 私有数据目录
 * @param installationId 持久安装身份，不因作品目录改变而变化
 * @param password 仅供此 owner 和此目标使用的数据库凭据；空值只供只读身份预检，需认证的提供者必须拒绝借用连接
 */
public record DatabasePrivateStorageSettings(String owner, Path directory, String installationId, String password) {
    /**
     * 校验 owner 与安装身份，并规范化私有数据目录。
     * @param owner 固定的插件 owner
     * @param directory 经宿主校验的 owner 私有数据目录
     * @param installationId 持久安装身份
     * @param password 仅供此 owner 和此目标使用的数据库凭据
     */
    public DatabasePrivateStorageSettings {
        if (owner == null || !owner.matches("[a-z][a-z0-9]*(-[a-z0-9]+)*")) {
            throw new IllegalArgumentException("DATABASE_OWNER_INVALID");
        }
        directory = Objects.requireNonNull(directory).toAbsolutePath().normalize();
        Objects.requireNonNull(installationId);
        Objects.requireNonNull(password);
        if (installationId.isBlank()) throw new IllegalArgumentException("DATABASE_OWNER_INVALID");
    }

    @Override public String toString() { return "DatabasePrivateStorageSettings[owner=" + owner + "]"; }
}
