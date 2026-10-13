package top.sywyar.pixivdownload.plugin.api.database;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * 宿主交给选定提供者的连接设置与主库存储根；物理文件名和服务端布局由提供者决定。
 * @param jdbcUrl 显式地址；空值是否允许由提供者校验
 * @param username 用户名
 * @param password 真实密码，不得用于日志
 * @param dataDirectory 应用数据根，不是具体数据库文件
 * @param legacyDirectories 仅供本提供者识别已发布布局的历史目录，检查时不可搬迁
 */
public record DatabaseConnectionSettings(String jdbcUrl, String username, String password,
                                         Path dataDirectory, List<Path> legacyDirectories) {
    /**
     * 校验连接字段并固定规范化的绝对目录。
     * @param dataDirectory 应用数据根目录，不是具体数据库文件
     * @param jdbcUrl 显式连接地址，空值由提供者校验
     * @param legacyDirectories 仅供提供者识别已发布布局的历史目录
     * @param password 密码，不得写入日志
     * @param username 数据库登录身份
     */
    public DatabaseConnectionSettings {
        Objects.requireNonNull(jdbcUrl, "jdbcUrl");
        Objects.requireNonNull(username, "username");
        Objects.requireNonNull(password, "password");
        dataDirectory = Objects.requireNonNull(dataDirectory).toAbsolutePath().normalize();
        legacyDirectories = legacyDirectories.stream().map(p -> p.toAbsolutePath().normalize()).distinct().toList();
    }
    /**
     * 不带历史目录的连接设置，常用于显式地址与隔离存储。
     * @param dataDirectory 应用数据根目录，不是具体数据库文件
     * @param jdbcUrl 显式连接地址，空值由提供者校验
     * @param password 密码，不得写入日志
     * @param username 数据库登录身份
     */
    public DatabaseConnectionSettings(String jdbcUrl, String username, String password, Path dataDirectory) {
        this(jdbcUrl, username, password, dataDirectory, List.of());
    }
    @Override public String toString() { return "DatabaseConnectionSettings[redacted]"; }
}
