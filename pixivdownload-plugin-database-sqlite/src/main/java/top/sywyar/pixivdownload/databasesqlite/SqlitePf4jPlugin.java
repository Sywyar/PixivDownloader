package top.sywyar.pixivdownload.databasesqlite;

import org.pf4j.Plugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivPluginProvider;

/** SQLite 外置包入口，不依赖业务子上下文。 */
public final class SqlitePf4jPlugin extends Plugin implements PixivPluginProvider {
    @Override public PixivFeaturePlugin featurePlugin() { return new SqliteDatabasePlugin(); }

    @Override public void stop() {
        for (var driver : java.util.Collections.list(java.sql.DriverManager.getDrivers())) {
            if (driver instanceof org.sqlite.JDBC && driver.getClass().getClassLoader() == getClass().getClassLoader()) {
                try {
                    java.sql.DriverManager.deregisterDriver(driver);
                } catch (java.sql.SQLException failure) {
                    throw new IllegalStateException("DATABASE_DRIVER_RELEASE_FAILED");
                }
            }
        }
    }
}
