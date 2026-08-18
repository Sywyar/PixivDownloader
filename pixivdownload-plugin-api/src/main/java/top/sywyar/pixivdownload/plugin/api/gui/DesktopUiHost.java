package top.sywyar.pixivdownload.plugin.api.gui;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/** Toolkit-neutral host operations required by an official desktop UI plugin. */
public interface DesktopUiHost {
    /**
     * Returns a localized host message.
     *
     * @param code stable message code
     * @param arguments message arguments
     * @return localized message
     */
    String message(String code, Object... arguments);
    /**
     * Returns the application version.
     *
     * @return application version
     */
    String applicationVersion();
    /**
     * Returns whether the process was launched from a packaged executable.
     *
     * @return whether the process was launched from an executable
     */
    boolean launchedFromExecutable();
    /**
     * Returns whether the current version is a nightly build.
     *
     * @return whether the current version is nightly
     */
    boolean currentVersionNightly();
    /**
     * Returns the secret token for local GUI API calls.
     *
     * @return GUI token
     */
    String guiToken();
    /**
     * Returns the HTTP header name used for the GUI token.
     *
     * @return GUI token header name
     */
    String guiTokenHeader();
    /**
     * Returns the application data directory.
     *
     * @return data directory
     */
    Path dataDirectory();
    /**
     * Returns the directory for desktop UI state.
     *
     * @return GUI state directory
     */
    Path guiStateDirectory();
    /**
     * Returns the public plugin installation directory.
     *
     * @return plugin installation directory
     */
    Path pluginsDirectory();
    /**
     * Returns the configuration path owned by a plugin.
     *
     * @param pluginId verified plugin id
     * @param extension file extension without a leading dot
     * @return plugin-owned configuration path
     */
    Path resolvePluginConfigPath(String pluginId, String extension);
    /**
     * Returns the image-classifier state path for an application root.
     *
     * @param rootFolder application root folder
     * @return image-classifier state path
     */
    Path resolveImageClassifierPath(String rootFolder);
    /**
     * Returns the setup configuration path for an application root.
     *
     * @param rootFolder application root folder
     * @return setup configuration path
     */
    Path resolveSetupConfigPath(String rootFolder);
    /**
     * Returns the database path for an application root.
     *
     * @param rootFolder application root folder
     * @return database path
     */
    Path resolveDatabasePath(String rootFolder);
    /**
     * Returns the configured download root or the supplied fallback.
     *
     * @param configPath host configuration path
     * @param defaultRootFolder fallback root folder
     * @return configured or fallback download root
     */
    String readDownloadRootFromConfig(Path configPath, String defaultRootFolder);
    /**
     * Returns the normalized application root folder.
     *
     * @param rootFolder root folder to normalize
     * @return normalized root folder
     */
    String normalizeRootFolder(String rootFolder);
    /**
     * Returns the current backend lifecycle snapshot.
     *
     * @return backend snapshot
     */
    BackendSnapshot backendSnapshot();
    /**
     * Subscribes to backend lifecycle changes.
     *
     * @param listener lifecycle listener
     * @return subscription handle
     */
    AutoCloseable subscribeBackend(Consumer<BackendSnapshot> listener);
    /**
     * Starts the backend and invokes the callback after success.
     *
     * @param afterStart success callback
     * @return whether the operation was accepted
     */
    boolean startBackend(Runnable afterStart);
    /**
     * Stops the backend and invokes the callback after success.
     *
     * @param afterStop success callback
     * @return whether the operation was accepted
     */
    boolean stopBackend(Runnable afterStop);
    /**
     * Restarts the backend and invokes the callback after success.
     *
     * @param afterRestart success callback
     * @return whether the operation was accepted
     */
    boolean restartBackend(Runnable afterRestart);
    /**
     * Returns whether operating-system auto-start is supported.
     *
     * @return whether auto-start is supported
     */
    boolean autoStartSupported();
    /**
     * Returns whether operating-system auto-start is enabled.
     *
     * @return whether auto-start is enabled
     */
    boolean autoStartEnabled();
    /**
     * Updates operating-system auto-start state.
     *
     * @param enabled requested state
     * @throws IOException when the operating-system entry cannot be updated
     * @throws InterruptedException when the update process is interrupted
     */
    void setAutoStartEnabled(boolean enabled) throws IOException, InterruptedException;
    /**
     * Reads credentials owned by one plugin.
     *
     * @param ownerPluginId credential owner id
     * @return decrypted credential values
     * @throws IOException when credential storage cannot be read
     */
    Map<String, String> readCredentials(String ownerPluginId) throws IOException;
    /**
     * Applies credential updates for one plugin.
     *
     * @param ownerPluginId credential owner id
     * @param updates credential updates
     * @throws IOException when credential storage cannot be updated
     */
    void updateCredentials(String ownerPluginId, Map<String, String> updates) throws IOException;
    /**
     * Executes an operation while holding the requested credential-owner locks.
     *
     * @param ownerPluginIds credential owner ids
     * @param operation operation to execute
     * @throws IOException when lock acquisition or the operation fails
     */
    void withCredentialLocks(Collection<String> ownerPluginIds, IoOperation operation) throws IOException;
    /**
     * Captures the encrypted credential file owned by one plugin.
     *
     * @param ownerPluginId credential owner id
     * @return defensive credential snapshot
     * @throws IOException when credential storage cannot be read
     */
    CredentialSnapshot snapshotCredentials(String ownerPluginId) throws IOException;
    /**
     * Restores a previously captured credential snapshot.
     *
     * @param ownerPluginId credential owner id
     * @param snapshot snapshot to restore
     * @throws IOException when credential storage cannot be restored
     */
    void restoreCredentials(String ownerPluginId, CredentialSnapshot snapshot) throws IOException;
    /**
     * Locates an available FFmpeg installation.
     *
     * @return available installation, if found
     */
    Optional<FfmpegInstallation> locateFfmpeg();
    /**
     * Returns the directory reserved for a host-managed FFmpeg installation.
     *
     * @return managed FFmpeg directory
     */
    Path managedFfmpegDirectory();
    /**
     * Returns whether this host can install FFmpeg.
     *
     * @return whether managed installation is supported
     */
    boolean supportsManagedFfmpegInstall();
    /**
     * Installs host-managed FFmpeg and reports coarse progress.
     *
     * @param proxy optional download proxy
     * @param listener progress listener
     * @return installed FFmpeg paths
     * @throws IOException when installation fails
     * @throws InterruptedException when installation is interrupted
     */
    FfmpegInstallation installManagedFfmpeg(FfmpegProxy proxy, FfmpegProgressListener listener) throws IOException, InterruptedException;
    /**
     * Returns the current maintenance-task snapshot.
     *
     * @return maintenance snapshot
     */
    MaintenanceSnapshot maintenanceSnapshot();
    /**
     * Returns default artwork metadata backfill options.
     *
     * @return default backfill options
     */
    BackfillOptions defaultBackfillOptions();
    /**
     * Returns whether the database supports the requested backfill column.
     *
     * @param column requested column
     * @return whether the column is supported
     */
    boolean supportsBackfillColumn(DatabaseColumn column);
    /**
     * Counts artwork metadata backfill candidates.
     *
     * @param options backfill options
     * @return candidate count
     * @throws Exception when the database cannot be inspected
     */
    int countBackfillCandidates(BackfillOptions options) throws Exception;
    /**
     * Runs artwork metadata backfill.
     *
     * @param options backfill options
     * @return aggregate result
     * @throws Exception when backfill fails
     */
    BackfillSummary runBackfill(BackfillOptions options) throws Exception;
    /**
     * Counts legacy JSON-to-database migration candidates.
     *
     * @param options migration options
     * @return candidate count
     * @throws Exception when legacy state cannot be inspected
     */
    int countMigrationCandidates(MigrationOptions options) throws Exception;
    /**
     * Runs legacy JSON-to-database migration.
     *
     * @param options migration options
     * @param reporter progress reporter
     * @return aggregate result
     * @throws Exception when migration fails
     */
    MigrationSummary runMigration(MigrationOptions options, Consumer<String> reporter) throws Exception;
    /**
     * Opens an isolated log session for a desktop tool.
     *
     * @param stem stable log file stem
     * @return active log session
     * @throws Exception when the log session cannot be created
     */
    ToolLogSession openToolLog(String stem) throws Exception;

    /** Stable backend lifecycle states exposed to desktop providers. */
    enum BackendState {
        /** Backend is stopped. */
        STOPPED,
        /** Backend is starting. */
        STARTING,
        /** Backend is running. */
        RUNNING,
        /** Backend is stopping. */
        STOPPING,
        /** Backend failed to start or run. */
        FAILED
    }
    /**
     * Backend lifecycle state and its last failure, if any.
     *
     * @param state current lifecycle state
     * @param lastError last lifecycle failure, if any
     */
    record BackendSnapshot(BackendState state, Throwable lastError) {}
    /**
     * Defensive snapshot of one plugin's encrypted credential file.
     *
     * @param existed whether the credential file existed
     * @param content encrypted file bytes
     */
    record CredentialSnapshot(boolean existed, byte[] content) {
        /**
         * Copies credential bytes on construction.
         *
         * @param existed whether the credential file existed
         * @param content encrypted file bytes
         */
        public CredentialSnapshot { content = content == null ? new byte[0] : content.clone(); }
        /**
         * Returns a copy of the captured credential bytes.
         *
         * @return copied credential bytes
         */
        @Override public byte[] content() { return content.clone(); }
    }
    /**
     * Paths and origin metadata for an FFmpeg installation.
     *
     * @param ffmpegPath FFmpeg executable path
     * @param ffprobePath FFprobe executable path
     * @param homeDir installation home directory
     * @param sourceMessageCode localized source message code
     */
    record FfmpegInstallation(Path ffmpegPath, Path ffprobePath, Path homeDir, String sourceMessageCode) {}
    /**
     * Proxy settings used only for the managed FFmpeg download.
     *
     * @param enabled whether the proxy is enabled
     * @param host proxy host
     * @param port proxy port
     */
    record FfmpegProxy(boolean enabled, String host, int port) {
        /**
         * Normalizes a missing proxy host to an empty string.
         *
         * @param enabled whether the proxy is enabled
         * @param host proxy host
         * @param port proxy port
         */
        public FfmpegProxy { host = host == null ? "" : host.trim(); }
    }
    /** Receives coarse progress while installing managed FFmpeg. */
    @FunctionalInterface interface FfmpegProgressListener {
        /**
         * Reports the current stage and units.
         *
         * @param stage stable progress stage
         * @param current completed units
         * @param total total units, or zero when unknown
         */
        void onProgress(String stage, long current, long total);
    }
    /**
     * Database column requested by a desktop backfill tool.
     *
     * @param tableName table name
     * @param columnName column name
     */
    record DatabaseColumn(String tableName, String columnName) {}
    /**
     * User-selected artwork metadata backfill options.
     *
     * @param dbPath database path
     * @param proxyHost proxy host
     * @param proxyPort proxy port
     * @param useProxy whether the proxy is enabled
     * @param delayMs delay between requests in milliseconds
     * @param limit maximum candidate count
     * @param dryRun whether to avoid persistent writes
     */
    record BackfillOptions(String dbPath, String proxyHost, int proxyPort, boolean useProxy, long delayMs, int limit, boolean dryRun) {}
    /**
     * Aggregate result of an artwork metadata backfill run.
     *
     * @param totalCandidates total candidate count
     * @param processed processed count
     * @param filledAuthor author values filled
     * @param filledR18 adult flags filled
     * @param filledAi AI flags filled
     * @param filledDescription descriptions filled
     * @param filledTags tag sets filled
     * @param filledSeries series values filled
     * @param deletedCount deleted records observed
     * @param skipped skipped count
     * @param previouslyUnreachable previously unreachable count
     * @param newlyUnreachable newly unreachable count
     * @param dryRun whether persistent writes were disabled
     * @param rateLimited whether the run stopped for rate limiting
     */
    record BackfillSummary(int totalCandidates, int processed, int filledAuthor, int filledR18, int filledAi,
                           int filledDescription, int filledTags, int filledSeries, int deletedCount, int skipped,
                           int previouslyUnreachable, int newlyUnreachable, boolean dryRun, boolean rateLimited) {}
    /**
     * User-selected legacy migration paths.
     *
     * @param dbPath destination database path
     * @param rootFolder legacy data root
     */
    record MigrationOptions(String dbPath, String rootFolder) {}
    /**
     * Aggregate result of a legacy migration run.
     *
     * @param totalCandidates total candidate count
     * @param migrated migrated count
     * @param skipped skipped count
     * @param historyFileMissing whether the legacy history file was absent
     * @param message summary message
     */
    record MigrationSummary(int totalCandidates, int migrated, int skipped, boolean historyFileMissing, String message) {}
    /**
     * Read-only progress snapshot for the active maintenance task.
     *
     * @param active whether maintenance is active
     * @param trigger trigger identifier
     * @param index current task index
     * @param total total task count
     * @param taskName current task name
     * @param taskStartedAt task start epoch milliseconds
     * @param unitsDone completed work units
     * @param unitsTotal total work units
     */
    record MaintenanceSnapshot(boolean active, String trigger, int index, int total, String taskName,
                               long taskStartedAt, int unitsDone, int unitsTotal) {}
    /** I/O operation executed while host locks are held. */
    @FunctionalInterface interface IoOperation {
        /**
         * Executes the operation.
         *
         * @throws IOException when the operation fails
         */
        void run() throws IOException;
    }
    /** Isolated HTML log session owned by a desktop tool invocation. */
    interface ToolLogSession extends AutoCloseable {
        /**
         * Returns the stable latest-log path.
         *
         * @return latest-log path
         */
        Path latestPath();
        /**
         * Returns this invocation's immutable log path.
         *
         * @return invocation log path
         */
        Path sessionPath();
        /**
         * Opens the latest log in the system browser.
         *
         * @throws Exception when the browser cannot be opened
         */
        void openLatestInBrowser() throws Exception;
        /** Closes the log session. */
        @Override void close();
    }
}
