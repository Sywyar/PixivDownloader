package top.sywyar.pixivdownload.databasepostgresql;

import org.pf4j.Plugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivPluginProvider;

/** PostgreSQL 外置包入口，不依赖业务子上下文。 */
public final class PostgresPf4jPlugin extends Plugin implements PixivPluginProvider {
    @Override public PixivFeaturePlugin featurePlugin() { return new PostgresDatabasePlugin(); }

    @Override public void stop() {
        for (var driver : java.util.Collections.list(java.sql.DriverManager.getDrivers())) {
            if (driver instanceof org.postgresql.Driver && driver.getClass().getClassLoader() == getClass().getClassLoader()) {
                try {
                    java.sql.DriverManager.deregisterDriver(driver);
                } catch (java.sql.SQLException failure) {
                    throw new IllegalStateException("DATABASE_DRIVER_RELEASE_FAILED");
                }
            }
        }
    }
}
