package top.sywyar.pixivdownload.gui;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.sywyar.pixivdownload.core.db.DatabaseTarget;
import top.sywyar.pixivdownload.core.db.schema.DatabaseSchemaInspector;
import top.sywyar.pixivdownload.core.db.schema.ManagedDatabaseSchema;
import top.sywyar.pixivdownload.i18n.MessageBundles;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiHost;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiSession;
import top.sywyar.pixivdownload.tools.ArtworksBackFill;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Set;

/** 在后端启动前检查受管结构并按既有策略回填，所有连接和日志会话归当次执行。 */
final class StartupSchemaBackfill {
    private static final Logger log = LoggerFactory.getLogger(GuiLauncher.class);

    private StartupSchemaBackfill() {}

    static void run(
            DesktopUiSession frame,
            DesktopUiHost host,
            ManagedDatabaseSchema.DatabaseSchema expectedSchema,
            DatabaseTarget target
    ) {
        try {
            if (!target.available()) {
                BackendLifecycleManager.startAsync();
                return;
            }
            runStartupSchemaBackfill(frame, options(host).withTarget(target), expectedSchema);
        } catch (Throwable failure) {
            DesktopUiFailover.rethrowFatal(failure);
            log.error(MessageBundles.getForLog("gui.launcher.log.startup.schema-backfill-flow.unexpected"), failure);
            BackendLifecycleManager.startAsync();
        }
    }

    /**
     * 由后端在启动时通过 {@code ALTER TABLE ... ADD COLUMN} 自动补齐的列
     * （带安全默认值、无需联网抓取即可迁移，见 {@code DatabaseInitializer} 的安全补列阶段）。
     * <p>这些列在旧库里缺失只是后端首启前的暂时状态，会被自动迁移补齐，不应阻断
     * {@link #supportsStartupAutoBackfill} 的整段元数据自动回填判定。与
     * {@link ArtworksBackFill#SUPPORTED_DATABASE_COLUMNS} 区分：后者可使用本地元数据或网络详情补齐。
     */
    private static final class StartupColumns {
        private static final Set<ArtworksBackFill.DatabaseColumn> AUTO_MIGRATED = Set.of(
                new ArtworksBackFill.DatabaseColumn("artworks", "file_name"),
                new ArtworksBackFill.DatabaseColumn("artworks", "file_author_name_id"),
                new ArtworksBackFill.DatabaseColumn("artworks", "deleted"),
                new ArtworksBackFill.DatabaseColumn("novels", "deleted"),
                new ArtworksBackFill.DatabaseColumn("artworks", "upload_time"),
                new ArtworksBackFill.DatabaseColumn("artworks", "is_original"),
                new ArtworksBackFill.DatabaseColumn("novels", "upload_time"),
                new ArtworksBackFill.DatabaseColumn("artworks", "metadata_json"),
                new ArtworksBackFill.DatabaseColumn("novels", "metadata_json"),
                new ArtworksBackFill.DatabaseColumn("statistics", "daily_date"),
                new ArtworksBackFill.DatabaseColumn("statistics", "daily_completed"),
                new ArtworksBackFill.DatabaseColumn("statistics", "daily_failed")
        );
    }

    private static void runStartupSchemaBackfill(
            DesktopUiSession frame,
            ArtworksBackFill.Options options,
            ManagedDatabaseSchema.DatabaseSchema expectedSchema
    ) {
        var target = options.target();
        if (target.localFile().filter(file -> !hasInspectableDatabase(file)).isPresent()) {
            log.info(MessageBundles.getForLog("gui.launcher.log.startup.schema-check.skipped", target.displayName()));
            BackendLifecycleManager.startAsync();
            return;
        }

        DatabaseSchemaInspector.SchemaComparison comparison;
        try (var connection = target.openConnection()) {
            comparison = DatabaseSchemaInspector.compare(connection, target, expectedSchema);
        } catch (Throwable error) {
            DesktopUiFailover.rethrowFatal(error);
            log.warn(MessageBundles.getForLog("gui.launcher.log.startup.schema-check.compare-failed"), error);
            DesktopUiDialogs.showMessageDialog(
                    frame,
                    MessageBundles.get(
                            "gui.launcher.dialog.schema-check-failed.message",
                            Objects.toString(error.getMessage(), error.getClass().getSimpleName())
                    ),
                    MessageBundles.get("gui.launcher.dialog.schema-check-failed.title"),
                    DesktopUiDialogs.WARNING_MESSAGE
            );
            BackendLifecycleManager.startAsync();
            return;
        }

        if (comparison.matches()) {
            log.info(MessageBundles.getForLog("gui.launcher.log.startup.schema-check.passed", target.displayName()));
        } else {
            log.info(MessageBundles.getForLog("gui.launcher.log.startup.schema-check.mismatch", comparison.summary(8)));
        }
        if (!supportsStartupAutoBackfill(comparison)) {
            log.info(MessageBundles.getForLog("gui.launcher.log.startup.schema-check.auto-backfill.unsupported", comparison.summary(8)));
            DesktopUiDialogs.showMessageDialog(
                    frame,
                    MessageBundles.get("gui.launcher.dialog.schema-mismatch.no-auto-backfill.message", comparison.summary(6)),
                    MessageBundles.get("gui.launcher.dialog.schema-mismatch.title"),
                    DesktopUiDialogs.INFORMATION_MESSAGE
            );
            BackendLifecycleManager.startAsync();
            return;
        }

        int pendingCount;
        try {
            pendingCount = ArtworksBackFill.countCandidates(options);
            log.info(MessageBundles.getForLog("gui.launcher.log.startup.backfill.pending", pendingCount));
        } catch (Throwable error) {
            DesktopUiFailover.rethrowFatal(error);
            log.warn(MessageBundles.getForLog("gui.launcher.log.startup.backfill.check-failed"), error);
            DesktopUiDialogs.showMessageDialog(
                    frame,
                    MessageBundles.get(
                            "gui.launcher.dialog.backfill-check-failed.message",
                            Objects.toString(error.getMessage(), error.getClass().getSimpleName())
                    ),
                    MessageBundles.get("gui.launcher.dialog.backfill-check-failed.title"),
                    DesktopUiDialogs.WARNING_MESSAGE
            );
            BackendLifecycleManager.startAsync();
            return;
        }

        if (pendingCount <= 0) {
            BackendLifecycleManager.startAsync();
            return;
        }

        DesktopUiDialogs.showMessageDialog(
                frame,
                MessageBundles.get("gui.launcher.dialog.backfill-pending.message", pendingCount),
                MessageBundles.get("gui.launcher.dialog.auto-backfill.title"),
                DesktopUiDialogs.INFORMATION_MESSAGE
        );

        ToolHtmlLogSession logSession = null;
        ArtworksBackFill.Summary summary = null;
        Throwable failure = null;
        try {
            try {
                logSession = ToolHtmlLogSession.open("artworks-backfill", ArtworksBackFill.class);
                try {
                    logSession.openLatestInBrowser();
                } catch (Exception browserError) {
                    log.warn(MessageBundles.getForLog("gui.launcher.log.startup.backfill.open-log-page-failed"), browserError);
                }
            } catch (Exception logError) {
                log.warn(MessageBundles.getForLog("gui.launcher.log.startup.backfill.create-log-session-failed"), logError);
            }
            summary = ArtworksBackFill.run(options);
        } catch (Throwable error) {
            DesktopUiFailover.rethrowFatal(error);
            failure = error;
            log.error(MessageBundles.getForLog("gui.launcher.log.startup.backfill.failed"), error);
        } finally {
            if (logSession != null) {
                try {
                    logSession.close();
                } catch (Exception ignored) {
                }
            }
        }

        ArtworksBackFill.Summary finalSummary = summary;
        Throwable finalFailure = failure;
        BackendLifecycleManager.startAsync(() -> showStartupBackfillResult(frame, finalSummary, finalFailure, pendingCount));
    }

    private static boolean supportsStartupAutoBackfill(DatabaseSchemaInspector.SchemaComparison comparison) {
        // 结构可用只决定能否查询候选；是否需要回填由缺失数据决定。
        return comparison.details().stream().allMatch(StartupSchemaBackfill::isNonBlockingStartupDifference);
    }

    /** 该差异是否由回填工具实际填充——即差异的列在 {@link ArtworksBackFill#SUPPORTED_DATABASE_COLUMNS} 支持范围内。 */
    private static boolean isBackfillableDifference(DatabaseSchemaInspector.SchemaDifference difference) {
        return difference.kind() == DatabaseSchemaInspector.SchemaDifferenceKind.MISSING_COLUMN
                && difference.hasColumn()
                && ArtworksBackFill.supportsDatabaseColumn(difference.tableName(), difference.columnName());
    }

    /**
     * 该差异是否不阻断启动期自动回填：要么可回填（见 {@link #isBackfillableDifference}），
     * 要么是后端启动时会自愈的差异（缺表 / 自动迁移列）。
     */
    private static boolean isNonBlockingStartupDifference(
            DatabaseSchemaInspector.SchemaDifference difference
    ) {
        if (difference.isHarmlessAddition()) return true;
        if (difference.hasColumn()) {
            if (isBackfillableDifference(difference)) {
                return true;
            }
            return difference.kind() == DatabaseSchemaInspector.SchemaDifferenceKind.MISSING_COLUMN
                    && StartupColumns.AUTO_MIGRATED.contains(
                            new ArtworksBackFill.DatabaseColumn(difference.tableName(), difference.columnName()));
        }
        // 缺表和可创建索引由后端补齐；同名索引漂移及缺失表级唯一约束不能据此放行。
        return difference.kind() == DatabaseSchemaInspector.SchemaDifferenceKind.MISSING_TABLE
                || difference.kind() == DatabaseSchemaInspector.SchemaDifferenceKind.MISSING_INDEX;
    }

    private static void showStartupBackfillResult(
            DesktopUiSession owner,
            ArtworksBackFill.Summary summary,
            Throwable failure,
            int checkedCount
    ) {
        if (failure != null) {
            DesktopUiDialogs.show(
                    owner,
                    MessageBundles.get("gui.launcher.dialog.auto-backfill.title"),
                    MessageBundles.get(
                            "gui.launcher.dialog.auto-backfill.failed.message",
                            Objects.toString(failure.getMessage(), failure.getClass().getSimpleName())
                    ),
                    failure
            );
            return;
        }

        if (summary == null) {
            return;
        }

        String resultText = summary.rateLimited()
                ? MessageBundles.get("gui.launcher.dialog.auto-backfill.rate-limited")
                : MessageBundles.get("gui.launcher.dialog.auto-backfill.completed");
        DesktopUiDialogs.showMessageDialog(
                owner,
                MessageBundles.get(
                        "gui.launcher.dialog.auto-backfill.summary.message",
                        resultText,
                        checkedCount,
                        summary.processed(),
                        summary.totalCandidates()
                ),
                MessageBundles.get("gui.launcher.dialog.auto-backfill.title"),
                summary.rateLimited() ? DesktopUiDialogs.WARNING_MESSAGE : DesktopUiDialogs.INFORMATION_MESSAGE
        );
    }

    static ArtworksBackFill.Options options(DesktopUiHost host) throws java.io.IOException {
        DesktopUiHost.BackfillOptions defaults = host.defaultBackfillOptions();
        String root = host.applicationConfig().read("download.root-folder");
        if (root == null || root.isBlank()) root = new top.sywyar.pixivdownload.core.appconfig.DownloadConfig().getRootFolder();
        return new ArtworksBackFill.Options(
                defaults.dbPath(),
                defaults.proxyHost(),
                defaults.proxyPort(),
                defaults.useProxy(),
                defaults.delayMs(),
                defaults.limit(),
                false
        ).withSources(root, defaults.alwaysNetwork()).forStartup();
    }

    private static boolean hasInspectableDatabase(Path databasePath) {
        try {
            return Files.isRegularFile(databasePath) && Files.size(databasePath) > 0;
        } catch (Exception e) {
            log.warn(MessageBundles.getForLog(
                    "gui.launcher.log.database.inspect-failed",
                    databasePath.toAbsolutePath(),
                    e.getMessage()
            ));
            return false;
        }
    }
}
