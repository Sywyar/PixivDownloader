package top.sywyar.pixivdownload.plugin.storage;

import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.config.InstallIdentity;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.config.credential.PluginCredentialStore;
import top.sywyar.pixivdownload.core.db.DatabaseTarget;
import top.sywyar.pixivdownload.plugin.api.database.DatabasePrivateStorageSettings;
import top.sywyar.pixivdownload.plugin.api.storage.PluginDatabase;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/** 私库 owner 与凭据由宿主固定；密码沿用已认证加密存储，不进入 child Environment。 */
@Component
public final class PluginDatabaseFactory {
    private final DatabaseTarget target;
    private final DataSource main;
    private final PluginCredentialStore credentials;

    public PluginDatabaseFactory(DatabaseTarget target, DataSource main, PluginCredentialStore credentials) {
        this.target = target;
        this.main = main;
        this.credentials = credentials;
    }

    public PluginDatabase create(String owner) {
        if (owner == null || !owner.matches("[a-z][a-z0-9]*(-[a-z0-9]+)*")) {
            throw new IllegalArgumentException("DATABASE_OWNER_INVALID");
        }
        return new AppPluginDatabase(owner, target, main, () -> privateSettings(owner, credentials));
    }

    public PluginDatabase create(String owner, List<top.sywyar.pixivdownload.plugin.api.schema.SchemaContribution> schema) {
        return create(owner, schema, List.of());
    }
    public PluginDatabase create(String owner, List<top.sywyar.pixivdownload.plugin.api.schema.SchemaContribution> schema,
            List<top.sywyar.pixivdownload.plugin.api.storage.relational.StorageMigration> migrations) {
        var database = (AppPluginDatabase) create(owner);
        try { database.initialize(schema, migrations); return database; }
        catch (Exception | Error failure) {
            try { database.close(); }
            catch (VirtualMachineError | ThreadDeath fatal) {
                if (fatal != failure) fatal.addSuppressed(failure);
                throw fatal;
            }
            catch (RuntimeException | Error cleanup) {
                if (cleanup != failure) failure.addSuppressed(cleanup);
            }
            if (failure instanceof Error fatal) throw fatal;
            throw new IllegalStateException("PRIVATE_STORAGE_INITIALIZATION_FAILED", failure);
        }
    }

    public static DatabasePrivateStorageSettings privateSettings(String owner, PluginCredentialStore credentials) {
        return privateSettings(owner, credentials, true);
    }

    public static DatabasePrivateStorageSettings privateSettings(String owner, PluginCredentialStore credentials, boolean create) {
        if (owner == null || !owner.matches("[a-z][a-z0-9]*(-[a-z0-9]+)*")) {
            throw new IllegalArgumentException("DATABASE_OWNER_INVALID");
        }
        String installation;
        try {
            installation = create ? InstallIdentity.get() : InstallIdentity.readExisting()
                    .orElseThrow(() -> new IOException("DATABASE_INSTALL_IDENTITY_MISSING"));
        } catch (IOException failure) { throw new UncheckedIOException(failure); }
        String key;
        try {
            key = "host.database." + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                    (installation + "\0" + owner).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA256_UNAVAILABLE");
        }
        var password = new AtomicReference<String>();
        try {
            if (!create) return new DatabasePrivateStorageSettings(owner, RuntimeFiles.peekPluginDataDirectory(owner),
                    installation, credentials.readForTransfer(owner).getOrDefault(key, ""));
            credentials.withOwnerLocks(List.of(owner), () -> {
                String stored = credentials.readAll(owner).get(key);
                if (stored == null) {
                    byte[] bytes = new byte[32];
                    new SecureRandom().nextBytes(bytes);
                    stored = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
                    credentials.update(owner, Map.of(key, stored));
                }
                password.set(stored == null ? "" : stored);
            });
        } catch (IOException failure) {
            throw new UncheckedIOException(new IOException("DATABASE_PRIVATE_CREDENTIALS_UNAVAILABLE"));
        }
        return new DatabasePrivateStorageSettings(owner, RuntimeFiles.resolvePluginDataDirectory(owner), installation, password.get());
    }
}
