package top.sywyar.pixivdownload.plugin;

import top.sywyar.pixivdownload.author.AuthorSchemaContribution;
import top.sywyar.pixivdownload.collection.CollectionSchemaContribution;
import top.sywyar.pixivdownload.core.db.schema.contribution.ArtworkSchemaContribution;
import top.sywyar.pixivdownload.core.db.schema.contribution.CoreSchemaContribution;
import top.sywyar.pixivdownload.core.db.schema.contribution.FileNameSchemaContribution;
import top.sywyar.pixivdownload.core.db.schema.contribution.ImageHashSchemaContribution;
import top.sywyar.pixivdownload.core.db.schema.contribution.NovelSchemaContribution;
import top.sywyar.pixivdownload.core.db.schema.contribution.StatisticsSchemaContribution;
import top.sywyar.pixivdownload.core.db.schema.contribution.TagSchemaContribution;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginKind;
import top.sywyar.pixivdownload.plugin.api.schema.SchemaContribution;
import top.sywyar.pixivdownload.plugin.api.web.AccessPolicy;
import top.sywyar.pixivdownload.plugin.api.web.HttpMethod;
import top.sywyar.pixivdownload.plugin.api.web.I18nContribution;
import top.sywyar.pixivdownload.plugin.api.web.NavigationContribution;
import top.sywyar.pixivdownload.plugin.api.web.NavigationPlacements;
import top.sywyar.pixivdownload.plugin.api.web.StaticResourceContribution;
import top.sywyar.pixivdownload.plugin.api.web.WebRouteContribution;
import top.sywyar.pixivdownload.core.schedule.db.ScheduleSchemaContribution;
import top.sywyar.pixivdownload.series.MangaSeriesSchemaContribution;
import top.sywyar.pixivdownload.setup.guest.persistence.GuestInviteSchemaContribution;
import top.sywyar.pixivdownload.setup.ProgramAccess;

import java.util.List;
import java.util.Map;
import top.sywyar.pixivdownload.plugin.api.web.ProgramPermission;
import java.util.Set;

/**
 * 核心插件：承载核心层（schema、公共静态资源、基础路由等）的 contribution 声明。
 * <p>
 * 受管 schema 按领域拆成独立 contribution 类。宿主共享长期事实表由核心声明，
 * 插件自有长期事实表由对应功能插件通过 {@code schema()} 声明；运行期 registry
 * 以注册中心捕获的稳定插件 id 盖章所有权，并从安装态插件合并 schema，插件禁用
 * 不会导致其已声明的受管表缺席。
 */
public class CorePlugin implements PixivFeaturePlugin {

    @Override public List<String> credentialKeys() {
        return List.of(top.sywyar.pixivdownload.config.credential.AcquisitionCredentialLibrary.KEY);
    }

    @Override
    public String id() {
        return "core";
    }

    // 展示名 / 简介为纯 i18n key，namespace 由 displayNamespace() 提供。核心插件无单一内容 namespace、GUI 不呈现它
    // （必选），但 Web 插件管理页会展示并解析；故 displayNamespace() 显式借用插件管理页的 plugins namespace。
    @Override
    public String displayNamespace() {
        return "plugins";
    }

    @Override
    public String displayName() {
        return "name.core";
    }

    @Override
    public String description() {
        return "summary.core";
    }

    // 卡片展示用受控 token（非 URL / CSS / 远程资源；由插件管理页本地白名单映射）：核心壳。
    @Override
    public String iconKey() {
        return "gear";
    }

    @Override
    public String colorToken() {
        return "blue";
    }

    @Override
    public PluginKind kind() {
        return PluginKind.CORE;
    }

    @Override
    public List<SchemaContribution> schema() {
        return List.of(
                CoreSchemaContribution.CONTRIBUTION,
                ArtworkSchemaContribution.CONTRIBUTION,
                FileNameSchemaContribution.CONTRIBUTION,
                StatisticsSchemaContribution.CONTRIBUTION,
                TagSchemaContribution.CONTRIBUTION,
                ImageHashSchemaContribution.CONTRIBUTION,
                AuthorSchemaContribution.CONTRIBUTION,
                MangaSeriesSchemaContribution.CONTRIBUTION,
                CollectionSchemaContribution.CONTRIBUTION,
                GuestInviteSchemaContribution.CONTRIBUTION,
                NovelSchemaContribution.CONTRIBUTION,
                ScheduleSchemaContribution.CONTRIBUTION);
    }

    @Override
    public List<WebRouteContribution> routes() {
        var referenceRead = new ProgramPermission("api-reference.read", "permission.api-reference.read");
        // AuthFilter 复用本次请求按路径和方法解析的最具体声明；公开、邀请与本地授权不另走派生清单。
        // GUI、setup、PAC、actuator 等入口仍执行各自流程校验，声明本身不替代这些校验。
        return List.of(
                WebRouteContribution.visitor("/api/scripts").withProgramPermissions(Map.of(HttpMethod.GET, new ProgramPermission("scripts.read", "permission.scripts.read"))),
                WebRouteContribution.visitor("/api/scripts/*.user.js").withProgramPermissions(Map.of(HttpMethod.GET, new ProgramPermission("scripts.read", "permission.scripts.read"))),
                WebRouteContribution.visitor("/api/scripts/*/install").withProgramPermissions(Map.of(HttpMethod.GET, new ProgramPermission("scripts.read", "permission.scripts.read"))),
                WebRouteContribution.visitor("/api/scripts/*").withProgramPermissions(Map.of(HttpMethod.GET, new ProgramPermission("scripts.read", "permission.scripts.read"))),
                WebRouteContribution.publicRoute("/api/i18n/meta").withProgramPermissions(Map.of(HttpMethod.GET, new ProgramPermission("application.read", "permission.application.read"))),
                WebRouteContribution.publicRoute("/api/i18n/messages/*").withProgramPermissions(Map.of(HttpMethod.GET, new ProgramPermission("application.read", "permission.application.read"))),
                WebRouteContribution.publicRoute("/api/onboarding/profile").withProgramPermissions(Map.of(HttpMethod.GET, new ProgramPermission("profile.read", "permission.profile.read"), HttpMethod.POST, new ProgramPermission("profile.write", "permission.profile.write"))),
                WebRouteContribution.publicRoute("/api/onboarding/connectivity").withProgramPermissions(Map.of(HttpMethod.GET, new ProgramPermission("system.check", "program.permission.system.check"))),
                WebRouteContribution.admin("/api/library/artworks/*").withProgramPermissions(Map.of(HttpMethod.GET, new ProgramPermission("library.read", "permission.library.read"))),
                WebRouteContribution.admin("/api/library/artworks/*/partial").withProgramPermissions(Map.of(HttpMethod.GET, new ProgramPermission("library.read", "permission.library.read"))),
                WebRouteContribution.admin("/api/library/artworks/*/recover-metadata").withProgramPermissions(Map.of(HttpMethod.POST, new ProgramPermission("library.recover", "permission.library.recover"))),
                WebRouteContribution.admin("/api/library/artworks/*/move").withProgramPermissions(Map.of(HttpMethod.POST, new ProgramPermission("library.move", "permission.library.move"))),
                WebRouteContribution.admin("/api/plugins/recovery").withProgramPermissions(Map.of(HttpMethod.GET, new ProgramPermission("recovery.read", "permission.recovery.read"))),
                WebRouteContribution.admin("/api/plugins/recovery/restart").withProgramPermissions(Map.of(HttpMethod.POST, new ProgramPermission("system.restart", "program.permission.system.restart"))),
                WebRouteContribution.admin("/api/plugins/recovery/exit").withProgramPermissions(Map.of(HttpMethod.POST, new ProgramPermission("system.exit", "permission.system.exit"))),
                WebRouteContribution.admin("/api/admin/maintenance/status").withProgramPermissions(Map.of(HttpMethod.GET, new ProgramPermission("maintenance.read", "permission.maintenance.read"))),
                WebRouteContribution.admin("/api/admin/maintenance/run").withProgramPermissions(Map.of(HttpMethod.POST, new ProgramPermission("maintenance.run", "permission.maintenance.run"))),
                WebRouteContribution.visitor("/api/migration/json-to-sqlite").withProgramPermissions(Map.of(HttpMethod.POST, new ProgramPermission("legacy-migration.execute", "permission.legacy-migration.execute"))),
                WebRouteContribution.visitor("/api/migration/json-to-sqlite/stream").withProgramPermissions(Map.of(HttpMethod.GET, new ProgramPermission("legacy-migration.execute", "permission.legacy-migration.execute"))),
                WebRouteContribution.visitor("/api/download/queue/*/cancel").withProgramPermissions(Map.of(HttpMethod.POST, new ProgramPermission("downloads.cancel", "program.permission.downloads.cancel"))),
                WebRouteContribution.visitor("/api/download/queue/clear").withProgramPermissions(Map.of(HttpMethod.POST, new ProgramPermission("downloads.cancel", "program.permission.downloads.cancel"))),
                WebRouteContribution.admin("/api/web/operation-hooks/declarations").withProgramPermissions(Map.of(HttpMethod.GET, new ProgramPermission("hooks.read", "permission.hooks.read"))),
                WebRouteContribution.admin("/api/web/operation-hooks/declarations/prepare").withProgramPermissions(Map.of(HttpMethod.POST, new ProgramPermission("hooks.prepare", "permission.hooks.prepare"))),
                WebRouteContribution.admin("/api/web/operation-hooks/catalog").withProgramPermissions(Map.of(HttpMethod.GET, new ProgramPermission("hooks.read", "permission.hooks.read"))),
                WebRouteContribution.admin("/api/web/operation-hooks/configuration").withProgramPermissions(Map.of(HttpMethod.GET, new ProgramPermission("hooks.read", "permission.hooks.read"))),
                WebRouteContribution.admin("/api/web/operation-hooks/prepare").withProgramPermissions(Map.of(HttpMethod.POST, new ProgramPermission("hooks.prepare", "permission.hooks.prepare"))),
                WebRouteContribution.admin("/api/web/operation-hooks/publish").withProgramPermissions(Map.of(HttpMethod.POST, new ProgramPermission("hooks.publish", "permission.hooks.publish"))),
                WebRouteContribution.admin("/api/web/operation-hooks/targets").withProgramPermissions(Map.of(HttpMethod.GET, new ProgramPermission("hooks.read", "permission.hooks.read"))),
                WebRouteContribution.admin("/api/web/operation-hooks/definitions").withProgramPermissions(Map.of(HttpMethod.GET, new ProgramPermission("hooks.read", "permission.hooks.read"))),
                WebRouteContribution.admin("/api/plugins/status").withProgramPermissions(Map.of(HttpMethod.GET, new ProgramPermission("plugins.read", "permission.plugins.read"))),
                WebRouteContribution.admin("/api/plugins/revocations/*/refresh").withProgramPermissions(Map.of(HttpMethod.POST, new ProgramPermission("plugins.revocations.refresh", "permission.plugins.revocations.refresh"))),
                WebRouteContribution.admin("/api/plugins/*/impact").withProgramPermissions(Map.of(HttpMethod.GET, new ProgramPermission("plugins.read", "permission.plugins.read"))),
                WebRouteContribution.admin("/api/plugins/*/enabled-preview").withProgramPermissions(Map.of(HttpMethod.PUT, new ProgramPermission("plugins.enabled", "permission.plugins.enabled"))),
                WebRouteContribution.admin("/api/plugins/*/load").withProgramPermissions(Map.of(HttpMethod.POST, new ProgramPermission("plugins.load", "permission.plugins.load"))),
                WebRouteContribution.admin("/api/plugins/*/start").withProgramPermissions(Map.of(HttpMethod.POST, new ProgramPermission("plugins.start", "permission.plugins.start"))),
                WebRouteContribution.admin("/api/plugins/*/quiesce").withProgramPermissions(Map.of(HttpMethod.POST, new ProgramPermission("plugins.quiesce", "permission.plugins.quiesce"))),
                WebRouteContribution.admin("/api/plugins/*/stop").withProgramPermissions(Map.of(HttpMethod.POST, new ProgramPermission("plugins.stop", "permission.plugins.stop"))),
                WebRouteContribution.admin("/api/plugins/*/unload").withProgramPermissions(Map.of(HttpMethod.POST, new ProgramPermission("plugins.unload", "permission.plugins.unload"))),
                WebRouteContribution.admin("/api/plugins/*/remove").withProgramPermissions(Map.of(HttpMethod.POST, new ProgramPermission("plugins.remove", "permission.plugins.remove"))),
                WebRouteContribution.admin("/api/plugins/*/restart").withProgramPermissions(Map.of(HttpMethod.POST, new ProgramPermission("plugins.restart", "permission.plugins.restart"))),
                WebRouteContribution.admin("/api/plugins/*/reload").withProgramPermissions(Map.of(HttpMethod.POST, new ProgramPermission("plugins.reload", "permission.plugins.reload"))),
                WebRouteContribution.admin("/api/plugins/*/enabled").withProgramPermissions(Map.of(HttpMethod.PUT, new ProgramPermission("plugins.enabled", "permission.plugins.enabled"))),
                WebRouteContribution.admin("/api/plugins/*/trust").withProgramPermissions(Map.of(HttpMethod.PUT, new ProgramPermission("plugins.trust", "permission.plugins.trust"), HttpMethod.DELETE, new ProgramPermission("plugins.trust", "permission.plugins.trust"))),
                WebRouteContribution.admin("/api/plugins/backend-restart").withProgramPermissions(Map.of(HttpMethod.POST, new ProgramPermission("system.restart", "program.permission.system.restart"))),
                WebRouteContribution.admin("/api/plugins/install").withProgramPermissions(Map.of(HttpMethod.POST, new ProgramPermission("plugins.install", "permission.plugins.install"))),
                WebRouteContribution.admin("/api/plugins/updates").withProgramPermissions(Map.of(HttpMethod.GET, new ProgramPermission("plugins.read", "permission.plugins.read"))),
                WebRouteContribution.admin("/api/plugins/acquisitions/*").withProgramPermissions(Map.of(HttpMethod.GET, new ProgramPermission("plugins.read", "permission.plugins.read"))),
                WebRouteContribution.admin("/api/plugins/acquisitions").withProgramPermissions(Map.of(HttpMethod.GET, new ProgramPermission("plugins.read", "permission.plugins.read"))),
                WebRouteContribution.admin("/api/plugins/*/actions/load").withProgramPermissions(Map.of(HttpMethod.POST, new ProgramPermission("plugins.load", "permission.plugins.load"))),
                WebRouteContribution.admin("/api/plugins/*/actions/start").withProgramPermissions(Map.of(HttpMethod.POST, new ProgramPermission("plugins.start", "permission.plugins.start"))),
                WebRouteContribution.admin("/api/plugins/*/actions/quiesce").withProgramPermissions(Map.of(HttpMethod.POST, new ProgramPermission("plugins.quiesce", "permission.plugins.quiesce"))),
                WebRouteContribution.admin("/api/plugins/*/actions/stop").withProgramPermissions(Map.of(HttpMethod.POST, new ProgramPermission("plugins.stop", "permission.plugins.stop"))),
                WebRouteContribution.admin("/api/plugins/*/actions/unload").withProgramPermissions(Map.of(HttpMethod.POST, new ProgramPermission("plugins.unload", "permission.plugins.unload"))),
                WebRouteContribution.admin("/api/plugins/*/actions/remove").withProgramPermissions(Map.of(HttpMethod.POST, new ProgramPermission("plugins.remove", "permission.plugins.remove"))),
                WebRouteContribution.admin("/api/plugins/*/actions/restart").withProgramPermissions(Map.of(HttpMethod.POST, new ProgramPermission("plugins.restart", "permission.plugins.restart"))),
                WebRouteContribution.admin("/api/plugins/*/actions/reload").withProgramPermissions(Map.of(HttpMethod.POST, new ProgramPermission("plugins.reload", "permission.plugins.reload"))),
                WebRouteContribution.admin("/api/schedule/tasks").withProgramPermissions(Map.of(HttpMethod.GET, new ProgramPermission("schedule.read", "permission.schedule.read"), HttpMethod.POST, new ProgramPermission("schedule.create", "permission.schedule.create"))),
                WebRouteContribution.admin("/api/schedule/tasks/*").withProgramPermissions(Map.of(HttpMethod.GET, new ProgramPermission("schedule.read", "permission.schedule.read"), HttpMethod.PUT, new ProgramPermission("schedule.write", "permission.schedule.write"), HttpMethod.DELETE, new ProgramPermission("schedule.delete", "permission.schedule.delete"))),
                WebRouteContribution.admin("/api/schedule/tasks/*/queue").withProgramPermissions(Map.of(HttpMethod.GET, new ProgramPermission("schedule.read", "permission.schedule.read"))),
                WebRouteContribution.admin("/api/schedule/tasks/*/enabled").withProgramPermissions(Map.of(HttpMethod.POST, new ProgramPermission("schedule.run", "permission.schedule.run"))),
                WebRouteContribution.admin("/api/schedule/tasks/*/credential").withProgramPermissions(Map.of(HttpMethod.POST, new ProgramPermission("schedule.credentials", "permission.schedule.credentials"), HttpMethod.DELETE, new ProgramPermission("schedule.credentials", "permission.schedule.credentials"))),
                WebRouteContribution.admin("/api/schedule/credential-policies/actions").withProgramPermissions(Map.of(HttpMethod.POST, new ProgramPermission("schedule.credentials", "permission.schedule.credentials"))),
                WebRouteContribution.admin("/api/schedule/tasks/*/proxy").withProgramPermissions(Map.of(HttpMethod.POST, new ProgramPermission("schedule.write", "permission.schedule.write"))),
                WebRouteContribution.admin("/api/schedule/tasks/*/run").withProgramPermissions(Map.of(HttpMethod.POST, new ProgramPermission("schedule.run", "permission.schedule.run"))),
                WebRouteContribution.admin("/api/schedule/tasks/*/pause").withProgramPermissions(Map.of(HttpMethod.POST, new ProgramPermission("schedule.pause", "permission.schedule.pause"))),
                WebRouteContribution.admin("/api/schedule/tasks/*/pending/resolve").withProgramPermissions(Map.of(HttpMethod.POST, new ProgramPermission("schedule.pending.write", "permission.schedule.pending.write"))),
                WebRouteContribution.admin("/api/schedule/tasks/*/resume").withProgramPermissions(Map.of(HttpMethod.POST, new ProgramPermission("schedule.run", "permission.schedule.run"))),
                WebRouteContribution.admin("/api/schedule/tasks/*/pending").withProgramPermissions(Map.of(HttpMethod.GET, new ProgramPermission("schedule.read", "permission.schedule.read"), HttpMethod.DELETE, new ProgramPermission("schedule.pending.write", "permission.schedule.pending.write"))),
                WebRouteContribution.visitor("/api/archive/pack-artworks").withProgramPermissions(Map.of(HttpMethod.POST, new ProgramPermission("archives.create", "permission.archives.create"))),
                WebRouteContribution.visitor("/api/archive/list").withProgramPermissions(Map.of(HttpMethod.GET, new ProgramPermission("archives.read", "permission.archives.read"))),
                WebRouteContribution.visitor("/api/archive/status/*").withProgramPermissions(Map.of(HttpMethod.GET, new ProgramPermission("archives.read", "permission.archives.read"))),
                WebRouteContribution.visitor("/api/archive/download/*").withProgramPermissions(Map.of(HttpMethod.GET, new ProgramPermission("archives.download", "permission.archives.download"))),
                WebRouteContribution.invitedGuest("/api/collections").withProgramPermissions(Map.of(HttpMethod.GET, new ProgramPermission("collections.read", "permission.collections.read"), HttpMethod.POST, new ProgramPermission("collections.write", "permission.collections.write"))),
                WebRouteContribution.invitedGuest("/api/collections/*").withProgramPermissions(Map.of(HttpMethod.PUT, new ProgramPermission("collections.write", "permission.collections.write"), HttpMethod.DELETE, new ProgramPermission("collections.delete", "permission.collections.delete"))),
                WebRouteContribution.invitedGuest("/api/collections/*/download-root").withProgramPermissions(Map.of(HttpMethod.PUT, new ProgramPermission("collections.write", "permission.collections.write"))),
                WebRouteContribution.invitedGuest("/api/collections/*/sort-order").withProgramPermissions(Map.of(HttpMethod.PUT, new ProgramPermission("collections.write", "permission.collections.write"))),
                WebRouteContribution.invitedGuest("/api/collections/*/icon").withProgramPermissions(Map.of(HttpMethod.POST, new ProgramPermission("collections.write", "permission.collections.write"), HttpMethod.DELETE, new ProgramPermission("collections.write", "permission.collections.write"), HttpMethod.GET, new ProgramPermission("collections.read", "permission.collections.read"))),
                WebRouteContribution.invitedGuest("/api/collections/*/artworks/*").withProgramPermissions(Map.of(HttpMethod.POST, new ProgramPermission("collections.members.write", "permission.collections.members.write"), HttpMethod.DELETE, new ProgramPermission("collections.members.write", "permission.collections.members.write"))),
                WebRouteContribution.invitedGuest("/api/collections/of/*").withProgramPermissions(Map.of(HttpMethod.GET, new ProgramPermission("collections.read", "permission.collections.read"))),
                WebRouteContribution.invitedGuest("/api/collections/*/novels/*").withProgramPermissions(Map.of(HttpMethod.POST, new ProgramPermission("collections.members.write", "permission.collections.members.write"), HttpMethod.DELETE, new ProgramPermission("collections.members.write", "permission.collections.members.write"))),
                WebRouteContribution.invitedGuest("/api/collections/novels/of/*").withProgramPermissions(Map.of(HttpMethod.GET, new ProgramPermission("collections.read", "permission.collections.read"))),
                WebRouteContribution.invitedGuest("/api/authors").withProgramPermissions(Map.of(HttpMethod.GET, new ProgramPermission("authors.read", "permission.authors.read"))),
                WebRouteContribution.invitedGuest("/api/authors/paged").withProgramPermissions(Map.of(HttpMethod.GET, new ProgramPermission("authors.read", "permission.authors.read"))),
                WebRouteContribution.invitedGuest("/api/series").withProgramPermissions(Map.of(HttpMethod.GET, new ProgramPermission("series.read", "permission.series.read"))),
                WebRouteContribution.invitedGuest("/api/series/paged").withProgramPermissions(Map.of(HttpMethod.GET, new ProgramPermission("series.read", "permission.series.read"))),
                WebRouteContribution.invitedGuest("/api/series/*").withProgramPermissions(Map.of(HttpMethod.GET, new ProgramPermission("series.read", "permission.series.read"))),
                WebRouteContribution.invitedGuest("/api/series/*/cover").withProgramPermissions(Map.of(HttpMethod.GET, new ProgramPermission("series.read", "permission.series.read"))),
                WebRouteContribution.invitedGuest("/api/downloaded/thumbnail/*/*").withProgramPermissions(Map.of(HttpMethod.GET, new ProgramPermission("assets.read", "permission.assets.read"))),
                WebRouteContribution.invitedGuest("/api/downloaded/thumbnail-file/*/*").withProgramPermissions(Map.of(HttpMethod.GET, new ProgramPermission("assets.read", "permission.assets.read"))),
                WebRouteContribution.invitedGuest("/api/downloaded/image/*/*").withProgramPermissions(Map.of(HttpMethod.GET, new ProgramPermission("assets.read", "permission.assets.read"))),
                WebRouteContribution.invitedGuest("/api/downloaded/rawfile/*/*").withProgramPermissions(Map.of(HttpMethod.GET, new ProgramPermission("assets.export", "permission.assets.export"))),
                WebRouteContribution.admin("/api/local-import/presets").withProgramPermissions(Map.of(HttpMethod.GET, new ProgramPermission("local-import.read", "permission.local-import.read"))),
                WebRouteContribution.admin("/api/local-import/previews").withProgramPermissions(Map.of(HttpMethod.POST, new ProgramPermission("local-import.preview", "permission.local-import.preview"))),
                WebRouteContribution.admin("/api/local-import/previews/*").withProgramPermissions(Map.of(HttpMethod.GET, new ProgramPermission("local-import.read", "permission.local-import.read"))),
                WebRouteContribution.admin("/api/local-import/tasks/*").withProgramPermissions(Map.of(HttpMethod.GET, new ProgramPermission("local-import.read", "permission.local-import.read"))),
                WebRouteContribution.admin("/api/local-import/previews/*/items").withProgramPermissions(Map.of(HttpMethod.GET, new ProgramPermission("local-import.read", "permission.local-import.read"))),
                WebRouteContribution.admin("/api/local-import/tasks/*/items").withProgramPermissions(Map.of(HttpMethod.GET, new ProgramPermission("local-import.read", "permission.local-import.read"))),
                WebRouteContribution.admin("/api/local-import/tasks").withProgramPermissions(Map.of(HttpMethod.POST, new ProgramPermission("local-import.execute", "permission.local-import.execute"))),
                WebRouteContribution.admin("/api/local-import/previews/*/cancel").withProgramPermissions(Map.of(HttpMethod.POST, new ProgramPermission("local-import.cancel", "permission.local-import.cancel"))),
                WebRouteContribution.admin("/api/local-import/tasks/*/cancel").withProgramPermissions(Map.of(HttpMethod.POST, new ProgramPermission("local-import.cancel", "permission.local-import.cancel"))),
                WebRouteContribution.admin("/api/admin/update/check").withProgramPermissions(Map.of(HttpMethod.GET, new ProgramPermission("updates.read", "permission.updates.read"))),
                WebRouteContribution.admin("/api/admin/update/last").withProgramPermissions(Map.of(HttpMethod.GET, new ProgramPermission("updates.read", "permission.updates.read"))),
                WebRouteContribution.admin("/api/admin/update/download").withProgramPermissions(Map.of(HttpMethod.POST, new ProgramPermission("updates.download", "permission.updates.download"))),
                WebRouteContribution.admin("/api/admin/update/download/progress").withProgramPermissions(Map.of(HttpMethod.GET, new ProgramPermission("updates.read", "permission.updates.read"))),
                WebRouteContribution.admin("/api/admin/update/install").withProgramPermissions(Map.of(HttpMethod.POST, new ProgramPermission("updates.install", "permission.updates.install"))),
                // ── monitor：仅管理员（不在访客白名单）的页面与 API ───────────────────────────────
                WebRouteContribution.admin("/monitor.html"),
                WebRouteContribution.admin("/api/admin/system/connectivity").withProgramPermissions(Map.of(HttpMethod.POST, new ProgramPermission("system.check", "program.permission.system.check"))),
                WebRouteContribution.admin("/api/admin/system/restart").withProgramPermissions(Map.of(HttpMethod.POST, new ProgramPermission("system.restart", "program.permission.system.restart"))),
                WebRouteContribution.admin("/api/admin/system/reload").withProgramPermissions(Map.of(HttpMethod.POST, new ProgramPermission("system.reload", "program.permission.system.reload"))),
                WebRouteContribution.admin("/api/tools/logs/*").withProgramPermissions(Map.of(HttpMethod.GET, new ProgramPermission("logs.read", "program.permission.logs.read"))),
                WebRouteContribution.admin("/api/tools/path-prefixes/pin").withProgramPermissions(Map.of(HttpMethod.POST, new ProgramPermission("paths.write", "program.permission.paths.write"))),
                WebRouteContribution.admin("/api/tools/ffmpeg/tasks/*/cancel").withProgramPermissions(Map.of(HttpMethod.POST, new ProgramPermission("ffmpeg.cancel", "program.permission.ffmpeg.cancel"))),
                WebRouteContribution.admin("/api/tools/ffmpeg/tasks").withProgramPermissions(Map.of(HttpMethod.POST, new ProgramPermission("ffmpeg.install", "program.permission.ffmpeg.install"))),
                WebRouteContribution.admin("/api/tools/backfill/defaults").withProgramPermissions(Map.of(HttpMethod.GET, new ProgramPermission("backfill.read", "program.permission.backfill.read"))),
                WebRouteContribution.admin("/api/tools/backfill/candidates").withProgramPermissions(Map.of(HttpMethod.POST, new ProgramPermission("backfill.read", "program.permission.backfill.read"))),
                WebRouteContribution.admin("/api/tools/backfill/tasks").withProgramPermissions(Map.of(HttpMethod.POST, new ProgramPermission("backfill.execute", "program.permission.backfill.execute"))),
                WebRouteContribution.admin("/api/tools/backfill/tasks/current").withProgramPermissions(Map.of(HttpMethod.GET, new ProgramPermission("backfill.read", "program.permission.backfill.read"))),
                WebRouteContribution.admin("/api/tools/backfill/tasks/*").withProgramPermissions(Map.of(HttpMethod.GET, new ProgramPermission("backfill.read", "program.permission.backfill.read"))),
                WebRouteContribution.admin("/api/tools/backfill/tasks/*/cancel").withProgramPermissions(Map.of(HttpMethod.POST, new ProgramPermission("backfill.cancel", "program.permission.backfill.cancel"))),
                WebRouteContribution.admin("/api/tools/classifier/settings").withProgramPermissions(Map.of(HttpMethod.GET, new ProgramPermission("classifier.read", "program.permission.classifier.read"), HttpMethod.PUT, new ProgramPermission("classifier.configure", "program.permission.classifier.configure"))),
                WebRouteContribution.admin("/api/tools/classifier/folders").withProgramPermissions(Map.of(HttpMethod.GET, new ProgramPermission("classifier.read", "program.permission.classifier.read"), HttpMethod.DELETE, new ProgramPermission("classifier.delete", "program.permission.classifier.delete"))),
                WebRouteContribution.admin("/api/tools/classifier/images").withProgramPermissions(Map.of(HttpMethod.GET, new ProgramPermission("classifier.read", "program.permission.classifier.read"))),
                WebRouteContribution.admin("/api/tools/classifier/image").withProgramPermissions(Map.of(HttpMethod.GET, new ProgramPermission("classifier.read", "program.permission.classifier.read"))),
                WebRouteContribution.admin("/api/tools/classifier/moves").withProgramPermissions(Map.of(HttpMethod.POST, new ProgramPermission("classifier.write", "program.permission.classifier.write"))),
                WebRouteContribution.admin("/api/tools/startup-cache").withProgramPermissions(Map.of(HttpMethod.GET, new ProgramPermission("startup-cache.read", "program.permission.startup-cache.read"))),
                WebRouteContribution.admin("/api/tools/startup-cache/tasks").withProgramPermissions(Map.of(HttpMethod.POST, new ProgramPermission("startup-cache.execute", "program.permission.startup-cache.execute"))),
                WebRouteContribution.admin("/api/tools/startup-cache/tasks/*/cancel").withProgramPermissions(Map.of(HttpMethod.POST, new ProgramPermission("startup-cache.cancel", "program.permission.startup-cache.cancel"))),
                ProgramAccess.SELF_ROUTE,
                WebRouteContribution.admin("/api/security/program-keys"),
                WebRouteContribution.admin("/api/security/program-keys/**"),
                WebRouteContribution.visitor("/api/scripts**"),
                WebRouteContribution.visitor("/api/download/queue/**"),
                WebRouteContribution.visitor("/api/cancel/**"),
                WebRouteContribution.visitor("/api/download/cancel/**"),
                WebRouteContribution.visitor("/api/download/extensions").withProgramPermissions(Map.of(HttpMethod.GET, new ProgramPermission("downloads.extensions.read", "permission.downloads.extensions.read"))),
                WebRouteContribution.admin("/api/download/tasks").withProgramPermissions(Map.of(
                        HttpMethod.GET, new ProgramPermission("downloads.read", "program.permission.downloads.read"),
                        HttpMethod.POST, new ProgramPermission("downloads.create", "program.permission.downloads.create"))),
                WebRouteContribution.admin("/api/download/tasks/history").withProgramPermissions(Map.of(HttpMethod.GET,
                        new ProgramPermission("downloads.read", "program.permission.downloads.read"))),
                WebRouteContribution.admin("/api/download/tasks/*").withProgramPermissions(Map.of(HttpMethod.GET,
                        new ProgramPermission("downloads.read", "program.permission.downloads.read"))),
                WebRouteContribution.admin("/api/download/tasks/*/cancel").withProgramPermissions(Map.of(HttpMethod.POST,
                        new ProgramPermission("downloads.cancel", "program.permission.downloads.cancel"))),
                WebRouteContribution.admin("/api/download/batches").withProgramPermissions(Map.of(
                        HttpMethod.GET, new ProgramPermission("batches.read", "program.permission.batches.read"),
                        HttpMethod.POST, new ProgramPermission("batches.create", "program.permission.batches.create"))),
                WebRouteContribution.admin("/api/download/batches/*").withProgramPermissions(Map.of(
                        HttpMethod.GET, new ProgramPermission("batches.read", "program.permission.batches.read"),
                        HttpMethod.DELETE, new ProgramPermission("batches.delete", "program.permission.batches.delete"))),
                WebRouteContribution.admin("/api/download/batches/*/items").withProgramPermissions(Map.of(HttpMethod.GET,
                        new ProgramPermission("batches.read", "program.permission.batches.read"))),
                WebRouteContribution.admin("/api/download/batches/*/pause").withProgramPermissions(Map.of(HttpMethod.POST,
                        new ProgramPermission("batches.pause", "program.permission.batches.pause"))),
                WebRouteContribution.admin("/api/download/batches/*/cancel").withProgramPermissions(Map.of(HttpMethod.POST,
                        new ProgramPermission("batches.cancel", "program.permission.batches.cancel"))),
                WebRouteContribution.admin("/api/download/batches/*/resume").withProgramPermissions(Map.of(HttpMethod.POST,
                        new ProgramPermission("batches.resume", "program.permission.batches.resume"))),
                WebRouteContribution.admin("/api/download/batches/*/retry").withProgramPermissions(Map.of(HttpMethod.POST,
                        new ProgramPermission("batches.resume", "program.permission.batches.resume"))),
                WebRouteContribution.admin("/api/download/batches/*/credentials").withProgramPermissions(Map.of(HttpMethod.PUT,
                        new ProgramPermission("batches.credentials", "program.permission.batches.credentials"))),
                WebRouteContribution.admin("/api/download/batches/*/resolve").withProgramPermissions(Map.of(HttpMethod.POST,
                        new ProgramPermission("batches.resolve", "program.permission.batches.resolve"))),
                WebRouteContribution.admin("/api/download/batches/**"),
                WebRouteContribution.admin("/api/acquisition/credentials").withProgramPermissions(Map.of(HttpMethod.GET, new ProgramPermission("credentials.read", "permission.credentials.read"), HttpMethod.PUT, new ProgramPermission("credentials.write", "permission.credentials.write"))),
                WebRouteContribution.admin("/api/download/tasks/**"),
                WebRouteContribution.admin("/api/local-import/**"),
                WebRouteContribution.admin("/api/tools/backfill/**"),
                WebRouteContribution.admin("/api/tools/history").withProgramPermissions(Map.of(HttpMethod.GET, new ProgramPermission("tools.history.read", "program.permission.tools.history.read"))),
                WebRouteContribution.admin("/api/tools/directories").withProgramPermissions(Map.of(HttpMethod.GET, new ProgramPermission("directories.read", "program.permission.directories.read"), HttpMethod.POST, new ProgramPermission("directories.write", "program.permission.directories.write"))),
                WebRouteContribution.admin("/api/tools/classifier/**"),
                WebRouteContribution.admin("/api/tools/path-prefixes").withProgramPermissions(Map.of(HttpMethod.GET, new ProgramPermission("paths.read", "program.permission.paths.read"), HttpMethod.POST, new ProgramPermission("paths.write", "program.permission.paths.write"))),
                WebRouteContribution.admin("/api/tools/path-prefixes/**"),
                WebRouteContribution.admin("/api/tools/ffmpeg").withProgramPermissions(Map.of(HttpMethod.GET, new ProgramPermission("ffmpeg.read", "program.permission.ffmpeg.read"))),
                WebRouteContribution.admin("/api/tools/ffmpeg/**"),
                WebRouteContribution.admin("/api/tools/logs/**"),
                WebRouteContribution.admin("/api/tools/media").withProgramPermissions(Map.of(HttpMethod.GET, new ProgramPermission("media.read", "program.permission.media.read"))),
                WebRouteContribution.admin("/api/tools/media/previews").withProgramPermissions(Map.of(HttpMethod.POST, new ProgramPermission("media.preview", "program.permission.media.preview"))),
                WebRouteContribution.admin("/api/tools/media/tasks").withProgramPermissions(Map.of(HttpMethod.POST, new ProgramPermission("media.execute", "program.permission.media.execute"))),
                WebRouteContribution.admin("/api/tools/media/tasks/current").withProgramPermissions(Map.of(HttpMethod.GET, new ProgramPermission("media.read", "program.permission.media.read"))),
                WebRouteContribution.admin("/api/tools/media/tasks/cancel").withProgramPermissions(Map.of(HttpMethod.POST, new ProgramPermission("media.cancel", "program.permission.media.cancel"))),
                WebRouteContribution.admin("/api/tools/media/capabilities").withProgramPermissions(Map.of(HttpMethod.POST, new ProgramPermission("media.read", "program.permission.media.read"))),
                WebRouteContribution.admin("/api/tools/media/**"),
                WebRouteContribution.admin("/api/tools/file-import").withProgramPermissions(Map.of(HttpMethod.GET, new ProgramPermission("file-import.read", "program.permission.file-import.read"))),
                WebRouteContribution.admin("/api/tools/file-import/previews").withProgramPermissions(Map.of(HttpMethod.POST, new ProgramPermission("file-import.preview", "program.permission.file-import.preview"))),
                WebRouteContribution.admin("/api/tools/file-import/tasks").withProgramPermissions(Map.of(HttpMethod.POST, new ProgramPermission("file-import.execute", "program.permission.file-import.execute"))),
                WebRouteContribution.admin("/api/tools/file-import/tasks/current").withProgramPermissions(Map.of(HttpMethod.GET, new ProgramPermission("file-import.read", "program.permission.file-import.read"))),
                WebRouteContribution.admin("/api/tools/file-import/tasks/cancel").withProgramPermissions(Map.of(HttpMethod.POST, new ProgramPermission("file-import.cancel", "program.permission.file-import.cancel"))),
                WebRouteContribution.admin("/api/tools/file-import/completion/acknowledge").withProgramPermissions(Map.of(HttpMethod.POST, new ProgramPermission("file-import.acknowledge", "program.permission.file-import.acknowledge"))),
                WebRouteContribution.admin("/api/tools/file-import/**"),
                WebRouteContribution.admin("/api/settings/database").withProgramPermissions(Map.of(HttpMethod.GET, new ProgramPermission("database.read", "program.permission.database.read"))),
                WebRouteContribution.admin("/api/settings/database/**"),
                WebRouteContribution.admin("/api/settings/repositories").withProgramPermissions(Map.of(HttpMethod.GET, new ProgramPermission("repositories.read", "program.permission.repositories.read"), HttpMethod.PUT, new ProgramPermission("repositories.write", "program.permission.repositories.write"))),
                WebRouteContribution.admin("/api/settings/environment/**"),
                WebRouteContribution.admin("/api/settings/environment/preview").withProgramPermissions(Map.of(HttpMethod.POST, new ProgramPermission("environment.preview", "program.permission.environment.preview"))),
                WebRouteContribution.admin("/api/settings/environment/exports/*/archive").withProgramPermissions(Map.of(HttpMethod.GET, new ProgramPermission("environment.export", "program.permission.environment.export"))),
                WebRouteContribution.admin("/api/settings/environment/exports").withProgramPermissions(Map.of(HttpMethod.GET, new ProgramPermission("environment.read", "program.permission.environment.read"), HttpMethod.POST, new ProgramPermission("environment.export", "program.permission.environment.export"))),
                WebRouteContribution.admin("/api/settings/environment/restore").withProgramPermissions(Map.of(HttpMethod.GET, new ProgramPermission("environment.read", "program.permission.environment.read"), HttpMethod.POST, new ProgramPermission("environment.restore", "program.permission.environment.restore"))),
                WebRouteContribution.admin("/api/settings/environment/installation").withProgramPermissions(Map.of(HttpMethod.GET, new ProgramPermission("environment.read", "program.permission.environment.read"))),
                WebRouteContribution.admin("/api/settings/database/migrations").withProgramPermissions(Map.of(HttpMethod.POST, new ProgramPermission("database.migrate", "program.permission.database.migrate"))),
                WebRouteContribution.admin("/api/settings/database/migration-previews").withProgramPermissions(Map.of(HttpMethod.POST, new ProgramPermission("database.preview", "program.permission.database.preview"))),
                WebRouteContribution.admin("/api/settings/database/check").withProgramPermissions(Map.of(HttpMethod.POST, new ProgramPermission("database.check", "program.permission.database.check"))),
                WebRouteContribution.admin("/api/settings/database/migration").withProgramPermissions(Map.of(HttpMethod.GET, new ProgramPermission("database.read", "program.permission.database.read"))),
                WebRouteContribution.admin("/api/settings/database/status").withProgramPermissions(Map.of(HttpMethod.GET, new ProgramPermission("database.read", "program.permission.database.read"))),
                WebRouteContribution.admin("/api/settings/database/providers").withProgramPermissions(Map.of(HttpMethod.GET, new ProgramPermission("database.read", "program.permission.database.read"))),
                WebRouteContribution.admin("/api/settings").withProgramPermissions(Map.of(HttpMethod.GET, new ProgramPermission("settings.read", "program.permission.settings.read"), HttpMethod.POST, new ProgramPermission("settings.write", "program.permission.settings.write"))),
                WebRouteContribution.admin("/api/settings/presets").withProgramPermissions(Map.of(HttpMethod.GET, new ProgramPermission("settings.read", "program.permission.settings.read"))),
                WebRouteContribution.admin("/api/settings/validate").withProgramPermissions(Map.of(HttpMethod.POST, new ProgramPermission("settings.write", "program.permission.settings.write"))),
                WebRouteContribution.admin("/api/admin/update/**"),
            WebRouteContribution.admin("/api/admin/control-center").withProgramPermissions(Map.of(HttpMethod.GET, new ProgramPermission("control-center.read", "permission.control-center.read"))),
            WebRouteContribution.admin("/api/admin/control-center/directory").withProgramPermissions(Map.of(HttpMethod.POST, new ProgramPermission("control-center.directory", "permission.control-center.directory"))),
            WebRouteContribution.admin("/api/admin/system").withProgramPermissions(Map.of(HttpMethod.GET, new ProgramPermission("system.read", "program.permission.system.read"))),
                WebRouteContribution.admin("/api/admin/system/**"),
                WebRouteContribution.local("/api/download/status"),
                WebRouteContribution.admin("/api/schedule/sources").withProgramPermissions(Map.of(HttpMethod.GET,
                        new ProgramPermission("schedule.sources.read", "program.permission.schedule.sources.read"))),
                WebRouteContribution.admin("/api/schedule/**"),
                WebRouteContribution.visitor("/api/pixiv/user/*/meta").withProgramPermissions(Map.of(HttpMethod.GET, new ProgramPermission("pixiv.read", "permission.pixiv.read"))),
                WebRouteContribution.admin("/api/pixiv/user/*/meta/schedule").withProgramPermissions(Map.of(HttpMethod.POST, new ProgramPermission("authors.refresh", "permission.authors.refresh"))),
                WebRouteContribution.visitor("/api/pixiv/me/uid").withProgramPermissions(Map.of(HttpMethod.GET, new ProgramPermission("pixiv.read", "permission.pixiv.read"))),
                WebRouteContribution.visitor("/api/pixiv/thumbnail-proxy").withProgramPermissions(Map.of(HttpMethod.GET, new ProgramPermission("pixiv.read", "permission.pixiv.read"))),
                WebRouteContribution.admin("/home.html"),
                WebRouteContribution.admin("/home/**"),
                WebRouteContribution.admin("/pixiv-invite-manage.html"),
                WebRouteContribution.admin("/pixiv-invite-detail.html"),
                WebRouteContribution.admin("/plugin-manage.html"),
                WebRouteContribution.admin("/api/downloaded/batch").withProgramPermissions(Map.of(HttpMethod.POST, new ProgramPermission("library.read", "permission.library.read"))),
                WebRouteContribution.admin("/api/admin/**"),
                // 插件管理后端 API（PluginManagementController）：状态查询 + 外置插件运行期生命周期动词 + 本地包安装，仅管理员。
                // 与恢复模式访问放行 /api/plugins/ 同前缀，使核心进入恢复模式时管理员仍可查询状态并驱动修复。
                WebRouteContribution.admin("/api/plugins/**"),
                WebRouteContribution.admin("/monitor/**"),
                WebRouteContribution.admin("/pixiv-invite-manage/**"),
                WebRouteContribution.admin("/pixiv-invite-detail/**"),
                // 插件管理页（plugin-manage.html）的页面专属静态资源（JS / CSS），仅管理员。
                WebRouteContribution.admin("/plugin-manage/**"),
                // ── monitor + 访客只读（受邀访客可读、multi 匿名游客被挡）─────────────────────────
                WebRouteContribution.invitedGuest("/api/downloaded/statistics").withProgramPermissions(Map.of(HttpMethod.GET, new ProgramPermission("library.read", "permission.library.read"))),
                WebRouteContribution.invitedGuest("/api/downloaded/history").withProgramPermissions(Map.of(HttpMethod.GET, new ProgramPermission("library.read", "permission.library.read"))),
                WebRouteContribution.invitedGuest("/api/downloaded/history/paged").withProgramPermissions(Map.of(HttpMethod.GET, new ProgramPermission("library.read", "permission.library.read"))),
                WebRouteContribution.invitedGuest("/api/downloaded/by-move-folder").withProgramPermissions(Map.of(HttpMethod.GET, new ProgramPermission("library.read", "permission.library.read"))),
                WebRouteContribution.invitedGuest("/api/downloaded/thumbnail/**"),
                WebRouteContribution.invitedGuest("/api/downloaded/thumbnail-file/**"),
                WebRouteContribution.invitedGuest("/api/downloaded/rawfile/**"),
                WebRouteContribution.invitedGuest("/api/downloaded/image/**"),
                // /api/authors、/api/series、/api/collections 用无尾斜杠 startsWith 匹配
                // （同时命中 /api/authors 与 /api/authors/{id}）；模式以 ** 直接续接，去 ** 后还原为现状前缀。
                WebRouteContribution.invitedGuest("/api/authors**"),
                WebRouteContribution.invitedGuest("/api/series**"),
                WebRouteContribution.invitedGuest("/api/collections**"),
                new WebRouteContribution("/api/collections/memberships", AccessPolicy.INVITED_GUEST,
                        Set.of(HttpMethod.POST), false).withProgramPermissions(Map.of(HttpMethod.POST, new ProgramPermission("collections.read", "permission.collections.read"))),
                new WebRouteContribution("/api/collections/novels/memberships", AccessPolicy.INVITED_GUEST,
                        Set.of(HttpMethod.POST), false).withProgramPermissions(Map.of(HttpMethod.POST, new ProgramPermission("collections.read", "permission.collections.read"))),
                // ── 访客可达、不入 monitor：共享只读静态依赖（multi 游客 GET 亦可达）──────────────
                // 跨页共享只读静态依赖（访客可读、不入 monitor）。公开资源只在下方 PUBLIC 清单登记，
                // 避免同一路径出现互相冲突的访问策略。
                WebRouteContribution.visitorAndInvitedGuest("/css/admin-visibility.css"),
                WebRouteContribution.visitorAndInvitedGuest("/css/pixiv-side-modules.css"),
                WebRouteContribution.visitorAndInvitedGuest("/js/invite-modals.js"),
                WebRouteContribution.visitorAndInvitedGuest("/js/pixiv-navigation.js"),
                // 通用页面区块渲染器：与 /api/page-sections（VISITOR_AND_INVITED_GUEST）同口径显式声明，
                // 使受邀访客页面也能加载该共享 section 渲染器；不依赖 /js/** 的 VISITOR 兜底（否则邀请访客 403）。
                WebRouteContribution.visitorAndInvitedGuest("/js/pixiv-page-sections.js"),
                // 通用下钻渲染器：与 /api/drilldowns（VISITOR_AND_INVITED_GUEST）同口径显式声明，使受邀访客页面也能
                // 加载该共享下钻 helper；同 page-sections 不依赖 /js/** 的 VISITOR 兜底（否则邀请访客 403）。
                WebRouteContribution.visitorAndInvitedGuest("/js/pixiv-drilldowns.js"),
                WebRouteContribution.visitorAndInvitedGuest("/js/pixiv-side-modules.js"),
                WebRouteContribution.visitorAndInvitedGuest("/js/pixiv-vue.js"),
                WebRouteContribution.visitorAndInvitedGuest("/vendor/vue/vue.global.prod.js"),
                WebRouteContribution.visitorAndInvitedGuest("/js/pixiv-onboarding-core.js"),
                WebRouteContribution.visitorAndInvitedGuest("/js/pixiv-onboarding-overlay.js"),
                WebRouteContribution.visitorAndInvitedGuest("/js/pixiv-onboarding.js"),
                WebRouteContribution.visitorAndInvitedGuest("/css/pixiv-onboarding.css"),
                WebRouteContribution.publicRoute("/js/pixiv-ui-slots.js"),
                // 装配端点按当前身份裁剪贡献；共享资源须允许邀请访客读取，不能依赖 VISITOR 目录兜底。
                WebRouteContribution.visitorAndInvitedGuest("/api/navigation"),
                WebRouteContribution.visitorAndInvitedGuest("/api/page-sections"),
                WebRouteContribution.visitorAndInvitedGuest("/api/drilldowns"),
                // 通用 UI 槽位清单：宿主页面按 target 前缀加载活动插件声明的同源渲染模块。
                WebRouteContribution.visitorAndInvitedGuest("/api/web/ui-slots"),
                WebRouteContribution.publicRoute("/api/web/page-slots/**"),
                // ── 公开（两种模式均公开）：基础页面、公开 API、公开静态前缀 ──────────────────────
                WebRouteContribution.publicRoute("/"),
                WebRouteContribution.publicRoute("/index"),
                WebRouteContribution.publicRoute("/index.html"),
                // 接口参考页外壳与脚本不含安装信息，公开以便说明访问方式；接口目录本身只给管理员会话与授权的程序密钥。
                new WebRouteContribution("/openapi", AccessPolicy.PUBLIC, Set.of(HttpMethod.GET, HttpMethod.HEAD), false),
                new WebRouteContribution("/api/openapi.json", AccessPolicy.ADMIN, Set.of(HttpMethod.GET, HttpMethod.HEAD), false)
                        .withProgramPermissions(Map.of(HttpMethod.GET, referenceRead, HttpMethod.HEAD, referenceRead)),
                new WebRouteContribution("/openapi/ui.css", AccessPolicy.PUBLIC, Set.of(HttpMethod.GET, HttpMethod.HEAD), false),
                new WebRouteContribution("/openapi/ui.js", AccessPolicy.PUBLIC, Set.of(HttpMethod.GET, HttpMethod.HEAD), false),
                new WebRouteContribution("/openapi/scalar.js", AccessPolicy.PUBLIC, Set.of(HttpMethod.GET, HttpMethod.HEAD), false),
                WebRouteContribution.publicRoute("/login.html"),
                new WebRouteContribution("/api/web/page-publications/*", AccessPolicy.PUBLIC, Set.of(HttpMethod.GET, HttpMethod.HEAD), false),
                new WebRouteContribution("/api/web/page-publications/*/targets", AccessPolicy.PUBLIC, Set.of(HttpMethod.GET, HttpMethod.HEAD), false),
                WebRouteContribution.admin("/api/web/operation-hooks/**"),
                new WebRouteContribution("/api/operation-hooks/prepare", AccessPolicy.PUBLIC, Set.of(HttpMethod.POST), false),
                WebRouteContribution.publicRoute("/js/pixiv-pages.mjs"),
                WebRouteContribution.publicRoute("/js/pixiv-http-hooks.mjs"),
                WebRouteContribution.publicRoute("/sdk/values/value-codecs.mjs"),
                WebRouteContribution.publicRoute("/intro.html"),
                WebRouteContribution.publicRoute("/intro-canary.html"),
                WebRouteContribution.publicRoute("/favicon.ico"),
                WebRouteContribution.publicRoute("/css/pixiv-layout.css"),
                WebRouteContribution.publicRoute("/css/pixiv-motion.css"),
                WebRouteContribution.publicRoute("/css/lang-theme-switcher.css"),
                WebRouteContribution.publicRoute("/css/pixiv-feedback.css"),
                WebRouteContribution.publicRoute("/css/pixiv-scrollbar.css"),
                WebRouteContribution.publicRoute("/js/pixiv-feedback.js"),
                WebRouteContribution.publicRoute("/js/pixiv-actions.js"),
                WebRouteContribution.publicRoute("/js/pixiv-assets.js"),
                WebRouteContribution.publicRoute("/js/pixiv-i18n.js"),
                WebRouteContribution.publicRoute("/js/pixiv-lang-switcher.js"),
                WebRouteContribution.publicRoute("/js/pixiv-survey-frame-bridge.js"),
                WebRouteContribution.publicRoute("/js/pixiv-theme.js"),
                WebRouteContribution.publicRoute("/js/pixiv-layout.js"),
                // 品牌化错误状态页（400/403/404/429/500/502/503 与 4xx/5xx 兜底）。错误派发本身
                // 由容器 sendError → Spring Boot 静态错误视图解析完成（ERROR dispatch 旁路 AuthFilter）；
                // 这里声明的是页面与其共享 CSS/JS 被浏览器以普通 REQUEST 直接加载时的公开访问——
                // 刚拿到错误的任何身份（含未登录 / 受邀访客）都必须能渲染这些页面。
                WebRouteContribution.publicRoute("/error/**"),
                WebRouteContribution.publicRoute("/maintenance.html"),
                WebRouteContribution.publicRoute("/index/**"),
                WebRouteContribution.publicRoute("/intro/**"),
                WebRouteContribution.publicRoute("/intro-canary/**"),
                WebRouteContribution.publicRoute("/login/**"),
                WebRouteContribution.publicRoute("/maintenance/**"),
                WebRouteContribution.publicRoute("/vendor/fonts/**"),
                WebRouteContribution.publicRoute("/api/auth/**"),
                WebRouteContribution.publicRoute("/api/i18n/**"),
                WebRouteContribution.publicRoute("/i18n-static/**"),
                WebRouteContribution.publicRoute("/api/onboarding/**"),
                // ── VISITOR：multi 游客可达 / solo 需会话 / 邀请访客 403 / 不入 monitor ────────────
                // 目录兜底不覆盖更具体的文件声明。
                WebRouteContribution.visitor("/js/**"),
                WebRouteContribution.visitor("/css/**"),
                WebRouteContribution.visitor("/vendor/**"),
                // setup API 在尚未初始化或 multi 模式时走引导例外，已完成初始化的 solo 仍需会话。
                WebRouteContribution.visitor("/api/setup/**"),
                WebRouteContribution.visitor("/api/app/info").withProgramPermissions(Map.of(HttpMethod.GET, new ProgramPermission("application.read", "permission.application.read"))),
                WebRouteContribution.visitor("/api/quota/**"),
                WebRouteContribution.visitor("/api/archive/**"),
                WebRouteContribution.visitor("/api/migration/**"),
                // ── GUI：本地 + token 双重校验（由 AuthFilter 内联分支执行；声明为纳入守卫与镜像）──────
                WebRouteContribution.gui("/api/gui/**"),
                // ── actuator 公开探针：容器健康 / 信息端点（由 AuthFilter 内联 fast-path 放行；声明为纳入
                //    路由镜像守卫与全 URL 声明模型，不改其内联放行行为）。仅以下四条对外暴露。──────────────
                actuatorPublicProbe("/actuator/health"),
                actuatorPublicProbe("/actuator/health/liveness"),
                actuatorPublicProbe("/actuator/health/readiness"),
                actuatorPublicProbe("/actuator/info"),
                // ── 本地放行：PAC、setup 页面 / 静态、已下载本地资产 ────────────────────────────────
                WebRouteContribution.local("/proxy.pac"),
                WebRouteContribution.local("/setup.html"),
                WebRouteContribution.local("/setup/**"),
                WebRouteContribution.local("/__dev/error/**"),
                WebRouteContribution.local("/api/downloaded/**"));
    }

    /** 宿主私有的 actuator 探针声明入口；第三方 Plugin API 不暴露对应命名工厂。 */
    private static WebRouteContribution actuatorPublicProbe(String pathPattern) {
        return new WebRouteContribution(pathPattern, AccessPolicy.ACTUATOR_PUBLIC, Set.of(), false);
    }

    @Override
    public List<StaticResourceContribution> staticResources() {
        // 共享公共库（侧边模块 / i18n / 主题 / 语言切换 / 翻译等脚本、共享样式、第三方 vendor）
        // 作为核心公共资源声明：被所有页面跨插件复用，解析经核心 ClassLoader。本记录只描述 serving；
        // 逐文件访问（公开 / 邀请访客放行）由 routes() / RouteAccessRegistry 声明、AuthFilter 执行。
        return List.of(
                new StaticResourceContribution("classpath:/static/js/", "/js/"),
                new StaticResourceContribution("classpath:/static/css/", "/css/"),
                new StaticResourceContribution("classpath:/static/vendor/", "/vendor/"));
    }

    @Override
    public List<I18nContribution> i18n() {
        // 管理 / 安全 / 引导页与跨插件共享文案留核心：common（全站公共）、setup/login/intro
        // （首次安装与登录引导）、monitor（运行监控）、invite（访客邀请）、tour（新手引导）、
        // maintenance（维护窗口）。AI 翻译文案由 AI 插件贡献 translate namespace。
        // 第三参为 /api/i18n/meta 的展示顺序：核心与功能插件交错排列，故各 namespace 自带
        // 全局序号，使合并结果不随插件注册先后漂移（保持历史 namespace 顺序）。
        return List.of(
                new I18nContribution("common", "i18n.web.common", 1),
                new I18nContribution("setup", "i18n.web.setup", 2),
                new I18nContribution("login", "i18n.web.login", 3),
                new I18nContribution("intro", "i18n.web.intro", 4),
                new I18nContribution("monitor", "i18n.web.monitor", 15),
                new I18nContribution("invite", "i18n.web.invite", 17),
                new I18nContribution("security", "i18n.web.security", 23),
                new I18nContribution("openapi", "i18n.web.openapi"),
                new I18nContribution("tour", "i18n.web.tour", 18),
                new I18nContribution("maintenance", "i18n.web.maintenance", 19),
                // 错误状态页（/error/*.html）专属文案。
                new I18nContribution("status", "i18n.web.status", 22),
                // 插件管理页（plugin-manage.html）专属文案；同时承载无内容 namespace 的核心 / 计划任务宿主插件名称 / 简介。
                new I18nContribution("plugins", "i18n.web.plugins", 20));
    }

    @Override
    public List<NavigationContribution> navigation() {
        // 邀请码管理、插件管理与接口参考入口均为 ADMIN，标签使用各自的核心 i18n namespace。
        //
        // 邀请管理属于核心安全能力；可选内容插件撤回后仍可管理和撤销已有邀请。
        // 仅贡献到 Web 侧栏与桌面安全入口，不占用顶部应用导航。
        return List.of(
                new NavigationContribution(
                        "invite-manage",
                        Set.of(NavigationPlacements.APP_SIDEBAR, NavigationPlacements.GALLERY_SIDEBAR_TOOLS,
                                NavigationPlacements.NOVEL_SIDEBAR_TOOLS, NavigationPlacements.DESKTOP_SECURITY_ACTIONS),
                        "invite", "nav.label", "/pixiv-invite-manage.html",
                        "invite-manage", AccessPolicy.ADMIN, 80, Set.of(), "nav.description"),
                // 插件管理与下载、画廊同属主页面入口。
                new NavigationContribution(
                        "plugin-manage",
                        Set.of(NavigationPlacements.APP_TOP, NavigationPlacements.GALLERY_SIDEBAR,
                                NavigationPlacements.NOVEL_SIDEBAR),
                        "plugins", "nav.label", "/plugin-manage.html",
                        "puzzle", AccessPolicy.ADMIN, 85, Set.of("plugin-update-summary"), null),
                // 接口参考页本身公开；入口只给管理员，桌面安全页借同一贡献打开它。
                new NavigationContribution(
                        "api-reference",
                        Set.of(NavigationPlacements.APP_SIDEBAR, NavigationPlacements.GALLERY_SIDEBAR_TOOLS,
                                NavigationPlacements.NOVEL_SIDEBAR_TOOLS, NavigationPlacements.DESKTOP_SECURITY_ACTIONS),
                        "openapi", "nav.label", "/openapi",
                        "code", AccessPolicy.ADMIN, 90, Set.of(), "nav.description"));
    }
}
