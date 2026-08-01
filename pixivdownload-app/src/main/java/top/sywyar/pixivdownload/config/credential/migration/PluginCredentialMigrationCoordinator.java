package top.sywyar.pixivdownload.config.credential.migration;

import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;

/**
 * Runs credential migration after root singletons are ready and before SmartLifecycle creates
 * external plugin child contexts.
 */
@Component
public class PluginCredentialMigrationCoordinator implements SmartInitializingSingleton {

    private final PluginCredentialMigrationService migrationService;

    public PluginCredentialMigrationCoordinator(PluginCredentialMigrationService migrationService) {
        this.migrationService = java.util.Objects.requireNonNull(migrationService, "migrationService");
    }

    @Override
    public void afterSingletonsInstantiated() {
        migrationService.migrateAll();
    }
}
