package top.sywyar.pixivdownload.core.db;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 宿主主库连接目标及连接池设置。 */
@Configuration
@ConfigurationProperties(prefix = "database")
public class DatabasePoolProperties {

    public static final int DEFAULT_MAXIMUM_POOL_SIZE = 28;
    public static final int MINIMUM_POOL_SIZE = 8;

    private int maximumPoolSize = DEFAULT_MAXIMUM_POOL_SIZE;
    private String engine = "sqlite";
    private String jdbcUrl = "";
    private String username = "";
    private String password = "";

    public String getEngine() { return engine; }
    public void setEngine(String engine) { this.engine = engine; }
    public String getJdbcUrl() { return jdbcUrl; }
    public void setJdbcUrl(String jdbcUrl) { this.jdbcUrl = jdbcUrl == null ? "" : jdbcUrl; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username == null ? "" : username; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password == null ? "" : password; }

    public int getMaximumPoolSize() {
        return Math.max(MINIMUM_POOL_SIZE, maximumPoolSize);
    }

    public void setMaximumPoolSize(int maximumPoolSize) {
        this.maximumPoolSize = maximumPoolSize;
    }
}
