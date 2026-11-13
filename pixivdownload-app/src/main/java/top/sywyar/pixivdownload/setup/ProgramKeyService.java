package top.sywyar.pixivdownload.setup;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.plugin.registry.PluginRegistry;
import top.sywyar.pixivdownload.plugin.registry.route.RouteAccessRegistry;
import top.sywyar.pixivdownload.plugin.api.web.ProgramIdentity;
import top.sywyar.pixivdownload.gui.media.DesktopMediaToolRegistry;
import top.sywyar.pixivdownload.gui.importing.DesktopFileImportToolRegistry;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/** 管理程序凭据与当前权限目录；路由是否准入仍由 AuthFilter 决定。 */
@Service
public final class ProgramKeyService {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(ProgramKeyService.class);
    private final ProgramKeyStore store;
    private final RouteAccessRegistry routes;
    private final PluginRegistry plugins;
    private final DesktopMediaToolRegistry media;
    private final DesktopFileImportToolRegistry imports;
    private final top.sywyar.pixivdownload.core.download.DownloadLifecycleRegistry downloads;

    private final java.util.function.Supplier<Set<String>> batchOwners;

    @Autowired
    public ProgramKeyService(RouteAccessRegistry routes, PluginRegistry plugins,
                             DesktopMediaToolRegistry media, DesktopFileImportToolRegistry imports,
                             top.sywyar.pixivdownload.core.download.DownloadLifecycleRegistry downloads,
                             top.sywyar.pixivdownload.plugin.api.schedule.capability.ScheduleCapabilityAccess schedules) {
        this(RuntimeFiles.programKeysPath(), routes, plugins, media, imports, downloads, () -> schedules.snapshot().owners().stream()
                .filter(owner -> !owner.sourceTypes().isEmpty() || !owner.workTypes().isEmpty())
                .map(owner -> owner.owner().featurePluginId()).collect(java.util.stream.Collectors.toUnmodifiableSet()));
    }
    ProgramKeyService(Path path, RouteAccessRegistry routes, PluginRegistry plugins) {
        this(path, routes, plugins, new DesktopMediaToolRegistry(), new DesktopFileImportToolRegistry());
    }
    ProgramKeyService(Path path, RouteAccessRegistry routes, PluginRegistry plugins,
                      DesktopMediaToolRegistry media, DesktopFileImportToolRegistry imports) {
        this(path, routes, plugins, media, imports, new top.sywyar.pixivdownload.core.download.DownloadLifecycleRegistry());
    }
    ProgramKeyService(Path path, RouteAccessRegistry routes, PluginRegistry plugins,
                      DesktopMediaToolRegistry media, DesktopFileImportToolRegistry imports,
                      top.sywyar.pixivdownload.core.download.DownloadLifecycleRegistry downloads) {
        this(path, routes, plugins, media, imports, downloads, Set::of);
    }
    ProgramKeyService(Path path, RouteAccessRegistry routes, PluginRegistry plugins,
                      DesktopMediaToolRegistry media, DesktopFileImportToolRegistry imports,
                      top.sywyar.pixivdownload.core.download.DownloadLifecycleRegistry downloads,
                      java.util.function.Supplier<Set<String>> batchOwners) {
        this.store = new ProgramKeyStore(path); this.routes = routes; this.plugins = plugins;
        this.media = media; this.imports = imports; this.downloads = downloads; this.batchOwners = batchOwners;
    }
    public record Permission(String id, String owner, String namespace, String ownerNamespace, String ownerLabelKey, String labelKey,
                             List<Endpoint> endpoints) {}
    public record Endpoint(String method, String path) {}

    public List<Permission> permissions() {
        var declarations = new TreeMap<String, Permission>();
        for (var registered : routes.routes()) {
            var plugin = plugins.find(registered.pluginId());
            if (plugin.isEmpty()) continue;
            for (var grant : registered.route().programPermissions().entrySet()) {
                String id = registered.pluginId() + ":" + grant.getValue().id();
                var endpoint = new Endpoint(grant.getKey().name(), registered.route().pathPattern());
                var previous = declarations.get(id);
                var endpoints = new ArrayList<Endpoint>();
                if (previous != null) endpoints.addAll(previous.endpoints());
                endpoints.add(endpoint);
                declarations.put(id, new Permission(id, registered.pluginId(), plugin.get().displayNamespace(), plugin.get().displayNamespace(),
                        plugin.get().displayName(), grant.getValue().labelKey(), List.copyOf(endpoints)));
            }
        }
        var mediaOwners = media.tools().stream().map(tool -> tool.identity().pluginId()).collect(java.util.stream.Collectors.toSet());
        var importOwners = imports.tools().stream().map(tool -> tool.identity().pluginId()).collect(java.util.stream.Collectors.toSet());
        var downloadOwners = downloads.submissionOwners();
        var currentBatchOwners = batchOwners.get();
        for (var registered : plugins.registeredPlugins()) {
            var targets = new ArrayList<String>();
            if (currentBatchOwners.contains(registered.id())) targets.add("batches.execute");
            if (downloadOwners.contains(registered.id())) targets.add("downloads.create");
            if (!registered.plugin().guiConfigContributions().isEmpty()) targets.addAll(List.of("settings.read", "settings.write"));
            if (mediaOwners.contains(registered.id())) targets.addAll(List.of("media.read", "media.preview", "media.execute", "media.cancel"));
            if (importOwners.contains(registered.id())) targets.addAll(List.of("file-import.read", "file-import.preview", "file-import.execute", "file-import.cancel", "file-import.acknowledge"));
            for (String permission : targets) {
                String id = registered.id() + ":" + permission;
                var routePermission = declarations.get("core:" + (permission.equals("batches.execute") ? "batches.create" : permission));
                if (routePermission == null) continue;
                var endpoints = new ArrayList<>(routePermission.endpoints());
                if (permission.equals("batches.execute") && declarations.containsKey("core:batches.resume"))
                    endpoints.addAll(declarations.get("core:batches.resume").endpoints());
                declarations.putIfAbsent(id, new Permission(id, registered.id(), "plugins", registered.plugin().displayNamespace(),
                        registered.plugin().displayName(), "program.permission." + permission, List.copyOf(endpoints)));
            }
        }
        return List.copyOf(declarations.values());
    }

    List<ProgramKeyStore.View> list() throws IOException { return store.list(); }
    ProgramKeyStore.Created create(ProgramKeyStore.Policy policy) throws IOException {
        validate(policy);
        var created = store.create(policy);
        LOG.info("Program key created: id={}, permissions={}, unrestrictedIp={}", created.key().id(),
                policy.permissions().size(), policy.anyIp());
        return created;
    }
    ProgramKeyStore.View update(UUID id, long revision, ProgramKeyStore.Policy policy) throws IOException {
        validate(policy);
        var changed = store.update(id, revision, policy);
        LOG.info("Program key updated: id={}, revision={}, permissions={}", id, changed.revision(), policy.permissions().size());
        return changed;
    }
    void revoke(UUID id, long revision) throws IOException {
        store.revoke(id, revision); LOG.info("Program key revoked: id={}", id);
    }
    ProgramIdentity authenticate(String secret, String address) throws IOException {
        var key = store.authenticate(secret, address);
        return key == null ? null : new ProgramIdentity(key.id(), key.policy().permissions());
    }
    private void validate(ProgramKeyStore.Policy policy) {
        var available = permissions().stream().map(Permission::id).collect(java.util.stream.Collectors.toSet());
        if (!available.containsAll(policy.permissions())) throw new IllegalArgumentException("PROGRAM_KEY_PERMISSIONS");
    }
}
