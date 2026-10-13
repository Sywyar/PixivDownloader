package top.sywyar.pixivdownload.plugin.runtime.bootstrap.database;

import top.sywyar.pixivdownload.plugin.api.database.DatabaseConnectionSettings;
import top.sywyar.pixivdownload.plugin.api.database.DatabaseConnectionTarget;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import java.nio.file.Path;

/** 随完整进程固定的 provider 与连接目标；缺席和解析失败均保留，不随后端重启重新选择。 */
public final class DatabaseProviderBinding {
    private final DatabaseProviderSelection.Result selection;
    private final Optional<DatabaseConnectionSettings> settings;
    private final Optional<DatabaseConnectionTarget> target;
    private final Optional<Path> localFile;
    private volatile boolean failed;

    public DatabaseProviderBinding(DatabaseProviderSelection.Result selection,
            Optional<DatabaseConnectionSettings> settings, Optional<DatabaseConnectionTarget> target,
            Optional<Path> localFile) {
        this.selection = Objects.requireNonNull(selection);
        this.settings = Objects.requireNonNull(settings);
        this.target = Objects.requireNonNull(target);
        this.localFile = Objects.requireNonNull(localFile);
    }

    public DatabaseProviderSelection.Result selection() { return selection; }
    public Optional<DatabaseConnectionSettings> settings() { return settings; }
    public Optional<DatabaseConnectionTarget> target() { return target; }
    public Optional<Path> localFile() { return localFile; }
    public boolean available() { return target.isPresent() && !failed; }

    /** 失败后只允许完整进程重启恢复，不能借后端重启改投另一目标。 */
    public void invalidate() { failed = true; }

    public static DatabaseProviderBinding resolve(DatabaseProviderSelection.Result selection,
                                                   Supplier<DatabaseConnectionSettings> settingsSupplier,
                                                   UnaryOperator<DatabaseProviderBinding> prepare) {
        if (!selection.available()) {
            return unavailable(selection);
        }
        DatabaseConnectionSettings settings;
        try {
            settings = Objects.requireNonNull(settingsSupplier.get());
            var target = Objects.requireNonNull(selection.provider().orElseThrow().resolve(settings));
            var binding = new DatabaseProviderBinding(selection, Optional.of(settings), Optional.of(target),
                    Objects.requireNonNull(target.localFile()));
            return Objects.requireNonNull(prepare.apply(binding));
        } catch (VirtualMachineError | ThreadDeath fatal) {
            throw fatal;
        } catch (Throwable failure) {
            // 插件异常可能含连接凭据；只保留受控的失败状态。
            return unavailable(selection);
        }
    }

    public static DatabaseProviderBinding unavailable(DatabaseProviderSelection.Result selection) {
        return new DatabaseProviderBinding(selection, Optional.empty(), Optional.empty(), Optional.empty());
    }

    @Override public String toString() {
        return "DatabaseProviderBinding[owner=" + selection.owner() + ", available=" + available() + "]";
    }
}
