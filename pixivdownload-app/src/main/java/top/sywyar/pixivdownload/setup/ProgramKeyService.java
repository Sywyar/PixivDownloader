package top.sywyar.pixivdownload.setup;

import com.fasterxml.jackson.annotation.JsonInclude;
import top.sywyar.pixivdownload.plugin.api.web.HttpApiNullable;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.plugin.registry.PluginRegistry;
import top.sywyar.pixivdownload.plugin.registry.route.RouteAccessRegistry;
import top.sywyar.pixivdownload.plugin.api.web.ProgramIdentity;
import top.sywyar.pixivdownload.gui.media.DesktopMediaToolRegistry;
import top.sywyar.pixivdownload.gui.importing.DesktopFileImportToolRegistry;
import top.sywyar.pixivdownload.plugin.api.schedule.capability.ScheduleCapabilityOwnerSnapshot;
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

    private final java.util.function.Supplier<List<ScheduleCapabilityOwnerSnapshot>> scheduleOwners;
    private final java.util.function.Supplier<List<top.sywyar.pixivdownload.plugin.management.PluginManagementService.PluginManagementEntry>> managementTargets;

    @Autowired
    public ProgramKeyService(RouteAccessRegistry routes, PluginRegistry plugins,
                             DesktopMediaToolRegistry media, DesktopFileImportToolRegistry imports,
                             top.sywyar.pixivdownload.core.download.DownloadLifecycleRegistry downloads,
                             top.sywyar.pixivdownload.plugin.api.schedule.capability.ScheduleCapabilityAccess schedules,
                             org.springframework.beans.factory.ObjectProvider<top.sywyar.pixivdownload.plugin.management.PluginManagementService> management) {
        this(RuntimeFiles.programKeysPath(), routes, plugins, media, imports, downloads, () -> schedules.snapshot().owners(),
                () -> management.getObject().list().plugins());
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
        this(path, routes, plugins, media, imports, downloads, List::of, List::of);
    }
    ProgramKeyService(Path path, RouteAccessRegistry routes, PluginRegistry plugins,
                      DesktopMediaToolRegistry media, DesktopFileImportToolRegistry imports,
                      top.sywyar.pixivdownload.core.download.DownloadLifecycleRegistry downloads,
                      java.util.function.Supplier<List<ScheduleCapabilityOwnerSnapshot>> scheduleOwners,
                      java.util.function.Supplier<List<top.sywyar.pixivdownload.plugin.management.PluginManagementService.PluginManagementEntry>> managementTargets) {
        this.store = new ProgramKeyStore(path); this.routes = routes; this.plugins = plugins;
        this.media = media; this.imports = imports; this.downloads = downloads;
        this.scheduleOwners = scheduleOwners;
        this.managementTargets = managementTargets;
    }
    public record Permission(String id, String owner, String namespace, @HttpApiNullable String ownerNamespace,
            String ownerLabelKey, String labelKey,
                             List<Endpoint> endpoints) {}
    public static final String SCHEDULE_AUTHOR_METADATA = "/api/pixiv/user/{userId}/meta/schedule";
    public static final String TASK_ID_POINTER = "request#/taskId";

    public record Condition(String keyFrom) {}
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Endpoint(String method, String path, Condition when) {
        public Endpoint(String method, String path) { this(method, path, null); }
    }

    public List<Permission> permissions() {
        var declarations = new TreeMap<String, Permission>();
        for (var registered : routes.routes()) {
            var plugin = plugins.find(registered.pluginId());
            if (plugin.isEmpty()) continue;
            for (var grant : registered.route().programPermissions().entrySet()) {
                if (ProgramAccess.isSelf(registered, grant.getKey())) continue;
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
        var credentials = declarations.get("core:credentials.read");
        if (credentials != null) declarations.put("core:credentials.use", new Permission("core:credentials.use", "core",
                credentials.namespace(), credentials.ownerNamespace(), credentials.ownerLabelKey(), "permission.credentials.use",
                List.of(new Endpoint("HEADER", "X-Acquisition-Credentials"))));
        var mediaOwners = media.tools().stream().map(tool -> tool.identity().pluginId()).collect(java.util.stream.Collectors.toSet());
        var importOwners = imports.tools().stream().map(tool -> tool.identity().pluginId()).collect(java.util.stream.Collectors.toSet());
        var downloadOwners = downloads.submissionOwners();
        var sourceOwners = new HashSet<String>();
        var workOwners = new HashSet<String>();
        var policyOwners = new HashSet<String>();
        for (var capability : scheduleOwners.get()) {
            String owner = capability.owner().featurePluginId();
            if (!capability.sourceTypes().isEmpty()) sourceOwners.add(owner);
            if (!capability.workTypes().isEmpty()) workOwners.add(owner);
            if (!capability.credentialPolicyIds().isEmpty()) policyOwners.add(owner);
        }
        for (var registered : plugins.registeredPlugins()) {
            var targets = new ArrayList<String>();
            boolean sourceOwner = sourceOwners.contains(registered.id());
            if (sourceOwner || workOwners.contains(registered.id())) targets.add("batches.execute");
            if (sourceOwner || policyOwners.contains(registered.id())) targets.add("schedule.manage");
            if (downloadOwners.contains(registered.id())) targets.add("downloads.create");
            if (!registered.plugin().guiConfigContributions().isEmpty()) targets.addAll(List.of("settings.read", "settings.write"));
            if (mediaOwners.contains(registered.id())) targets.addAll(List.of("media.read", "media.preview", "media.execute", "media.cancel"));
            if (importOwners.contains(registered.id())) targets.addAll(List.of("file-import.read", "file-import.preview", "file-import.execute", "file-import.cancel", "file-import.acknowledge"));
            for (String permission : targets) {
                String id = registered.id() + ":" + permission;
                String routeId = switch (permission) {
                    case "batches.execute" -> "batches.create";
                    case "schedule.manage" -> "schedule.read";
                    default -> permission;
                };
                var routePermission = declarations.get("core:" + routeId);
                if (routePermission == null) continue;
                var endpoints = new ArrayList<>(routePermission.endpoints());
                if (permission.equals("schedule.manage")) {
                    endpoints.clear();
                    declarations.values().stream().filter(value -> value.id().startsWith("core:schedule.")
                            && !value.id().equals("core:schedule.sources.read"))
                            .forEach(value -> endpoints.addAll(value.endpoints()));
                    var refresh = declarations.get("core:authors.refresh");
                    if (sourceOwner && refresh != null) refresh.endpoints().stream()
                            .filter(endpoint -> endpoint.method().equals("POST")
                                    && endpoint.path().equals(SCHEDULE_AUTHOR_METADATA.replace("{userId}", "*")))
                            .map(endpoint -> new Endpoint(endpoint.method(), endpoint.path(), new Condition(TASK_ID_POINTER)))
                            .forEach(endpoints::add);
                }
                if (permission.equals("batches.execute") && declarations.containsKey("core:batches.resume"))
                    endpoints.addAll(declarations.get("core:batches.resume").endpoints());
                var direct = declarations.get(id);
                if (direct == null) {
                    declarations.put(id, new Permission(id, registered.id(), "plugins", registered.plugin().displayNamespace(),
                            registered.plugin().displayName(), "program.permission." + permission, List.copyOf(endpoints)));
                } else {
                    endpoints.addAll(direct.endpoints());
                    declarations.put(id, new Permission(id, direct.owner(), direct.namespace(), direct.ownerNamespace(),
                            direct.ownerLabelKey(), direct.labelKey(), endpoints.stream().distinct().toList()));
                }
            }
        }
        var managementEndpoints = declarations.values().stream()
                .filter(value -> value.id().startsWith("core:plugins.") && !value.id().equals("core:plugins.read")
                        && !value.id().equals("core:plugins.install") && !value.id().equals("core:plugins.revocations.refresh"))
                .flatMap(value -> value.endpoints().stream()).toList();
        if (!managementEndpoints.isEmpty()) {
            for (var target : managementTargets.get()) {
                if (!top.sywyar.pixivdownload.plugin.management.PluginManagementService.allowsManagementGrant(target)) continue;
                String id = target.id() + ":plugins.manage";
                declarations.put(id, new Permission(id, target.id(), "plugins", target.displayNamespace(),
                        target.displayNameKey(), "program.permission.plugins.manage", managementEndpoints));
            }
        }
        return List.copyOf(declarations.values());
    }

    List<ProgramKeyStore.View> list() throws IOException { return store.list(); }
    ProgramKeyStore.View get(UUID id) throws IOException { return store.get(id); }
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
    ProgramKeyStore.View transport(UUID id, long revision, ProgramKeyStore.Transport transport) throws IOException {
        var result = store.transport(id, revision, transport);
        LOG.info("Program key transport policy updated: id={}, revision={}, transport={}", id, result.revision(), transport);
        return result;
    }
    void revoke(UUID id, long revision) throws IOException {
        store.revoke(id, revision); LOG.info("Program key revoked: id={}", id);
    }
    ProgramIdentity authenticate(String secret, String address, boolean secure) throws IOException {
        var key = store.authenticate(secret, address, secure);
        return key == null ? null : new ProgramIdentity(key.id(), key.policy().permissions());
    }
    private void validate(ProgramKeyStore.Policy policy) {
        var available = permissions().stream().map(Permission::id).collect(java.util.stream.Collectors.toSet());
        if (!available.containsAll(policy.permissions())) throw new IllegalArgumentException("PROGRAM_KEY_PERMISSIONS");
    }
}
