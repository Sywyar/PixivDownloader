package top.sywyar.pixivdownload.core.db;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 宿主 SQLite 连接池容量设置。 */
@Configuration
@ConfigurationProperties(prefix = "database")
public class DatabasePoolProperties {

    public static final int DEFAULT_MAXIMUM_POOL_SIZE = 28;
    public static final int MINIMUM_POOL_SIZE = 8;

    private int maximumPoolSize = DEFAULT_MAXIMUM_POOL_SIZE;

    public int getMaximumPoolSize() {
        return Math.max(MINIMUM_POOL_SIZE, maximumPoolSize);
    }

    public void setMaximumPoolSize(int maximumPoolSize) {
        this.maximumPoolSize = maximumPoolSize;
    }
}
