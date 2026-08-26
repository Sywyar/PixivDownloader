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
import top.sywyar.pixivdownload.plugin.api.gui.RepositoryConfigEntry;
import top.sywyar.pixivdownload.plugin.api.gui.TrustedKeyConfigEntry;
import top.sywyar.pixivdownload.plugin.signature.PluginTrustStores;
import top.sywyar.pixivdownload.tools.ArtworksBackFill;
import top.sywyar.pixivdownload.update.UpdateConfig;

import javax.sql.DataSource;
import java.io.IOException;
import java.awt.datatransfer.StringSelection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.awt.Desktop;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** 应用拥有的稳定桌面界面宿主契约实现。 */
final class AppDesktopUiHost implements DesktopUiHost {
    private final DesktopUiLocalApiClient localApiClient;
    private final ConfigFile applicationConfig;
    private final Supplier<DataSource> backfillDataSource;
    private final DesktopUiOnboardingState onboardingState = new DesktopUiOnboardingState();
    private final DesktopToolHistory toolHistory = new DesktopToolHistory(RuntimeFiles.guiStateDirectory());
    private final DesktopWindowStateStore windowState = new DesktopWindowStateStore(RuntimeFiles.guiStateDirectory());

    AppDesktopUiHost(int serverPort) {
        this(serverPort, yamlConfig(RuntimeFiles.resolveConfigYamlPath()));
    }

    AppDesktopUiHost(int serverPort, ConfigFile applicationConfig) {
        this(serverPort, applicationConfig, () -> BackendLifecycleManager.requiredBean(DataSource.class));
    }

    AppDesktopUiHost(int serverPort, ConfigFile applicationConfig, Supplier<DataSource> backfillDataSource) {
        this.localApiClient = new DesktopUiLocalApiClient(serverPort);
        this.applicationConfig = applicationConfig;
        this.backfillDataSource = java.util.Objects.requireNonNull(backfillDataSource, "backfillDataSource");
    }

    void resetIncompleteOnboardingState(String rootFolder) {
        var state = onboardingState.snapshot(rootFolder);
        if (!state.complete() && !state.setupComplete()) onboardingState.clear();
    }

    @Override public String applicationName(){return top.sywyar.pixivdownload.common.AppInfo.NAME;}
    @Override public String projectUrl(){return top.sywyar.pixivdownload.common.AppInfo.GITHUB_URL;}
    @Override public String releasesUrl(){return top.sywyar.pixivdownload.common.AppInfo.RELEASES_URL;}
    @Override public String defaultUpdateManifestUrl(){return top.sywyar.pixivdownload.update.UpdateConfig.DEFAULT_MANIFEST_URL;}
    @Override public String defaultNightlyUpdateManifestUrl(){return top.sywyar.pixivdownload.update.UpdateConfig.DEFAULT_NIGHTLY_MANIFEST_URL;}
    @Override public ConfigFile applicationConfig(){return applicationConfig;}
    @Override public List<top.sywyar.pixivdownload.plugin.api.gui.GuiConfigGroupContribution> coreConfigGroups() {
        return DesktopCoreConfigCatalog.groups();
    }
    @Override public List<top.sywyar.pixivdownload.plugin.api.gui.GuiConfigFieldContribution> coreConfigFields() {
        return DesktopCoreConfigCatalog.fields(this);
    }
    @Override public List<RepositoryConfigEntry> readPluginRepositories(ConfigFile configFile) throws IOException {
        return new top.sywyar.pixivdownload.gui.config.PluginRepositoryConfigEditor(configFile).read();
    }
    @Override public void writePluginRepositories(ConfigFile configFile, List<RepositoryConfigEntry> entries)
            throws IOException {
        new top.sywyar.pixivdownload.gui.config.PluginRepositoryConfigEditor(configFile).write(entries);
    }
    @Override public TrustedKeyConfigEntry officialPluginRepositoryKey() {
        var key = PluginTrustStores.builtInOfficialPluginRoot();
        return TrustedKeyConfigEntry.create(key.keyId(), key.algorithm(), key.publicKeySpkiBase64(),
                key.state().name(), key.publisher(), key.trustLabel());
    }
    @Override public ConfigFile pluginConfig(String pluginId){return propertiesConfig(resolvePluginConfigPath(pluginId,"properties"));}
    @Override public java.util.List<UiLocale> visibleLocales(){
        return top.sywyar.pixivdownload.i18n.LocaleCatalog.defaultCatalog().visibleLocales().stream().map(AppDesktopUiHost::mapLocale).toList();
    }
    @Override public java.util.Optional<UiLocale> matchLocale(String tag){
        return top.sywyar.pixivdownload.i18n.LocaleCatalog.defaultCatalog().match(tag).map(AppDesktopUiHost::mapLocale);
    }
    @Override public UiLocaleResolution resolveLocale(java.util.Locale requested){
        var catalog=top.sywyar.pixivdownload.i18n.LocaleCatalog.defaultCatalog();
        var target=catalog.resolve(requested);
        return new UiLocaleResolution(mapLocale(target),catalog.fallbackChain(target).stream().map(AppDesktopUiHost::mapLocale).toList());
    }
    @Override public java.util.Locale detectSystemLocale(){return top.sywyar.pixivdownload.i18n.SystemLocaleDetector.detectAndApply();}
    @Override public String stripTrailingPathSeparators(String value){return top.sywyar.pixivdownload.core.db.pathprefix.PathPrefixCodec.stripTrailingSeparators(value);}
    @Override public String defaultProxyHost(){return top.sywyar.pixivdownload.config.ProxyConfig.DEFAULT_HOST;}
    @Override public int defaultProxyPort(){return top.sywyar.pixivdownload.config.ProxyConfig.DEFAULT_PORT;}
    @Override public int minimumPasswordLength(){return top.sywyar.pixivdownload.setup.SetupService.MIN_PASSWORD_LENGTH;}
    @Override public int recommendedPasswordLength(){return top.sywyar.pixivdownload.setup.SetupService.RECOMMENDED_PASSWORD_LENGTH;}
    @Override public String defaultMaintenanceTime(){return top.sywyar.pixivdownload.maintenance.MaintenanceProperties.DEFAULT_TIME;}
    @Override public boolean validMaintenanceTime(String value){return top.sywyar.pixivdownload.maintenance.MaintenanceProperties.parseTime(value).isPresent();}
    @Override public java.util.Set<String> reservedPluginRepositoryIds(){
        return java.util.Set.of(top.sywyar.pixivdownload.plugin.catalog.repository.PluginRepository.OFFICIAL_ID,
                top.sywyar.pixivdownload.plugin.catalog.repository.PluginRepository.LEGACY_CONFIGURED_ID);
    }
    @Override public java.util.Set<String> validatedConfigKeys(java.util.Collection<String> keys)throws java.io.IOException{
        return top.sywyar.pixivdownload.gui.config.ConfigFileEditor.validatedKeySet(keys);
    }
    @Override public java.util.Map<String,String> validatedConfigValues(java.util.Map<String,String> values)throws java.io.IOException{
        return top.sywyar.pixivdownload.gui.config.ConfigFileEditor.validatedValues(values);
    }
    @Override public String requireSafeConfigKey(String key)throws java.io.IOException{return top.sywyar.pixivdownload.gui.config.ConfigFileEditor.requireSafeKey(key);}
    @Override public String requireSafeConfigValue(String value)throws java.io.IOException{return top.sywyar.pixivdownload.gui.config.ConfigFileEditor.requireSafeValue(value);}
    private static UiLocale mapLocale(top.sywyar.pixivdownload.i18n.LocaleDescriptor descriptor){
        return new UiLocale(descriptor.tag(),descriptor.nativeName(),descriptor.resourceSuffix());
    }
    private static ConfigFile yamlConfig(java.nio.file.Path path){
        var editor=new top.sywyar.pixivdownload.gui.config.ConfigFileEditor(path);
        return new ConfigFile(){
            @Override public java.util.Map<String,String> readAll(java.util.Collection<String> keys)throws java.io.IOException{return editor.readAll(keys);}
            @Override public void writeAll(java.util.Map<String,String> values)throws java.io.IOException{editor.writeAll(values);}
            @Override public void removeAll(java.util.Collection<String> keys)throws java.io.IOException{editor.removeAll(keys);}
            @Override public ConfigSnapshot snapshot()throws java.io.IOException{var snapshot=editor.snapshot();return new ConfigSnapshot(snapshot.existed(),snapshot.lines());}
            @Override public void restore(ConfigSnapshot snapshot)throws java.io.IOException{
                editor.restore(new top.sywyar.pixivdownload.gui.config.ConfigFileEditor.FileSnapshot(snapshot.existed(),snapshot.lines()));
            }
        };
    }
    private static ConfigFile propertiesConfig(java.nio.file.Path path){
        var editor=new top.sywyar.pixivdownload.gui.config.PropertiesConfigFileEditor(path);
        return new ConfigFile(){
            @Override public java.util.Map<String,String> readAll(java.util.Collection<String> keys)throws java.io.IOException{return editor.readAll(keys);}
            @Override public void writeAll(java.util.Map<String,String> values)throws java.io.IOException{editor.writeAll(values);}
            @Override public void removeAll(java.util.Collection<String> keys)throws java.io.IOException{editor.removeAll(keys);}
            @Override public ConfigSnapshot snapshot()throws java.io.IOException{var snapshot=editor.snapshot();return new ConfigSnapshot(snapshot.existed(),snapshot.lines());}
            @Override public void restore(ConfigSnapshot snapshot)throws java.io.IOException{
                editor.restore(new top.sywyar.pixivdownload.gui.config.PropertiesConfigFileEditor.FileSnapshot(snapshot.existed(),snapshot.lines()));
            }
        };
    }
    private final PluginCredentialStore credentialStore = new PluginCredentialStore();

    @Override public String message(String code, Object... arguments) { return MessageBundles.get(code, arguments); }
    @Override public String applicationVersion() { return AppVersion.getDisplayVersionOrDefault(""); }
    @Override public boolean launchedFromExecutable() { return AppInfo.isLaunchedFromExe(); }
    @Override public boolean currentVersionNightly() { return UpdateConfig.isCurrentVersionNightly(); }
    @Override public String guiToken() { return GuiTokenHolder.get(); }
    @Override public String guiTokenHeader() { return GuiTokenHolder.HEADER_NAME; }
    @Override public Path dataDirectory() { return RuntimeFiles.dataDirectory(); }
    @Override public Optional<WindowStateSnapshot> loadWindowState() { return windowState.load(); }
    @Override public boolean saveWindowState(WindowStateSnapshot state) { return windowState.save(state); }
    @Override public List<ToolHistoryEntry> toolHistory() { return toolHistory.entries(); }
    @Override public void recordToolHistory(ToolId toolId, ToolOutcome outcome, long startedAtEpochMs,
            Integer processedCount, Integer changedCount, Integer failedCount, Path logPath) {
        toolHistory.record(toolId, outcome, startedAtEpochMs, processedCount, changedCount, failedCount, logPath);
    }
    @Override public Path pluginsDirectory() { return RuntimeFiles.pluginsDirectory(); }
    @Override public Path resolvePluginConfigPath(String id, String extension) { return RuntimeFiles.resolvePluginConfigPath(id, extension); }
    @Override public Path resolveImageClassifierPath(String rootFolder) { return RuntimeFiles.resolveImageClassifierPath(rootFolder); }
    @Override public Path resolveSetupConfigPath(String rootFolder) { return RuntimeFiles.resolveSetupConfigPath(rootFolder); }
    @Override public Path resolveDatabasePath(String rootFolder) { return RuntimeFiles.resolveDatabasePath(rootFolder); }
    @Override public String readDownloadRootFromConfig(Path path, String fallback) { return RuntimeFiles.readDownloadRootFromConfig(path, fallback); }
    @Override public String normalizeRootFolder(String rootFolder) { return RuntimeFiles.normalizeRootFolder(rootFolder); }
    @Override public void openExternalUri(java.net.URI uri) throws Exception {
        Desktop.getDesktop().browse(uri);
    }
    @Override public void openLocalPath(Path path) throws Exception {
        Desktop.getDesktop().open(path.toFile());
    }
    @Override public void copyText(String text) {
        java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
    }

    @Override public BackendSnapshot backendSnapshot() { return map(BackendLifecycleManager.snapshot()); }
    @Override public AutoCloseable subscribeBackend(Consumer<BackendSnapshot> consumer) {
        BackendLifecycleManager.Listener listener = value -> consumer.accept(map(value));
        BackendLifecycleManager.addListener(listener);
        return () -> BackendLifecycleManager.removeListener(listener);
    }
    @Override public boolean startBackend(Runnable afterStart) { return BackendLifecycleManager.startAsync(afterStart); }
    @Override public boolean stopBackend(Runnable afterStop) { return BackendLifecycleManager.stopAsync(afterStop); }
    @Override public boolean restartBackend(Runnable afterRestart) { return BackendLifecycleManager.restartAsync(afterRestart); }
    @Override public void requestApplicationExit() {
        GuiLauncher.requestApplicationExit();
    }
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
                value.homeDir(), map(value.source())));
    }
    @Override public Path managedFfmpegDirectory() { return FfmpegLocator.managedToolsDir(); }
    @Override public Path prepareManagedFfmpegDirectory() throws IOException {
        return Files.createDirectories(managedFfmpegDirectory());
    }
    @Override public boolean supportsManagedFfmpegInstall() { return FfmpegInstaller.supportsManagedDownload(); }
    @Override public FfmpegInstallation installManagedFfmpeg(FfmpegProxy proxy, FfmpegProgressListener listener)
            throws IOException, InterruptedException {
        var value = FfmpegInstaller.installManaged(new FfmpegInstaller.ProxySettings(proxy.enabled(), proxy.host(), proxy.port()),
                (stage, current, total) -> listener.onProgress(map(stage), current, total));
        return new FfmpegInstallation(value.ffmpegPath(), value.ffprobePath(), value.homeDir(), map(value.source()));
    }

    private static FfmpegSource map(top.sywyar.pixivdownload.ffmpeg.FfmpegInstallation.Source source) {
        return switch (source) {
            case MANAGED -> FfmpegSource.MANAGED;
            case BUNDLED -> FfmpegSource.BUNDLED;
            case SYSTEM -> FfmpegSource.SYSTEM;
        };
    }

    private static FfmpegInstallStage map(FfmpegInstaller.ProgressStage stage) {
        return switch (stage) {
            case CONNECTING -> FfmpegInstallStage.CONNECTING;
            case DOWNLOADING -> FfmpegInstallStage.DOWNLOADING;
            case EXTRACTING -> FfmpegInstallStage.EXTRACTING;
            case COMPLETED -> FfmpegInstallStage.COMPLETED;
        };
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
        return ArtworksBackFill.countCandidates(map(options), requireBackfillDataSource());
    }
    @Override public BackfillSummary runBackfill(BackfillOptions options) throws Exception {
        var value = ArtworksBackFill.run(map(options), requireBackfillDataSource());
        return new BackfillSummary(value.totalCandidates(), value.processed(), value.filledAuthor(), value.filledR18(),
                value.filledAi(), value.filledDescription(), value.filledTags(), value.filledSeries(), value.deletedCount(),
                value.skipped(), value.previouslyUnreachable(), value.newlyUnreachable(), value.dryRun(), value.rateLimited());
    }

    private DataSource requireBackfillDataSource() {
        try {
            return java.util.Objects.requireNonNull(backfillDataSource.get(), "backfillDataSource result");
        } catch (RuntimeException failure) {
            throw new IllegalStateException(message("gui.message.backend-busy"), failure);
        }
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

    @Override public GuiResponse exchangeGui(GuiRequest request) { return localApiClient.exchange(request); }
    @Override public OnboardingSnapshot onboardingState(String rootFolder) { return onboardingState.snapshot(rootFolder); }
    @Override public boolean saveOnboardingProgress(int step) { return onboardingState.saveProgress(step); }
    @Override public boolean markOnboardingSeen() { return onboardingState.markSeen(); }
    @Override public boolean markOnboardingProxyConfigured() { return onboardingState.markProxyConfigured(); }
    @Override public boolean markOnboardingFinished() { return onboardingState.markFinished(); }
    @Override public boolean clearOnboardingState() { return onboardingState.clear(); }

    private final DesktopUiTools desktopUiTools = new DesktopUiTools();

    @Override public FolderCheckResult checkArtworkFolders(Path databasePath) throws Exception {
        return desktopUiTools.checkArtworkFolders(databasePath);
    }
    @Override public void updateArtworkFolder(Path databasePath, long artworkId, boolean moved, String newPath) throws Exception {
        desktopUiTools.updateArtworkFolder(databasePath, artworkId, moved, newPath);
    }
    @Override public ImageClassifierSettings loadImageClassifierSettings(String rootFolder) throws IOException {
        return desktopUiTools.loadImageClassifierSettings(rootFolder);
    }
    @Override public void saveImageClassifierSettings(String rootFolder, ImageClassifierSettings settings) throws IOException {
        desktopUiTools.saveImageClassifierSettings(rootFolder, settings);
    }
    @Override public boolean isImageClassifierDirectory(Path path) {
        return desktopUiTools.isImageClassifierDirectory(path);
    }
    @Override public List<Path> listImageClassifierFolders(Path parent) throws IOException {
        return desktopUiTools.listImageClassifierFolders(parent);
    }
    @Override public List<Path> listImageClassifierImages(Path folder) throws IOException {
        return desktopUiTools.listImageClassifierImages(folder);
    }
    @Override public void deleteImageClassifierFolderIfEmpty(Path folder) throws IOException {
        desktopUiTools.deleteImageClassifierFolderIfEmpty(folder);
    }
    @Override public ImageClassifierServer checkImageClassifierServer(String configuredUrl) {
        return desktopUiTools.checkImageClassifierServer(configuredUrl);
    }
    @Override public Optional<ImageClassifierArtwork> resolveImageClassifierArtwork(Path folder, ImageClassifierServer server) {
        return desktopUiTools.resolveImageClassifierArtwork(folder, server);
    }
    @Override public Path classifyImageFolder(Path sourceFolder, List<Path> images, long artworkId, Path targetFolder,
                                              ImageClassifierServer server,
                                              ImageClassifierDeleteFailureHandler deleteFailureHandler) throws IOException {
        return desktopUiTools.classifyImageFolder(sourceFolder, images, artworkId, targetFolder, server, deleteFailureHandler);
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
