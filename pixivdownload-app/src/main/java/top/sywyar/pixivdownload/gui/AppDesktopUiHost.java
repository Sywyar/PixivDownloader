package top.sywyar.pixivdownload.gui;

import top.sywyar.pixivdownload.common.AppInfo;
import top.sywyar.pixivdownload.common.AppVersion;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.config.credential.PluginCredentialStore;
import top.sywyar.pixivdownload.ffmpeg.FfmpegInstaller;
import top.sywyar.pixivdownload.ffmpeg.FfmpegLocator;
import top.sywyar.pixivdownload.i18n.MessageBundles;
import top.sywyar.pixivdownload.maintenance.MaintenanceStatusHolder;
import top.sywyar.pixivdownload.migration.JsonToSqliteMigration;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiHost;
import top.sywyar.pixivdownload.tools.ArtworksBackFill;
import top.sywyar.pixivdownload.update.UpdateConfig;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/** App-owned implementation of the stable desktop UI host contract. */
final class AppDesktopUiHost implements DesktopUiHost {
    private final PluginCredentialStore credentialStore = new PluginCredentialStore();

    @Override public String message(String code, Object... arguments) { return MessageBundles.get(code, arguments); }
    @Override public String applicationVersion() { return AppVersion.getDisplayVersionOrDefault(""); }
    @Override public boolean launchedFromExecutable() { return AppInfo.isLaunchedFromExe(); }
    @Override public boolean currentVersionNightly() { return UpdateConfig.isCurrentVersionNightly(); }
    @Override public String guiToken() { return GuiTokenHolder.get(); }
    @Override public String guiTokenHeader() { return GuiTokenHolder.HEADER_NAME; }
    @Override public Path dataDirectory() { return RuntimeFiles.dataDirectory(); }
    @Override public Path guiStateDirectory() { return RuntimeFiles.guiStateDirectory(); }
    @Override public Path pluginsDirectory() { return RuntimeFiles.pluginsDirectory(); }
    @Override public Path resolvePluginConfigPath(String id, String extension) { return RuntimeFiles.resolvePluginConfigPath(id, extension); }
    @Override public Path resolveImageClassifierPath(String rootFolder) { return RuntimeFiles.resolveImageClassifierPath(rootFolder); }
    @Override public Path resolveSetupConfigPath(String rootFolder) { return RuntimeFiles.resolveSetupConfigPath(rootFolder); }
    @Override public Path resolveDatabasePath(String rootFolder) { return RuntimeFiles.resolveDatabasePath(rootFolder); }
    @Override public String readDownloadRootFromConfig(Path path, String fallback) { return RuntimeFiles.readDownloadRootFromConfig(path, fallback); }
    @Override public String normalizeRootFolder(String rootFolder) { return RuntimeFiles.normalizeRootFolder(rootFolder); }

    @Override public BackendSnapshot backendSnapshot() { return map(BackendLifecycleManager.snapshot()); }
    @Override public AutoCloseable subscribeBackend(Consumer<BackendSnapshot> consumer) {
        BackendLifecycleManager.Listener listener = value -> consumer.accept(map(value));
        BackendLifecycleManager.addListener(listener);
        return () -> BackendLifecycleManager.removeListener(listener);
    }
    @Override public boolean startBackend(Runnable afterStart) { return BackendLifecycleManager.startAsync(afterStart); }
    @Override public boolean stopBackend(Runnable afterStop) { return BackendLifecycleManager.stopAsync(afterStop); }
    @Override public boolean restartBackend(Runnable afterRestart) { return BackendLifecycleManager.restartAsync(afterRestart); }
    @Override public boolean autoStartSupported() { return AutoStartManager.isSupported(); }
    @Override public boolean autoStartEnabled() { return AutoStartManager.isEnabled(); }
    @Override public void setAutoStartEnabled(boolean enabled) throws IOException, InterruptedException { AutoStartManager.setEnabled(enabled); }

    @Override public Map<String, String> readCredentials(String owner) throws IOException { return credentialStore.readAll(owner); }
    @Override public void updateCredentials(String owner, Map<String, String> updates) throws IOException { credentialStore.update(owner, updates); }
    @Override public void withCredentialLocks(Collection<String> owners, IoOperation operation) throws IOException {
        credentialStore.withOwnerLocks(owners, operation::run);
    }
    @Override public CredentialSnapshot snapshotCredentials(String owner) throws IOException {
        PluginCredentialStore.Snapshot value = credentialStore.snapshot(owner);
        return new CredentialSnapshot(value.existed(), value.content());
    }
    @Override public void restoreCredentials(String owner, CredentialSnapshot snapshot) throws IOException {
        credentialStore.restore(owner, new PluginCredentialStore.Snapshot(snapshot.existed(), snapshot.content()));
    }

    @Override public Optional<FfmpegInstallation> locateFfmpeg() {
        return FfmpegLocator.locate().map(value -> new FfmpegInstallation(value.ffmpegPath(), value.ffprobePath(),
                value.homeDir(), value.sourceMessageCode()));
    }
    @Override public Path managedFfmpegDirectory() { return FfmpegLocator.managedToolsDir(); }
    @Override public boolean supportsManagedFfmpegInstall() { return FfmpegInstaller.supportsManagedDownload(); }
    @Override public FfmpegInstallation installManagedFfmpeg(FfmpegProxy proxy, FfmpegProgressListener listener)
            throws IOException, InterruptedException {
        var value = FfmpegInstaller.installManaged(new FfmpegInstaller.ProxySettings(proxy.enabled(), proxy.host(), proxy.port()),
                listener::onProgress);
        return new FfmpegInstallation(value.ffmpegPath(), value.ffprobePath(), value.homeDir(), value.sourceMessageCode());
    }

    @Override public MaintenanceSnapshot maintenanceSnapshot() {
        var value = MaintenanceStatusHolder.snapshot();
        return new MaintenanceSnapshot(value.active(), value.trigger(), value.index(), value.total(), value.taskName(),
                value.taskStartedAt(), value.unitsDone(), value.unitsTotal());
    }
    @Override public BackfillOptions defaultBackfillOptions() { return map(ArtworksBackFill.Options.defaults()); }
    @Override public boolean supportsBackfillColumn(DatabaseColumn column) {
        return ArtworksBackFill.supportsDatabaseColumn(column.tableName(), column.columnName());
    }
    @Override public int countBackfillCandidates(BackfillOptions options) throws Exception {
        return ArtworksBackFill.countCandidates(map(options));
    }
    @Override public BackfillSummary runBackfill(BackfillOptions options) throws Exception {
        var value = ArtworksBackFill.run(map(options));
        return new BackfillSummary(value.totalCandidates(), value.processed(), value.filledAuthor(), value.filledR18(),
                value.filledAi(), value.filledDescription(), value.filledTags(), value.filledSeries(), value.deletedCount(),
                value.skipped(), value.previouslyUnreachable(), value.newlyUnreachable(), value.dryRun(), value.rateLimited());
    }
    @Override public int countMigrationCandidates(MigrationOptions options) throws Exception {
        return JsonToSqliteMigration.countCandidates(new JsonToSqliteMigration.Options(options.dbPath(), options.rootFolder()));
    }
    @Override public MigrationSummary runMigration(MigrationOptions options, Consumer<String> reporter) throws Exception {
        var value = JsonToSqliteMigration.run(new JsonToSqliteMigration.Options(options.dbPath(), options.rootFolder()), reporter);
        return new MigrationSummary(value.totalCandidates(), value.migrated(), value.skipped(), value.historyFileMissing(), value.message());
    }
    @Override public ToolLogSession openToolLog(String stem) throws Exception {
        Class<?> loggerType = stem.contains("migration") ? JsonToSqliteMigration.class : ArtworksBackFill.class;
        ToolHtmlLogSession delegate = ToolHtmlLogSession.open(stem, loggerType);
        return new ToolLogSession() {
            @Override public Path latestPath() { return delegate.latestPath(); }
            @Override public Path sessionPath() { return delegate.sessionPath(); }
            @Override public void openLatestInBrowser() throws Exception { delegate.openLatestInBrowser(); }
            @Override public void close() { delegate.close(); }
        };
    }

    private static BackendSnapshot map(BackendLifecycleManager.Snapshot value) {
        return new BackendSnapshot(BackendState.valueOf(value.state().name()), value.error());
    }
    private static BackfillOptions map(ArtworksBackFill.Options value) {
        return new BackfillOptions(value.dbPath(), value.proxyHost(), value.proxyPort(), value.useProxy(), value.delayMs(), value.limit(), value.dryRun());
    }
    private static ArtworksBackFill.Options map(BackfillOptions value) {
        return new ArtworksBackFill.Options(value.dbPath(), value.proxyHost(), value.proxyPort(), value.useProxy(), value.delayMs(), value.limit(), value.dryRun());
    }
}
