package top.sywyar.pixivdownload.migration;

public record MigrationResponse(int migrated, int skipped, String message) {}
