package top.sywyar.pixivdownload.plugin.api.gui;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Iterator;
import java.util.function.Consumer;

/** Toolkit-neutral host operations required by an official desktop UI plugin. */
public interface DesktopUiHost {
    /** Returns the product name rendered by the desktop UI. */
    String applicationName();
    /** Returns the public project URL. */
    String projectUrl();
    /** Returns the public releases URL. */
    String releasesUrl();
    /** Returns the default stable update manifest URL. */
    String defaultUpdateManifestUrl();
    /** Returns the default nightly update manifest URL. */
    String defaultNightlyUpdateManifestUrl();
    /** Returns the host-owned application configuration port. */
    ConfigFile applicationConfig();
    /** Returns a host-owned plugin properties configuration port. */
    ConfigFile pluginConfig(String pluginId);
    /** Returns locales visible in the desktop language selector. */
    java.util.List<UiLocale> visibleLocales();
    /** Matches a persisted locale tag or alias. */
    java.util.Optional<UiLocale> matchLocale(String tag);
    /** Resolves a locale and its ordered target-to-source fallback chain. */
    UiLocaleResolution resolveLocale(java.util.Locale requested);
    /** Detects and applies the host system locale policy. */
    java.util.Locale detectSystemLocale();
    /** Returns the default proxy host. */
    /** Removes trailing separators from a path value using host path rules. */
    String stripTrailingPathSeparators(String value);
    String defaultProxyHost();
    /** Returns the default proxy port. */
    int defaultProxyPort();
    /** Returns the minimum accepted setup password length. */
    int minimumPasswordLength();
    /** Returns the recommended setup password length. */
    int recommendedPasswordLength();
    /** Returns the default maintenance time. */
    String defaultMaintenanceTime();
    /** Validates a maintenance time. */
    boolean validMaintenanceTime(String value);
    /** Returns repository ids reserved by the host. */
    java.util.Set<String> reservedPluginRepositoryIds();
    /** Validates configuration keys with the host's authoritative rules. */
    java.util.Set<String> validatedConfigKeys(java.util.Collection<String> keys) throws java.io.IOException;
    /** Validates configuration values with the host's authoritative rules. */
    java.util.Map<String,String> validatedConfigValues(java.util.Map<String,String> values) throws java.io.IOException;
    /** Validates one configuration key. */
    String requireSafeConfigKey(String key) throws java.io.IOException;
    /** Validates one configuration value. */
    String requireSafeConfigValue(String value) throws java.io.IOException;
    /** Reads structured plugin repositories from a host-owned configuration file. */
    default List<RepositoryConfigEntry> readPluginRepositories(ConfigFile configFile) throws IOException {
        throw new UnsupportedOperationException("Plugin repository persistence is not supported by this host");
    }
    /** Writes structured plugin repositories to a host-owned configuration file. */
    default void writePluginRepositories(ConfigFile configFile, List<RepositoryConfigEntry> entries) throws IOException {
        throw new UnsupportedOperationException("Plugin repository persistence is not supported by this host");
    }
    /** Returns the built-in official repository trust root as a pure configuration value. */
    default TrustedKeyConfigEntry officialPluginRepositoryKey() {
        throw new UnsupportedOperationException("The official plugin repository key is not supported by this host");
    }

    /** Host-owned configuration persistence port. */
    interface ConfigFile {
        /** Reads one key. */
        default String read(String key) throws java.io.IOException {
            return readAll(java.util.Set.of(key)).get(key);
        }
        /** Reads selected keys. */
        java.util.Map<String,String> readAll(java.util.Collection<String> keys) throws java.io.IOException;
        /** Writes one key. */
        default void write(String key,String value) throws java.io.IOException {
            writeAll(java.util.Map.of(key,value == null ? "" : value));
        }
        /** Writes selected values. */
        void writeAll(java.util.Map<String,String> values) throws java.io.IOException;
        /** Removes selected keys. */
        void removeAll(java.util.Collection<String> keys) throws java.io.IOException;
        /** Captures exact file state for rollback. */
        ConfigSnapshot snapshot() throws java.io.IOException;
        /** Restores exact file state. */
        void restore(ConfigSnapshot snapshot) throws java.io.IOException;
    }

    /** Exact line snapshot of a host-owned configuration file. */
    record ConfigSnapshot(boolean existed,java.util.List<String> lines) {
        /** Defensively copies snapshot lines. */
        public ConfigSnapshot {
            lines=java.util.List.copyOf(lines == null ? java.util.List.of() : lines);
        }
    }

    /** Desktop locale descriptor. */
    record UiLocale(String tag,String nativeName,String resourceSuffix) {
        /** Returns this descriptor as a JDK locale. */
        public java.util.Locale toLocale(){return java.util.Locale.forLanguageTag(tag);}
    }

    /** Resolved desktop locale and fallback chain. */
    record UiLocaleResolution(UiLocale target,java.util.List<UiLocale> fallbackChain) {
        /** Defensively copies the fallback chain. */
        public UiLocaleResolution {fallbackChain=java.util.List.copyOf(fallbackChain);}
    }

    /** Stable repository proxy choices rendered by the desktop UI. */
    enum RepositoryProxyPolicy {
        /** Require direct HTTPS. */
        DIRECT_STRICT("direct-strict"),
        /** Allow the configured trusted proxy. */
        PROXY_TRUSTED("proxy-trusted"),
        /** Use a custom repository policy. */
        CUSTOM("custom");
        /** Default repository proxy policy. */
        public static final RepositoryProxyPolicy DEFAULT=DIRECT_STRICT;
        private final String configId;
        RepositoryProxyPolicy(String configId){this.configId=configId;}
        /** Returns the persisted policy id. */
        public String configId(){return configId;}
        /** Resolves a persisted id or the default. */
        public static RepositoryProxyPolicy fromConfig(String raw){
            if(raw!=null){
                for(RepositoryProxyPolicy policy:values()){
                    if(policy.configId.equalsIgnoreCase(raw.trim())){return policy;}
                }
            }
            return DEFAULT;
        }
    }
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
    /** Creates and returns the directory reserved for a host-managed FFmpeg installation. */
    default Path prepareManagedFfmpegDirectory() throws IOException {
        throw new UnsupportedOperationException("Managed FFmpeg storage is not supported by this host");
    }
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

    /** Executes one authenticated request against the host-owned local GUI API. */
    default GuiResponse exchangeGui(GuiRequest request) {
        throw new UnsupportedOperationException("Local GUI requests are not supported by this host");
    }

    /** Reads one local GUI endpoint. Relative paths are resolved below {@code /api/gui/}. */
    default GuiResponse guiGet(String path, int readTimeoutMillis) {
        return exchangeGui(GuiRequest.get(path, readTimeoutMillis));
    }

    /** Posts a JSON-compatible JDK value to one local GUI endpoint. */
    default GuiResponse guiPostJson(String path, Object body, int readTimeoutMillis) {
        return exchangeGui(GuiRequest.json(path, body, readTimeoutMillis, null));
    }

    /** Posts a plugin-owned GUI action with the discovery-bound owner header. */
    default GuiResponse guiPostJson(String path, Object body, int readTimeoutMillis, String ownerPluginId) {
        return exchangeGui(GuiRequest.json(path, body, readTimeoutMillis, ownerPluginId));
    }

    /** Sends a form request to one local GUI endpoint. */
    default GuiResponse guiForm(String method, String path, String body, int readTimeoutMillis) {
        return exchangeGui(GuiRequest.form(method, path, body, readTimeoutMillis));
    }

    /** Returns the host-owned onboarding persistence snapshot. */
    default OnboardingSnapshot onboardingState(String rootFolder) {
        throw new UnsupportedOperationException("Onboarding state is not supported by this host");
    }

    /** Persists the current onboarding page. */
    default boolean saveOnboardingProgress(int step) { return false; }
    /** Marks the onboarding guide entry as seen. */
    default boolean markOnboardingSeen() { return false; }
    /** Marks proxy configuration as completed. */
    default boolean markOnboardingProxyConfigured() { return false; }
    /** Marks the onboarding wizard as finished. */
    default boolean markOnboardingFinished() { return false; }
    /** Clears all persisted onboarding markers. */
    default boolean clearOnboardingState() { return false; }

    /** Supported request body encodings for the local GUI transport. */
    enum GuiBodyFormat { NONE, JSON, FORM }

    /** Toolkit-neutral local GUI request. */
    record GuiRequest(String method, String path, Object body, GuiBodyFormat bodyFormat,
                      int readTimeoutMillis, int maxResponseBytes, String ownerPluginId,
                      String languageTag) {
        private static final int MAX_GET_BYTES = 1024 * 1024;
        private static final int MAX_POST_BYTES = 64 * 1024;

        public GuiRequest {
            method = method == null ? "GET" : method.trim().toUpperCase(java.util.Locale.ROOT);
            if (!method.equals("GET") && !method.equals("POST")) {
                throw new IllegalArgumentException("Only GET and POST are supported");
            }
            String requestedPath = path == null ? "" : path.trim();
            path = requestedPath.startsWith("/api/gui/")
                    ? requestedPath
                    : "/api/gui/" + requestedPath.replaceFirst("^/+", "");
            if (path.contains("..") || path.indexOf('\r') >= 0 || path.indexOf('\n') >= 0) {
                throw new IllegalArgumentException("Unsafe local GUI path");
            }
            bodyFormat = bodyFormat == null ? GuiBodyFormat.NONE : bodyFormat;
            readTimeoutMillis = Math.max(1, readTimeoutMillis);
            maxResponseBytes = Math.max(1, maxResponseBytes);
            ownerPluginId = ownerPluginId == null || ownerPluginId.isBlank() ? null : ownerPluginId.trim();
            languageTag = languageTag == null || languageTag.isBlank()
                    ? java.util.Locale.getDefault().toLanguageTag()
                    : languageTag;
        }

        public static GuiRequest get(String path, int timeout) {
            return new GuiRequest("GET", path, null, GuiBodyFormat.NONE, timeout, MAX_GET_BYTES, null, null);
        }
        public static GuiRequest json(String path, Object body, int timeout, String ownerPluginId) {
            return new GuiRequest("POST", path, body, GuiBodyFormat.JSON, timeout, MAX_POST_BYTES,
                    ownerPluginId, null);
        }
        public static GuiRequest form(String method, String path, String body, int timeout) {
            return new GuiRequest(method, path, body, body == null ? GuiBodyFormat.NONE : GuiBodyFormat.FORM,
                    timeout, MAX_POST_BYTES, null, null);
        }
    }

    /** Reachability, HTTP status and parsed response of one local GUI request. */
    record GuiResponse(boolean reachable, int status, GuiValue body, String rawBody,
                       boolean bodyLimitExceeded) {
        public GuiResponse { rawBody = rawBody == null ? "" : rawBody; }
        public boolean is2xx() { return status >= 200 && status < 300; }
        public boolean successful() { return status == 200; }
        public boolean responseParsed() { return body != null; }
        public static GuiResponse unreachable() { return new GuiResponse(false, 0, null, "", false); }
    }

    /** Read-only JSON value backed only by JDK maps, lists and scalar types. */
    final class GuiValue implements Iterable<GuiValue> {
        private static final Object MISSING = new Object();
        private static final GuiValue MISSING_VALUE = new GuiValue(MISSING);
        private final Object value;
        private GuiValue(Object value) { this.value = value; }
        public static GuiValue of(Object value) { return new GuiValue(value); }
        public GuiValue path(String field) {
            if (value instanceof Map<?, ?> map && map.containsKey(field)) return of(map.get(field));
            return MISSING_VALUE;
        }
        public GuiValue path(int index) {
            if (value instanceof List<?> list && index >= 0 && index < list.size()) return of(list.get(index));
            return MISSING_VALUE;
        }
        public GuiValue get(String field) {
            return value instanceof Map<?, ?> map && map.containsKey(field) ? of(map.get(field)) : null;
        }
        public boolean hasNonNull(String field) {
            GuiValue child = get(field);
            return child != null && !child.isNull();
        }
        public boolean isMissingNode() { return value == MISSING; }
        public boolean isNull() { return value == null; }
        public boolean isArray() { return value instanceof List<?>; }
        public boolean isObject() { return value instanceof Map<?, ?>; }
        public boolean isBoolean() { return value instanceof Boolean; }
        public boolean isNumber() { return value instanceof Number; }
        public boolean isTextual() { return value instanceof String; }
        public boolean isValueNode() {
            return value == null || value instanceof String || value instanceof Number || value instanceof Boolean;
        }
        public boolean isEmpty() {
            if (value instanceof List<?> list) return list.isEmpty();
            if (value instanceof Map<?, ?> map) return map.isEmpty();
            if (value instanceof String text) return text.isEmpty();
            return false;
        }
        public String asText() { return asText(""); }
        public String asText(String fallback) {
            return isValueNode() && value != null ? String.valueOf(value) : fallback;
        }
        public boolean asBoolean() { return asBoolean(false); }
        public boolean asBoolean(boolean fallback) {
            if (value instanceof Boolean bool) return bool;
            if (value instanceof String text) {
                if ("true".equalsIgnoreCase(text)) return true;
                if ("false".equalsIgnoreCase(text)) return false;
            }
            return fallback;
        }
        public int asInt() { return asInt(0); }
        public int asInt(int fallback) {
            if (value instanceof Number number) return number.intValue();
            try { return value == null ? fallback : Integer.parseInt(String.valueOf(value)); }
            catch (NumberFormatException ignored) { return fallback; }
        }
        public long asLong() { return asLong(0L); }
        public long asLong(long fallback) {
            if (value instanceof Number number) return number.longValue();
            try { return value == null ? fallback : Long.parseLong(String.valueOf(value)); }
            catch (NumberFormatException ignored) { return fallback; }
        }
        @Override public Iterator<GuiValue> iterator() {
            if (!(value instanceof List<?> list)) return java.util.Collections.emptyIterator();
            return list.stream().map(GuiValue::of).iterator();
        }
    }

    /** Persisted onboarding state plus setup completion. */
    record OnboardingSnapshot(boolean seen, boolean proxyConfigured, int progress,
                              boolean finished, boolean setupComplete) {
        public boolean complete() { return finished && setupComplete; }
    }

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
     * @param source installation origin
     */
    record FfmpegInstallation(Path ffmpegPath, Path ffprobePath, Path homeDir, FfmpegSource source) {}
    /** Stable origin of an FFmpeg installation. */
    enum FfmpegSource { MANAGED, BUNDLED, SYSTEM }
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
    /** Stable stages reported while installing managed FFmpeg. */
    enum FfmpegInstallStage { CONNECTING, DOWNLOADING, EXTRACTING, COMPLETED }
    /** Receives coarse progress while installing managed FFmpeg. */
    @FunctionalInterface interface FfmpegProgressListener {
        /**
         * Reports the current stage and units.
         *
         * @param stage stable progress stage
         * @param current completed units
         * @param total total units, or zero when unknown
         */
        void onProgress(FfmpegInstallStage stage, long current, long total);
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

    /**
     * Checks artwork folders recorded in the selected database.
     *
     * @param databasePath SQLite database path
     * @return total active artwork count and inaccessible entries
     * @throws Exception when the database cannot be read
     */
    default FolderCheckResult checkArtworkFolders(Path databasePath) throws Exception {
        throw new UnsupportedOperationException("Artwork folder checking is not supported by this host");
    }

    /**
     * Updates the original or moved folder for one artwork.
     *
     * @param databasePath SQLite database path
     * @param artworkId artwork identifier
     * @param moved whether the moved-folder column is selected
     * @param newPath replacement folder path
     * @throws Exception when the update fails
     */
    default void updateArtworkFolder(Path databasePath, long artworkId, boolean moved, String newPath) throws Exception {
        throw new UnsupportedOperationException("Artwork folder updates are not supported by this host");
    }

    /**
     * Loads persisted image-classifier settings.
     *
     * @param rootFolder application download root
     * @return classifier settings
     * @throws IOException when settings cannot be read
     */
    default ImageClassifierSettings loadImageClassifierSettings(String rootFolder) throws IOException {
        throw new UnsupportedOperationException("Image classifier settings are not supported by this host");
    }

    /**
     * Persists image-classifier settings.
     *
     * @param rootFolder application download root
     * @param settings settings to persist
     * @throws IOException when settings cannot be written
     */
    default void saveImageClassifierSettings(String rootFolder, ImageClassifierSettings settings) throws IOException {
        throw new UnsupportedOperationException("Image classifier settings are not supported by this host");
    }

    /**
     * Tests whether a classifier path is an existing directory.
     *
     * @param path path to test
     * @return whether the path is an existing directory
     */
    default boolean isImageClassifierDirectory(Path path) {
        throw new UnsupportedOperationException("Image classifier paths are not supported by this host");
    }

    /**
     * Lists classifier work folders in display order.
     *
     * @param parent parent folder
     * @return ordered child folders
     * @throws IOException when the folder cannot be read
     */
    default List<Path> listImageClassifierFolders(Path parent) throws IOException {
        throw new UnsupportedOperationException("Image classifier folders are not supported by this host");
    }

    /**
     * Lists supported images in one classifier work folder.
     *
     * @param folder work folder
     * @return ordered image paths
     * @throws IOException when the folder cannot be read
     */
    default List<Path> listImageClassifierImages(Path folder) throws IOException {
        throw new UnsupportedOperationException("Image classifier images are not supported by this host");
    }

    /**
     * Deletes a classifier work folder only when it is empty.
     *
     * @param folder work folder
     * @throws IOException when the folder cannot be inspected or deleted
     */
    default void deleteImageClassifierFolderIfEmpty(Path folder) throws IOException {
        throw new UnsupportedOperationException("Image classifier cleanup is not supported by this host");
    }

    /**
     * Resolves the configured classifier server, including the HTTP/HTTPS fallback.
     *
     * @param configuredUrl configured server URL
     * @return availability and the URL that actually responded
     */
    default ImageClassifierServer checkImageClassifierServer(String configuredUrl) {
        throw new UnsupportedOperationException("Image classifier server checks are not supported by this host");
    }

    /**
     * Resolves artwork identity and optional metadata for a classifier folder.
     *
     * @param folder classifier work folder
     * @param server previously resolved server status
     * @return artwork metadata, or empty when no positive artwork ID can be resolved
     */
    default Optional<ImageClassifierArtwork> resolveImageClassifierArtwork(Path folder, ImageClassifierServer server) {
        throw new UnsupportedOperationException("Image classifier artwork lookup is not supported by this host");
    }

    /**
     * Copies, removes and records one classified artwork.
     *
     * <p>Copy failures are rolled back and thrown. Source-deletion failures are reported to
     * {@code deleteFailureHandler}; returning {@code true} retries deletion, while returning
     * {@code false} keeps the copied destination and any remaining source files.</p>
     *
     * @param sourceFolder source work folder
     * @param images source images
     * @param artworkId artwork identifier
     * @param targetFolder selected classifier destination
     * @param server previously resolved server status
     * @param deleteFailureHandler user decision callback for source-deletion failures
     * @return actual destination folder
     * @throws IOException when destination creation or copying fails
     */
    default Path classifyImageFolder(Path sourceFolder, List<Path> images, long artworkId, Path targetFolder,
                                     ImageClassifierServer server,
                                     ImageClassifierDeleteFailureHandler deleteFailureHandler) throws IOException {
        throw new UnsupportedOperationException("Image classification is not supported by this host");
    }

    /**
     * Artwork folder reported by the folder checker.
     *
     * @param artworkId artwork identifier
     * @param title artwork title
     * @param path inaccessible path, possibly {@code null}
     * @param moved whether the moved-folder column supplied the path
     */
    record FolderArtwork(long artworkId, String title, String path, boolean moved) {}

    /**
     * Aggregate folder-check result.
     *
     * @param total total active artwork count
     * @param inaccessible inaccessible artwork folders
     */
    record FolderCheckResult(int total, List<FolderArtwork> inaccessible) {
        /** Copies the result list so callers cannot mutate host state. */
        public FolderCheckResult {
            inaccessible = inaccessible == null ? List.of() : List.copyOf(inaccessible);
        }
    }

    /**
     * One configured classifier destination.
     *
     * @param folder destination folder
     * @param remark user-visible remark
     */
    record ImageClassifierTarget(String folder, String remark) {}

    /**
     * Persisted classifier settings.
     *
     * @param defaultFolder default source parent folder
     * @param showSkipButton whether the skip button is visible
     * @param serverUrl configured backend URL
     * @param targets classifier destinations
     */
    record ImageClassifierSettings(String defaultFolder, boolean showSkipButton, String serverUrl,
                                   List<ImageClassifierTarget> targets) {
        /** Normalizes nullable scalar values and copies the target list. */
        public ImageClassifierSettings {
            defaultFolder = defaultFolder == null ? "" : defaultFolder;
            serverUrl = serverUrl == null || serverUrl.isBlank() ? "http://localhost:6999" : serverUrl.trim();
            targets = targets == null ? List.of() : List.copyOf(targets);
        }
    }

    /**
     * Resolved classifier server status.
     *
     * @param available whether the server responded successfully
     * @param url configured or successfully resolved URL
     */
    record ImageClassifierServer(boolean available, String url) {}

    /**
     * Artwork metadata used by the classifier view.
     *
     * @param artworkId artwork identifier
     * @param title optional title
     * @param xRestrict optional Pixiv restriction value
     */
    record ImageClassifierArtwork(long artworkId, String title, Integer xRestrict) {}

    /** Receives source-deletion failures and returns whether deletion should be retried. */
    @FunctionalInterface
    interface ImageClassifierDeleteFailureHandler {
        /**
         * Handles one failed deletion attempt.
         *
         * @param detail failure detail
         * @param sourceFolder source folder that still exists
         * @return {@code true} to retry, {@code false} to keep remaining source files
         */
        boolean retry(String detail, Path sourceFolder);
    }
}
